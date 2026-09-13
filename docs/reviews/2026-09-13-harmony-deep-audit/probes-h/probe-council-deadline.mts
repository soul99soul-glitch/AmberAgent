// Isolated probe: real council manager, mock runner (no network). Verifies
// 1) run-level totalTimeoutMs produces timed_out and cascades to seats
// 2) race: deadline fires exactly while synthesize resolves -> status cancellation overwritten
import { createModelCouncilManager } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/deepread/src/main/ets/council/manager.ts';
import { makeRuntimeSetting } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/deepread/src/main/ets/council/models.ts';
import type { ModelConfig } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/deepread/src/main/ets/domain/model_config.ts';

const pool: ModelConfig[] = [
  { id: 'm1', providerId: 'p', baseUrl: 'http://x', apiKey: 'k', model: 'mock-1', label: 'M1' } as unknown as ModelConfig,
  { id: 'm2', providerId: 'p', baseUrl: 'http://x', apiKey: 'k', model: 'mock-2', label: 'M2' } as unknown as ModelConfig,
];

let abortObserved = 0;
const runner = {
  generate: async (req: any): Promise<any> => {
    const isSynth: boolean = req.userPrompt.includes('Synthesize:');
    // ignore-abort runner: never inspects req.signal
    await new Promise(r => setTimeout(r, isSynth ? 50 : 20000));
    return { text: isSynth ? JSON.stringify({ consensus: ['c'], conflicts: [], risks: [], finalRecommendation: 'ok' }) : 'seat body', warnings: [] };
  },
};

const nonAbortRunner = {
  generate: async (req: any): Promise<any> => {
    const isSynth: boolean = req.userPrompt.includes('Synthesize:');
    await new Promise((resolve, reject) => {
      const t = setTimeout(resolve, isSynth ? 50 : 20000);
      if (req.signal !== null && req.signal.addEventListener !== undefined) {
        req.signal.addEventListener('abort', () => { clearTimeout(t); abortObserved += 1; reject(new Error('aborted')); });
      }
    });
    return { text: isSynth ? JSON.stringify({ consensus: ['c'], conflicts: [], risks: [], finalRecommendation: 'ok' }) : 'seat body', warnings: [] };
  },
};

async function scenario(name: string, runnerImpl: any, totalTimeoutMs: number): Promise<void> {
  abortObserved = 0;
  const setting = makeRuntimeSetting({ totalTimeoutMs, seatTimeoutMs: 60000, defaultRounds: 2 });
  const mgr = createModelCouncilManager({ runner: runnerImpl, fileStore: null, modelPool: pool, setting });
  const run = mgr.start({ mode: 'compare', objective: 'probe' });
  const done = await mgr.wait(run.runId, 4000);
  console.log(`${name}: status=${done?.status} turns=${done?.turns.map(t => t.status).join(',')} aborts=${abortObserved}`);
}

// A) deadline fires while seats ignore abort -> timed_out
await scenario('deadline-ignore-abort', runner, 300);
// B) deadline fires while seats honor abort -> timed_out (cancelled seats)
await scenario('deadline-honor-abort', nonAbortRunner, 300);
