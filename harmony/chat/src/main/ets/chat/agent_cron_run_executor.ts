// agent_cron_run_executor — Cron 任务执行器 Port + 默认实现
//
// Android 基准: AgentCronWorker.doWork
//   prepare → markStarted → ChatService 发送+等待 → markCompleted/Failed
//   → 通知 → scheduleNextRun
//
// 鸿蒙: 纯逻辑 executor 契约;entry 注入「发送到会话」与 Notifier。
// 无头运行 = 调用 ChatTurnDeps 路径(runChatTurn),不依赖 ChatPage UI。

import type { AgentCronTask, AgentCronManager } from './agent_cron.ts';
import type { ChatTurnDeps } from './chat_turn.ts';
import { runChatTurn } from './chat_turn.ts';
import { currentMessages } from './conversation.ts';
import type { Conversation } from './conversation.ts';
import type { ConversationRepository } from './persistence.ts';
import type { UIMessage } from './message.ts';

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

export interface CronRunResult {
  taskId: string;
  conversationId: string;
  ok: boolean;
  error: Error | null;
}

/** 执行一个 cron 任务:定位/创建会话 → runChatTurn(prompt) → 终态登记 */
export const runCronTaskOnce = async (
  deps: CronRunExecutorDeps,
  task: AgentCronTask,
): Promise<CronRunResult> => {
  const notifier = deps.notifier;
  try {
    notifier?.notifyRunning(task);
    await deps.manager.markRunStarted(task.id);

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
    const out: Conversation = await runChatTurn(conversation, task.prompt, turnDeps);
    await deps.repository.save(out);

    await deps.manager.markRunCompleted(task.id);
    const doneTask: AgentCronTask = { ...task, lastStatus: 'Succeeded', lastError: null };
    notifier?.notifyCompleted(doneTask);
    return { taskId: task.id, conversationId: out.id, ok: true, error: null };
  } catch (e) {
    const error = e instanceof Error ? e : new Error(String(e));
    await deps.manager.markRunFailed(task.id, error.message).catch((): void => {});
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
