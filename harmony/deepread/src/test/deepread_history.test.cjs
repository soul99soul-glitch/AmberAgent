const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const compile = source => ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;
const loadDomain = name => {
  const filename = path.resolve(__dirname, '../main/ets/domain', name);
  const exports = {};
  vm.runInNewContext(compile(fs.readFileSync(filename, 'utf8')), {
    exports, require: relative => loadDomain(relative),
  }, { filename });
  return exports;
};
const domain = { ...loadDomain('models.ts'), ...loadDomain('helpers.ts'), ...loadDomain('library.ts') };
const labels = { complete: '完成', failed: '失败', incomplete: '未完成', running: '生成中' };
const historyStatus = row => labels[domain.deepReadLibraryStatus(row)];
const readyStates = () => Object.fromEntries(['OVERVIEW', 'NARRATIVE', 'ANALYSIS', 'EXTENDED_READING']
  .map(stage => [stage, { status: 'READY', errorMessage: null }]));
const entry = output => ({ output, phase: 'COMPLETE', lastError: null });

// Regression fixture from the real domain: phase is terminal while analysis is unfinished/failed.
test('history exposes a failed section even when COMPLETE phase has no cache error string', () => {
  const output = domain.makeEmptyDeepReadOutput();
  output.summary = '已保存概览';
  output.generationPhase = 'COMPLETE';
  output.sectionStates = { ...readyStates(), ANALYSIS: { status: 'FAILED', errorMessage: null } };
  assert.equal(domain.isComplete(output), false);
  assert.equal(domain.firstFailedStage(output), 'ANALYSIS');
  assert.equal(historyStatus(entry(output)), '失败');
});

test('phase alone never marks a partial or unverified article complete', () => {
  const output = domain.makeEmptyDeepReadOutput();
  output.summary = '已保存部分稿';
  output.generationPhase = 'COMPLETE';
  assert.equal(historyStatus(entry(output)), '未完成');
  output.generationComplete = true;
  output.sectionStates = { OVERVIEW: { status: 'READY', errorMessage: null } };
  assert.equal(historyStatus(entry(output)), '未完成');
});

test('verified complete content stays complete despite stale cache metadata', () => {
  const output = domain.makeEmptyDeepReadOutput();
  output.generationComplete = true;
  output.sectionStates = readyStates();
  const row = entry(output);
  row.phase = 'WRITING';
  row.lastError = '旧轮错误';
  assert.equal(historyStatus(row), '完成');
});

test('cache errors and empty failed records remain failures while untouched drafts are incomplete', () => {
  const output = domain.makeEmptyDeepReadOutput();
  const row = entry(output);
  row.phase = 'IDLE';
  assert.equal(historyStatus(row), '未完成');
  row.lastError = '未获取到资料';
  assert.equal(historyStatus(row), '失败');
  row.lastError = null;
  output.sectionStates.OVERVIEW = { status: 'FAILED', errorMessage: null };
  assert.equal(historyStatus(row), '失败');
});
