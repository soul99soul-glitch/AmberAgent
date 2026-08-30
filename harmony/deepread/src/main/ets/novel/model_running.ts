// novel/model_running — 小说模型调用端口(移植自 Android NovelModelRunning.kt)
// entry 通过 NovelChatRunAdapter 复用 Chat provider/tool loop 与 canonical snapshot。

import type { StreamTransportState, UIMessage } from '../agent/message.ts';
import type { NovelModelTarget } from './models.ts';

export type NovelToolContinuationVerdict =
  | { kind: 'approved' }
  | { kind: 'denied'; reason: string }
  | { kind: 'answered'; answer: string };

export type NovelModelOperation =
  | { kind: 'turn'; userPrompt: string }
  | {
    kind: 'tool_continuation';
    toolCallId: string;
    verdict: NovelToolContinuationVerdict;
  };

export type NovelModelToolProfile = 'all' | 'read_only' | 'none';

export interface NovelModelRequest {
  runId: string;
  projectId: string;
  systemPrompt: string;
  maxOutputTokens: number | null;
  modelTarget: NovelModelTarget;
  // C6 candidate/review/planning 必须只读；普通创作 turn 默认保留完整工具目录。
  toolProfile?: NovelModelToolProfile;
  // Novel transcript 的 canonical 完整历史；adapter 不再从纯文本重建消息。
  history: UIMessage[];
  operation: NovelModelOperation;
  // 每次 conversation store save 都必须等待此 port，保证 user/pending/tool output/终态
  // 已持久化后才推进下一步。
  checkpoint: (messages: UIMessage[]) => Promise<void>;
}

export type NovelModelEvent =
  | { kind: 'status'; text: string }
  | {
    kind: 'snapshot'; messages: UIMessage[]; generationActive: boolean;
    textDeltasLive: boolean; transport: StreamTransportState;
  }
  | { kind: 'waiting_user' }
  | { kind: 'completed' }
  | { kind: 'failed'; message: string };

export interface NovelModelStream {
  subscribe(cb: (event: NovelModelEvent) => void): () => void;
}

export interface NovelModelRunning {
  // 预校验模型可解析;不可用则抛错(fail fast,不落库)
  validate(modelTarget: NovelModelTarget, projectId: string): Promise<void>;
  // 启动一次生成;事件经 subscribe 推送(调用方须同步订阅,避免漏早期事件)
  start(request: NovelModelRequest): NovelModelStream;
  cancel(runId: string): void;
}
