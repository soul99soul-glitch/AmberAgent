// council/prompts — 议会各阶段提示词构造(移植自 Android ModelCouncilManager 私有方法)

import type { ModelCouncilSeat, ModelCouncilTaskSpec, ModelCouncilTurn } from './models.ts';

export const SYNTHESIZER_SYSTEM_PROMPT =
  '你是 AmberAgent 的 Model Council 裁判。只综合证据，不引入未给出的事实。';

export const truncate = (s: string, max: number): string => {
  if (s.length <= max) return s;
  return s.slice(0, max) + '…';
};

// 单个席位某轮输出的摘要块
export const summaryBlock = (turn: ModelCouncilTurn, limit: number = 700): string => {
  return `Round ${turn.round} / ${turn.seatName} / ${turn.modelLabel} / ${turn.status}:\n${truncate(turn.content, limit)}`;
};

export const seatSystemPrompt = (seat: ModelCouncilSeat): string => {
  const extra: string = seat.systemPrompt.length > 0 ? ' ' + seat.systemPrompt : '';
  return `You are \`${seat.name}\` in AmberAgent Model Council. Role: ${seat.role}.${extra}\n`
    + 'Hard boundaries: you have no tools; do not claim to have inspected files, apps, web pages, '
    + 'or private data; give concise, evidence-backed analysis.';
};

const taskHeader = (task: ModelCouncilTaskSpec): string => {
  let s: string = `Objective: ${task.objective}\n`;
  if (task.context.length > 0) s += `\nContext:\n${task.context}\n`;
  if (task.evaluationCriteria.length > 0) s += `\nEvaluation criteria: ${task.evaluationCriteria}\n`;
  s += `\nOutput format: ${task.outputFormat}\n`;
  return s;
};

// 第 1 轮:独立作答
export const openingPrompt = (task: ModelCouncilTaskSpec, seat: ModelCouncilSeat): string => {
  return taskHeader(task)
    + `\nGive your independent answer as \`${seat.name}\`. Do not reference other council seats.`;
};

// 中间轮:回应其他席位
export const responsePrompt = (
  task: ModelCouncilTaskSpec,
  seat: ModelCouncilSeat,
  previousTurns: ModelCouncilTurn[],
): string => {
  const others: ModelCouncilTurn[] = previousTurns.filter(t => t.seatId !== seat.seatId && t.content.length > 0);
  let s: string = taskHeader(task);
  if (others.length > 0) {
    s += '\nOther seats said:\n';
    for (let i = 0; i < others.length; i++) {
      s += '\n' + summaryBlock(others[i], 700) + '\n';
    }
  }
  s += `\nRespond as \`${seat.name}\`. Revise or defend your position. `
    + 'Focus on disagreements, missing evidence, and practical implications.';
  return s;
};

// 末轮:最终立场
export const finalPositionPrompt = (
  task: ModelCouncilTaskSpec,
  seat: ModelCouncilSeat,
  turns: ModelCouncilTurn[],
): string => {
  let s: string = taskHeader(task);
  const debate: ModelCouncilTurn[] = turns.filter(t => t.content.length > 0);
  if (debate.length > 0) {
    s += '\nDebate so far:\n';
    for (let i = 0; i < debate.length; i++) {
      s += '\n' + summaryBlock(debate[i], 700) + '\n';
    }
  }
  s += `\nGive your final position as \`${seat.name}\`. Be decisive and concise.`;
  return s;
};

// 裁判综合
export const synthesisPrompt = (task: ModelCouncilTaskSpec, turns: ModelCouncilTurn[]): string => {
  let s: string = `Objective: ${task.objective}\n`;
  if (task.context.length > 0) s += `\nContext:\n${task.context}\n`;
  const done: ModelCouncilTurn[] = turns.filter(t => t.content.length > 0);
  if (done.length > 0) {
    s += '\nCouncil turns:\n';
    for (let i = 0; i < done.length; i++) {
      s += '\n' + summaryBlock(done[i], 1200) + '\n';
    }
  }
  s += '\nSynthesize: consensus, conflicts, strongest evidence, risks, and a final recommendation.';
  return s;
};
