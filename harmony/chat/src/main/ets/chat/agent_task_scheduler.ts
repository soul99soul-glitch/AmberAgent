// agent_task_scheduler — AgentTaskScheduler 生命周期门面(D-131)
//
// Android 基准: feature/task/.../AgentTaskScheduler.kt(全文 103)
//   enqueue/start/complete/fail/cancel/retry/cleanup/reconcileOnStartup/
//   list/read/status + AgentRuntimeStatus + AgentTaskAdapter
// 承载层适配:tasksFlow StateFlow → AgentTaskStore.subscribe(转发);
//   byType = groupingBy.eachCount(LinkedHashMap 遇序)→ Map 插入序

import type {
  AgentTaskSnapshot, AgentTaskStatus, AgentTaskQueueState,
  AgentTaskRecoveryState, AgentTaskStore,
} from './agent_task.ts';
import { copyAgentTaskSnapshot } from './agent_task.ts';

export interface AgentRuntimeStatus {
  total: number;
  queued: number;
  running: number;
  completed: number;
  failed: number;
  cancelled: number;
  timedOut: number;
  interrupted: number;
  byType: Map<string, number>;
}

// AgentTaskScheduler.kt:99-103
export interface AgentTaskAdapter {
  readonly type: string;
  readonly maxConcurrency: number;
  cancel(taskId: string): Promise<boolean>;
}

export class AgentTaskScheduler {
  constructor(private readonly taskStore: AgentTaskStore) {}

  // tasksFlow(:8)→ 订阅转发
  subscribe(listener: (snapshots: AgentTaskSnapshot[]) => void): void {
    this.taskStore.subscribe(listener);
  }

  // :10-22
  async enqueue(
    snapshot: AgentTaskSnapshot,
    cancel: (() => Promise<boolean>) | null = null,
    retry: (() => Promise<boolean>) | null = null,
  ): Promise<AgentTaskSnapshot> {
    return this.taskStore.upsert(
      copyAgentTaskSnapshot(snapshot, {
        status: 'QUEUED',
        queueState: 'QUEUED',
        recoveryState: 'ACTIVE',
      }),
      cancel,
      retry,
    );
  }

  // :24-36
  async start(
    snapshot: AgentTaskSnapshot,
    cancel: (() => Promise<boolean>) | null = null,
    retry: (() => Promise<boolean>) | null = null,
  ): Promise<AgentTaskSnapshot> {
    return this.taskStore.upsert(
      copyAgentTaskSnapshot(snapshot, {
        status: 'RUNNING',
        queueState: 'ACTIVE',
        recoveryState: 'ACTIVE',
      }),
      cancel,
      retry,
    );
  }

  // :38-45
  async complete(taskId: string, summary: string | null = null): Promise<AgentTaskSnapshot | null> {
    return this.taskStore.update(taskId, {
      status: 'COMPLETED',
      queueState: 'TERMINAL',
      summary,
      cancelCapability: false,
    });
  }

  // :47-55
  async fail(taskId: string, message: string, code: string = 'failed'): Promise<AgentTaskSnapshot | null> {
    return this.taskStore.update(taskId, {
      status: 'FAILED',
      queueState: 'TERMINAL',
      error: message,
      lastErrorCode: code,
      cancelCapability: false,
    });
  }

  // :57
  async cancel(taskId: string): Promise<AgentTaskSnapshot> {
    return this.taskStore.cancel(taskId);
  }

  // :59
  async retry(taskId: string): Promise<AgentTaskSnapshot> {
    return this.taskStore.retry(taskId);
  }

  // :61-62
  async cleanup(taskId: string, deletePrivateOutput: boolean = false): Promise<boolean> {
    return this.taskStore.cleanup(taskId, deletePrivateOutput);
  }

  // :64
  async reconcileOnStartup(): Promise<AgentTaskSnapshot[]> {
    return this.taskStore.reconcileOnStartup();
  }

  // :66-67
  list(type: string | null = null, status: AgentTaskStatus | null = null): AgentTaskSnapshot[] {
    return this.taskStore.list(type, status);
  }

  // :69
  read(taskId: string): AgentTaskSnapshot | null {
    return this.taskStore.read(taskId);
  }

  // :71-84
  status(): AgentRuntimeStatus {
    const tasks: AgentTaskSnapshot[] = this.taskStore.list();
    const byType: Map<string, number> = new Map();
    tasks.forEach((snapshot: AgentTaskSnapshot) => {
      byType.set(snapshot.type, (byType.get(snapshot.type) ?? 0) + 1);
    });
    const countOf = (status: AgentTaskStatus): number =>
      tasks.filter((s: AgentTaskSnapshot): boolean => s.status === status).length;
    return {
      total: tasks.length,
      queued: countOf('QUEUED'),
      running: countOf('RUNNING'),
      completed: countOf('COMPLETED'),
      failed: countOf('FAILED'),
      cancelled: countOf('CANCELLED'),
      timedOut: countOf('TIMED_OUT'),
      interrupted: countOf('INTERRUPTED'),
      byType,
    };
  }
}
