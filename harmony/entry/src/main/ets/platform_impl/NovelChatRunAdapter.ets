// Entry 边界：只注入 Harmony AbortController；provider/model/auth 解析由 AppContainer
// 通过 resolveRuntime 回调提供，避免 Adapter ↔ AppContainer 初始化环。

import type { NovelModelRunning, NovelModelTarget, StreamTransportState } from '@amber/deepread-domain';
import type { NovelInteractiveRuntimeConfig } from '@amber/chat-domain';
import { createNovelInteractiveAdapter } from '@amber/chat-domain';
import { createEntryAbortController } from './EntryAbortController.ets';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0x0001;
const TAG: string = 'Novel.Transport';

export const createEntryNovelChatRunAdapter = (
  resolveRuntime: (
    modelTarget: NovelModelTarget, projectId: string,
  ) => Promise<NovelInteractiveRuntimeConfig | null>,
): NovelModelRunning => createNovelInteractiveAdapter({
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
});
