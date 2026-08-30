import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import {
  MAX_SETTING_PROPOSALS, formatDiscussionAnswersFromToolPayload, parseDiscussionAsk,
  parseSettingProposals, stripDiscussionAskFence, stripSettingProposalsFence,
} from '../main/ets/novel/discussion_structured.ts';

test('parseDiscussionAsk parses fenced JSON and normalizes questions', () => {
  const content = `先定视角。\n\n\`\`\`ask_user
{"questions":[{"id":"pov","question":" 用谁的视角？ ","options":["女主"," ","反派"],"selectionType":"multiple"}]}
\`\`\``;
  const parsed = parseDiscussionAsk(content);
  assert.ok(parsed !== null);
  assert.equal(parsed.displayContent, '先定视角。');
  assert.equal(parsed.questions[0].id, 'pov');
  assert.deepEqual(parsed.questions[0].options, ['女主', '反派']);
  assert.equal(parsed.questions[0].selectionType, 'multi');
  assert.deepEqual(JSON.parse(parsed.toolInput), {
    questions: [{
      id: 'pov', question: '用谁的视角？', options: ['女主', '反派'], selection_type: 'multi',
    }],
  });
});

test('stripDiscussionAskFence removes complete and hides incomplete fence', () => {
  assert.equal(stripDiscussionAskFence('正文\n```ask_user\n{"questions":[]}\n```'), '正文');
  assert.equal(stripDiscussionAskFence('正文\n```ask_user\n{"questions":[{"id":"q'), '正文');
  assert.equal(stripDiscussionAskFence('```ask_user\n{"questions":'), '');
});

test('formatDiscussionAnswersFromToolPayload formats scalar and array answers', () => {
  const parsed = parseDiscussionAsk(`\`\`\`ask_user
{"questions":[{"id":"pov","question":"视角？"},{"id":"tone","question":"基调？"}]}
\`\`\``);
  assert.ok(parsed !== null);
  const formatted = formatDiscussionAnswersFromToolPayload(
    '{"answers":{"pov":"女主","tone":["克制","冷峻"]}}', parsed.questions);
  assert.equal(formatted, '【我的选择】\n- 视角？ → 女主\n- 基调？ → 克制、冷峻');
  assert.equal(formatDiscussionAnswersFromToolPayload('{', parsed.questions), '{');
});

test('parseSettingProposals requires object envelope and proposal array', () => {
  assert.equal(parseSettingProposals('```setting_proposals\n[]\n```'), null);
  assert.equal(parseSettingProposals('```setting_proposals\n{"proposals":{}}\n```'), null);
});

test('parseSettingProposals filters invalid entries and strips complete fence', () => {
  const parsed = parseSettingProposals(`讨论正文
\`\`\`setting_proposals
{"proposals":[
  {"kind":"character","title":" 主角 ","content":" 新设定 "},
  {"kind":"unknown","title":"坏 kind","content":"x"},
  {"kind":"world","title":"","content":"x"},
  "not-an-object"
]}
\`\`\``);
  assert.ok(parsed !== null);
  assert.equal(parsed.displayContent, '讨论正文');
  assert.deepEqual(parsed.proposals, [{ kind: 'character', title: '主角', content: '新设定' }]);
  assert.equal(stripSettingProposalsFence('正文\n```setting_proposals\n{"proposals":'), '正文');
});

test('parseSettingProposals enforces proposal count cap', () => {
  const proposals = Array.from({ length: MAX_SETTING_PROPOSALS + 5 }, (_value, index) => ({
    kind: 'other', title: `p${index}`, content: 'x',
  }));
  const parsed = parseSettingProposals(`\`\`\`setting_proposals\n${JSON.stringify({ proposals: proposals })}\n\`\`\``);
  assert.ok(parsed !== null);
  assert.equal(parsed.proposals.length, MAX_SETTING_PROPOSALS);
});
