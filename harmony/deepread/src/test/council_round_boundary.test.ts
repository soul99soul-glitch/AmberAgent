import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createModelCouncilManager } from '../main/ets/council/manager.ts';
import { makeRuntimeSetting, makeSeat } from '../main/ets/council/models.ts';
import { makeModelConfig } from '../main/ets/domain/model_config.ts';
import type { CouncilGenerateRequest } from '../main/ets/council/runner.ts';

const pool = [makeModelConfig({ id: 'one', model: 'one', baseUrl: 'https://provider.example', apiKey: 'test' })];

test('queued council seats use only preceding rounds in response and final prompts', async () => {
  const calls: CouncilGenerateRequest[] = [];
  const manager = createModelCouncilManager({
    modelPool: pool, fileStore: null, setting: makeRuntimeSetting({ maxSeats: 8 }),
    runner: { async generate(request) {
      calls.push(request);
      await new Promise(resolve => setTimeout(resolve, 2));
      return { text: `ROUND_${request.key?.round}_SEAT_${request.key?.seatId}`, warnings: [] };
    } },
  });
  const seats = Array.from({ length: 6 }, (_, index) => makeSeat({
    seatId: `seat_${index}`, name: `Seat ${index}`, role: `role_${index}`, modelId: 'one',
  }));
  const started = manager.start({ mode: 'debate', objective: 'Compare evidence', seats, rounds: 3 });
  const done = await manager.wait(started.runId, 2000);
  assert.equal(done?.status, 'completed');
  assert.equal(done?.turns.length, 18);
  const laterRounds = calls.filter(request => request.key !== undefined && request.key.round > 1);
  assert.equal(laterRounds.length, 12);
  for (const request of laterRounds) {
    const round = request.key!.round;
    assert.ok(request.userPrompt.includes(`ROUND_${round - 1}_SEAT_`), 'preceding evidence remains available');
    assert.ok(!request.userPrompt.includes(`ROUND_${round}_SEAT_`),
      `round ${round}, ${request.key!.seatId} saw a peer in the same round`);
  }
});

for (const text of ['', ' \n\t ']) {
  test(`empty council synthesis preserves seats and reports partial failure: ${JSON.stringify(text)}`, async () => {
    const manager = createModelCouncilManager({
      modelPool: pool, fileStore: null, setting: makeRuntimeSetting({}),
      runner: { async generate(request) {
        return { text: request.key === undefined ? text : 'valid seat position', warnings: [] };
      } },
    });
    const started = manager.start({ mode: 'compare', objective: 'Question' });
    const done = await manager.wait(started.runId, 2000);
    assert.equal(done?.status, 'partial_failed');
    assert.ok(done?.turns.every(turn => turn.status === 'completed'));
    assert.ok(done?.result?.error.includes('empty response'));
    assert.equal(done?.result?.finalRecommendation, '');
  });
}
