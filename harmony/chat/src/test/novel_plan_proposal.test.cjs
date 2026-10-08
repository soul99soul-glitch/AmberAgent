// E13-A UI projection group: chapter-plan proposal parsing. Pure projection only;
// approval/save route is the existing parent locator chain (not exercised here).
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
process.env.TSX_TSCONFIG_PATH ??= path.resolve(__dirname, '../../tsconfig.json');
require('tsx/cjs');
const toolActivity = require('../main/ets/chat/tool_activity.ts');

const loadProjection = () => {
  const file = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl/NovelPlanProposalProjection.ets');
  const exports = {};
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, { exports, require: () => toolActivity, Error, JSON, Array, String }, { filename: file });
  return exports;
};
const { parseNovelChapterPlanProposal } = loadProjection();

const part = (input, toolName = 'novel_workspace_write') => ({
  type: 'tool', toolCallId: 't', toolName, input, output: [], approvalState: { type: 'pending' }, metadata: null,
});
const write = (content, path = 'plan/this-chapter.md') => ({ operation: 'write', path, content });
const del = (path = 'plan/this-chapter.md') => ({ operation: 'delete', path, content: null });
const input = (patches, proposalId = 'p-1') => JSON.stringify({ proposal_id: proposalId, patches });

test('valid whole-plan proposals project every patch in order with write/delete semantics', () => {
  const view = parseNovelChapterPlanProposal(part(input([
    write('# 第一章\n主角登场'),
    del(),
    write(''),
  ])));
  assert.equal(view.proposalId, 'p-1');
  assert.equal(view.patches.length, 3); // repeated writes/deletes are all preserved
  // vm 沙箱原型不同,deepEqual 前经 JSON 归一
  const patches = JSON.parse(JSON.stringify(view.patches));
  assert.deepEqual(patches[0], { operation: 'write', path: 'plan/this-chapter.md', content: '# 第一章\n主角登场' });
  assert.deepEqual(patches[1], { operation: 'delete', path: 'plan/this-chapter.md', content: null });
  assert.deepEqual(patches[2], { operation: 'write', path: 'plan/this-chapter.md', content: '' }); // empty write is a real write
  const branched = parseNovelChapterPlanProposal(part(input([write('x', 'branches/main/plan/this-chapter.md')])));
  assert.equal(branched.patches[0].path, 'branches/main/plan/this-chapter.md');
});

test('mixed paths, unknown operations, bad shapes and bad JSON stay on the generic tool UI', () => {
  const cases = [
    input([write('a'), write('b', 'chapters/1.md')]),                       // plan + other file
    input([write('a', 'branches/main/plan/this-chapter.md'), write('b', 'branches/dev/plan/this-chapter.md')]), // two branches
    input([write('a', 'branches/a/b/plan/this-chapter.md')]),               // non-canonical branch depth
    input([{ operation: 'append', path: 'plan/this-chapter.md', content: 'x' }]),
    input([{ operation: 'write', path: 'plan/this-chapter.md', content: null }]),
    input([{ operation: 'delete', path: 'plan/this-chapter.md', content: 'x' }]),
    input([], 'p-1'),
    JSON.stringify({ proposal_id: '', patches: [write('a')] }),
    JSON.stringify({ patches: [write('a')] }),
    '{"proposal_id":"p-1","patches":',
    'not json',
  ];
  for (const bad of cases) assert.equal(parseNovelChapterPlanProposal(part(bad)), null, bad);
  assert.equal(parseNovelChapterPlanProposal(part(input([write('a')]), 'some_other_tool')), null);
});

const withResult = (state, result) => ({ ...part(input([write('计划内容')])),
  approvalState: { type: state }, output: result === undefined ? [] : [{ type: 'text', text: JSON.stringify(result) }] });

test('approval is waiting, and only an exact accepted proposal result means applied', () => {
  assert.equal(parseNovelChapterPlanProposal(withResult('pending')).status, 'pending');
  assert.equal(parseNovelChapterPlanProposal(withResult('approved')).status, 'approved');
  assert.equal(parseNovelChapterPlanProposal(withResult('auto')).status, 'running');
  assert.equal(parseNovelChapterPlanProposal(withResult('approved', { status: 'accepted', proposal_id: 'p-1' })).status, 'accepted');
  assert.equal(parseNovelChapterPlanProposal(withResult('approved', { status: 'accepted', proposal_id: 'other' })).status, 'unknown');
  assert.equal(parseNovelChapterPlanProposal(withResult('approved', { status: 'completed', proposal_id: 'p-1' })).status, 'unknown');
});

test('rejected and actual structured failed outputs remain visible without approval actions', () => {
  assert.equal(parseNovelChapterPlanProposal(withResult('denied')).status, 'rejected');
  assert.equal(parseNovelChapterPlanProposal(withResult('approved', { status: 'rejected', proposal_id: 'p-1' })).status, 'rejected');
  const failed = parseNovelChapterPlanProposal(withResult('approved', { status: 'failed', message: '计划已变化，请重新生成', recoverable: true }));
  assert.equal(failed.status, 'failed');
  assert.equal(failed.resultText, '计划已变化，请重新生成');
  assert.equal(parseNovelChapterPlanProposal(withResult('approved', { status: 'rejected', proposal_id: 'other' })).status, 'unknown');
});

test('a failed result for another explicit proposal cannot resolve this card', () => {
  const mismatched = parseNovelChapterPlanProposal(withResult('approved', { status: 'failed', proposal_id: 'other', message: '另一提案失败' }));
  assert.equal(mismatched.status, 'unknown');
  assert.ok(mismatched.resultText.includes('other'));
});
