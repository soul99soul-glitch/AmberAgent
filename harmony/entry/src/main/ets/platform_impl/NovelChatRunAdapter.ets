// Entry 边界：只注入 Harmony AbortController；provider/model/auth 解析由 AppContainer
// 通过 resolveRuntime 回调提供，避免 Adapter ↔ AppContainer 初始化环。
// E13:包装既有 NovelModelRunning.start/subscribe — 经 onRawSnapshot(视觉变换前)
//   用真实 runId 维护共享后台活动集;同步 wrapper 订阅不丢早期 terminal;
//   waiting_user/completed/failed/cancel 注销精确 run,脱页后仍如实维护。

import type {
  NovelModelEvent, NovelModelRequest, NovelModelRunning, NovelModelStream, NovelModelTarget,
  StreamTransportState,
  NovelContextPreviewReceipt,
  NovelRuntimeSnapshot, NovelPreparedRuntime,
} from '@amber/deepread-domain';
import type { Context } from '@kit.AbilityKit';
import type { NovelInteractiveRuntimeConfig } from '@amber/chat-domain';
import { createNovelInteractiveAdapter } from '@amber/chat-domain';
import { createEntryAbortController } from './EntryAbortController.ets';
import { reportGenerationProgress } from './BackgroundGenerationKeepAlive.ets';
import { GenerationProgressTracker } from './GenerationProgressTracker.ets';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0x0001;
const TAG: string = 'Novel.Transport';

export const createEntryNovelChatRunAdapter = (
  resolveRuntime: (
    modelTarget: NovelModelTarget, projectId: string, snapshot?: NovelRuntimeSnapshot,
  ) => Promise<NovelInteractiveRuntimeConfig | null>,
): NovelModelRunning => {
  const projectIds: Map<string, string> = new Map();
  const trackers: Map<string, GenerationProgressTracker> = new Map();

  const report = (runId: string, active: boolean): void => {
    const context: Context | undefined = AppStorage.get<Context>('abilityContext');
    if (context === undefined) return;
    const totals = trackers.get(runId)?.totals() ?? { chars: 0, bytes: 0 };
    reportGenerationProgress(context, {
      source: 'novel', runId: runId, generationActive: active,
      novelWorkspaceProjectId: projectIds.get(runId) ?? '',
      receivedChars: totals.chars, receivedBytes: totals.bytes,
    });
  };

  const inner: NovelModelRunning = createNovelInteractiveAdapter({
    resolveRuntime,
    createAbortController: createEntryAbortController,
    onTransportQualified: (
      runId: string, transport: StreamTransportState,
      firstParsedDeltaAt: number | null, dataEndAt: number | null,
    ): void => {
      hilog.info(DOMAIN, TAG,
        'run=%{public}s transport=%{public}s first=%{public}s end=%{public}s',
        runId, transport, firstParsedDeltaAt === null ? 'none' : String(firstParsedDeltaAt),
        dataEndAt === null ? 'none' : String(dataEndAt));
    },
    onRawSnapshot: (request: NovelModelRequest, messages): void => {
      let tracker: GenerationProgressTracker | undefined = trackers.get(request.runId);
      if (tracker === undefined) {
        tracker = new GenerationProgressTracker();
        // 启动历史基线:已有内容不计为本租期接收
        tracker.seedBaseline(request.history);
        trackers.set(request.runId, tracker);
      }
      const gained = tracker.observe(messages);
      if (gained.chars > 0 || gained.bytes > 0) report(request.runId, true);
    },
  });

  return {
    prepareOrdinaryRequest: (request: NovelModelRequest): Promise<NovelPreparedRuntime> =>
      inner.prepareOrdinaryRequest!(request),
    previewContext: (request: NovelModelRequest): Promise<NovelContextPreviewReceipt> => inner.previewContext!(request),
    inputBudgetTokens: (target: NovelModelTarget, projectId: string, outputTokens: number): Promise<number> =>
      inner.inputBudgetTokens!(target, projectId, outputTokens),
    estimateInputTokens: (systemPrompt: string, userPrompt: string): number =>
      inner.estimateInputTokens!(systemPrompt, userPrompt),
    validate: (modelTarget: NovelModelTarget, projectId: string): Promise<void> =>
      inner.validate(modelTarget, projectId),
    start: (request: NovelModelRequest): NovelModelStream => {
      const stream: NovelModelStream = inner.start(request);
      projectIds.set(request.runId, request.projectId);
      // 同步订阅(先于返回),早期 terminal 不会漏
      const unsubscribe: () => void = stream.subscribe((event: NovelModelEvent): void => {
        if (event.kind === 'snapshot') {
          report(request.runId, event.generationActive);
          return;
        }
        if (event.kind === 'waiting_user' || event.kind === 'completed' || event.kind === 'failed') {
          report(request.runId, false);
          trackers.delete(request.runId);
          projectIds.delete(request.runId);
          unsubscribe();
        }
      });
      // preparing/连接期以零进度登记活动
      report(request.runId, true);
      return stream;
    },
    cancel: (runId: string): void => {
      report(runId, false);
      trackers.delete(runId);
      projectIds.delete(runId);
      inner.cancel(runId);
    },
  };
};
