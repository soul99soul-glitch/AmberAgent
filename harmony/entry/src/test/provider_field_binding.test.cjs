// Official ArkUI Builder contract: one directly passed object literal keeps state reactive.
// This validates source binding and real callbacks; device screenshots prove rendering.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
const source = fs.readFileSync(path.resolve(__dirname,
  '../main/ets/pages/ChatProviderDetailPage.ets'), 'utf8');
function fieldCalls() {
  const result = [];
  for (const match of source.matchAll(/this\.field\(/g)) {
    const scanner = ts.createScanner(ts.ScriptTarget.Latest, true, ts.LanguageVariant.Standard,
      source.slice(match.index + 'this.field'.length));
    let depth = 0;
    for (let token = scanner.scan(); token !== ts.SyntaxKind.EndOfFileToken; token = scanner.scan()) {
      if (token === ts.SyntaxKind.OpenParenToken) depth++;
      if (token === ts.SyntaxKind.CloseParenToken && --depth === 0) {
        result.push(source.slice(match.index, match.index + 'this.field'.length + scanner.getTextPos()));
        break;
      }
    }
  }
  return result;
}
test('UI-12 Detail field Builder uses one typed object literal and retains native draft callbacks', () => {
  const signature = /^  field\(([\s\S]*?)\)\s*\{/m.exec(source);
  assert.ok(signature);
  const header = ts.createSourceFile('field.ts', 'function field(' + signature[1] + ') {}',
    ts.ScriptTarget.Latest, true);
  assert.equal(header.statements[0].parameters.length, 1, 'Builder state must not pass by primitive value');
  assert.equal(header.statements[0].parameters[0].type.getText(header), 'ProviderFieldOptions');
  const calls = fieldCalls(); assert.equal(calls.length, 6);
  const states = ['name', 'apiKey', 'baseUrl', 'chatPath', 'baseUrl', 'baseUrl'];
  calls.forEach((call, index) => {
    const parsed = ts.createSourceFile('call.ts', call, ts.ScriptTarget.Latest, true);
    const expression = parsed.statements[0].expression;
    assert.equal(expression.arguments.length, 1);
    assert.ok(ts.isObjectLiteralExpression(expression.arguments[0]), 'pass the literal directly for Builder reference binding');
    const object = expression.arguments[0].getText(parsed);
    const owner = { name: 'old-name', apiKey: 'old-key', baseUrl: 'old-url', chatPath: 'old-path',
      provider: { type: 'claude' } };
    const js = ts.transpileModule('return (' + object + ');', {
      compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
    const options = new Function(js).call(owner);
    assert.equal(options.value, owner[states[index]]);
    assert.equal(options.obscure, index === 1); assert.equal(options.mono, index !== 0);
    options.onChange('typed-draft');
    assert.equal(owner[states[index]], 'typed-draft');
  });
});
