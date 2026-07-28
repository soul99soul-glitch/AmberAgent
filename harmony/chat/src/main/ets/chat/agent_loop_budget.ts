// agent_loop_budget — 工具循环预算提示(D-056)
//
// Android 基准: feature/runtime/api AgentLoopBudgetPrompt.kt(全文 53 行)
//   阶段阈值(SMALL_LOOP_MAX_STEPS=4)/build 文案逐字/shouldHideTools

export type AgentLoopBudgetStage = 'warn' | 'tight' | 'final';

const SMALL_LOOP_MAX_STEPS: number = 4;

const remainingSteps = (stepIndex: number, maxSteps: number): number =>
  Math.max(maxSteps - stepIndex, 0);

// stage(:22-40)
export const agentLoopBudgetStage = (
  stepIndex: number, maxSteps: number,
): AgentLoopBudgetStage | null => {
  const remaining: number = remainingSteps(stepIndex, maxSteps);
  if (maxSteps <= SMALL_LOOP_MAX_STEPS) {
    if (remaining <= 0) return 'final';
    if (remaining <= 1) return 'final';
    if (remaining <= 2 && stepIndex > 0) return 'tight';
    if (remaining <= 3 && stepIndex > 0) return 'warn';
    return null;
  }
  if (remaining <= 0) return 'final';
  if (remaining <= 2) return 'final';
  if (remaining <= 6) return 'tight';
  if (remaining <= 12) return 'warn';
  return null;
};

// build(:4-17,文案逐字)
export const buildAgentLoopBudgetPrompt = (stepIndex: number, maxSteps: number): string => {
  const stage: AgentLoopBudgetStage | null = agentLoopBudgetStage(stepIndex, maxSteps);
  if (stage === null) return '';
  const remaining: number = remainingSteps(stepIndex, maxSteps);
  if (stage === 'warn') {
    return `Agent loop budget: ${remaining} steps remain. Avoid starting new branches; reuse gathered evidence and call only tools that clearly unblock the final answer.`;
  }
  if (stage === 'tight') {
    return `Agent loop budget: ${remaining} steps remain. Start converging now; call only essential tools and prepare a final answer from the evidence already gathered.`;
  }
  return `Agent loop budget: ${remaining} steps remain. Do not start more tool work; provide the final answer now and explicitly note any unfinished items.`;
};

// shouldHideTools(:19-20)
export const agentLoopShouldHideTools = (
  stepIndex: number, maxSteps: number, hasResumableTools: boolean,
): boolean =>
  !hasResumableTools && agentLoopBudgetStage(stepIndex, maxSteps) === 'final';
