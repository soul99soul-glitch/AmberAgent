const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const entryRoot = path.resolve(__dirname, '../../../entry/src/main/ets');
const domainRoot = path.resolve(__dirname, '../main/ets/domain');
const cache = new Map();
function loadPureModule(filename) {
  filename = path.resolve(filename);
  if (cache.has(filename)) return cache.get(filename);
  const exports = {};
  cache.set(filename, exports);
  const requireModule = spec => {
    if (spec === '@amber/deepread-domain') return Object.assign({},
      ...['models.ts', 'helpers.ts', 'enums.ts', 'input_sources.ts', 'synthesis_templates.ts'].map(name => loadPureModule(path.join(domainRoot, name))));
    if (spec === '@amber/chat-domain') return loadPureModule(path.resolve(__dirname, '../../../chat/src/main/ets/chat/markdown_blocks.ts'));
    if (spec.startsWith('.')) {
      const resolved = path.resolve(path.dirname(filename), spec);
      for (const candidate of [resolved, resolved + '.ets', resolved + '.ts']) {
        if (fs.existsSync(candidate)) return loadPureModule(candidate);
      }
    }
    throw Error('unexpected pure module ' + spec);
  };
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, { exports, require: requireModule, Error, JSON, Math, Number, String, Array,
    Object, Map, Set, Date, Promise, Uint8Array, TextEncoder, TextDecoder, URL }, { filename });
  return exports;
}
function method(source, name) {
  const match = new RegExp('^  (?:private )?(?:async )?' + name + '\\(', 'm').exec(source);
  if (!match) throw Error('missing actual method ' + name);
  let end = source.indexOf('{', match.index) + 1, depth = 1;
  for (; depth && end < source.length; end++) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  return source.slice(match.index, end);
}
// Method fixtures omit ArkUI's constructor. Retained roots require these actual
// production prop/callback initializers before their original lifecycle runs.
function rootComponentInitializers(source) {
  return (source.match(/^  (?:@\w+(?:\([^)]*\))?\s*)*(?:onBackHandler|embeddedRoot|rootVisible|rootCreate):.*$/gm) || [])
    .map(line => line.replace(/@\w+(?:\([^)]*\))?\s*/g, '')).join('\n');
}
function actualPage(file, names, env) {
  const source = fs.readFileSync(path.join(entryRoot, file), 'utf8');
  const code = ts.transpileModule('class Page {' + rootComponentInitializers(source) + '\n' + names.map(name => method(source, name)).join('\n') + '} return new Page();',
    { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  return new Function(...Object.keys(env), code)(...Object.values(env));
}
module.exports = { entryRoot, domainRoot, loadPureModule, method, actualPage, rootComponentInitializers };
