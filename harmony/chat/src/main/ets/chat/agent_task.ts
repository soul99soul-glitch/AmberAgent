// agent_task — feature/task 三件套(D-130 依赖面)
//
// Android 基准:
//   feature/task/.../AgentTaskModels.kt(全文 136)— 三枚举/RetryPolicy/
//     OutputRef/Snapshot(kotlinx @SerialName snake_case 线格式)
//   feature/task/.../AgentTaskRecoveryManager.kt(全文 68)— recoverOnStartup
//     五分支 + TaskRecoveryAdapter
//   feature/task/.../AgentTaskStore.kt(全文 248 + :235-248 映射)— 文件持久化
//     任务看板(filesDir/amberagent/tasks/*.json)
// 承载层适配(登记):
//   - java.io.File → AgentTaskFilePort(mkdirs/listJsonFileNames/readText→
//     string|null/writeText/delete/exists);loadSnapshots/persist 由此异步化
//     (构造 → createAgentTaskStore 异步工厂)
//   - File.canonicalFile 私有输出校验 → files.isPathInside(root, path) 端口
//     (沙箱内无符号链接场景等价)
//   - MutableStateFlow → subscribe 监听列表(publish 时推送 list() 快照)
//   - File.exists 输出探测 → 异步 outputExists 回调注入(RecoveryManager)
//   - System.currentTimeMillis → Date.now()

import type { JsonObject, JsonValue } from './json.ts';

// ===== 枚举(AgentTaskModels.kt:7-71;@SerialName 小写线格式) =====

export type AgentTaskStatus =
  'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'TIMED_OUT' | 'INTERRUPTED';
export type AgentTaskQueueState = 'SCHEDULED' | 'QUEUED' | 'ACTIVE' | 'TERMINAL';
export type AgentTaskRecoveryState =
  'ACTIVE' | 'SCHEDULED' | 'INTERRUPTED' | 'RESTORABLE' | 'RETRYABLE' | 'OUTPUT_ONLY' | 'CLEANUP_ONLY';

// :31-32
export const agentTaskStatusRunning = (s: AgentTaskStatus): boolean =>
  s === 'QUEUED' || s === 'RUNNING';

const AGENT_TASK_STATUS_SERIAL: Record<AgentTaskStatus, string> = {
  QUEUED: 'queued', RUNNING: 'running', COMPLETED: 'completed', FAILED: 'failed',
  CANCELLED: 'cancelled', TIMED_OUT: 'timed_out', INTERRUPTED: 'interrupted',
};
const AGENT_TASK_QUEUE_STATE_SERIAL: Record<AgentTaskQueueState, string> = {
  SCHEDULED: 'scheduled', QUEUED: 'queued', ACTIVE: 'active', TERMINAL: 'terminal',
};
const AGENT_TASK_RECOVERY_STATE_SERIAL: Record<AgentTaskRecoveryState, string> = {
  ACTIVE: 'active', SCHEDULED: 'scheduled', INTERRUPTED: 'interrupted',
  RESTORABLE: 'restorable', RETRYABLE: 'retryable',
  OUTPUT_ONLY: 'output_only', CLEANUP_ONLY: 'cleanup_only',
};

const enumSerial = <T extends string>(table: Record<T, string>, value: T): string => table[value];

const parseEnum = <T extends string>(table: Record<T, string>, raw: string | undefined): T | null => {
  if (raw === undefined) return null;
  const keys: T[] = Object.keys(table) as T[];
  const found: T | undefined = keys.find((k: T): boolean => table[k] === raw);
  return found !== undefined ? found : null;
};

// ===== AgentTaskRetryPolicy(:73-83) =====

export interface AgentTaskRetryPolicy {
  retryable: boolean;          // 默认 false
  requiresApproval: boolean;   // 默认 true;'requires_approval'
  maxRetries: number;          // 默认 0;'max_retries'
  retryCount: number;          // 默认 0;'retry_count'
  reason: string | null;
}

export interface AgentTaskRetryPolicyInit {
  retryable?: boolean;
  requiresApproval?: boolean;
  maxRetries?: number;
  retryCount?: number;
  reason?: string | null;
}

export const makeAgentTaskRetryPolicy = (init: AgentTaskRetryPolicyInit = {}): AgentTaskRetryPolicy => ({
  retryable: init.retryable ?? false,
  requiresApproval: init.requiresApproval ?? true,
  maxRetries: init.maxRetries ?? 0,
  retryCount: init.retryCount ?? 0,
  reason: init.reason !== undefined ? init.reason : null,
});

// ===== AgentTaskOutputRef(:85-92) =====

export interface AgentTaskOutputRef {
  type: string;           // 默认 'file'
  path: string;
  tailOffset: number;     // 默认 0;'tail_offset'
  exists: boolean;        // 默认 false
}

export interface AgentTaskOutputRefInit {
  type?: string;
  path: string;
  tailOffset?: number;
  exists?: boolean;
}

export const makeAgentTaskOutputRef = (init: AgentTaskOutputRefInit): AgentTaskOutputRef => ({
  type: init.type ?? 'file',
  path: init.path,
  tailOffset: init.tailOffset ?? 0,
  exists: init.exists ?? false,
});

// ===== AgentTaskSnapshot(:94-136,22 字段) =====

export interface AgentTaskSnapshot {
  schemaVersion: number;                 // 默认 2;'schema_version'
  taskId: string;                        // 'task_id'
  type: string;
  title: string;
  spec: JsonObject | null;
  runtime: string | null;
  queueState: AgentTaskQueueState;       // 默认 ACTIVE;'queue_state'
  recoveryState: AgentTaskRecoveryState; // 默认 ACTIVE;'recovery_state'
  retryPolicy: AgentTaskRetryPolicy;     // 'retry_policy'
  outputRef: AgentTaskOutputRef | null;  // 'output_ref'
  sourceToolName: string | null;         // 'source_tool_name'
  sourceConversationId: string | null;   // 'source_conversation_id'
  status: AgentTaskStatus;
  outputPath: string | null;             // 'output_path'
  outputOffset: number;                  // 默认 0;'output_offset'
  createdAtMs: number;                   // 'created_at_ms'
  updatedAtMs: number;                   // 默认 = createdAtMs;'updated_at_ms'
  lastHeartbeatMs: number | null;        // 'last_heartbeat_ms'
  notified: boolean;                     // 默认 false
  cancelCapability: boolean;             // 默认 false;'cancel_capability'
  permissionTraceId: string | null;      // 'permission_trace_id'
  summary: string | null;
  lastErrorCode: string | null;          // 'last_error_code'
  error: string | null;
}

export interface AgentTaskSnapshotInit {
  schemaVersion?: number;
  taskId: string;
  type: string;
  title: string;
  spec?: JsonObject | null;
  runtime?: string | null;
  queueState?: AgentTaskQueueState;
  recoveryState?: AgentTaskRecoveryState;
  retryPolicy?: AgentTaskRetryPolicy;
  outputRef?: AgentTaskOutputRef | null;
  sourceToolName?: string | null;
  sourceConversationId?: string | null;
  status: AgentTaskStatus;
  outputPath?: string | null;
  outputOffset?: number;
  createdAtMs: number;
  updatedAtMs?: number;
  lastHeartbeatMs?: number | null;
  notified?: boolean;
  cancelCapability?: boolean;
  permissionTraceId?: string | null;
  summary?: string | null;
  lastErrorCode?: string | null;
  error?: string | null;
}

export const makeAgentTaskSnapshot = (init: AgentTaskSnapshotInit): AgentTaskSnapshot => ({
  schemaVersion: init.schemaVersion ?? 2,
  taskId: init.taskId,
  type: init.type,
  title: init.title,
  spec: init.spec !== undefined ? init.spec : null,
  runtime: init.runtime !== undefined ? init.runtime : null,
  queueState: init.queueState ?? 'ACTIVE',
  recoveryState: init.recoveryState ?? 'ACTIVE',
  retryPolicy: init.retryPolicy ?? makeAgentTaskRetryPolicy(),
  outputRef: init.outputRef !== undefined ? init.outputRef : null,
  sourceToolName: init.sourceToolName !== undefined ? init.sourceToolName : null,
  sourceConversationId: init.sourceConversationId !== undefined ? init.sourceConversationId : null,
  status: init.status,
  outputPath: init.outputPath !== undefined ? init.outputPath : null,
  outputOffset: init.outputOffset ?? 0,
  createdAtMs: init.createdAtMs,
  updatedAtMs: init.updatedAtMs ?? init.createdAtMs,
  lastHeartbeatMs: init.lastHeartbeatMs !== undefined ? init.lastHeartbeatMs : null,
  notified: init.notified ?? false,
  cancelCapability: init.cancelCapability ?? false,
  permissionTraceId: init.permissionTraceId !== undefined ? init.permissionTraceId : null,
  summary: init.summary !== undefined ? init.summary : null,
  lastErrorCode: init.lastErrorCode !== undefined ? init.lastErrorCode : null,
  error: init.error !== undefined ? init.error : null,
});

// Kotlin data class copy 的 patch 形(全可选;与 Init 区别 = 无必填)
export interface AgentTaskSnapshotPatch {
  schemaVersion?: number;
  taskId?: string;
  type?: string;
  title?: string;
  spec?: JsonObject | null;
  runtime?: string | null;
  queueState?: AgentTaskQueueState;
  recoveryState?: AgentTaskRecoveryState;
  retryPolicy?: AgentTaskRetryPolicy;
  outputRef?: AgentTaskOutputRef | null;
  sourceToolName?: string | null;
  sourceConversationId?: string | null;
  status?: AgentTaskStatus;
  outputPath?: string | null;
  outputOffset?: number;
  createdAtMs?: number;
  updatedAtMs?: number;
  lastHeartbeatMs?: number | null;
  notified?: boolean;
  cancelCapability?: boolean;
  permissionTraceId?: string | null;
  summary?: string | null;
  lastErrorCode?: string | null;
  error?: string | null;
}

// Kotlin data class copy 等价(全字段显式,零丢失)
export const copyAgentTaskSnapshot = (
  s: AgentTaskSnapshot, patch: AgentTaskSnapshotPatch,
): AgentTaskSnapshot => makeAgentTaskSnapshot({
  schemaVersion: patch.schemaVersion ?? s.schemaVersion,
  taskId: patch.taskId !== undefined ? patch.taskId : s.taskId,
  type: patch.type !== undefined ? patch.type : s.type,
  title: patch.title !== undefined ? patch.title : s.title,
  spec: patch.spec !== undefined ? patch.spec : s.spec,
  runtime: patch.runtime !== undefined ? patch.runtime : s.runtime,
  queueState: patch.queueState ?? s.queueState,
  recoveryState: patch.recoveryState ?? s.recoveryState,
  retryPolicy: patch.retryPolicy ?? s.retryPolicy,
  outputRef: patch.outputRef !== undefined ? patch.outputRef : s.outputRef,
  sourceToolName: patch.sourceToolName !== undefined ? patch.sourceToolName : s.sourceToolName,
  sourceConversationId:
    patch.sourceConversationId !== undefined ? patch.sourceConversationId : s.sourceConversationId,
  status: patch.status !== undefined ? patch.status : s.status,
  outputPath: patch.outputPath !== undefined ? patch.outputPath : s.outputPath,
  outputOffset: patch.outputOffset ?? s.outputOffset,
  createdAtMs: patch.createdAtMs !== undefined ? patch.createdAtMs : s.createdAtMs,
  updatedAtMs: patch.updatedAtMs ?? s.updatedAtMs,
  lastHeartbeatMs: patch.lastHeartbeatMs !== undefined ? patch.lastHeartbeatMs : s.lastHeartbeatMs,
  notified: patch.notified ?? s.notified,
  cancelCapability: patch.cancelCapability ?? s.cancelCapability,
  permissionTraceId:
    patch.permissionTraceId !== undefined ? patch.permissionTraceId : s.permissionTraceId,
  summary: patch.summary !== undefined ? patch.summary : s.summary,
  lastErrorCode: patch.lastErrorCode !== undefined ? patch.lastErrorCode : s.lastErrorCode,
  error: patch.error !== undefined ? patch.error : s.error,
});

// ===== 线格式(kotlinx @SerialName 逐字;缺省字段不编码 = kotlinx 默认) =====

const retryPolicyToJson = (p: AgentTaskRetryPolicy): JsonObject => {
  const j: JsonObject = {};
  j['retryable'] = p.retryable;
  j['requires_approval'] = p.requiresApproval;
  j['max_retries'] = p.maxRetries;
  j['retry_count'] = p.retryCount;
  if (p.reason !== null) j['reason'] = p.reason;
  return j;
};

const outputRefToJson = (r: AgentTaskOutputRef): JsonObject => {
  const j: JsonObject = {};
  j['type'] = r.type;
  j['path'] = r.path;
  j['tail_offset'] = r.tailOffset;
  j['exists'] = r.exists;
  return j;
};

export const agentTaskSnapshotToJson = (s: AgentTaskSnapshot): JsonObject => {
  const j: JsonObject = {};
  j['schema_version'] = s.schemaVersion;
  j['task_id'] = s.taskId;
  j['type'] = s.type;
  j['title'] = s.title;
  if (s.spec !== null) j['spec'] = s.spec;
  if (s.runtime !== null) j['runtime'] = s.runtime;
  j['queue_state'] = enumSerial(AGENT_TASK_QUEUE_STATE_SERIAL, s.queueState);
  j['recovery_state'] = enumSerial(AGENT_TASK_RECOVERY_STATE_SERIAL, s.recoveryState);
  j['retry_policy'] = retryPolicyToJson(s.retryPolicy);
  if (s.outputRef !== null) j['output_ref'] = outputRefToJson(s.outputRef);
  if (s.sourceToolName !== null) j['source_tool_name'] = s.sourceToolName;
  if (s.sourceConversationId !== null) j['source_conversation_id'] = s.sourceConversationId;
  j['status'] = enumSerial(AGENT_TASK_STATUS_SERIAL, s.status);
  if (s.outputPath !== null) j['output_path'] = s.outputPath;
  j['output_offset'] = s.outputOffset;
  j['created_at_ms'] = s.createdAtMs;
  j['updated_at_ms'] = s.updatedAtMs;
  if (s.lastHeartbeatMs !== null) j['last_heartbeat_ms'] = s.lastHeartbeatMs;
  j['notified'] = s.notified;
  j['cancel_capability'] = s.cancelCapability;
  if (s.permissionTraceId !== null) j['permission_trace_id'] = s.permissionTraceId;
  if (s.summary !== null) j['summary'] = s.summary;
  if (s.lastErrorCode !== null) j['last_error_code'] = s.lastErrorCode;
  if (s.error !== null) j['error'] = s.error;
  return j;
};

// kotlinx 宽松读:缺键 → 数据类默认(与 Android Json{ignoreUnknownKeys} 加载同语义)
const jsonStr = (j: JsonObject, key: string): string | undefined => {
  const v: JsonValue | undefined = j[key];
  return typeof v === 'string' ? v : undefined;
};
const jsonNum = (j: JsonObject, key: string): number | undefined => {
  const v: JsonValue | undefined = j[key];
  return typeof v === 'number' ? v : undefined;
};
const jsonBool = (j: JsonObject, key: string): boolean | undefined => {
  const v: JsonValue | undefined = j[key];
  return typeof v === 'boolean' ? v : undefined;
};
const jsonObj = (j: JsonObject, key: string): JsonObject | undefined => {
  const v: JsonValue | undefined = j[key];
  return typeof v === 'object' && v !== null && !Array.isArray(v) ? (v as JsonObject) : undefined;
};

export const agentTaskSnapshotFromJson = (j: JsonObject): AgentTaskSnapshot => {
  const retryRaw: JsonObject | undefined = jsonObj(j, 'retry_policy');
  const retry: AgentTaskRetryPolicy = retryRaw !== undefined ? makeAgentTaskRetryPolicy({
    retryable: jsonBool(retryRaw, 'retryable'),
    requiresApproval: jsonBool(retryRaw, 'requires_approval'),
    maxRetries: jsonNum(retryRaw, 'max_retries'),
    retryCount: jsonNum(retryRaw, 'retry_count'),
    reason: jsonStr(retryRaw, 'reason') !== undefined ? jsonStr(retryRaw, 'reason') : null,
  }) : makeAgentTaskRetryPolicy();
  const outputRaw: JsonObject | undefined = jsonObj(j, 'output_ref');
  const outputPath: string | undefined = jsonStr(j, 'output_path');
  const outputRef: AgentTaskOutputRef | null = outputRaw !== undefined ? makeAgentTaskOutputRef({
    type: jsonStr(outputRaw, 'type'),
    path: jsonStr(outputRaw, 'path') ?? '',
    tailOffset: jsonNum(outputRaw, 'tail_offset'),
    exists: jsonBool(outputRaw, 'exists'),
  }) : null;
  return makeAgentTaskSnapshot({
    schemaVersion: jsonNum(j, 'schema_version'),
    taskId: jsonStr(j, 'task_id') ?? '',
    type: jsonStr(j, 'type') ?? '',
    title: jsonStr(j, 'title') ?? '',
    spec: jsonObj(j, 'spec') !== undefined ? (jsonObj(j, 'spec') as JsonObject) : null,
    runtime: jsonStr(j, 'runtime') !== undefined ? (jsonStr(j, 'runtime') as string) : null,
    queueState: parseEnum(AGENT_TASK_QUEUE_STATE_SERIAL, jsonStr(j, 'queue_state')) ?? undefined,
    recoveryState:
      parseEnum(AGENT_TASK_RECOVERY_STATE_SERIAL, jsonStr(j, 'recovery_state')) ?? undefined,
    retryPolicy: retry,
    outputRef,
    sourceToolName: jsonStr(j, 'source_tool_name') !== undefined ? jsonStr(j, 'source_tool_name') : null,
    sourceConversationId:
      jsonStr(j, 'source_conversation_id') !== undefined ? jsonStr(j, 'source_conversation_id') : null,
    status: parseEnum(AGENT_TASK_STATUS_SERIAL, jsonStr(j, 'status')) ?? 'QUEUED',
    outputPath: outputPath !== undefined ? outputPath : null,
    outputOffset: jsonNum(j, 'output_offset'),
    createdAtMs: jsonNum(j, 'created_at_ms') ?? 0,
    updatedAtMs: jsonNum(j, 'updated_at_ms'),
    lastHeartbeatMs:
      jsonNum(j, 'last_heartbeat_ms') !== undefined ? (jsonNum(j, 'last_heartbeat_ms') as number) : null,
    notified: jsonBool(j, 'notified'),
    cancelCapability: jsonBool(j, 'cancel_capability'),
    permissionTraceId:
      jsonStr(j, 'permission_trace_id') !== undefined ? jsonStr(j, 'permission_trace_id') : null,
    summary: jsonStr(j, 'summary') !== undefined ? jsonStr(j, 'summary') : null,
    lastErrorCode: jsonStr(j, 'last_error_code') !== undefined ? jsonStr(j, 'last_error_code') : null,
    error: jsonStr(j, 'error') !== undefined ? jsonStr(j, 'error') : null,
  });
};

// ===== 状态映射(AgentTaskStore.kt:235-248) =====

export const agentTaskStatusToQueueState = (status: AgentTaskStatus, type: string): AgentTaskQueueState => {
  if (type === 'cron' && status === 'QUEUED') return 'SCHEDULED';
  if (status === 'QUEUED') return 'QUEUED';
  if (status === 'RUNNING') return 'ACTIVE';
  return 'TERMINAL';
};

export const agentTaskStatusToRecoveryState = (
  status: AgentTaskStatus, type: string, retryPolicy: AgentTaskRetryPolicy,
): AgentTaskRecoveryState => {
  if (type === 'cron' && status === 'QUEUED') return 'SCHEDULED';
  if (agentTaskStatusRunning(status)) return 'ACTIVE';
  if (retryPolicy.retryable &&
    (status === 'FAILED' || status === 'INTERRUPTED' || status === 'TIMED_OUT')) {
    return 'RETRYABLE';
  }
  if (status === 'COMPLETED') return 'OUTPUT_ONLY';
  return 'CLEANUP_ONLY';
};

// ===== AgentTaskRecoveryManager(全文 68) =====

export class AgentTaskRecoveryManager {
  constructor(
    private readonly outputExists: (snapshot: AgentTaskSnapshot) => Promise<boolean>,
  ) {}

  async recoverOnStartup(snapshot: AgentTaskSnapshot, nowMs: number = Date.now()): Promise<AgentTaskSnapshot> {
    const outputExists: boolean = await this.outputExists(snapshot);
    let outputRef: AgentTaskOutputRef | null = null;
    if (snapshot.outputRef !== null) {
      outputRef = makeAgentTaskOutputRef({
        type: snapshot.outputRef.type,
        path: snapshot.outputRef.path,
        tailOffset: snapshot.outputRef.tailOffset,
        exists: outputExists,
      });
    } else if (snapshot.outputPath !== null) {
      outputRef = makeAgentTaskOutputRef({
        type: snapshot.type === 'terminal' ? 'terminal_log' : 'file',
        path: snapshot.outputPath,
        tailOffset: snapshot.outputOffset,
        exists: outputExists,
      });
    }
    if (snapshot.type === 'cron') {
      // 禁用的 cron 任务重启后不得复活为待调度(spec.enabled 缺失的旧数据视为启用)
      if (snapshot.spec !== null && snapshot.spec['enabled'] === false) {
        return copyAgentTaskSnapshot(snapshot, {
          status: 'CANCELLED',
          queueState: 'TERMINAL',
          recoveryState: 'CLEANUP_ONLY',
          cancelCapability: false,
          outputRef,
          lastHeartbeatMs: nowMs,
          updatedAtMs: nowMs,
        });
      }
      return copyAgentTaskSnapshot(snapshot, {
        status: 'QUEUED',
        queueState: 'SCHEDULED',
        recoveryState: 'SCHEDULED',
        cancelCapability: false,
        outputRef,
        lastHeartbeatMs: nowMs,
      });
    }
    if (agentTaskStatusRunning(snapshot.status)) {
      return copyAgentTaskSnapshot(snapshot, {
        status: 'INTERRUPTED',
        queueState: 'TERMINAL',
        recoveryState: outputExists ? 'OUTPUT_ONLY' : 'INTERRUPTED',
        updatedAtMs: nowMs,
        error: 'Task was interrupted because AmberAgent restarted.',
        lastErrorCode: 'interrupted_by_restart',
        cancelCapability: false,
        outputRef,
      });
    }
    if (snapshot.status === 'COMPLETED' && outputExists) {
      return copyAgentTaskSnapshot(snapshot, {
        recoveryState: 'OUTPUT_ONLY',
        cancelCapability: false,
        outputRef,
        lastHeartbeatMs: nowMs,
      });
    }
    if ((snapshot.status === 'FAILED' || snapshot.status === 'INTERRUPTED' ||
      snapshot.status === 'TIMED_OUT') && snapshot.retryPolicy.retryable) {
      return copyAgentTaskSnapshot(snapshot, {
        recoveryState: 'RETRYABLE',
        cancelCapability: false,
        outputRef,
      });
    }
    return copyAgentTaskSnapshot(snapshot, {
      recoveryState: 'CLEANUP_ONLY',
      cancelCapability: false,
      outputRef,
    });
  }
}

// TaskRecoveryAdapter(:65-68)
export interface TaskRecoveryAdapter {
  readonly type: string;
  canRetry(snapshot: AgentTaskSnapshot): boolean;
}

// ===== AgentTaskFilePort(承载层) =====

export interface AgentTaskFilePort {
  mkdirs(dir: string): Promise<void>;
  // Android listFiles{extension=="json"} → 文件名列表(不含路径)
  listJsonFileNames(dir: string): Promise<string[]>;
  // 不存在/读取失败 → null(Android runCatching{readText}.getOrNull 语义)
  readText(path: string): Promise<string | null>;
  writeText(path: string, text: string): Promise<void>;
  delete(path: string): Promise<void>;
  exists(path: string): Promise<boolean>;
  // File.canonicalFile 私有输出校验等价:path 是否位于 root 之下
  isPathInside(root: string, path: string): Promise<boolean>;
}

// AsyncMutex(同 D-127/D-128 模式)
class AsyncMutex {
  private tail: Promise<void> = Promise.resolve();

  withLock<T>(block: () => Promise<T>): Promise<T> {
    const run: Promise<T> = this.tail.then(block);
    this.tail = run.then((): void => undefined, (): void => undefined);
    return run;
  }
}

// ===== AgentTaskStore(全文 248) =====

export interface AgentTaskStoreDeps {
  taskDir: string;        // filesDir/amberagent/tasks
  appFilesDir: string;    // filesDir(privateOutputFile 根)
  files: AgentTaskFilePort;
  recoveryManager?: AgentTaskRecoveryManager;
}

export class AgentTaskStore {
  private readonly deps: AgentTaskStoreDeps;
  private readonly recoveryManager: AgentTaskRecoveryManager;
  private readonly mutex: AsyncMutex = new AsyncMutex();
  private readonly tasks: Map<string, AgentTaskSnapshot> = new Map();
  private readonly cancelCallbacks: Map<string, () => Promise<boolean>> = new Map();
  private readonly retryCallbacks: Map<string, () => Promise<boolean>> = new Map();
  private readonly listeners: Array<(snapshots: AgentTaskSnapshot[]) => void> = [];

  private constructor(deps: AgentTaskStoreDeps) {
    this.deps = deps;
    this.recoveryManager = deps.recoveryManager ?? new AgentTaskRecoveryManager(
      async (snapshot: AgentTaskSnapshot): Promise<boolean> => {
        const path: string | null = snapshot.outputPath !== null
          ? snapshot.outputPath
          : (snapshot.outputRef !== null ? snapshot.outputRef.path : null);
        if (path === null) return false;
        return deps.files.exists(path);
      },
    );
  }

  // Android init{loadSnapshots();publish()} → 异步工厂
  static async create(deps: AgentTaskStoreDeps): Promise<AgentTaskStore> {
    const store: AgentTaskStore = new AgentTaskStore(deps);
    await deps.files.mkdirs(deps.taskDir);
    await store.loadSnapshots();
    store.publish();
    return store;
  }

  subscribe(listener: (snapshots: AgentTaskSnapshot[]) => void): void {
    this.listeners.push(listener);
  }

  async register(
    snapshot: AgentTaskSnapshot,
    cancel: (() => Promise<boolean>) | null = null,
    retry: (() => Promise<boolean>) | null = null,
  ): Promise<AgentTaskSnapshot> {
    return this.upsert(snapshot, cancel, retry);
  }

  async upsert(
    snapshot: AgentTaskSnapshot,
    cancel: (() => Promise<boolean>) | null = null,
    retry: (() => Promise<boolean>) | null = null,
  ): Promise<AgentTaskSnapshot> {
    return this.mutex.withLock(async (): Promise<AgentTaskSnapshot> => {
      this.tasks.set(snapshot.taskId, snapshot);
      if (cancel !== null) {
        this.cancelCallbacks.set(snapshot.taskId, cancel);
      } else if (!snapshot.cancelCapability) {
        this.cancelCallbacks.delete(snapshot.taskId);
      }
      if (retry !== null) {
        this.retryCallbacks.set(snapshot.taskId, retry);
      } else if (!snapshot.retryPolicy.retryable) {
        this.retryCallbacks.delete(snapshot.taskId);
      }
      await this.persist(snapshot);
      this.publish();
      return snapshot;
    });
  }

  async update(
    taskId: string,
    patch: {
      status?: AgentTaskStatus | null;
      queueState?: AgentTaskQueueState | null;
      summary?: string | null;
      error?: string | null;
      lastErrorCode?: string | null;
      outputPath?: string | null;
      outputOffset?: number | null;
      cancelCapability?: boolean | null;
      recoveryState?: AgentTaskRecoveryState | null;
      retryPolicy?: AgentTaskRetryPolicy | null;
      outputRef?: AgentTaskOutputRef | null;
      lastHeartbeatMs?: number | null;
    } = {},
  ): Promise<AgentTaskSnapshot | null> {
    return this.mutex.withLock(async (): Promise<AgentTaskSnapshot | null> => {
      const current: AgentTaskSnapshot | undefined = this.tasks.get(taskId);
      if (current === undefined) return null;
      const status: AgentTaskStatus | null = patch.status !== undefined ? patch.status : null;
      // 显式声明 cancelCapability=false = 终态封口 → 同步注销取消回调,防止
      //   completed/failed/interrupted 残留回调被后续 cleanup 误判为"活跃"(R25)。
      if (patch.cancelCapability === false) {
        this.cancelCallbacks.delete(taskId);
      }
      const next: AgentTaskSnapshot = copyAgentTaskSnapshot(current, {
        status: status !== null ? status : current.status,
        queueState: patch.queueState !== undefined && patch.queueState !== null
          ? patch.queueState
          : (status !== null
            ? agentTaskStatusToQueueState(status, current.type)
            : current.queueState),
        summary: patch.summary !== undefined && patch.summary !== null ? patch.summary : current.summary,
        // error/lastErrorCode:undefined=保留(未提供),null/''=显式清除 —
        // retry 成功与 subagent 正常收口传 null 意图是清掉旧错误,旧语义会永远保留
        error: patch.error !== undefined ? patch.error : current.error,
        lastErrorCode: patch.lastErrorCode !== undefined
          ? patch.lastErrorCode
          : current.lastErrorCode,
        outputPath: patch.outputPath !== undefined && patch.outputPath !== null
          ? patch.outputPath
          : current.outputPath,
        outputOffset: patch.outputOffset !== undefined && patch.outputOffset !== null
          ? patch.outputOffset
          : current.outputOffset,
        cancelCapability: patch.cancelCapability !== undefined && patch.cancelCapability !== null
          ? patch.cancelCapability
          : current.cancelCapability,
        recoveryState: patch.recoveryState !== undefined && patch.recoveryState !== null
          ? patch.recoveryState
          : (status !== null
            ? agentTaskStatusToRecoveryState(status, current.type, current.retryPolicy)
            : current.recoveryState),
        retryPolicy: patch.retryPolicy ?? current.retryPolicy,
        outputRef: patch.outputRef !== undefined && patch.outputRef !== null
          ? patch.outputRef
          : current.outputRef,
        lastHeartbeatMs: patch.lastHeartbeatMs !== undefined && patch.lastHeartbeatMs !== null
          ? patch.lastHeartbeatMs
          : current.lastHeartbeatMs,
        updatedAtMs: Date.now(),
      });
      this.tasks.set(taskId, next);
      await this.persist(next);
      this.publish();
      return next;
    });
  }

  async remove(taskId: string): Promise<boolean> {
    return this.mutex.withLock(async (): Promise<boolean> => {
      const removed: boolean = this.tasks.delete(taskId);
      this.cancelCallbacks.delete(taskId);
      this.retryCallbacks.delete(taskId);
      await this.deps.files.delete(`${this.deps.taskDir}/${taskId}.json`);
      this.publish();
      return removed;
    });
  }

  // :107-111  running 降序 → updatedAtMs 降序
  list(type: string | null = null, status: AgentTaskStatus | null = null): AgentTaskSnapshot[] {
    const out: AgentTaskSnapshot[] = [];
    this.tasks.forEach((snapshot: AgentTaskSnapshot) => {
      if (type !== null && snapshot.type !== type) return;
      if (status !== null && snapshot.status !== status) return;
      out.push(snapshot);
    });
    out.sort((a: AgentTaskSnapshot, b: AgentTaskSnapshot): number => {
      const ra: boolean = agentTaskStatusRunning(a.status);
      const rb: boolean = agentTaskStatusRunning(b.status);
      if (ra !== rb) return ra ? -1 : 1;
      return b.updatedAtMs - a.updatedAtMs;
    });
    return out;
  }

  read(taskId: string): AgentTaskSnapshot | null {
    const found: AgentTaskSnapshot | undefined = this.tasks.get(taskId);
    return found !== undefined ? found : null;
  }

  // 「可取消」由 cancelCapability + 活回调回答,不再由 status 猜测(R14×R25):
  //   approval_required 子代理任务板映射 INTERRUPTED 但运行对象仍 live 且回调在,
  //   此时看板取消必须真正触发回调。正常终态(非运行且无活回调)仍保持 no-op。
  async cancel(taskId: string): Promise<AgentTaskSnapshot> {
    const current: AgentTaskSnapshot | undefined = this.tasks.get(taskId);
    if (current === undefined) throw new Error(`Unknown agent task: ${taskId}`);
    const callback: (() => Promise<boolean>) | undefined = this.cancelCallbacks.get(taskId);
    if (callback === undefined || !current.cancelCapability) {
      // 正常终态(非运行且无可取消能力)→ no-op 保持;运行中但缺失回调 → 记录错误
      if (!agentTaskStatusRunning(current.status)) return current;
      const updated: AgentTaskSnapshot | null = await this.update(taskId, {
        error: 'Task cannot be cancelled from AmberAgent.',
      });
      return updated !== null ? updated : current;
    }
    let ok: boolean = false;
    try {
      ok = await callback();
    } catch {
      ok = false;
    }
    // A completed owner may win while the cancellation callback is awaiting.
    const latest: AgentTaskSnapshot | undefined = this.tasks.get(taskId);
    if (latest !== undefined && !agentTaskStatusRunning(latest.status) && !latest.cancelCapability) return latest;
    // 成功取消置 cancelCapability:false(update 同时注销回调),避免残留回调
    //   让后续 cleanup 永久拒绝。
    const updated: AgentTaskSnapshot | null = ok
      ? await this.update(taskId, {
        status: 'CANCELLED',
        summary: 'Cancellation requested.',
        cancelCapability: false,
      })
      : await this.update(taskId, { error: 'Cancellation request failed.' });
    return updated !== null ? updated : current;
  }

  async retry(taskId: string): Promise<AgentTaskSnapshot> {
    const current: AgentTaskSnapshot | undefined = this.tasks.get(taskId);
    if (current === undefined) throw new Error(`Unknown agent task: ${taskId}`);
    if (!current.retryPolicy.retryable) {
      const updated: AgentTaskSnapshot | null = await this.update(taskId, {
        error: 'Task is not retryable.',
        lastErrorCode: 'retry_not_allowed',
      });
      return updated !== null ? updated : current;
    }
    if (current.retryPolicy.retryCount >= current.retryPolicy.maxRetries) {
      const updated: AgentTaskSnapshot | null = await this.update(taskId, {
        error: 'Task retry limit reached.',
        lastErrorCode: 'retry_limit_reached',
      });
      return updated !== null ? updated : current;
    }
    const callback: (() => Promise<boolean>) | undefined = this.retryCallbacks.get(taskId);
    if (callback === undefined) {
      const updated: AgentTaskSnapshot | null = await this.update(taskId, {
        recoveryState: 'RETRYABLE',
        error: 'Task retry is available in metadata, but no live retry adapter is registered.',
        lastErrorCode: 'retry_adapter_missing',
      });
      return updated !== null ? updated : current;
    }
    const retryPolicy: AgentTaskRetryPolicy = makeAgentTaskRetryPolicy({
      retryable: current.retryPolicy.retryable,
      requiresApproval: current.retryPolicy.requiresApproval,
      maxRetries: current.retryPolicy.maxRetries,
      retryCount: current.retryPolicy.retryCount + 1,
      reason: current.retryPolicy.reason,
    });
    let ok: boolean = false;
    try {
      ok = await callback();
    } catch {
      ok = false;
    }
    const updated: AgentTaskSnapshot | null = ok
      ? await this.update(taskId, {
        status: 'QUEUED',
        queueState: 'QUEUED',
        recoveryState: 'ACTIVE',
        retryPolicy,
        summary: 'Retry requested.',
        error: null,
        lastErrorCode: null,
      })
      : await this.update(taskId, {
        retryPolicy,
        error: 'Retry request failed.',
        lastErrorCode: 'retry_failed',
      });
    return updated !== null ? updated : current;
  }

  // :107-111 terminal 判定:仅 COMPLETED/FAILED/CANCELLED/TIMED_OUT/INTERRUPTED
  //   为终态。QUEUED/RUNNING(含子代理 approval_required 映射的 RUNNING)非终态,
  //   不可清理 — 否则任务仍在跑/仍可取消却从看板消失、并删掉取消回调(R25)。
  async cleanup(taskId: string, deletePrivateOutput: boolean = false): Promise<boolean> {
    return this.mutex.withLock(async (): Promise<boolean> => {
      const current: AgentTaskSnapshot | undefined = this.tasks.get(taskId);
      if (current === undefined) return false;
      // 非终态(QUEUED/RUNNING)一律拒绝
      if (agentTaskStatusRunning(current.status)) return false;
      // 映射为 INTERRUPTED 但运行对象仍 live 且回调在(approval_required 子代理等)
      //   → 明确"可活跃",也必须拒绝清理记录/回调(R14×R25)。
      if (current.cancelCapability && this.cancelCallbacks.has(taskId)) return false;
      if (deletePrivateOutput) {
        const outputFile: string | null = await this.privateOutputFile(current);
        if (outputFile !== null) await this.deps.files.delete(outputFile);
      }
      this.tasks.delete(taskId);
      this.cancelCallbacks.delete(taskId);
      this.retryCallbacks.delete(taskId);
      await this.deps.files.delete(`${this.deps.taskDir}/${taskId}.json`);
      this.publish();
      return true;
    });
  }

  async reconcileOnStartup(): Promise<AgentTaskSnapshot[]> {
    return this.mutex.withLock(async (): Promise<AgentTaskSnapshot[]> => {
      const now: number = Date.now();
      const recovered: AgentTaskSnapshot[] = [];
      const entries: AgentTaskSnapshot[] = [];
      this.tasks.forEach((snapshot: AgentTaskSnapshot) => { entries.push(snapshot); });
      for (const snapshot of entries) {
        const next: AgentTaskSnapshot = await this.recoveryManager.recoverOnStartup(snapshot, now);
        recovered.push(next);
        this.tasks.set(next.taskId, next);
        await this.persist(next);
      }
      this.publish();
      recovered.sort((a: AgentTaskSnapshot, b: AgentTaskSnapshot): number => b.updatedAtMs - a.updatedAtMs);
      return recovered;
    });
  }

  private async loadSnapshots(): Promise<void> {
    const names: string[] = await this.deps.files.listJsonFileNames(this.deps.taskDir);
    for (const name of names) {
      const raw: string | null = await this.deps.files.readText(`${this.deps.taskDir}/${name}`);
      if (raw === null) continue;
      let snapshot: AgentTaskSnapshot | null = null;
      try {
        snapshot = agentTaskSnapshotFromJson(JSON.parse(raw) as JsonObject);
      } catch {
        snapshot = null;
      }
      if (snapshot === null) continue;
      const restored: AgentTaskSnapshot =
        await this.recoveryManager.recoverOnStartup(snapshot, Date.now());
      this.tasks.set(restored.taskId, restored);
      if (JSON.stringify(agentTaskSnapshotToJson(restored)) !== JSON.stringify(agentTaskSnapshotToJson(snapshot))) {
        await this.persist(restored);
      }
    }
  }

  private async privateOutputFile(snapshot: AgentTaskSnapshot): Promise<string | null> {
    const path: string | null = snapshot.outputRef !== null
      ? snapshot.outputRef.path
      : snapshot.outputPath;
    if (path === null) return null;
    const inside: boolean = await this.deps.files.isPathInside(this.deps.appFilesDir, path);
    return inside ? path : null;
  }

  private async persist(snapshot: AgentTaskSnapshot): Promise<void> {
    try {
      await this.deps.files.writeText(
        `${this.deps.taskDir}/${snapshot.taskId}.json`,
        JSON.stringify(agentTaskSnapshotToJson(snapshot)),
      );
    } catch {
      // Android runCatching 静默(持久化失败不阻断内存态)——忠实保留
    }
  }

  private publish(): void {
    const snapshots: AgentTaskSnapshot[] = this.list();
    this.listeners.forEach((listener: (s: AgentTaskSnapshot[]) => void) => { listener(snapshots); });
  }
}
