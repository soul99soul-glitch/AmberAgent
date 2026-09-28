// subagent_models — 子代理运行时模型(D-132a Task 1)
// Android 基准: feature/subagent/api/.../SubAgentModels.kt(全文)
// D-128 已有 SubAgentRuntimeSetting/SubAgentMode/SubAgentOverride/
// SubAgentDefinition 及四个默认常量继续以 agent_prompt_config.ts 为唯一事实源。
// Uuid → string，沿用 Harmony chat-domain 既有适配。

import type { SubAgentDefinition } from './agent_prompt_config.ts';

export const EXTENDED_SUB_AGENT_TIMEOUT_MS: number = 20 * 60000;
export const EXTENDED_SUB_AGENT_OUTPUT_BUDGET_CHARS: number = 200000;

export type SubAgentToolProfile =
  'none' | 'read_only' | 'workspace_read' | 'web_read' | 'history_read';

export interface SubAgentTaskSpec {
  objective: string;
  outputFormat: string; // Android @SerialName("output_format")
  toolsAndSources: string; // Android @SerialName("tools_and_sources")
  boundaries: string;
  context: string;
  sessionGrantId: string; // Android @SerialName("session_grant_id")
  sourceSessionIds: string[]; // Android @SerialName("source_session_ids")
  historyQuery: string; // Android @SerialName("history_query")
  shardIndex: number; // Android @SerialName("shard_index")
  shardCount: number; // Android @SerialName("shard_count")
}

export interface SubAgentTaskSpecInit {
  objective: string;
  outputFormat: string;
  toolsAndSources: string;
  boundaries: string;
  context?: string;
  sessionGrantId?: string;
  sourceSessionIds?: string[];
  historyQuery?: string;
  shardIndex?: number;
  shardCount?: number;
}

export const makeSubAgentTaskSpec = (init: SubAgentTaskSpecInit): SubAgentTaskSpec => ({
  objective: init.objective,
  outputFormat: init.outputFormat,
  toolsAndSources: init.toolsAndSources,
  boundaries: init.boundaries,
  context: init.context ?? '',
  sessionGrantId: init.sessionGrantId ?? '',
  sourceSessionIds: init.sourceSessionIds ?? [],
  historyQuery: init.historyQuery ?? '',
  shardIndex: init.shardIndex ?? 0,
  shardCount: init.shardCount ?? 1,
});

export type SubAgentRunStatus =
  'running' | 'completed' | 'approval_required' | 'failed' |
  'cancelled' | 'timed_out' | 'interrupted';

export const subAgentRunStatusRunning = (status: SubAgentRunStatus): boolean =>
  status === 'running';

export interface SubAgentResult {
  status: SubAgentRunStatus;
  summary: string;
  findings: string[];
  evidence: string[];
  risks: string[];
  confidence: string;
  recommendedNextSteps: string[];
  error: string;
}

export interface SubAgentResultInit {
  status: SubAgentRunStatus;
  summary?: string;
  findings?: string[];
  evidence?: string[];
  risks?: string[];
  confidence?: string;
  recommendedNextSteps?: string[];
  error?: string;
}

export const makeSubAgentResult = (init: SubAgentResultInit): SubAgentResult => ({
  status: init.status,
  summary: init.summary ?? '',
  findings: init.findings ?? [],
  evidence: init.evidence ?? [],
  risks: init.risks ?? [],
  confidence: init.confidence ?? '',
  recommendedNextSteps: init.recommendedNextSteps ?? [],
  error: init.error ?? '',
});

export interface SubAgentRun {
  runId: string;
  parentConversationId: string;
  definition: SubAgentDefinition;
  task: SubAgentTaskSpec;
  status: SubAgentRunStatus;
  result: SubAgentResult | null;
  displayText: string;
  transcriptPath: string;
  startedAtMs: number;
  updatedAtMs: number;
}

export interface SubAgentActivityPresentation {
  label: string;
  tone: 'success' | 'error' | 'warning';
  icon: 'check' | 'close';
}

const activityStatusLabel = (status: SubAgentRunStatus): string => {
  if (status === 'completed') return '已完成';
  if (status === 'failed') return '失败';
  if (status === 'cancelled') return '已取消';
  if (status === 'timed_out') return '超时';
  if (status === 'interrupted') return '已中断';
  if (status === 'approval_required') return '等待审批';
  return '运行中';
};

export const subAgentActivityPresentation = (runs: SubAgentRun[]): SubAgentActivityPresentation => {
  let tone: 'success' | 'error' | 'warning' = 'success';
  for (const run of runs) {
    if (run.status === 'failed' || run.status === 'timed_out') tone = 'error';
    else if (run.status !== 'completed' && tone !== 'error') tone = 'warning';
  }
  const parts: string[] = [];
  const statuses: SubAgentRunStatus[] = [
    'completed', 'failed', 'cancelled', 'timed_out', 'interrupted', 'approval_required', 'running',
  ];
  for (const status of statuses) {
    const count: number = runs.filter((run: SubAgentRun): boolean => run.status === status).length;
    if (count > 0) parts.push(`${activityStatusLabel(status)} ${count} 个`);
  }
  return {
    label: runs.length === 1 ? `子代理${activityStatusLabel(runs[0].status)}` : `子代理：${parts.join(' · ')}`,
    tone,
    icon: tone === 'success' ? 'check' : 'close',
  };
};

export interface SubAgentRunInit {
  runId: string;
  parentConversationId: string;
  definition: SubAgentDefinition;
  task: SubAgentTaskSpec;
  status: SubAgentRunStatus;
  result?: SubAgentResult | null;
  displayText?: string;
  transcriptPath: string;
  startedAtMs: number;
  updatedAtMs?: number;
}

export const makeSubAgentRun = (init: SubAgentRunInit): SubAgentRun => ({
  runId: init.runId,
  parentConversationId: init.parentConversationId,
  definition: init.definition,
  task: init.task,
  status: init.status,
  result: init.result !== undefined ? init.result : null,
  displayText: init.displayText ?? '',
  transcriptPath: init.transcriptPath,
  startedAtMs: init.startedAtMs,
  updatedAtMs: init.updatedAtMs ?? init.startedAtMs,
});

export interface SubAgentValidationResult {
  definition: SubAgentDefinition;
  warnings: string[];
}

export const makeSubAgentValidationResult = (
  definition: SubAgentDefinition, warnings: string[] = [],
): SubAgentValidationResult => ({ definition, warnings });
