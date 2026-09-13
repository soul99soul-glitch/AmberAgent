// agent_cron_run_executor — Cron 任务执行器 Port + 默认实现
//
// 状态机归属: scheduler.fire(AgentCronStore.createEntryCronSchedulerPort)
// 独占 prepare/markRunStarted/Completed/Failed + scheduleNextRun。
// 本文件只做: notify + ChatTurn + save, 不再 mark*(避免双重登记)。
//
// Android 基准: AgentCronWorker.doWork 控制流在 scheduler.fire, 执行体为 ChatTurn。

import type { AgentCronTask, AgentCronManager } from './agent_cron.ts';
import type { ChatTurnDeps } from './chat_turn.ts';
import { runChatTurn } from './chat_turn.ts';
import type { Conversation } from './conversation.ts';
import type { ConversationRepository } from './persistence.ts';
import type { AbortSignalLike } from '@amber/deepread-domain';

export interface CronNotifierPort {
  notifyRunning(task: AgentCronTask): void;
  notifyCompleted(task: AgentCronTask): void;
  notifyFailed(task: AgentCronTask, error: Error): void;
  cancel(taskId: string): void;
}

export interface CronRunExecutorDeps {
  manager: AgentCronManager;
  repository: ConversationRepository;
  /** 单次生成的 ChatTurn 依赖工厂(assistant/provider/store 等) */
  buildTurnDeps: (conversation: Conversation, task: AgentCronTask) => ChatTurnDeps;
  notifier?: CronNotifierPort;
  nowMs?: () => number;
}

/** R17:entry 执行器形状——taskId + run 级 signal(scheduler 传入) */
export type CronRunExecutor = (taskId: string, signal: AbortSignalLike) => Promise<void>;

export interface CronRunResult {
  taskId: string;
  conversationId: string;
  ok: boolean;
  error: Error | null;
}

/** 执行一个 cron 任务:定位/创建会话 → runChatTurn(prompt) → save;不调用 mark*。
 *  R17:signal 可选,透传 ChatTurnDeps.abortSignal 贯穿 Provider(工具循环继承)。 */
export const runCronTaskOnce = async (
  deps: CronRunExecutorDeps,
  task: AgentCronTask,
  signal?: AbortSignalLike,
): Promise<CronRunResult> => {
  const notifier = deps.notifier;
  try {
    notifier?.notifyRunning(task);

    let conversation: Conversation | null = null;
    if (task.conversationId.length > 0) {
      conversation = await deps.repository.getById(task.conversationId);
    }
    if (conversation === null) {
      // 无绑定会话时新建(与 Android Worker 绑 conversation 语义对齐:有 id 用 id)
      conversation = {
        id: task.conversationId.length > 0 ? task.conversationId : `cron-${task.id}`,
        assistantId: '',
        title: task.title,
        createAt: new Date().toISOString(),
        updateAt: new Date().toISOString(),
        chatSuggestions: [],
        isPinned: false,
        autoApproveToolCalls: false,
        messageNodes: [],
      };
      await deps.repository.save(conversation);
    }

    const turnDeps: ChatTurnDeps = deps.buildTurnDeps(conversation, task);
    const out: Conversation = await runChatTurn(
      conversation, task.prompt, signal !== undefined ? { ...turnDeps, abortSignal: signal } : turnDeps);
    await deps.repository.save(out);

    const doneTask: AgentCronTask = { ...task, lastStatus: 'Succeeded', lastError: null };
    notifier?.notifyCompleted(doneTask);
    return { taskId: task.id, conversationId: out.id, ok: true, error: null };
  } catch (e) {
    const error = e instanceof Error ? e : new Error(String(e));
    notifier?.notifyFailed(task, error);
    return { taskId: task.id, conversationId: task.conversationId, ok: false, error };
  }
};

/** 到期调度:manager 负责 nextRun 计算;本函数只扫 due 并串行执行 */
export const fireDueCronTasks = async (
  deps: CronRunExecutorDeps,
  nowMs?: number,
): Promise<CronRunResult[]> => {
  const now: number = nowMs ?? (deps.nowMs !== undefined ? deps.nowMs() : Date.now());
  const tasks: AgentCronTask[] = await deps.manager.listTasksSnapshot();
  const out: CronRunResult[] = [];
  for (const task of tasks) {
    if (!task.enabled) continue;
    if (task.nextRunAtMs === null || task.nextRunAtMs > now) continue;
    out.push(await runCronTaskOnce(deps, task));
  }
  return out;
};

/** 诊断:任务是否到期 */
export const isCronTaskDue = (task: AgentCronTask, nowMs: number): boolean =>
  task.enabled && task.nextRunAtMs !== null && task.nextRunAtMs <= nowMs;
