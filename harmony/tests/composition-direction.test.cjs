const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../chat/node_modules/typescript');

const entry = path.resolve(__dirname, '../entry/src/main/ets');
const root = path.join(entry, 'di/AppContainer.ets');
const importsByFile = new Map();

function runtimeImports(filename) {
  if (importsByFile.has(filename)) return importsByFile.get(filename);
  const source = ts.createSourceFile(filename, fs.readFileSync(filename, 'utf8'),
    ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
  const result = [];
  for (const statement of source.statements) {
    if (!ts.isImportDeclaration(statement) && !ts.isExportDeclaration(statement)) continue;
    const module = statement.moduleSpecifier;
    if (!module || !ts.isStringLiteral(module) || !module.text.startsWith('.')) continue;
    if (statement.isTypeOnly || statement.importClause?.isTypeOnly) continue;
    const bindings = statement.importClause?.namedBindings ?? statement.exportClause;
    if (!statement.importClause?.name && bindings
      && (ts.isNamedImports(bindings) || ts.isNamedExports(bindings))
      && bindings.elements.length > 0 && bindings.elements.every(element => element.isTypeOnly)) continue;
    const base = path.resolve(path.dirname(filename), module.text);
    const target = [base, `${base}.ets`, `${base}.ts`, path.join(base, 'index.ts')]
      .find(candidate => fs.existsSync(candidate) && fs.statSync(candidate).isFile());
    assert.ok(target, `Missing dependency: ${path.relative(entry, filename)} -> ${module.text}`);
    result.push(target);
  }
  importsByFile.set(filename, result);
  return result;
}

function pathToRoot(filename, visited = new Set()) {
  if (filename === root) return [filename];
  if (visited.has(filename)) return null;
  visited.add(filename);
  for (const imported of runtimeImports(filename)) {
    const found = pathToRoot(imported, visited);
    if (found !== null) return [filename, ...found];
  }
  return null;
}

test('composition and extracted session owners do not read back through AppContainer', () => {
  const owners = fs.readdirSync(path.join(entry, 'di'))
    .filter(name => name.endsWith('.ets') && name !== 'AppContainer.ets')
    .map(name => path.join(entry, 'di', name));
  for (const name of ['ConversationRunService', 'ConversationRunDependencies',
    'ConversationRunTools', 'ConversationRunAuxiliary']) {
    owners.push(path.join(entry, 'platform_impl', `${name}.ets`));
  }
  for (const directory of ['platform_impl/chat', 'platform_impl/home', 'platform_impl/novel',
    'platform_impl/provider', 'platform_impl/deepread_settings', 'platform_impl/search_settings',
    'platform_impl/webmount_settings']) {
    owners.push(...fs.readdirSync(path.join(entry, directory))
      .filter(name => name.endsWith('.ets'))
      .map(name => path.join(entry, directory, name)));
  }
  const failures = owners.map(filename => pathToRoot(filename)).filter(Boolean)
    .map(chain => chain.map(filename => path.relative(entry, filename)).join(' -> '));
  assert.deepEqual(failures, [], `Runtime imports must stay below the composition root:\n${failures.join('\n')}`);
});
