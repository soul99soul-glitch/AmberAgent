import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { responsePrompt, summaryBlock } from '../main/ets/council/prompts.ts';
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

test('responsePrompt: filters out self turns', () => {
  const selfTurn = turn({ seatId: 's1', seatName: '支持者', content: '我自己的话' });
  const p = responsePrompt(task, seat, [selfTurn]);
  assert.ok(!p.includes('我自己的话'));
});

test('summaryBlock: truncates long content', () => {
  const b = summaryBlock(turn({ content: 'y'.repeat(1000) }), 50);
  assert.ok(b.includes('…'));
  assert.ok(b.length < 200);
});
