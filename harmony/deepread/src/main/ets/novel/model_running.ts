// novel/model_running — 小说模型调用端口(移植自 Android NovelModelRunning.kt)
// entry 用流式 OpenAI 兼容客户端实现(NovelModelAdapter.ets),复用 fetchStream + createOpenAiTextStream。

import type { StreamTransportState, UIMessage } from '../agent/message.ts';

export interface NovelModelRequest {
  runId: string;
  systemPrompt: string;
  userPrompt: string;
  maxOutputTokens: number | null;
  modelId: string | null;   // null = 跟随默认模型
}

export type NovelModelEvent =
  | { kind: 'activity' }
  | { kind: 'status'; text: string }
  | {
    kind: 'snapshot'; messages: UIMessage[]; generationActive: boolean;
    textDeltasLive: boolean; transport: StreamTransportState;
  }
  | { kind: 'delta'; text: string }
  | { kind: 'replace'; text: string }
  | { kind: 'completed' }
  | { kind: 'failed'; message: string };

export interface NovelModelStream {
  subscribe(cb: (event: NovelModelEvent) => void): () => void;
}

export interface NovelModelRunning {
  // 预校验模型可解析;不可用则抛错(fail fast,不落库)
  validate(modelId: string | null): Promise<void>;
  // 启动一次生成;事件经 subscribe 推送(调用方须同步订阅,避免漏早期事件)
  start(request: NovelModelRequest): NovelModelStream;
  cancel(runId: string): void;
}
