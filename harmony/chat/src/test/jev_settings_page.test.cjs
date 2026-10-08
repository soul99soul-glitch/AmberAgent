const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
process.env.TSX_TSCONFIG_PATH ??= path.resolve(__dirname, '../../tsconfig.json');
require('tsx/cjs');
const models = require('../main/ets/chat/jev_models.ts');
const approval = require('../main/ets/chat/jev_approval.ts');
const source = fs.readFileSync(path.resolve(__dirname, '../../../entry/src/main/ets/pages/SettingJevPage.ets'), 'utf8');
const names = ['setMode', 'setApiMode', 'patchText', 'purposeSettings', 'patchPurpose'];
const methods = names.map(name => source.match(new RegExp(`  private ${name}\\([^]*?\\n  \\}`))[0]).join('\n');
const exportsProbe = {};
vm.runInNewContext(ts.transpileModule(`class Probe {${methods}};exports.Probe=Probe;`, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText,
{ exports: exportsProbe, makeJevSettings: models.makeJevSettings, jevPurposeSettings: approval.jevPurposeSettings,
  MODE_VALUES: ['off', 'shadow', 'active'], API_MODE_VALUES: ['typesafe', 'vercel'], MOTION_OVERLAY: 0, Curve: { EaseOut: 0 } });
const fields = ['approvalTriage', 'webAutomation', 'toolContextSelection', 'contextRetention', 'autoApproval', 'completionCheck'];
const purposes = ['approval_triage', 'web_automation', 'tool_context_selection', 'context_retention', 'auto_approval', 'completion_check'];
const snapshot = value => JSON.parse(JSON.stringify(value));
function fixture() {
  const page = new exportsProbe.Probe(); page.settings = models.makeJevSettings({ mode: 'active', apiKey: 'key', model: 'model' });
  for (const field of fields) page.settings[field] = models.makeJevNewPurposeSettings({ mode: 'active', allowToolMetadata: true,
    allowTaskText: true, allowPageContent: true, allowToolOutput: true });
  page.persist = () => {}; page.getUIContext = () => ({ animateTo: (_opts, callback) => callback() });
  return page;
}
test('actual global mode/API/key/base/model setters retain all six purpose settings', () => {
  const page = fixture(); const before = Object.fromEntries(fields.map(field => [field, snapshot(page.settings[field])]));
  page.setMode(1); page.setApiMode(1); page.patchText({ apiKey: 'new', model: 'new', baseUrl: 'http://localhost' });
  for (const field of fields) assert.deepEqual(snapshot(page.settings[field]), before[field], field);
});
test('each actual purpose setter changes only its target and retains independent output consent', () => {
  for (let index = 0; index < purposes.length; index++) {
    const page = fixture(); const before = snapshot(page.settings);
    page.patchPurpose(purposes[index], { mode: 'shadow', allowTaskText: false });
    for (let other = 0; other < fields.length; other++) {
      const field = fields[other]; assert.deepEqual(snapshot(page.settings[field]), index === other
        ? { ...before[field], mode: 'shadow', allowTaskText: false } : before[field], field);
    }
  }
});
