// Isolated probe: real createScheduler from harmony deepread, mock RunManagerDeps.
// Hypothesis: when the old run ignores the abort signal for >5s (prefetch does not
// receive the signal), waitForAbort gives up and the "REPLACE" dedup lets two runs
// for the same topic execute concurrently; the late run's finally also calls
// notifier.cancelRunning unconditionally.
import { createScheduler, isInterruptedPhase } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/deepread/src/main/ets/agent/scheduler.ts';
import { makeEmptyDeepReadOutput } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/deepread/src/main/ets/domain/models.ts';
import type { RunResult, RunManagerDeps } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/deepread/src/main/ets/agent/run_manager.ts';

let concurrent = 0;
let maxConcurrent = 0;
const runStarts: string[] = [];
const runEnds: string[] = [];
const notifierLog: string[] = [];

class AC {
  aborted = false;
  listeners: Array<() => void> = [];
  signal = {
    aborted: false,
    addEventListener: (_t: string, l: () => void): void => { this.listeners.push(l); },
    removeEventListener: (_t: string, l: () => void): void => {
      const i = this.listeners.indexOf(l); if (i >= 0) this.listeners.splice(i, 1);
    },
  };
  abort(): void { this.aborted = true; this.signal.aborted = true; for (const l of this.listeners) l(); }
}

const store = new Map<string, any>();
const runManager: RunManagerDeps = {
  prefetcher: { collect: async () => [] } as any,
  collectRun: async () => [],
  aiClient: { generateText: async () => [] } as any,
  model: 'mock',
  playbookMarkdown: '',
  nowIso: () => '2026-09-13',
  repository: {
    get: (id: string) => store.get(id) ?? null,
    save: (id: string, _t: string, o: any) => { store.set(id, o); },
    clear: (id: string) => { store.delete(id); },
  },
};
// run() ignores opts.signal entirely, like createRunContext->prefetcher.collect does.
const scheduler = createScheduler({
  runManager,
  observeOutput: () => ({ subscribe: () => () => {}, getCurrent: () => undefined }),
  isInterruptedPhase,
  createAbortController: () => new AC() as any,
  notifier: {
    notifyRunning: async (id: string) => { notifierLog.push(`notifyRunning:${id}`); },
    notifyCompleted: async (id: string) => { notifierLog.push(`notifyCompleted:${id}`); },
    cancelRunning: async (id: string) => { notifierLog.push(`cancelRunning:${id}`); },
  },
});
// Override run() body duration by monkeypatching? Instead, wrap by patching the module binding is
// impossible; simulate long run through runManager.prefetcher.collect delay AND collectRun delay.
(runManager.prefetcher as any).collect = async (): Promise<any[]> => {
  concurrent += 1; maxConcurrent = Math.max(maxConcurrent, concurrent); runStarts.push('start');
  await new Promise(r => setTimeout(r, 5500));
  concurrent -= 1; runEnds.push('end');
  return [{
    sourceId: 's1', url: 'https://e.com', title: 't', source: 'mock', evidenceText: 'body',
    credibility: 'high', freshness: 'unknown', publishedAt: null, imageCandidates: [],
  }];
};

const A: Promise<RunResult> = scheduler.run('topic-1', 'T', {});
await new Promise(r => setTimeout(r, 80));
const B: Promise<RunResult> = scheduler.run('topic-1', 'T', { force: true });
const [ra, rb] = await Promise.allSettled([A, B]);
console.log('maxConcurrentRuns=', maxConcurrent, '(1 = dedup held, 2 = overlap)');
console.log('notifierLog=', JSON.stringify(notifierLog));
console.log('A=', ra.status, 'B=', rb.status);
