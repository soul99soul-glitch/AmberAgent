// DeepRead's entry bridge to the existing shared background activity set.
// The scheduler owns lifetime; this map holds measurement only. Late raw
// snapshots cannot recreate a finished activity, and a prior token cannot
// unregister a replacement run. No durable or cross-process resume is implied.

import type { Context } from '@kit.AbilityKit';
import type { UIMessage } from '@amber/chat-domain';
import { GenerationProgressTracker } from './GenerationProgressTracker.ets';
import type { GenerationProgressTotals } from './GenerationProgressTracker.ets';
import { reportGenerationProgress } from './BackgroundGenerationKeepAlive.ets';

interface DeepReadGenerationState {
  tracker: GenerationProgressTracker;
  title: string;
}

const trackers: Map<string, DeepReadGenerationState> = new Map<string, DeepReadGenerationState>();

const runIdFor = (topicId: string, runToken: number): string => `${topicId}:${runToken}`;

const report = (topicId: string, runId: string, active: boolean, state: DeepReadGenerationState): void => {
  const context: Context | undefined = AppStorage.get<Context>('abilityContext');
  if (context === undefined) return;
  const totals: GenerationProgressTotals = state.tracker.totals();
  reportGenerationProgress(context, {
    source: 'deepread', runId, generationActive: active, topicId: topicId, title: state.title,
    receivedChars: totals.chars, receivedBytes: totals.bytes,
  });
};

export const setDeepReadGenerationActivity = (
  topicId: string, runToken: number, active: boolean, title: string = '',
): void => {
  const runId: string = runIdFor(topicId, runToken);
  let state: DeepReadGenerationState | undefined = trackers.get(runId);
  if (active) {
    if (state === undefined) {
      state = { tracker: new GenerationProgressTracker(), title };
      trackers.set(runId, state);
    }
    report(topicId, runId, true, state);
    return;
  }
  if (state === undefined) return;
  trackers.delete(runId);
  report(topicId, runId, false, state);
};

// Every planning/writing request seeds its actual history before transport.
// Previous stages keep their accumulated received totals but are not counted
// a second time when included in a following request.
export const beginDeepReadGenerationStep = (topicId: string, runToken: number, history: UIMessage[]): void => {
  trackers.get(runIdFor(topicId, runToken))?.tracker.seedBaseline(history);
};

export const observeDeepReadGenerationSnapshot = (
  topicId: string, runToken: number, messages: UIMessage[],
): void => {
  const runId: string = runIdFor(topicId, runToken);
  const state: DeepReadGenerationState | undefined = trackers.get(runId);
  if (state === undefined) return;
  const gained: GenerationProgressTotals = state.tracker.observe(messages);
  if (gained.chars > 0 || gained.bytes > 0) report(topicId, runId, true, state);
};
