import type { ModelCouncilRun, ModelCouncilRunStatus, ModelCouncilTurn } from '@amber/deepread-domain';
import { makeSeat, makeEmptyResult } from '@amber/deepread-domain';
import type { UIMessagePartTool } from './message.ts';
import type { JsonObject, JsonValue } from './json.ts';
import { toolOutputJson } from './tool_activity.ts';
import { toolInputAsJson } from './message.ts';

const object = (value: JsonValue): JsonObject | null =>
  value !== null && typeof value === 'object' && !Array.isArray(value) ? value : null;
const str = (value: JsonValue): string => typeof value === 'string' ? value : '';
const strings = (value: JsonValue): string[] => Array.isArray(value)
  ? value.filter((item): item is string => typeof item === 'string') : [];

// 工具输出是聊天历史的既有来源；只在此边界转换 Android snake_case。
export const councilRunFromTools = (runId: string, tools: UIMessagePartTool[]): ModelCouncilRun | null => {
  for (const tool of tools.slice().reverse()) {
    const payload = toolOutputJson(tool);
    const saved = object(payload['run']);
    if (saved !== null && saved['runId'] === runId && Array.isArray(saved['seats']) && Array.isArray(saved['turns']))
      return saved as unknown as ModelCouncilRun;
    if (payload['run_id'] !== runId || !Array.isArray(payload['turns'])) continue;
    const turns: ModelCouncilTurn[] = [];
    for (const item of payload['turns']) {
      const turn = object(item);
      if (turn === null) continue;
      turns.push({ round: typeof turn['round'] === 'number' ? turn['round'] : 1,
        seatId: str(turn['seat_id']), seatName: str(turn['seat_name']), role: str(turn['role']),
        modelId: str(turn['model_id']), modelLabel: [str(turn['provider_name']), str(turn['model_name'])].filter(Boolean).join(' / '),
        status: (str(turn['status']) || 'completed') as ModelCouncilRunStatus, content: str(turn['content']),
        error: str(turn['error']), warnings: strings(turn['warnings']), toolMessages: [], sources: [] });
    }
    const seats = turns.filter((turn, index) => turns.findIndex(item => item.seatId === turn.seatId) === index)
      .map(turn => makeSeat({ seatId: turn.seatId, name: turn.seatName, role: turn.role, modelId: turn.modelId }));
    const rawSeats = payload['seats'];
    if (Array.isArray(rawSeats) && rawSeats.length > 0) {
      seats.length = 0;
      for (const item of rawSeats) { const seat = object(item); if (seat !== null)
        seats.push(makeSeat({ seatId: str(seat['seat_id']), name: str(seat['name']), role: str(seat['role']) })); }
    }
    const result = makeEmptyResult();
    const rawResult = object(payload['result']);
    if (rawResult !== null) {
      result.finalRecommendation = str(rawResult['final_recommendation']); result.error = str(rawResult['error']);
      result.warnings = strings(rawResult['warnings']);
    }
    const start = tools.find(item => item.toolName === 'model_council_start');
    const input = start === undefined ? {} : toolInputAsJson(start);
    const task = object(input['task']) ?? input;
    const mode = str(payload['mode']) === 'debate' ? 'debate' : 'compare';
    return { runId, status: (str(payload['status']) || 'interrupted') as ModelCouncilRunStatus,
      mode, seats, turns, result, task: { mode, objective: str(task['objective']), context: '',
        outputFormat: '', evaluationCriteria: '', rounds: Math.max(1, ...turns.map(turn => turn.round)), seats },
      transcriptPath: str(payload['transcript_path']), startedAtMs: 0, updatedAtMs: 0 };
  }
  return null;
};
