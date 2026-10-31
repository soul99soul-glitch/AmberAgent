const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const repoRoot = path.resolve(__dirname, '../../../..');

test('H6: release preflight passes all local baseline checks and marks external items CONDITIONAL', async () => {
  const { runHarmonyReleasePreflight } = await import(path.join(repoRoot, 'scripts/harmony-release-preflight.mjs'));
  const result = runHarmonyReleasePreflight(repoRoot);

  assert.equal(result.ok, true, `preflight local checks must pass: ${JSON.stringify(result.checks)}`);
  assert.equal(result.summary.failCount, 0);
  assert.equal(result.summary.passCount, 4);
  assert.equal(result.summary.conditionalCount, 3);

  const byId = new Map(result.checks.map((c) => [c.id, c]));
  assert.equal(byId.get('H6-VERSION')?.status, 'PASS');
  assert.equal(byId.get('H6-SDK-BASELINE')?.status, 'PASS');
  assert.equal(byId.get('H6-PERMISSION-REASONS')?.status, 'PASS');
  assert.equal(byId.get('H6-LEGAL-ABOUT')?.status, 'PASS');
  assert.equal(byId.get('H6-BUNDLE-NAME')?.status, 'CONDITIONAL');
  assert.equal(byId.get('H6-RELEASE-SIGNING')?.status, 'CONDITIONAL');
  assert.equal(byId.get('H6-PRIVACY-FILING')?.status, 'CONDITIONAL');
});

test('H6: SettingAboutPage and string.json accurately describe Amber capabilities, encryption, and dual license', () => {
  const aboutPage = fs.readFileSync(
    path.join(repoRoot, 'harmony/entry/src/main/ets/pages/SettingAboutPage.ets'),
    'utf8',
  );
  assert.match(aboutPage, /getBundleInfoForSelfSync/);
  assert.match(aboutPage, /数据与隐私/);
  assert.match(aboutPage, /Asset Store/);
  assert.match(aboutPage, /AGPL v3；商业使用需取得商业许可/);
  assert.doesNotMatch(aboutPage, /不收集任何用户数据/);

  const stringJson = fs.readFileSync(
    path.join(repoRoot, 'harmony/entry/src/main/resources/base/element/string.json'),
    'utf8',
  );
  assert.match(stringJson, /Amber 智能体与深度阅读工作台/);
  assert.doesNotMatch(stringJson, /决定是否启动深读/);

  const checklistPath = path.join(repoRoot, 'docs/release/huawei/appgallery-submission-checklist.md');
  assert.equal(fs.existsSync(checklistPath), true);
  const checklist = fs.readFileSync(checklistPath, 'utf8');
  assert.match(checklist, /CONDITIONAL-1/);
  assert.match(checklist, /CONDITIONAL-2/);
  assert.match(checklist, /CONDITIONAL-3/);
  assert.match(checklist, /CONDITIONAL-4/);
});
