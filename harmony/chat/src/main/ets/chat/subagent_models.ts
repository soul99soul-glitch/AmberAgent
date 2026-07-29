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
