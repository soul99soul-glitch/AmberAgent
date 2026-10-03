import type { AbortSignalLike, ModelCouncilManager, ModelCouncilRun, CouncilTaskInput, ModelCouncilSeat } from '@amber/deepread-domain';
import { makeSeat } from '@amber/deepread-domain';
import type { AgentTool } from './tool.ts';
import { makeAgentTool, makeInputSchemaObj } from './tool.ts';
import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessagePart } from './message.ts';

export interface CouncilToolsDeps {
  manager: (starting?: boolean) => Promise<ModelCouncilManager>;
  read: (runId: string) => Promise<ModelCouncilRun | null>;
}

const objectValue = (value: JsonValue): JsonObject => {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new Error('参数必须为对象');
  return value;
};
const stringValue = (input: JsonObject, name: string): string =>
  typeof input[name] === 'string' ? input[name] as string : '';
const output = (value: object): UIMessagePart[] => [
  { type: 'text', text: JSON.stringify(value), metadata: null },
];
const payload = (run: ModelCouncilRun | null): object => run === null
  ? { status: 'not_found', error: '议会运行不存在或归档不可读' }
  : { run_id: run.runId, status: run.status, round: run.turns.reduce((n, turn) => Math.max(n, turn.round), 0),
    seats: run.seats.map(seat => ({ seat_id: seat.seatId, name: seat.name })), run };

const parseTaskInput = (value: JsonValue): CouncilTaskInput => {
  const root = objectValue(value);
  const input = root['task'] !== undefined ? objectValue(root['task']) : root;
  if (root['allow_external_cli'] === true || input['allow_external_cli'] === true)
    throw new Error('鸿蒙议会暂不支持外部 CLI 席位，请使用已配置的 API 模型');
  const planned = input['planned_seats'] ?? root['planned_seats'] ?? input['seats'];
  const seats: ModelCouncilSeat[] = [];
  if (Array.isArray(planned)) {
    for (const raw of planned) {
      const seat = objectValue(raw);
      const runner = stringValue(seat, 'runner_type');
      if (runner.length > 0 && runner !== 'api') throw new Error('议会席位仅支持 API 模型');
      seats.push(makeSeat({ seatId: stringValue(seat, 'seat_id'), name: stringValue(seat, 'name'),
        role: stringValue(seat, 'role'), systemPrompt: stringValue(seat, 'system_prompt'),
        modelId: stringValue(seat, 'model_ref') || stringValue(seat, 'model_id') }));
    }
  }
  return { mode: stringValue(input, 'mode') === 'debate' ? 'debate' : 'compare',
    objective: stringValue(input, 'objective'), context: stringValue(input, 'context'),
    rounds: typeof input['rounds'] === 'number' ? input['rounds'] as number : undefined,
    seats: seats.length > 0 ? seats : undefined };
};

export const createCouncilTools = (deps: CouncilToolsDeps): AgentTool[] => {
  const taskProperties: JsonObject = {
    objective: { type: 'string', description: '讨论议题' },
    mode: { type: 'string', enum: ['compare', 'debate'] }, rounds: { type: 'integer' },
    context: { type: 'string' },
    planned_seats: { type: 'array', items: { type: 'object', properties: {
      name: { type: 'string' }, role: { type: 'string' }, system_prompt: { type: 'string' },
      model_ref: { type: 'string', description: '可选：模型库配置 ID；省略则自动分配' },
    }, required: ['name', 'role', 'system_prompt'] } },
  };
  const runProperties: JsonObject = { run_id: { type: 'string' }, wait_timeout_ms: { type: 'integer' } };
  return [
    makeAgentTool({ name: 'model_council_status', description: '查看正在运行的议会。',
      parameters: () => makeInputSchemaObj({}),
      execute: async () => output({ enabled: true, runs: (await deps.manager()).listActive().map(payload) }) }),
    makeAgentTool({ name: 'model_council_start', description: '使用已配置 API 模型启动议会；compare 单轮，debate 多轮。外部 CLI 不支持。',
      parameters: () => makeInputSchemaObj({ ...taskProperties, task: { type: 'object', properties: taskProperties } }),
      systemPrompt: () => 'When @council is requested, call model_council_start with the objective and optional planned_seats, then wait/read and synthesize the verdict. Seats support configured API models only; do not request external CLI.',
      execute: async input => output(payload((await deps.manager(true)).start(parseTaskInput(input)))) }),
    makeAgentTool({ name: 'model_council_read', description: '读取议会席位、每轮发言、最终结果或持久化归档。',
      parameters: () => makeInputSchemaObj(runProperties, ['run_id']),
      execute: async input => output(payload(await deps.read(stringValue(objectValue(input), 'run_id')))) }),
    makeAgentTool({ name: 'model_council_wait', description: '等待议会结束；仍运行时继续 wait/read。',
      parameters: () => makeInputSchemaObj(runProperties, ['run_id']),
      execute: async (input, signal?: AbortSignalLike) => {
        const args = objectValue(input);
        const id = stringValue(args, 'run_id');
        const manager = await deps.manager();
        if (manager.snapshot(id) !== null) await manager.wait(id,
          Math.max(0, Math.min(180000, typeof args['wait_timeout_ms'] === 'number' ? args['wait_timeout_ms'] as number : 10000)), signal);
        return output(payload(await deps.read(id)));
      } }),
    makeAgentTool({ name: 'model_council_cancel', description: '取消正在运行的指定议会。',
      parameters: () => makeInputSchemaObj(runProperties, ['run_id']),
      execute: async input => { const id = stringValue(objectValue(input), 'run_id');
        (await deps.manager()).cancel(id); return output(payload(await deps.read(id))); } }),
    makeAgentTool({ name: 'model_council_make_report', description: '将议会每席每轮发言和综合裁决生成 Markdown 报告。',
      parameters: () => makeInputSchemaObj(runProperties, ['run_id']),
      execute: async input => {
        const run = await deps.read(stringValue(objectValue(input), 'run_id'));
        if (run === null) return output(payload(null));
        let report = `# 模型议会报告\n\n${run.task.objective}\n\n`;
        for (const turn of run.turns) report += `## ${turn.seatName} · 第 ${turn.round} 轮\n\n${turn.content}\n\n`;
        report += `## 综合裁决\n\n${run.result?.finalRecommendation ?? run.result?.error ?? ''}`;
        return output({ run_id: run.runId, status: run.status, report_markdown: report });
      } }),
  ];
};
