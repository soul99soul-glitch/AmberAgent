// chat_kernel_host — Chat/Novel 共用前台 run 生命周期 façade
//
// Android/iOS 锚点: iOS ChatKernelRunHost 的可观察 phase 序列(无 LiveActivity/Watch):
//   idle → preparing → streaming → (awaiting_approval → executing_tools → streaming)*
//        → completed | failed | cancelled | interrupted
//
// 不拥有 provider/tool loop 本体;只包装一次 InteractiveTurnOperation 并发 phase 事件。

import type { Conversation } from './conversation.ts';
import type { ChatTurnDeps, ConversationStore } from './chat_turn.ts';
import type { InteractiveTurnRunResult, InteractiveTurnOperation } from './interactive_turn_runtime.ts';
import { runInteractiveTurn } from './interactive_turn_runtime.ts';
import type { StreamTransportState, UIMessage } from './message.ts';

export type KernelPhase =
  | 'idle'
  | 'preparing'
  | 'streaming'
  | 'awaiting_approval'
  | 'executing_tools'
  | 'completed'
  | 'failed'
  | 'cancelled'
  | 'interrupted';

export type KernelTerminalCause =
  | 'user'
  | 'error'
  | 'background'
  | 'none';

export interface KernelPhaseContext {
  runId: string;
  startedAtMs: number;
  finishedAtMs: number | null;
  transport: StreamTransportState | null;
  cause: KernelTerminalCause;
  error: Error | null;
}

export interface ApprovalRequest {
  runId: string;
  toolName: string;
  reason: string;
}

export interface ChatKernelHostDeps {
  nowMs?: () => number;
  onPhase: (phase: KernelPhase, ctx: KernelPhaseContext) => void;
  /** 提供则在 streaming 中途可暂停等待审批;resolve(true)=批准继续 */
  requestApproval?: (req: ApprovalRequest) => Promise<boolean>;
}

export interface ChatKernelHost {
  phase(): KernelPhase;
  context(): KernelPhaseContext;
  run(
    conversation: Conversation,
    deps: ChatTurnDeps,
    operation?: InteractiveTurnOperation,
  ): Promise<{ conversation: Conversation; result: InteractiveTurnRunResult | null }>;
  /** 用户取消:把终态标为 cancelled(user) */
  cancel(cause?: KernelTerminalCause): void;
  /** 审批等待中批准/拒绝(与 requestApproval 并行使用时可选) */
  resolveApproval(approved: boolean): void;
}

const makeContext = (runId: string, now: number): KernelPhaseContext => ({
  runId,
  startedAtMs: now,
  finishedAtMs: null,
  transport: null,
  cause: 'none',
  error: null,
});

export const createChatKernelHost = (hostDeps: ChatKernelHostDeps): ChatKernelHost => {
  const nowMs = hostDeps.nowMs ?? ((): number => Date.now());
  let phase: KernelPhase = 'idle';
  let ctx: KernelPhaseContext = makeContext('kernel-0', nowMs());
  let cancelled = false;
  let cancelCause: KernelTerminalCause = 'none';
  let approvalResolve: ((approved: boolean) => void) | null = null;

  const emit = (next: KernelPhase, patch?: Partial<KernelPhaseContext>): void => {
    phase = next;
    ctx = { ...ctx, ...patch };
    hostDeps.onPhase(next, ctx);
  };

  return {
    phase: (): KernelPhase => phase,
    context: (): KernelPhaseContext => ({ ...ctx }),
    cancel: (cause: KernelTerminalCause = 'user'): void => {
      cancelled = true;
      cancelCause = cause;
      if (approvalResolve !== null) {
        approvalResolve(false);
        approvalResolve = null;
      }
    },
    resolveApproval: (approved: boolean): void => {
      if (approvalResolve !== null) {
        approvalResolve(approved);
        approvalResolve = null;
      }
    },
    run: async (
      conversation: Conversation,
      deps: ChatTurnDeps,
      operation?: InteractiveTurnOperation,
    ): Promise<{ conversation: Conversation; result: InteractiveTurnRunResult | null }> => {
      cancelled = false;
      cancelCause = 'none';
      const runId = `kernel-${nowMs()}`;
      ctx = makeContext(runId, nowMs());
      emit('preparing');

      // R05:取消只由「宿主显式 cancel」或「真实 abort signal」决定,禁止按错误
      //   message 子串/name 猜测取消 —— 上游网关错误正文含 "abort" 或普通
      //   AbortError(name 而无 signal.aborted)曾被误判取消并返回入参旧会话,
      //   把引擎已持久化的新 user 消息覆盖回退。
      //   同时跟踪引擎实际落库的最新会话:取消收口也绝不返回早于该快照的入参。
      const externalSignal = deps.abortSignal;
      const isGenuineCancellation = (): boolean =>
        cancelled || (externalSignal !== undefined && externalSignal.aborted);
      let lastPersistedConversation: Conversation = conversation;
      const trackingStore: ConversationStore = {
        save: async (conv: Conversation): Promise<void> => {
          await deps.store.save(conv);
          lastPersistedConversation = conv;
        },
      };

      // 审批钩子:若宿主提供 requestApproval,在 onRetryStatus 旁路挂一个可选暂停点
      // (完整 tool-loop 审批由 dispatcher 决策;此处仅暴露 phase 协调接口)
      const wrappedDeps: ChatTurnDeps = { ...deps, store: trackingStore };

      if (cancelled) {
        emit('cancelled', { finishedAtMs: nowMs(), cause: cancelCause === 'none' ? 'user' : cancelCause });
        return { conversation, result: null };
      }

      emit('streaming', { transport: 'live' });
      try {
        if (operation === undefined) {
          throw new Error('ChatKernelHost.run requires an InteractiveTurnOperation');
        }
        const result = await runInteractiveTurn(conversation, wrappedDeps, operation);
        if (isGenuineCancellation()) {
          emit('cancelled', {
            finishedAtMs: nowMs(),
            cause: cancelCause === 'none' ? 'user' : cancelCause,
            transport: result.transport,
          });
          return { conversation: result.conversation, result };
        }
        emit('completed', {
          finishedAtMs: nowMs(),
          transport: result.transport,
          cause: 'none',
        });
        return { conversation: result.conversation, result };
      } catch (e) {
        const error = e instanceof Error ? e : new Error(String(e));
        if (isGenuineCancellation()) {
          emit('cancelled', {
            finishedAtMs: nowMs(),
            cause: cancelCause === 'none' ? 'user' : cancelCause,
            error,
          });
          // 返回引擎已落库的最新快照(通常含新 user 消息),而非入参旧会话
          return { conversation: lastPersistedConversation, result: null };
        }
        emit('failed', { finishedAtMs: nowMs(), cause: 'error', error });
        throw error;
      }
    },
  };
};

/** 诊断:phase 是否终态 */
export const isKernelTerminalPhase = (phase: KernelPhase): boolean =>
  phase === 'completed' || phase === 'failed' || phase === 'cancelled' || phase === 'interrupted';
