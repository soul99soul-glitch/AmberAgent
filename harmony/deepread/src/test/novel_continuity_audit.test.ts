// Novel continuity audit + setting proposal parser tests

import { test } from 'node:test';
import assert from 'node:assert/strict';

import { parseAuditReport } from '../main/ets/novel/continuity_audit.ts';
import {
  parseSettingProposals, isSettingProposalEnvelope, SETTING_PROPOSAL_MARKER,
} from '../main/ets/novel/setting_proposal_parser.ts';

test('parseAuditReport: invalid JSON throws', () => {
  assert.throws(() => parseAuditReport('not json at all'));
  assert.throws(() => parseAuditReport('{"foo":1}'));
});

test('parseAuditReport: severity defaults minor', () => {
  const issues = parseAuditReport('{"issues":[{"summary":"x"}]}');
  assert.equal(issues[0].severity, 'minor');
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
