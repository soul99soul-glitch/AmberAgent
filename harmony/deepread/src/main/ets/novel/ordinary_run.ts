// One branch's latest ordinary request and its canonical progress. No provider credentials.
import type { NovelModelRequest, NovelModelOperation, NovelResponsesResumeCursor } from './model_running.ts';
import type { NovelCandidateProvenance, NovelChatMode, NovelGenerationGranularity } from './models.ts';
import type { NovelRunKind } from './prompt_catalog.ts';
import type { UIMessage } from '../agent/message.ts';
import { invalidInput } from './error.ts';

export type NovelOrdinaryRunStatus = 'running' | 'failed' | 'interrupted' | 'waiting_user' | 'completed';
export type NovelOrdinaryRequest = Omit<NovelModelRequest, 'checkpoint' | 'runId' | 'projectId'>;
export interface NovelOrdinaryRun {
  version: 1;
  id: string;
  branchId: string;
  mode: NovelChatMode;
  granularity: NovelGenerationGranularity | null;
  runKind: NovelRunKind | null;
  userText: string;
  candidate?: NovelCandidateProvenance;
  originalRequest: NovelOrdinaryRequest;
  // Prefix is for presentation only; fresh exact retry never sends prior failed partials.
  transcriptPrefix: UIMessage[];
  checkpointMessages: UIMessage[];
  cursor: NovelResponsesResumeCursor | null;
  status: NovelOrdinaryRunStatus;
  error: string | null;
  startedAt: number;
  updatedAt: number;
}
export interface NovelOrdinaryRunView {
  record: NovelOrdinaryRun | null;
  active: boolean;
  retryAllowed: boolean;
  resumeAllowed: boolean;
  blockedReason: string;
}
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T;
export const freezeNovelOrdinaryRequest = (request: NovelModelRequest): NovelOrdinaryRequest => copy({
  systemPrompt: request.systemPrompt, context: request.context, maxOutputTokens: request.maxOutputTokens,
  modelTarget: request.modelTarget, runtimeSnapshot: request.runtimeSnapshot,
  responsesResumeEnabled: request.responsesResumeEnabled, taskOptions: request.taskOptions,
  toolProfile: request.toolProfile, history: request.history, operation: request.operation,
});
export const ordinaryRunMessages = (run: NovelOrdinaryRun, messages: UIMessage[] = run.checkpointMessages): UIMessage[] => {
  const originalIds = new Set(run.originalRequest.history.map(message => message.id));
  const prefixIds = new Set(run.transcriptPrefix.map(message => message.id));
  return [...run.transcriptPrefix.map(message => messages.find(updated => updated.id === message.id) ?? message),
    ...messages.filter(message => !originalIds.has(message.id) && !prefixIds.has(message.id))];
};
export const ordinaryRunHistoryCount = (run: NovelOrdinaryRun): number => {
  const operation = run.originalRequest.operation;
  if (operation.kind === 'tool_continuation') {
    const index = run.transcriptPrefix.findIndex(message => message.parts.some(part => part.type === 'tool' && part.toolCallId === operation.toolCallId));
    if (index >= 0) return index;
  }
  return run.transcriptPrefix.length;
};
export const ordinaryContinuationHistory = (history: UIMessage[], progress: UIMessage[]): UIMessage[] => history.map(message => {
  const current = progress.find(updated => updated.id === message.id);
  if (current === undefined) return message;
  return { ...message, parts: message.parts.map(part => {
    if (part.type !== 'tool') return part;
    const tool = current.parts.find(updated => updated.type === 'tool' && updated.toolCallId === part.toolCallId);
    return tool?.type === 'tool' ? { ...part, approvalState: tool.approvalState, output: tool.output } : part;
  }) };
});
export const assertOrdinaryRunTranscript = (run: NovelOrdinaryRun, current: UIMessage[]): void => {
  if (JSON.stringify(ordinaryRunMessages(run)) !== JSON.stringify(current)) {
    throw invalidInput('生成后对话已变化，请保留已保存内容并重新生成');
  }
};
export const ordinaryRunCursor = (run: NovelOrdinaryRun, cursor: NovelResponsesResumeCursor | undefined): NovelResponsesResumeCursor | null => {
  if (cursor === undefined) return run.cursor;
  if (cursor.responseId.length === 0 || !Number.isInteger(cursor.sequence) || cursor.sequence < 0
    || cursor.providerId !== (run.originalRequest.runtimeSnapshot?.responsesProviderId
      ?? run.originalRequest.runtimeSnapshot?.providerId)) throw invalidInput('恢复游标与原请求模型不一致');
  if (run.cursor !== null && (run.cursor.responseId !== cursor.responseId || cursor.sequence < run.cursor.sequence)) {
    throw invalidInput('恢复游标来源已变化或顺序倒退');
  }
  return copy(cursor);
};
export const ordinaryRecoveryRequest = (run: NovelOrdinaryRun, kind: 'retry' | 'resume'): NovelOrdinaryRequest => {
  if (run.originalRequest.runtimeSnapshot === undefined) throw invalidInput('原请求模型配置未能冻结，请重新生成');
  if (run.status === 'completed' || run.status === 'waiting_user') throw invalidInput('本次生成无需恢复，请完成当前交互');
  if (kind === 'resume') {
    if (!run.originalRequest.responsesResumeEnabled || run.cursor === null) throw invalidInput('此请求没有可继续的服务端响应，请明确重试原请求');
    const operation: NovelModelOperation = { kind: 'resume_response', cursor: copy(run.cursor) };
    return { ...copy(run.originalRequest), history: copy(run.checkpointMessages), operation };
  }
  return copy(run.originalRequest);
};
