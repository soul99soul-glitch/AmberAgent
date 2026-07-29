// agent_task_tools — AgentTaskTools 六件(D-131)
//
// Android 基准: feature/tools/impl/src/main/kotlin/app/amber/feature/tools/AgentTaskTools.kt(全文 210 行)
//   agent_task_list/read/cancel/retry/cleanup + agent_runtime_status
//   toJson 键序 = Android :168-204
// 承载层适配:
//   - java.io.File.exists → AgentTaskOutputProbe.exists 异步端口
//   - kotlinx JsonPrimitive.contentOrNull/toBooleanStrictOrNull → 本文件输入助手

import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessagePart } from './message.ts';
import type { AgentTool } from './tool.ts';
import { makeAgentTool, makeInputSchemaObj } from './tool.ts';
import type { AgentTaskRetryPolicy, AgentTaskSnapshot } from './agent_task.ts';
import type { AgentRuntimeStatus, AgentTaskScheduler } from './agent_task_scheduler.ts';

// java.io.File.exists 的异步承载端口；outputRef 存在时不调用。
export interface AgentTaskOutputProbe {
  outputExists(path: string): Promise<boolean>;
}

const EMPTY_OUTPUT_PROBE: AgentTaskOutputProbe = {
  outputExists: (_path: string): Promise<boolean> => Promise.resolve(false),
};

// ToolJson.kt:string:JsonPrimitive.contentOrNull(数字/布尔转其文本;对象/数组为 null)
const contentOrNull = (input: JsonValue, key: string): string | null => {
  if (typeof input !== 'object' || input === null || Array.isArray(input)) return null;
  const value: JsonValue | undefined = (input as JsonObject)[key];
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return null;
};

const requiredString = (input: JsonValue, key: string): string => {
  const value: string | null = contentOrNull(input, key);
  if (value === null) throw new Error(`${key} is required`);
  return value;
};

// toBooleanStrictOrNull:仅 true/false 文本(含 JSON boolean 的 content)生效。
const strictBoolean = (input: JsonValue, key: string): boolean | null => {
  const value: string | null = contentOrNull(input, key);
  if (value === 'true') return true;
  if (value === 'false') return false;
  return null;
};

const stringProp = (description: string): JsonObject => ({
  type: 'string', description,
});

const booleanProp = (description: string): JsonObject => ({
  type: 'boolean', description,
});

const textJson = (payload: JsonObject): UIMessagePart[] => [{
  type: 'text',
  text: JSON.stringify(payload),
  metadata: null,
}];

const taskStatus = (raw: string | null): AgentTaskSnapshot['status'] | null => {
  if (raw === null) return null;
  const statuses: AgentTaskSnapshot['status'][] = [
    'QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'INTERRUPTED',
  ];
  return statuses.find((status: AgentTaskSnapshot['status']): boolean =>
    status.toLowerCase() === raw.toLowerCase()) ?? null;
};

const taskSnapshotToJson = async (
  snapshot: AgentTaskSnapshot,
  outputProbe: AgentTaskOutputProbe,
): Promise<JsonObject> => {
  const retry: AgentTaskRetryPolicy = snapshot.retryPolicy;
  let outputExists: boolean = false;
  if (snapshot.outputRef !== null) {
    outputExists = snapshot.outputRef.exists;
  } else if (snapshot.outputPath !== null) {
    outputExists = await outputProbe.outputExists(snapshot.outputPath);
  }

  const json: JsonObject = {};
  json['task_id'] = snapshot.taskId;
  json['type'] = snapshot.type;
  json['title'] = snapshot.title;
  json['queue_state'] = snapshot.queueState.toLowerCase();
  json['recovery_state'] = snapshot.recoveryState.toLowerCase();
  json['retryable'] = retry.retryable;
  json['retry_requires_approval'] = retry.requiresApproval;
  json['retry_count'] = retry.retryCount;
  json['retry_max'] = retry.maxRetries;
  if (retry.reason !== null) json['retry_reason'] = retry.reason;
  json['output_exists'] = outputExists;
  if (snapshot.outputRef !== null) {
    const outputRef: JsonObject = {};
    outputRef['type'] = snapshot.outputRef.type;
    outputRef['path'] = snapshot.outputRef.path;
    outputRef['tail_offset'] = snapshot.outputRef.tailOffset;
    outputRef['exists'] = snapshot.outputRef.exists;
    json['output_ref'] = outputRef;
  }
  if (snapshot.lastHeartbeatMs !== null) json['last_heartbeat_ms'] = snapshot.lastHeartbeatMs;
  if (snapshot.spec !== null) json['spec'] = snapshot.spec;
  if (snapshot.runtime !== null) json['runtime'] = snapshot.runtime;
  if (snapshot.sourceToolName !== null) json['source_tool_name'] = snapshot.sourceToolName;
  if (snapshot.sourceConversationId !== null) json['source_conversation_id'] = snapshot.sourceConversationId;
  json['status'] = snapshot.status.toLowerCase();
  if (snapshot.outputPath !== null) json['output_path'] = snapshot.outputPath;
  json['output_offset'] = snapshot.outputOffset;
  json['created_at_ms'] = snapshot.createdAtMs;
  json['updated_at_ms'] = snapshot.updatedAtMs;
  json['notified'] = snapshot.notified;
  json['cancel_capability'] = snapshot.cancelCapability;
  if (snapshot.permissionTraceId !== null) json['permission_trace_id'] = snapshot.permissionTraceId;
  if (snapshot.summary !== null) json['summary'] = snapshot.summary.substring(0, 4000);
  if (snapshot.lastErrorCode !== null) json['last_error_code'] = snapshot.lastErrorCode;
  if (snapshot.error !== null) json['error'] = snapshot.error.substring(0, 1000);
  return json;
};

export class AgentTaskTools {
  private readonly taskScheduler: AgentTaskScheduler;
  private readonly outputProbe: AgentTaskOutputProbe;

  constructor(taskScheduler: AgentTaskScheduler, outputProbe: AgentTaskOutputProbe = EMPTY_OUTPUT_PROBE) {
    this.taskScheduler = taskScheduler;
    this.outputProbe = outputProbe;
  }

  tools(): AgentTool[] {
    return [
      this.listTool(),
      this.readTool(),
      this.cancelTool(),
      this.retryTool(),
      this.cleanupTool(),
      this.runtimeStatusTool(),
    ];
  }

  getTools(): AgentTool[] {
    return this.tools();
  }

  private listTool(): AgentTool {
    return makeAgentTool({
      name: 'agent_task_list',
      description: 'List AmberAgent background tasks in this Android app, including terminal jobs, subagents, model council runs, cron tasks, and report jobs.',
      parameters: (): ReturnType<typeof makeInputSchemaObj> => makeInputSchemaObj({
        type: stringProp('Optional task type filter, such as terminal, subagent, model_council, cron, officepro.'),
        status: stringProp('Optional status filter: queued, running, completed, failed, cancelled, timed_out, interrupted.'),
      }),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
        const status: AgentTaskSnapshot['status'] | null = taskStatus(contentOrNull(input, 'status'));
        const typeValue: string | null = contentOrNull(input, 'type');
        const type: string | null = typeValue !== null && typeValue.trim().length > 0 ? typeValue : null;
        const tasks: AgentTaskSnapshot[] = this.taskScheduler.list(type, status);
        const serialized: JsonValue[] = [];
        for (const task of tasks) {
          serialized.push(await taskSnapshotToJson(task, this.outputProbe));
        }
        return textJson({ status: 'ok', tasks: serialized });
      },
    });
  }

  private readTool(): AgentTool {
    return makeAgentTool({
      name: 'agent_task_read',
      description: 'Read one AmberAgent background task snapshot by task_id.',
      parameters: (): ReturnType<typeof makeInputSchemaObj> => makeInputSchemaObj({
        task_id: stringProp('Agent task id.'),
      }, ['task_id']),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
        const taskId: string = requiredString(input, 'task_id');
        const task: AgentTaskSnapshot | null = this.taskScheduler.read(taskId);
        const payload: JsonObject = { status: task === null ? 'not_found' : 'ok', task_id: taskId };
        if (task !== null) payload['task'] = await taskSnapshotToJson(task, this.outputProbe);
        return textJson(payload);
      },
    });
  }

  private cancelTool(): AgentTool {
    return makeAgentTool({
      name: 'agent_task_cancel',
      description: 'Cancel a running AmberAgent background task when it exposes a cancel capability.',
      needsApproval: true,
      parameters: (): ReturnType<typeof makeInputSchemaObj> => makeInputSchemaObj({
        task_id: stringProp('Agent task id.'),
      }, ['task_id']),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
        const task: AgentTaskSnapshot = await this.taskScheduler.cancel(requiredString(input, 'task_id'));
        return textJson({ status: task.status.toLowerCase(), task: await taskSnapshotToJson(task, this.outputProbe) });
      },
    });
  }

  private retryTool(): AgentTool {
    return makeAgentTool({
      name: 'agent_task_retry',
      description: 'Retry an interrupted or failed AmberAgent background task only when its snapshot marks it retryable. Mutating or sensitive retries still require approval.',
      needsApproval: true,
      parameters: (): ReturnType<typeof makeInputSchemaObj> => makeInputSchemaObj({
        task_id: stringProp('Agent task id.'),
      }, ['task_id']),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
        const task: AgentTaskSnapshot = await this.taskScheduler.retry(requiredString(input, 'task_id'));
        return textJson({ status: task.status.toLowerCase(), task: await taskSnapshotToJson(task, this.outputProbe) });
      },
    });
  }

  private cleanupTool(): AgentTool {
    return makeAgentTool({
      name: 'agent_task_cleanup',
      description: 'Remove a completed, failed, interrupted, or cancelled AmberAgent task snapshot. It only deletes app-private output when delete_private_output=true.',
      needsApproval: true,
      parameters: (): ReturnType<typeof makeInputSchemaObj> => makeInputSchemaObj({
        task_id: stringProp('Agent task id.'),
        delete_private_output: booleanProp('Also delete app-private logs/transcripts referenced by this task. Workspace files are never deleted by this flag.'),
      }, ['task_id']),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
        const taskId: string = requiredString(input, 'task_id');
        const deletePrivateOutput: boolean = strictBoolean(input, 'delete_private_output') ?? false;
        const removed: boolean = await this.taskScheduler.cleanup(taskId, deletePrivateOutput);
        return textJson({
          status: removed ? 'ok' : 'not_found',
          task_id: taskId,
          delete_private_output: deletePrivateOutput,
        });
      },
    });
  }

  private runtimeStatusTool(): AgentTool {
    return makeAgentTool({
      name: 'agent_runtime_status',
      description: 'Read a concise AmberAgent harness runtime status summary, including task counts by status and type.',
      parameters: (): ReturnType<typeof makeInputSchemaObj> => makeInputSchemaObj({}),
      execute: async (): Promise<UIMessagePart[]> => {
        const runtime: AgentRuntimeStatus = this.taskScheduler.status();
        const byType: JsonObject = {};
        runtime.byType.forEach((count: number, type: string): void => { byType[type] = count; });
        return textJson({
          status: 'ok',
          total_tasks: runtime.total,
          queued: runtime.queued,
          running: runtime.running,
          completed: runtime.completed,
          failed: runtime.failed,
          cancelled: runtime.cancelled,
          timed_out: runtime.timedOut,
          interrupted: runtime.interrupted,
          by_type: byType,
        });
      },
    });
  }
}
