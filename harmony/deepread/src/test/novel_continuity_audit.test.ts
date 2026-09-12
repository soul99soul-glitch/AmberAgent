// Novel continuity audit + setting proposal parser tests

import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  parseAuditReport, buildAuditUserPrompt, CONTINUITY_AUDIT_SYSTEM_PROMPT,
} from '../main/ets/novel/continuity_audit.ts';
import {
  parseSettingProposals, isSettingProposalEnvelope, SETTING_PROPOSAL_MARKER,
} from '../main/ets/novel/setting_proposal_parser.ts';
import { makeNovelProject, makeNovelChapter } from '../main/ets/novel/models.ts';

test('parseAuditReport: valid JSON', () => {
  const issues = parseAuditReport(
    '{"issues":[{"severity":"blocker","chapterRef":"第1章","summary":"主角名字前后不一致","suggestion":"统一为张三"}]}');
  assert.equal(issues.length, 1);
  assert.equal(issues[0].severity, 'blocker');
  assert.equal(issues[0].chapterRef, '第1章');
  assert.ok(issues[0].summary.indexOf('名字') >= 0);
});

test('parseAuditReport: empty issues ok', () => {
  assert.deepEqual(parseAuditReport('{"issues":[]}'), []);
});

test('parseAuditReport: invalid JSON throws', () => {
  assert.throws(() => parseAuditReport('not json at all'));
  assert.throws(() => parseAuditReport('{"foo":1}'));
});

test('parseAuditReport: severity defaults minor', () => {
  const issues = parseAuditReport('{"issues":[{"summary":"x"}]}');
  assert.equal(issues[0].severity, 'minor');
});

test('buildAuditUserPrompt includes chapters and materials', () => {
  const project = makeNovelProject({ id: 'p1', name: '测试书', now: 1 });
  const withChapter = {
    ...project,
    chapters: [makeNovelChapter({ title: '序章', content: '正文内容', now: 1 })],
    materials: [{
      id: 'm1', kind: 'world' as const, title: '世界观', content: '设定',
      enabled: true, createdAt: 1, updatedAt: 1,
    }],
  };
  const prompt = buildAuditUserPrompt(withChapter);
  assert.ok(prompt.indexOf('测试书') >= 0);
  assert.ok(prompt.indexOf('序章') >= 0);
  assert.ok(prompt.indexOf('世界观') >= 0);
  assert.ok(CONTINUITY_AUDIT_SYSTEM_PROMPT.indexOf('JSON') >= 0);
});

test('parseSettingProposals from fenced JSON', () => {
  const text = '好的，我建议：\n```json\n{"type":"novel_setting_proposal","changes":[{"kind":"character","title":"主角年龄","value":"28岁","reason":"与时间线一致"}]}\n```\n';
  const proposals = parseSettingProposals(text, 'msg-1', 1000);
  assert.equal(proposals.length, 1);
  assert.equal(proposals[0].title, '主角年龄');
  assert.equal(proposals[0].content, '28岁');
  assert.equal(proposals[0].kind, 'character');
  assert.equal(proposals[0].status, 'pending');
  assert.equal(proposals[0].sourceMessageId, 'msg-1');
});

test('parseSettingProposals: empty / wrong type', () => {
  assert.deepEqual(parseSettingProposals('没有提案', 'm', 0), []);
  assert.deepEqual(
    parseSettingProposals('{"type":"other","changes":[{"title":"a","content":"b"}]}', 'm', 0),
    []);
  assert.equal(isSettingProposalEnvelope(
    `{"type":"${SETTING_PROPOSAL_MARKER}","changes":[]}`), true);
  assert.equal(isSettingProposalEnvelope('hello'), false);
});

test('parseSettingProposals: key/value alias', () => {
  const proposals = parseSettingProposals(
    '{"type":"novel_setting_proposal","changes":[{"key":"魔法体系","value":"低魔"}]}',
    'msg', 1);
  assert.equal(proposals[0].title, '魔法体系');
  assert.equal(proposals[0].content, '低魔');
});
