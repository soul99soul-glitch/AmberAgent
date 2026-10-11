const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('typescript');

test('custom protocols remain available after every branded preset has been added', async () => {
  const domain = await import('../main/ets/index.ts');
  const file = path.resolve(__dirname, '../../../entry/src/main/ets/components/ProviderTemplatePickerSheet.ets');
  const source = fs.readFileSync(file, 'utf8');
  const method = source.match(/  private pickProtocol\([^]*?\n  \}/)[0];
  const code = ts.transpileModule('class Picker {' + method + '}\nreturn Picker;', {
    compilerOptions: { target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const Picker = new Function('makeProviderSettingOpenAIVariant', 'makeProviderSettingClaude',
    'makeProviderSettingGoogle', code)(domain.makeProviderSettingOpenAIVariant,
    domain.makeProviderSettingClaude, domain.makeProviderSettingGoogle);
  const picker = new Picker();
  const created = [];
  picker.onPickPreset = value => created.push(value);
  for (const protocol of ['openai', 'claude', 'google']) picker.pickProtocol(protocol);
  assert.deepEqual(created.map(p => p.type), ['openai', 'claude', 'google']);
  assert.equal(new Set(created.map(p => p.id)).size, 3);
  assert.ok(created.every(p => p.apiKey === ''));
  assert.ok(/ForEach\(PROVIDER_PROTOCOLS/.test(source), 'custom protocols must be wired into the picker');
  assert.ok(/this\.pickProtocol\(protocol\)/.test(source), 'each custom row must create its selected protocol');
});
