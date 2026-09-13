// Isolated probe: real scheduler + real run_manager, mock prefetcher/aiClient.
// Hypothesis: abort during prefetch is not honored (collect has no signal), so the
// cancelled run still persists COLLECTING and RUNNING section state before the
// supervisor's per-pass check throws 'aborted'.
import { createScheduler, isInterruptedPhase } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/deepread/src/main/ets/agent/scheduler.ts';
import type { RunManagerDeps } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/deepread/src/main/ets/agent/run_manager.ts';

let releasePrefetch!: () => void;
const gate = new Promise<void>(r => { releasePrefetch = r; });
const saves: string[] = [];
const store = new Map<string, any>();

class AC { aborted = false; listeners: Array<() => void> = [];
  signal = { aborted: false,
    addEventListener: (_t: string, l: () => void): void => { this.listeners.push(l); },
    removeEventListener: (_t: string, l: () => void): void => { const i = this.listeners.indexOf(l); if (i >= 0) this.listeners.splice(i, 1); } };
  abort(): void { this.aborted = true; this.signal.aborted = true; for (const l of this.listeners) l(); } }

const deps: RunManagerDeps = {
  prefetcher: {
    collect: async (): Promise<any[]> => { await gate; return [{
      sourceId: 's1', url: 'https://e.com/a', title: 'evidence', source: 'mock',
      evidenceText: 'evidence body '.repeat(30), credibility: 'high', freshness: 'unknown',
      publishedAt: null, imageCandidates: [],
    }]; },
  } as any,
  collectRun: async () => { throw new Error('should not reach model'); },
  aiClient: { generateText: async () => { throw new Error('should not reach plan'); } } as any,
  model: 'm', playbookMarkdown: '', nowIso: () => '2026-09-13',
  repository: {
    get: (id: string) => store.get(id) ?? null,
    save: (id: string, _t: string, o: any) => { store.set(id, o); saves.push(`${o.generationPhase}:${o.sectionStates?.OVERVIEW?.status ?? '-'}`); },
    clear: (id: string) => { store.delete(id); },
  },
};
const scheduler = createScheduler({
  runManager: deps,
  observeOutput: () => ({ subscribe: () => () => {}, getCurrent: () => undefined }),
  isInterruptedPhase,
  createAbortController: () => new AC() as any,
});
const p = scheduler.run('topic-X', 'T', {});
await new Promise(r => setTimeout(r, 50));
console.log('running before abort =', scheduler.isRunning('topic-X'));
scheduler.abort('topic-X');           // user cancels during prefetch
releasePrefetch();                     // prefetch (no signal) completes anyway
let res: any = null; try { res = await p; } catch (e) { res = { threw: String(e) }; }
console.log('saves =', JSON.stringify(saves));
console.log('isRunning after =', scheduler.isRunning('topic-X'));
console.log('result =', JSON.stringify(res.threw !== undefined ? res : { ok: res.ok, error: res.error, phase: res.output.generationPhase, overview: res.output.sectionStates?.OVERVIEW?.status }));
