// Cron tool protocol; scheduling, persistence and task locks belong to AgentCronManager.

import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessagePart } from './message.ts';
import type { AgentTool } from './tool.ts';
import { makeAgentTool, makeInputSchemaObj } from './tool.ts';
import type { AgentCronManager, AgentCronTask } from './agent_cron.ts';

// ===== AgentCronTools(全文 150) =====

// ToolJson.kt:string/requiredString/boolean(contentOrNull 系)
const cronInputString = (input: JsonValue, key: string): string | undefined => {
  if (typeof input !== 'object' || input === null || Array.isArray(input)) return undefined;
  const raw: JsonValue | undefined = (input as JsonObject)[key];
  if (raw === undefined || raw === null) return undefined;
  if (typeof raw === 'string') return raw;
  if (typeof raw === 'number' || typeof raw === 'boolean') return String(raw);
  return undefined;
};

const cronInputRequiredString = (input: JsonValue, key: string): string => {
  const value: string | undefined = cronInputString(input, key);
  if (value === undefined) throw new Error(`${key} is required`);
  return value;
};

const cronInputBoolean = (input: JsonValue, key: string): boolean | undefined => {
  const content: string | undefined = cronInputString(input, key);
  if (content === undefined) return undefined;
  if (content === 'true') return true;
  if (content === 'false') return false;
  return undefined;
};

// :124-139  toJson(键序逐字;prompt.take(2000);可空字段键省略)
const cronTaskToolJson = (task: AgentCronTask): JsonObject => {
  const j: JsonObject = {};
  j['id'] = task.id;
  j['title'] = task.title;
  j['prompt'] = task.prompt.substring(0, 2000);
  j['cron_expression'] = task.cronExpression;
  j['timezone'] = task.timezoneId;
  j['conversation_id'] = task.conversationId;
  j['enabled'] = task.enabled;
  if (task.nextRunAtMs !== null) j['next_run_at_ms'] = task.nextRunAtMs;
  if (task.lastRunAtMs !== null) j['last_run_at_ms'] = task.lastRunAtMs;
  j['last_status'] = task.lastStatus.toLowerCase();
  if (task.lastError !== null) j['last_error'] = task.lastError;
  j['run_count'] = task.runCount;
  j['created_at_ms'] = task.createdAtMs;
  j['updated_at_ms'] = task.updatedAtMs;
  return j;
};

const textJson = (payload: JsonObject): UIMessagePart[] => [
  { type: 'text', text: JSON.stringify(payload), metadata: null },
];

const stringProp = (description: string): JsonObject => ({ type: 'string', description });
const booleanProp = (description: string): JsonObject => ({ type: 'boolean', description });

export class AgentCronTools {
  constructor(private readonly manager: AgentCronManager) {}

  getTools(): AgentTool[] {
    return [this.listTool(), this.createTool(), this.updateTool(), this.deleteTool()];
  }

  private listTool(): AgentTool {
    return makeAgentTool({
      name: 'cron_task_list',
      description: 'List AmberAgent mobile cron tasks scheduled on this Android device.',
      parameters: (): ReturnType<typeof makeInputSchemaObj> => makeInputSchemaObj({}),
      execute: async (): Promise<UIMessagePart[]> => {
        const tasks: AgentCronTask[] = await this.manager.listTasksSnapshot();
        const payload: JsonObject = {
          status: 'ok',
          tasks: tasks.map((task: AgentCronTask): JsonObject => cronTaskToolJson(task)),
        };
        return textJson(payload);
      },
    });
  }

  private createTool(): AgentTool {
    return makeAgentTool({
      name: 'cron_task_create',
      description: "Create a mobile-side cron task. The task will trigger AmberAgent on this Android device and send the prompt to its own conversation when due. Supports 5-field cron expressions like '0 9 * * *', '*/30 * * * *', and '30 9 * * 1-5'.",
      needsApproval: true,
      allowsAutoApproval: false,
      parameters: (): ReturnType<typeof makeInputSchemaObj> => makeInputSchemaObj({
        title: stringProp('Short task name shown in Settings.'),
        prompt: stringProp('User instruction AmberAgent should run when the cron fires.'),
        cron_expression: stringProp('Five-field cron expression: minute hour day-of-month month day-of-week.'),
        timezone: stringProp('IANA timezone id. Defaults to this device timezone.'),
        enabled: booleanProp('Whether to schedule immediately. Defaults to true.'),
      }, ['prompt', 'cron_expression']),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
        const title: string = cronInputString(input, 'title') ?? '';
        const prompt: string = cronInputRequiredString(input, 'prompt');
        const cronExpression: string = cronInputRequiredString(input, 'cron_expression');
        const timezone: string | undefined = cronInputString(input, 'timezone');
        const enabled: boolean = cronInputBoolean(input, 'enabled') ?? true;
        const task: AgentCronTask = await this.manager.createTask(
          title, prompt, cronExpression, timezone !== undefined ? timezone : null, enabled);
        const payload: JsonObject = { status: 'created', task: cronTaskToolJson(task) };
        return textJson(payload);
      },
    });
  }

  private updateTool(): AgentTool {
    return makeAgentTool({
      name: 'cron_task_update',
      description: 'Update an existing mobile cron task. Omitted fields are preserved.',
      needsApproval: true,
      allowsAutoApproval: false,
      parameters: (): ReturnType<typeof makeInputSchemaObj> => makeInputSchemaObj({
        task_id: stringProp('Cron task id.'),
        title: stringProp('New task title.'),
        prompt: stringProp('New prompt to send when the task runs.'),
        cron_expression: stringProp('New five-field cron expression.'),
        timezone: stringProp('New IANA timezone id.'),
        enabled: booleanProp('Enable or pause the task.'),
      }, ['task_id']),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
        const task: AgentCronTask = await this.manager.updateTask(
          cronInputRequiredString(input, 'task_id'),
          {
            title: cronInputString(input, 'title') ?? null,
            prompt: cronInputString(input, 'prompt') ?? null,
            cronExpression: cronInputString(input, 'cron_expression') ?? null,
            timezoneId: cronInputString(input, 'timezone') ?? null,
            enabled: cronInputBoolean(input, 'enabled') ?? null,
          },
        );
        const payload: JsonObject = { status: 'updated', task: cronTaskToolJson(task) };
        return textJson(payload);
      },
    });
  }

  private deleteTool(): AgentTool {
    return makeAgentTool({
      name: 'cron_task_delete',
      description: 'Delete a mobile cron task and cancel its scheduled work.',
      needsApproval: true,
      allowsAutoApproval: false,
      parameters: (): ReturnType<typeof makeInputSchemaObj> => makeInputSchemaObj({
        task_id: stringProp('Cron task id.'),
      }, ['task_id']),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
        const taskId: string = cronInputRequiredString(input, 'task_id');
        const removed: boolean = await this.manager.deleteTask(taskId);
        const payload: JsonObject = {
          status: removed ? 'deleted' : 'not_found',
          task_id: taskId,
        };
        return textJson(payload);
      },
    });
  }
}
