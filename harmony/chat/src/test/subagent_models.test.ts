// subagent_models.test.ts — D-132a Task 1
// Android baseline: feature/subagent/api/.../SubAgentModels.kt (full file)
import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { SubAgentDefinition } from '../main/ets/chat/agent_prompt_config.ts';
import {
  DEFAULT_SUB_AGENT_MAX_CONCURRENT_RUNS,
  DEFAULT_SUB_AGENT_MAX_TURNS,
  DEFAULT_SUB_AGENT_OUTPUT_BUDGET_CHARS,
  DEFAULT_SUB_AGENT_TIMEOUT_MS,
  makeSubAgentDefinition,
} from '../main/ets/chat/agent_prompt_config.ts';
import type {
  SubAgentResult, SubAgentRun, SubAgentRunStatus, SubAgentTaskSpec,
  SubAgentToolProfile, SubAgentValidationResult,
} from '../main/ets/chat/subagent_models.ts';
import {
  EXTENDED_SUB_AGENT_OUTPUT_BUDGET_CHARS, EXTENDED_SUB_AGENT_TIMEOUT_MS,
  makeSubAgentResult, makeSubAgentRun, makeSubAgentTaskSpec,
  makeSubAgentValidationResult, subAgentRunStatusRunning,
} from '../main/ets/chat/subagent_models.ts';

const makeDefinition = (): SubAgentDefinition => makeSubAgentDefinition({
  id: 'reviewer',
  name: 'Reviewer',
  description: 'Use when a bounded review is useful.',
  systemPrompt: 'Read only and report once.',
  toolAllowlist: ['file_read'],
});

const makeTask = (): SubAgentTaskSpec => makeSubAgentTaskSpec({
  objective: 'Review a small change.',
  outputFormat: 'Findings with evidence.',
  toolsAndSources: 'Use file_read.',
  boundaries: 'Do not edit.',
});

test('constants exactly match Android', () => {
  assert.equal(DEFAULT_SUB_AGENT_MAX_CONCURRENT_RUNS, 2);
  assert.equal(DEFAULT_SUB_AGENT_TIMEOUT_MS, 300000);
  assert.equal(DEFAULT_SUB_AGENT_MAX_TURNS, 4);
  assert.equal(DEFAULT_SUB_AGENT_OUTPUT_BUDGET_CHARS, 12000);
  assert.equal(EXTENDED_SUB_AGENT_TIMEOUT_MS, 1200000);
  assert.equal(EXTENDED_SUB_AGENT_OUTPUT_BUDGET_CHARS, 200000);
});

test('tool profile union uses the exact Android wire names', () => {
  const profiles: SubAgentToolProfile[] = [
    'none', 'read_only', 'workspace_read', 'web_read', 'history_read',
  ];
  assert.equal(profiles.join(','), 'none,read_only,workspace_read,web_read,history_read');
});

test('run status union uses the exact Android wire names', () => {
  const statuses: SubAgentRunStatus[] = [
    'running', 'completed', 'approval_required', 'failed',
    'cancelled', 'timed_out', 'interrupted',
  ];
  const results: SubAgentResult[] = statuses.map((status: SubAgentRunStatus): SubAgentResult =>
    makeSubAgentResult({ status }));
  assert.deepEqual(
    results.map((result: SubAgentResult): SubAgentRunStatus => result.status),
    statuses,
  );
  assert.equal(subAgentRunStatusRunning('running'), true);
  assert.equal(subAgentRunStatusRunning('completed'), false);
});

test('TaskSpec applies Android defaults without inventing a wire-order contract', () => {
  const task: SubAgentTaskSpec = makeTask();
  assert.deepEqual(task, {
    objective: 'Review a small change.',
    outputFormat: 'Findings with evidence.',
    toolsAndSources: 'Use file_read.',
    boundaries: 'Do not edit.',
    context: '',
    sessionGrantId: '',
    sourceSessionIds: [],
    historyQuery: '',
    shardIndex: 0,
    shardCount: 1,
  });
});

test('Result applies Android defaults in memory', () => {
  const result: SubAgentResult = makeSubAgentResult({ status: 'completed' });
  assert.deepEqual(result, {
    status: 'completed', summary: '', findings: [], evidence: [], risks: [], confidence: '',
    recommendedNextSteps: [], error: '',
  });
});

test('Run preserves Android nullable and dependent defaults in memory', () => {
  const run: SubAgentRun = makeSubAgentRun({
    runId: 'run-1',
    parentConversationId: 'conversation-1',
    definition: makeDefinition(),
    task: makeTask(),
    status: 'running',
    transcriptPath: '/tmp/run-1.jsonl',
    startedAtMs: 100,
  });
  assert.equal(run.result, null);
  assert.equal(run.displayText, '');
  assert.equal(run.updatedAtMs, 100);
});

test('ValidationResult defaults warnings to an empty list', () => {
  const result: SubAgentValidationResult = makeSubAgentValidationResult(makeDefinition());
  assert.deepEqual(result.warnings, []);
  assert.equal(result.definition.id, 'reviewer');
});
