// probe-lib.cjs — shared helpers for the merged-evidence probes.
//
// Purpose: extract the ORIGINAL method / field / interface source of a real `.ets` page
// via the TypeScript AST (no hand-written reimplementation), then assemble a headless
// class so the real production bodies run against mocked platform edges only.
//
// ArkUI pages use `struct` (not valid TS) and a JSX-like DSL in `build()`. We:
//   1. textually replace `struct ` -> `class ` ONLY for parsing (offsets unchanged, so
//      extracted ranges map back to the original file byte-for-byte);
//   2. parse with ts.createSourceFile;
//   3. read members by name from the ClassDeclaration — node.getStart()..node.end is the
//      verbatim original source of each member.
// Parse errors from the excluded build() DSL are recovered by the parser and do not move
// the member ranges (verified by length/provenance assertions in each probe).

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const REPO_ROOT = path.resolve(__dirname, '../../..');
const ts = require(path.join(REPO_ROOT, 'harmony/chat/node_modules/typescript'));

function parsePage(filePath) {
  const raw = fs.readFileSync(filePath, 'utf8');
  // `struct` -> `class` (same length: both 6 chars) keeps all offsets aligned.
  const src = raw.replace(/\bstruct\b/g, 'class ');
  const sf = ts.createSourceFile(filePath, src, ts.ScriptTarget.ES2022, true, ts.ScriptKind.TS);
  return { raw, src, sf };
}

function findClass(sf, name) {
  let out = null;
  sf.forEachChild((n) => {
    if ((ts.isClassDeclaration(n) || ts.isInterfaceDeclaration(n))
      && n.name && n.name.text === name) out = n;
  });
  return out;
}

function getMember(sf, classNode, name) {
  for (const m of classNode.members) {
    const nm = m.name ? m.name.getText(sf) : '';
    if (nm === name) return m;
  }
  return null;
}

// Verbatim source of a class member (method or field), decorators included.
function memberText(sf, classNode, name) {
  const m = getMember(sf, classNode, name);
  if (m === null) throw new Error(`member not found: ${name}`);
  return sf.text.slice(m.getStart(sf), m.end);
}

// Field source without its state decorators (decorators are UI metadata, not logic; a
// headless class cannot evaluate @State). Everything else stays verbatim.
function fieldDecl(sf, classNode, name) {
  const text = memberText(sf, classNode, name);
  return text
    .replace(/@(?:State|Prop|Link|StorageProp|StorageLink|ObjectLink|Provide|Consume|Watch)\b(?:\([^)]*\))?\s*/g, '')
    .replace(/^\s*@[\w]+\s*/g, '')
    .trim();
}

function interfaceText(sf, name) {
  const n = findClass(sf, name);
  if (n === null) throw new Error(`interface not found: ${name}`);
  return sf.text.slice(n.getStart(sf), n.end);
}

// Generic transpile+require loader with caller supplied dependency resolver + sandbox.
function createLoader(depResolver) {
  const cache = new Map();
  function load(filename) {
    if (cache.has(filename)) return cache.get(filename);
    const source = fs.readFileSync(filename, 'utf8');
    const js = ts.transpileModule(source, {
      compilerOptions: {
        module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022,
        isolatedModules: true, moduleResolution: ts.ModuleResolutionKind.NodeJs,
      },
    }).outputText;
    const module = { exports: {} };
    function dependency(name) {
      const resolved = depResolver(name, load);
      return resolved === undefined ? {} : resolved;
    }
    vm.runInNewContext('(function(require,module,exports){' + js + '\n})',
      depResolver.sandboxGlobals || { console, setTimeout, clearTimeout },
      { filename })(dependency, module, module.exports);
    cache.set(filename, module.exports);
    return module.exports;
  }
  return { load, root: REPO_ROOT, ts };
}

const sha = (s) => require('node:crypto').createHash('sha256').update(s).digest('hex').slice(0, 16);

module.exports = {
  REPO_ROOT, ts, sha, parsePage, findClass, getMember, memberText,
  fieldDecl, interfaceText, createLoader,
};
