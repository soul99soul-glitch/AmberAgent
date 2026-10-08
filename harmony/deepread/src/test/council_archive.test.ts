import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createModelCouncilManager } from '../main/ets/council/manager.ts';
import { readCouncilArchive } from '../main/ets/council/archive.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import { makeModelConfig } from '../main/ets/domain/model_config.ts';
import { makeRuntimeSetting, makeSeat } from '../main/ets/council/models.ts';

test('归档保留席位配置与准确终态结果，新进程可读且不重启执行', async () => {
  const files = createMemoryFileStore();
  let calls = 0;
  const manager = createModelCouncilManager({ fileStore: files,
    modelPool: [makeModelConfig({ id: 'm', apiKey: 'secret', model: 'fixture' })],
    setting: makeRuntimeSetting({ enabled: true }),
    runner: { generate: async req => { calls++; req.onChunk('live'); return { text: 'verdict', warnings: [] }; } },
  });
  const run = manager.start({ mode: 'compare', objective: 'archive fixture', seats: ['a', 'b'].map(seatId =>
    makeSeat({ seatId, name: seatId, role: seatId, modelId: 'm', systemPrompt: 'actual seat prompt' })) });
  const completed = await manager.wait(run.runId, 1000);
  const stored = await readCouncilArchive(files, run.runId);
  assert.equal(stored?.status, completed?.status);
  assert.deepEqual(stored?.task, completed?.task);
  assert.deepEqual(stored?.turns, completed?.turns);
  assert.deepEqual(stored?.result, completed?.result);
  assert.equal(calls, 3);
  assert.ok(!(await files.readText(run.transcriptPath))?.includes('secret'));
  assert.equal(await readCouncilArchive(files, '../escape'), null);
});
