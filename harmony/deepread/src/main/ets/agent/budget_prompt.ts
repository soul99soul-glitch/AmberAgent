// AgentLoopBudgetPrompt — 照搬 Android AgentLoopBudgetPrompt.kt
// 防 LLM 烧光 maxSteps 还不调 writer tool:在剩余 step 少时插入中文提醒
// FINAL 阶段(无 resumable tool)直接隐藏所有 tool,逼模型回文本

export type BudgetStage = 'WARN' | 'TIGHT' | 'FINAL';

const SMALL_LOOP_MAX_STEPS = 4;

export const budgetStage = (stepIndex: number, maxSteps: number): BudgetStage | null => {
  const remaining = Math.max(0, maxSteps - stepIndex);
  if (maxSteps <= SMALL_LOOP_MAX_STEPS) {
    if (remaining <= 0) return 'FINAL';
    if (remaining <= 1) return 'FINAL';
    if (remaining <= 2 && stepIndex > 0) return 'TIGHT';
    if (remaining <= 3 && stepIndex > 0) return 'WARN';
    return null;
  }
  // maxSteps > 4:边界 <=2 FINAL, 3..6 TIGHT, 7..12 WARN(照搬 Android when 顺序)
  if (remaining <= 0) return 'FINAL';
  if (remaining <= 2) return 'FINAL';
  if (remaining <= 6) return 'TIGHT';
  if (remaining <= 12) return 'WARN';
  return null;
};

// FINAL 阶段且无 resumable tool → 隐藏 tools
export const shouldHideToolsForBudget = (
  stepIndex: number,
  maxSteps: number,
  hasResumableTools: boolean,
): boolean => {
  if (hasResumableTools) return false;
  return budgetStage(stepIndex, maxSteps) === 'FINAL';
};

// 生成中文 budget 提醒文本(WARN/TIGHT/FINAL 不同强度)
export const buildBudgetPrompt = (stepIndex: number, maxSteps: number): string => {
  const stage = budgetStage(stepIndex, maxSteps);
  if (stage === null) return '';
  const remaining = Math.max(0, maxSteps - stepIndex);
  switch (stage) {
    case 'WARN':
      return `【提醒】剩余可用步数约 ${remaining} 步。请尽快调用工具完成任务,避免超出预算。`;
    case 'TIGHT':
      return `【紧急】剩余仅 ${remaining} 步。必须立即调用工具写入结果,不要再生成无关文本。`;
    case 'FINAL':
      return `【最后机会】这是最后一步。必须立即调用工具写入结果,否则任务将失败。`;
  }
};
