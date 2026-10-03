// novel/model_running — 小说模型调用端口(移植自 Android NovelModelRunning.kt)
// entry 通过 NovelChatRunAdapter 复用 Chat provider/tool loop 与 canonical snapshot。

import type { StreamTransportState, UIMessage } from '../agent/message.ts';
import type { NovelModelTarget } from './models.ts';
import type { NovelMaterialInjectionDecision } from './material_injection.ts';

export type NovelToolContinuationVerdict =
  | { kind: 'approved' }
  | { kind: 'denied'; reason: string }
  | { kind: 'answered'; answer: string };

export type NovelModelOperation =
  | { kind: 'turn'; userPrompt: string }
  | { kind: 'resume_response'; cursor: NovelResponsesResumeCursor }
  | {
    kind: 'tool_continuation';
    toolCallId: string;
    verdict: NovelToolContinuationVerdict;
  };

export type NovelModelToolProfile = 'all' | 'read_only' | 'none';

export interface NovelContextSection {
  key: string;
  text: string;
  required: boolean;
}

export interface NovelRequestContext {
  sections: NovelContextSection[];
  excludedHistoryMessageIds: string[];
  materialDecisions?: NovelMaterialInjectionDecision[];
}

export interface NovelContextPreviewSection {
  key: string;
  included: boolean;
  required: boolean;
  estimatedTokens: number;
}
export interface NovelContextPreviewReceipt {
  tokenBudget: number;
  estimatedInputTokens: number;
  maxOutputTokens: number;
  modelLabel: string;
  preparedMessages: UIMessage[];
  sections: NovelContextPreviewSection[];
  materialDecisions: NovelMaterialInjectionDecision[];
  historyMessagesIncluded: number;
  historyMessagesExcluded: number;
}

export interface NovelModelRequest {
  runId: string;
  projectId: string;
  systemPrompt: string;
  maxOutputTokens: number | null;
  modelTarget: NovelModelTarget;
  runtimeSnapshot?: NovelRuntimeSnapshot;
  responsesResumeEnabled?: boolean;
  taskOptions?: NovelStructuredTaskOptions;
  // C6 candidate/review/planning 必须只读；普通创作 turn 默认保留完整工具目录。
  toolProfile?: NovelModelToolProfile;
  // 仅发送端副本使用；history/checkpoint 永远是完整 canonical 对话。
  context?: NovelRequestContext;
  // Novel transcript 的 canonical 完整历史；adapter 不再从纯文本重建消息。
  history: UIMessage[];
  operation: NovelModelOperation;
  // 每次 conversation store save 都必须等待此 port，保证 user/pending/tool output/终态
  // 已持久化后才推进下一步。
  checkpoint: (messages: UIMessage[], cursor?: NovelResponsesResumeCursor) => Promise<void>;
}

export interface NovelRuntimeSnapshot {
  providerId: string;
  modelId: string;
  configurationJson: string;
  frozenToolCatalogJson?: string;
  responsesProviderId?: string;
}
export interface NovelResponsesResumeCursor {
  responseId: string;
  sequence: number;
  providerId: string;
  terminalStatus?: 'completed';
}
export interface NovelPreparedRuntime {
  runtimeSnapshot: NovelRuntimeSnapshot;
  responsesResumeSupported: boolean;
}

export interface NovelStructuredTaskOptions {
  kind: 'stateDelta' | 'stateRebuild';
  reasoningEnabled: boolean;
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
  prepareOrdinaryRequest?(request: NovelModelRequest): Promise<NovelPreparedRuntime>;
  previewContext?(request: NovelModelRequest): Promise<NovelContextPreviewReceipt>;
  // 同一生产发送预算及估算口径；辅助全文任务只对正文分块，绝不截断作者必要事实。
  inputBudgetTokens?(modelTarget: NovelModelTarget, projectId: string, maxOutputTokens: number): Promise<number>;
  estimateInputTokens?(systemPrompt: string, userPrompt: string): number;
  // 预校验模型可解析;不可用则抛错(fail fast,不落库)
  validate(modelTarget: NovelModelTarget, projectId: string): Promise<void>;
  // 启动一次生成;事件经 subscribe 推送(调用方须同步订阅,避免漏早期事件)
  start(request: NovelModelRequest): NovelModelStream;
  cancel(runId: string): void;
}
