import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import {
  seatSystemPrompt, openingPrompt, responsePrompt, finalPositionPrompt,
  synthesisPrompt, summaryBlock, truncate,
} from '../main/ets/council/prompts.ts';
import { makeSeat } from '../main/ets/council/models.ts';
import type { ModelCouncilTaskSpec, ModelCouncilTurn } from '../main/ets/council/models.ts';

const task: ModelCouncilTaskSpec = {
  mode: 'debate', objective: '是否上线新功能', context: '背景信息',
  outputFormat: 'fmt', evaluationCriteria: '标准', rounds: 3, seats: [],
};

const seat = makeSeat({ seatId: 's1', name: '支持者', role: 'supporter', systemPrompt: '保持乐观' });

const turn = (over: Partial<ModelCouncilTurn> = {}): ModelCouncilTurn => ({
  round: 1, seatId: 'sX', seatName: '反对者', role: 'opponent', modelId: 'mA',
  modelLabel: 'A', status: 'completed', content: '反对意见', error: '', warnings: [],
  ...over,
});

test('truncate: short string unchanged', () => {
  assert.equal(truncate('abc', 10), 'abc');
});

test('truncate: long string cut with ellipsis', () => {
  assert.equal(truncate('abcdef', 3), 'abc…');
});

test('seatSystemPrompt: includes name, role, custom prompt', () => {
  const p = seatSystemPrompt(seat);
  assert.ok(p.includes('支持者'));
  assert.ok(p.includes('supporter'));
  assert.ok(p.includes('保持乐观'));
  assert.ok(p.includes('no tools'));
});

test('openingPrompt: includes objective and independent instruction', () => {
  const p = openingPrompt(task, seat);
  assert.ok(p.includes('是否上线新功能'));
  assert.ok(p.includes('背景信息'));
  assert.ok(p.includes('independent answer'));
});

test('responsePrompt: includes other seats, excludes self', () => {
  const others = [turn({ seatId: 's2', seatName: '反对者', content: '别人的观点' })];
  const p = responsePrompt(task, seat, others);
  assert.ok(p.includes('别人的观点'));
  assert.ok(p.includes('Revise or defend'));
});

test('responsePrompt: filters out self turns', () => {
  const selfTurn = turn({ seatId: 's1', seatName: '支持者', content: '我自己的话' });
  const p = responsePrompt(task, seat, [selfTurn]);
  assert.ok(!p.includes('我自己的话'));
});

test('finalPositionPrompt: includes debate and final instruction', () => {
  const p = finalPositionPrompt(task, seat, [turn({ content: '辩论内容' })]);
  assert.ok(p.includes('辩论内容'));
  assert.ok(p.includes('final position'));
});

test('synthesisPrompt: includes turns and synthesize instruction', () => {
  const p = synthesisPrompt(task, [turn({ content: '各方观点' })]);
  assert.ok(p.includes('各方观点'));
  assert.ok(p.includes('Synthesize'));
});

test('summaryBlock: format includes round/name/label/status', () => {
  const b = summaryBlock(turn({ round: 2, seatName: '裁判', modelLabel: 'J', content: 'xx' }));
  assert.ok(b.includes('Round 2'));
  assert.ok(b.includes('裁判'));
  assert.ok(b.includes('J'));
  assert.ok(b.includes('completed'));
  assert.ok(b.includes('xx'));
});

test('summaryBlock: truncates long content', () => {
  const b = summaryBlock(turn({ content: 'y'.repeat(1000) }), 50);
  assert.ok(b.includes('…'));
  assert.ok(b.length < 200);
});
