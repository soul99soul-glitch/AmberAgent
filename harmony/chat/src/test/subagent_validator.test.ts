// subagent_validator.test.ts — D-132a Task 2
// Android baselines: SmartSubAgentNames.kt, SubAgentValidator.kt,
// SubAgentValidatorTest.kt (all current cases and validator branches).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  DEFAULT_DYNAMIC_READ_ONLY_TOOLS,
  subAgentParseTask,
  subAgentResolveDefinition,
  subAgentValidateToolAllowlist,
} from '../main/ets/index.ts';
import type {
  SubAgentDefinition,
  SubAgentMode,
  SubAgentOverride,
  SubAgentRuntimeSetting,
} from '../main/ets/chat/agent_prompt_config.ts';
import {
  makeSubAgentDefinition,
  makeSubAgentOverride,
} from '../main/ets/chat/agent_prompt_config.ts';
import type { JsonObject, JsonValue } from '../main/ets/chat/json.ts';
import {
  EXTENDED_SUB_AGENT_OUTPUT_BUDGET_CHARS,
  EXTENDED_SUB_AGENT_TIMEOUT_MS,
} from '../main/ets/chat/subagent_models.ts';
interface CustomInputOptions {
  name?: string | null;
  toolProfile?: string | null;
  toolAllowlist?: string[] | null;
  maxTurns?: number | null;
  timeoutMs?: number | null;
  outputBudgetChars?: number | null;
  subagentId?: string | null;
  description?: string | null;
  systemPrompt?: string | null;
  modelId?: string | null;
  temperature?: string | number | null;
  reasoningLevel?: string | null;
  routingHint?: string | null;
}

interface SettingOptions {
  mode?: SubAgentMode;
  allowDynamicSubAgents?: boolean;
  maxTurns?: number;
  timeoutMs?: number;
  outputBudgetChars?: number;
  overrides?: Map<string, SubAgentOverride>;
  customDefinitions?: SubAgentDefinition[];
}

const makeSetting = (opts: SettingOptions = {}): SubAgentRuntimeSetting => ({
  enabled: true,
  mode: opts.mode ?? 'roster',
  allowDynamicSubAgents: opts.allowDynamicSubAgents ?? true,
  maxConcurrentRuns: 2,
  timeoutMs: opts.timeoutMs ?? 60000,
  maxTurns: opts.maxTurns ?? 4,
  outputBudgetChars: opts.outputBudgetChars ?? 12000,
  overrides: opts.overrides ?? new Map<string, SubAgentOverride>(),
  customDefinitions: opts.customDefinitions ?? [],
});

const setIfPresent = (target: JsonObject, key: string, value: JsonValue | undefined): void => {
  if (value !== undefined && value !== null) target[key] = value;
};

const requireJsonObject = (value: JsonValue | undefined): JsonObject => {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    assert.fail('Expected JSON object fixture');
  }
  return value;
};

const inputWithCustomSubagent = (opts: CustomInputOptions = {}): JsonObject => {
  const custom: JsonObject = {};
  const name: string | null = opts.name === undefined ? 'Focused Code Reviewer' : opts.name;
  const description: string | null = opts.description === undefined
    ? 'Use when a narrow read-only code review is needed for a specific file or behavior.'
    : opts.description;
  const systemPrompt: string | null = opts.systemPrompt === undefined
    ? 'You are a narrow reviewer. Boundaries: do not edit files, do not spawn agents, and do not use tools outside the allowlist. Report output as summary, findings, evidence, risks, and next steps.'
    : opts.systemPrompt;
  setIfPresent(custom, 'name', name);
  setIfPresent(custom, 'description', description);
  setIfPresent(custom, 'system_prompt', systemPrompt);
  setIfPresent(custom, 'tool_profile', opts.toolProfile);
  setIfPresent(custom, 'tool_allowlist', opts.toolAllowlist);
  setIfPresent(custom, 'max_turns', opts.maxTurns);
  setIfPresent(custom, 'timeout_ms', opts.timeoutMs);
  setIfPresent(custom, 'output_budget_chars', opts.outputBudgetChars);
  setIfPresent(custom, 'model_id', opts.modelId);
  setIfPresent(custom, 'temperature', opts.temperature);
  setIfPresent(custom, 'reasoning_level', opts.reasoningLevel);
  setIfPresent(custom, 'routing_hint', opts.routingHint);
  const input: JsonObject = {
    custom_subagent: custom,
    task: {
      objective: 'Review one issue',
      output_format: 'Findings with evidence',
      tools_and_sources: 'Use the listed tools only',
      boundaries: 'Do not edit files',
    },
  };
  setIfPresent(input, 'subagent_id', opts.subagentId);
  return input;
};

const inputWithSubagentId = (id: string): JsonObject => ({
  subagent_id: id,
  task: {
    objective: 'Review one issue',
    output_format: 'Findings with evidence',
    tools_and_sources: 'Use the listed tools only',
    boundaries: 'Do not edit files',
  },
});

const taskInputWithContext = (context: string): JsonObject => ({
  task: {
    objective: 'Review one issue',
    output_format: 'Findings with evidence',
    tools_and_sources: 'Use file_read only',
    boundaries: 'Do not edit files',
    context,
  },
});

const caughtError = (block: () => void): Error => {
  try {
    block();
  } catch (error) {
    assert.ok(error instanceof Error);
    return error;
  }
  assert.fail('Expected block to throw');
};

const assertTools = (actual: string[], expected: string[]): void => {
  assert.deepEqual(new Set<string>(actual), new Set<string>(expected));
};

test('present custom_subagent must have Android jsonObject shape', () => {
  assert.throws((): void => {
    subAgentResolveDefinition(
      { custom_subagent: 'not-an-object' }, makeSetting(), new Set<string>());
  }, TypeError);
  assert.throws((): void => {
    subAgentResolveDefinition(
      { custom_subagent: null }, makeSetting(), new Set<string>());
  }, TypeError);
});

test('present tool_allowlist must be an array of JSON primitives', () => {
  const scalar: JsonObject = inputWithCustomSubagent();
  const scalarCustom: JsonObject = requireJsonObject(scalar['custom_subagent']);
  scalarCustom['tool_allowlist'] = 'file_read';
  assert.throws((): void => {
    subAgentResolveDefinition(scalar, makeSetting(), new Set(['file_read']));
  }, TypeError);

  const badElement: JsonObject = inputWithCustomSubagent();
  const badElementCustom: JsonObject = requireJsonObject(badElement['custom_subagent']);
  badElementCustom['tool_allowlist'] = ['file_read', { bad: true }];
  assert.throws((): void => {
    subAgentResolveDefinition(badElement, makeSetting(), new Set(['file_read']));
  }, TypeError);
});

test('present source_session_ids must be an array of JSON primitives', () => {
  assert.throws((): void => {
    subAgentParseTask({ task: { objective: 'Review', source_session_ids: 'session-a' } });
  }, TypeError);
  assert.throws((): void => {
    subAgentParseTask({ task: { objective: 'Review', source_session_ids: [{ bad: true }] } });
  }, TypeError);
});

test('present primitive fields reject object and array shapes', () => {
  assert.throws((): void => {
    subAgentParseTask({ task: { objective: { bad: true } } });
  }, TypeError);
  const input: JsonObject = inputWithCustomSubagent();
  input['subagent_id'] = [];
  assert.throws((): void => {
    subAgentResolveDefinition(input, makeSetting(), new Set(['file_read']));
  }, TypeError);
});

test('task validation preserves exact required-field errors and accepts the context limit', () => {
  assert.equal(caughtError((): void => { subAgentParseTask({}); }).message,
    'task object is required');
  assert.equal(caughtError((): void => {
    subAgentParseTask({ task: { objective: '  ' } });
  }).message, 'objective is required');
  const normalized = subAgentParseTask({
    task: {
      objective: 'Review', output_format: ' ', tools_and_sources: ' ', boundaries: ' ',
    },
  });
  assert.equal(normalized.outputFormat,
    'Brief summary, findings, evidence, risks, and recommended next steps.');
  assert.equal(normalized.toolsAndSources,
    'Use only tools granted to this subagent. If no tools are granted, rely only on the task context.');
  assert.equal(normalized.boundaries,
    'Stay within the assigned objective; do not spawn subagents; report once and stop.');
  const atLimit = subAgentParseTask(taskInputWithContext('x'.repeat(6000)));
  assert.equal(atLimit.context.length, 6000);
});

test('smart mode rejects a built-in subagent id with the exact Android error', () => {
  const error = caughtError((): void => {
    subAgentResolveDefinition(
      inputWithSubagentId('explorer'), makeSetting({ mode: 'smart_dynamic' }),
      new Set(['file_read']));
  });
  assert.equal(error.message,
    'Built-in subagents are disabled in smart dynamic mode; use custom_subagent.');
});

test('roster mode allows a built-in subagent id', () => {
  const result = subAgentResolveDefinition(
    inputWithSubagentId('explorer'), makeSetting(), new Set(['file_read']));
  assert.equal(result.definition.id, 'explorer');
  assert.deepEqual(result.warnings, []);
});

test('smart mode replaces generic English and Chinese names instead of failing', () => {
  const smart = makeSetting({ mode: 'smart_dynamic' });
  const english = subAgentResolveDefinition(
    inputWithCustomSubagent({ name: 'General Helper' }), smart, new Set(['file_read']));
  const chinese = subAgentResolveDefinition(
    inputWithCustomSubagent({ name: '万能助手' }), smart, new Set(['file_read']));
  assert.match(english.definition.name, /^[A-Z][a-z]+$/);
  assert.equal(english.definition.id.includes('general'), false);
  assert.match(chinese.definition.name, /^[A-Z][a-z]+$/);
  assert.equal(chinese.definition.name.includes('万能'), false);
  assert.equal(chinese.definition.dynamic, true);
});

// Built-in override branch: canonical-id override lookup, explicit copy and setting caps.
test('built-in name selection applies its canonical override and runtime caps', () => {
  const override: SubAgentOverride = makeSubAgentOverride();
  override.systemPrompt = 'Overridden explorer prompt';
  override.modelId = '550e8400-e29b-41d4-a716-446655440000';
  override.temperature = 0.25;
  override.reasoningLevel = 'high';
  override.maxTurnsOverride = 9;
  override.timeoutMsOverride = 90000;
  override.outputBudgetOverride = 50000;
  const overrides: Map<string, SubAgentOverride> = new Map<string, SubAgentOverride>();
  overrides.set('explorer', override);
  const result = subAgentResolveDefinition(
    inputWithSubagentId('Explorer'), makeSetting({ overrides }), new Set<string>());
  assert.equal(result.definition.id, 'explorer');
  assert.equal(result.definition.systemPrompt, 'Overridden explorer prompt');
  assert.equal(result.definition.modelId, '550e8400-e29b-41d4-a716-446655440000');
  assert.equal(result.definition.temperature, 0.25);
  assert.equal(result.definition.reasoningLevel, 'high');
  assert.equal(result.definition.maxTurns, 4);
  assert.equal(result.definition.timeoutMs, 60000);
  assert.equal(result.definition.outputBudgetChars, 12000);
});

// SubAgentValidatorTest.kt:saved custom branch.
test('smart mode saved custom role is reduced to available read-only dynamic tools', () => {
  const saved: SubAgentDefinition = makeSubAgentDefinition({
    id: 'saved-reviewer',
    name: 'Saved Reviewer',
    description: 'Use when a saved reviewer should inspect a focused issue.',
    systemPrompt: 'Boundaries: do not edit files. Report output as findings with evidence.',
    toolAllowlist: ['file_read', 'file_read', 'terminal_execute', 'file_read'],
  });
  const result = subAgentResolveDefinition(
    inputWithSubagentId('saved-reviewer'),
    makeSetting({ mode: 'smart_dynamic', customDefinitions: [saved] }),
    new Set(['file_read', 'terminal_execute']));
  assert.deepEqual(result.definition.toolAllowlist, ['file_read']);
  assert.equal(result.definition.dynamic, true);
});

test('roster mode saved custom role drops unavailable tools and remains non-dynamic', () => {
  const saved: SubAgentDefinition = makeSubAgentDefinition({
    id: 'saved-reviewer', name: 'Saved Reviewer',
    description: 'Use when a saved reviewer should inspect a focused issue.',
    systemPrompt: 'Boundaries: do not edit files. Report output as findings with evidence.',
    toolAllowlist: ['file_read', 'file_read', 'missing_tool', 'file_read'], dynamic: false,
  });
  const result = subAgentResolveDefinition(
    inputWithSubagentId('Saved Reviewer'), makeSetting({ customDefinitions: [saved] }),
    new Set(['file_read']));
  assert.deepEqual(result.definition.toolAllowlist, ['file_read']);
  assert.equal(result.definition.dynamic, false);
  assert.deepEqual(result.warnings, []);
});

// SubAgentValidatorTest.kt:dynamic default profiles and non-Latin ids.
test('dynamic role defaults to available read-only tools only', () => {
  assertTools(Array.from(DEFAULT_DYNAMIC_READ_ONLY_TOOLS), [
    'tools_list', 'file_list', 'file_read', 'file_search',
    'conversation_search', 'conversation_expand',
    'session_list', 'session_search',
    'officepro_status', 'officepro_dashboard',
    'search_web', 'scrape_web',
    'apps_list', 'apps_installed_list', 'permissions_status', 'skills_list', 'mcp_list',
  ]);
  const result = subAgentResolveDefinition(
    inputWithCustomSubagent(), makeSetting(),
    new Set([
      'file_read', 'file_search', 'session_search', 'session_read',
      'terminal_execute', 'http_request', 'officepro_capture_context',
      'officepro_context_digest',
    ]));
  assertTools(result.definition.toolAllowlist, ['file_read', 'file_search', 'session_search']);
  assert.equal(result.definition.dynamic, true);
});

test('custom_subagent and subagent_id are mutually exclusive', () => {
  const error = caughtError((): void => {
    subAgentResolveDefinition(
      inputWithCustomSubagent({ name: 'Micro Poet', toolProfile: 'none', subagentId: 'fixer' }),
      makeSetting(), new Set<string>());
  });
  assert.equal(error.message, 'Pass either subagent_id or custom_subagent, not both.');
});

test('resolve selection and dynamic setting errors exactly match Android', () => {
  assert.equal(caughtError((): void => {
    subAgentResolveDefinition({ task: { objective: 'x' } }, makeSetting(), new Set<string>());
  }).message, 'subagent_id or custom_subagent is required');
  assert.equal(caughtError((): void => {
    subAgentResolveDefinition(inputWithSubagentId('ghost'), makeSetting(), new Set<string>());
  }).message, 'Unknown subagent_id: ghost');
  assert.equal(caughtError((): void => {
    subAgentResolveDefinition(
      inputWithCustomSubagent(), makeSetting({ allowDynamicSubAgents: false }),
      new Set(['file_read']));
  }).message, 'Dynamic subagents are disabled in settings');
});

test('dynamic prompt independently adds boundary, report, and short-focus instructions', () => {
  const boundaryOnly = subAgentResolveDefinition(inputWithCustomSubagent({
    systemPrompt: 'Boundaries: do not edit files and stay within the assigned objective.',
    toolProfile: 'none',
  }), makeSetting(), new Set<string>());
  assert.equal(boundaryOnly.definition.systemPrompt.includes(
    'Report output as: concise summary, findings, evidence, risks, and recommended next steps.'), true);
  assert.equal(boundaryOnly.definition.systemPrompt.includes(
    'use only granted tools; do not ask the user.'), false);

  const reportOnly = subAgentResolveDefinition(inputWithCustomSubagent({
    systemPrompt: 'Report output as findings, evidence, risks, and recommended next steps.',
    toolProfile: 'none',
  }), makeSetting(), new Set<string>());
  assert.equal(reportOnly.definition.systemPrompt.includes(
    'Boundaries: stay within the assigned objective; do not spawn subagents;'), true);
  assert.equal(reportOnly.definition.systemPrompt.match(/Report output as/g)?.length, 1);

  const short = subAgentResolveDefinition(inputWithCustomSubagent({
    systemPrompt: 'Do not edit. Report.', toolProfile: 'none',
  }), makeSetting(), new Set<string>());
  assert.equal(short.definition.systemPrompt,
    'Do not edit. Report.\n\nFocus: produce a narrow, verifiable result for the supervisor and avoid broad commentary.');
});

// SubAgentValidatorTest.kt:all tool-profile branches.
test('dynamic role web profile narrows to web-read tools', () => {
  const result = subAgentResolveDefinition(
    inputWithCustomSubagent({ toolProfile: 'web_read' }),
    makeSetting({ mode: 'smart_dynamic' }),
    new Set(['tools_list', 'file_read', 'search_web', 'scrape_web']));
  assertTools(result.definition.toolAllowlist, ['tools_list', 'search_web', 'scrape_web']);
});

test('dynamic role explicit allowlist cannot escape its profile', () => {
  const result = subAgentResolveDefinition(inputWithCustomSubagent({
    toolProfile: 'web_read', toolAllowlist: ['search_web', 'terminal_execute'],
  }), makeSetting({ mode: 'smart_dynamic' }), new Set(['search_web', 'terminal_execute']));
  assertTools(result.definition.toolAllowlist, ['search_web']);
});

test('dynamic role profile none allows no tools', () => {
  const result = subAgentResolveDefinition(
    inputWithCustomSubagent({ toolProfile: 'none' }),
    makeSetting({ mode: 'smart_dynamic' }), new Set(['file_read']));
  assertTools(result.definition.toolAllowlist, []);
});

test('dynamic role allows empty tools when its profile has no available tools', () => {
  const result = subAgentResolveDefinition(
    inputWithCustomSubagent({ toolProfile: 'workspace_read' }),
    makeSetting({ mode: 'smart_dynamic' }), new Set<string>());
  assertTools(result.definition.toolAllowlist, []);
});

test('dynamic history profile does not include full session-read tools', () => {
  const result = subAgentResolveDefinition(
    inputWithCustomSubagent({ toolProfile: 'history_read' }),
    makeSetting({ mode: 'smart_dynamic' }),
    new Set(['session_list', 'session_search', 'session_read', 'session_expand']));
  assertTools(result.definition.toolAllowlist, ['session_list', 'session_search']);
});

test('invalid dynamic tool profile has the exact Android error', () => {
  const error = caughtError((): void => {
    subAgentResolveDefinition(
      inputWithCustomSubagent({ toolProfile: 'write_all' }), makeSetting(),
      new Set(['file_read']));
  });
  assert.equal(error.message,
    'custom_subagent.tool_profile must be one of none, read_only, workspace_read, web_read, history_read; got: write_all');
});

// SubAgentValidatorTest.kt:history and shard task fields.
test('task spec parses history shard fields and trims source ids', () => {
  const task = subAgentParseTask({
    task: {
      objective: 'Summarize historical sessions',
      output_format: 'Summary with source ids',
      tools_and_sources: 'Use session_read with the grant',
      boundaries: 'Only read granted sessions',
      session_grant_id: 'grant-1',
      history_query: '飞书增强模式',
      shard_index: 1,
      shard_count: 3,
      source_session_ids: [' session-a ', '', 'session-b'],
    },
  });
  assert.equal(task.sessionGrantId, 'grant-1');
  assert.deepEqual(task.sourceSessionIds, ['session-a', 'session-b']);
  assert.equal(task.historyQuery, '飞书增强模式');
  assert.equal(task.shardIndex, 1);
  assert.equal(task.shardCount, 3);
});

test('task spec rejects oversized context with the exact Android message', () => {
  const error = caughtError((): void => {
    subAgentParseTask(taskInputWithContext('x'.repeat(6001)));
  });
  assert.equal(error.message,
    'task.context is too large (6001 chars); keep it under 6000 chars and pass only the minimum evidence needed.');
});

test('task spec rejects raw tool result dump markers using Android matching behavior', () => {
  const primary = caughtError((): void => {
    subAgentParseTask(taskInputWithContext(
      '{"tool_call_id":"call_1","tool_name":"file_read","output":"raw"}\n' +
      '<tool_result>raw output</tool_result>'));
  });
  assert.equal(primary.message,
    'task.context must not include raw tool result dumps; summarize the relevant evidence and let the subagent re-read allowed sources.');
  const aliases = caughtError((): void => {
    subAgentParseTask(taskInputWithContext(
      '{"toolCallId":"call_2","toolName":"file_read"}\nUIMessagePart.Tool'));
  });
  assert.equal(aliases.message.includes('raw tool result dumps'), true);
  const oneMarker = subAgentParseTask(taskInputWithContext('UIMessagePart.Tool only'));
  assert.equal(oneMarker.context, 'UIMessagePart.Tool only');
  const longDump = caughtError((): void => {
    subAgentParseTask(taskInputWithContext(
      `tool_result output ${'x'.repeat(1500)}`));
  });
  assert.equal(longDump.message.includes('raw tool result dumps'), true);
});

// Tool allowlist: unavailable names are checked before profile intersection, recursion is forbidden.
test('dynamic role rejects unavailable explicit tools with sorted exact error', () => {
  const error = caughtError((): void => {
    subAgentResolveDefinition(inputWithCustomSubagent({
      toolAllowlist: ['z_missing', 'file_read', 'a_missing'],
    }), makeSetting(), new Set(['file_read']));
  });
  assert.equal(error.message,
    'Tool allowlist contains unavailable tools: a_missing, z_missing');
});

test('all explicit allowlist tools must exist and subagent recursion is forbidden', () => {
  assert.equal(caughtError((): void => {
    subAgentValidateToolAllowlist(
      new Set(['file_read', 'subagent_run']), new Set(['file_read', 'subagent_run']));
  }).message, 'Subagents cannot call subagent_* tools');
  assert.equal(caughtError((): void => {
    subAgentValidateToolAllowlist(new Set(['missing']), new Set<string>());
  }).message, 'Tool allowlist contains unavailable tools: missing');
});

// SubAgentValidatorTest.kt:budget coercion and extended limits.
test('dynamic role caps budget hints to runtime maxima and Android minima', () => {
  const result = subAgentResolveDefinition(inputWithCustomSubagent({
    maxTurns: 9, timeoutMs: 100, outputBudgetChars: 999999,
  }), makeSetting(), new Set(['file_read']));
  assert.equal(result.definition.maxTurns, 4);
  assert.equal(result.definition.timeoutMs, 1000);
  assert.equal(result.definition.outputBudgetChars, 12000);
  const minimum = subAgentResolveDefinition(inputWithCustomSubagent({
    maxTurns: 0, timeoutMs: 0, outputBudgetChars: 0,
  }), makeSetting(), new Set(['file_read']));
  assert.equal(minimum.definition.maxTurns, 1);
  assert.equal(minimum.definition.timeoutMs, 1000);
  assert.equal(minimum.definition.outputBudgetChars, 1000);
});

test('Int parsing uses exact signed Int32 boundaries', () => {
  assert.equal(subAgentParseTask({
    task: { objective: 'Review', shard_index: '2147483647', shard_count: '-2147483648' },
  }).shardIndex, 2147483647);
  assert.equal(subAgentParseTask({
    task: { objective: 'Review', shard_index: '2147483648', shard_count: '-2147483649' },
  }).shardIndex, 0);
  assert.equal(subAgentParseTask({
    task: { objective: 'Review', shard_index: '2147483648', shard_count: '-2147483649' },
  }).shardCount, 1);

  const validMin = subAgentResolveDefinition(inputWithCustomSubagent({
    maxTurns: -2147483648, outputBudgetChars: -2147483648,
  }), makeSetting(), new Set(['file_read']));
  assert.equal(validMin.definition.maxTurns, 1);
  assert.equal(validMin.definition.outputBudgetChars, 1000);

  const overflow: JsonObject = inputWithCustomSubagent();
  const overflowCustom: JsonObject = requireJsonObject(overflow['custom_subagent']);
  overflowCustom['max_turns'] = '-2147483649';
  overflowCustom['output_budget_chars'] = '-2147483649';
  const overflowResult = subAgentResolveDefinition(overflow, makeSetting(), new Set(['file_read']));
  assert.equal(overflowResult.definition.maxTurns, 4);
  assert.equal(overflowResult.definition.outputBudgetChars, 12000);
});

test('timeout parsing uses exact signed Int64 boundaries before clamping', () => {
  const validMin: JsonObject = inputWithCustomSubagent();
  const validMinCustom: JsonObject = requireJsonObject(validMin['custom_subagent']);
  validMinCustom['timeout_ms'] = '-9223372036854775808';
  assert.equal(subAgentResolveDefinition(
    validMin, makeSetting(), new Set(['file_read'])).definition.timeoutMs, 1000);

  const underflow: JsonObject = inputWithCustomSubagent();
  const underflowCustom: JsonObject = requireJsonObject(underflow['custom_subagent']);
  underflowCustom['timeout_ms'] = '-9223372036854775809';
  assert.equal(subAgentResolveDefinition(
    underflow, makeSetting(), new Set(['file_read'])).definition.timeoutMs, 60000);

  const validMax: JsonObject = inputWithCustomSubagent();
  const validMaxCustom: JsonObject = requireJsonObject(validMax['custom_subagent']);
  validMaxCustom['timeout_ms'] = '9223372036854775807';
  assert.equal(subAgentResolveDefinition(
    validMax, makeSetting(), new Set(['file_read'])).definition.timeoutMs, 60000);
});

test('dynamic role accepts extended budget when setting allows it', () => {
  const result = subAgentResolveDefinition(inputWithCustomSubagent({
    timeoutMs: EXTENDED_SUB_AGENT_TIMEOUT_MS,
    outputBudgetChars: EXTENDED_SUB_AGENT_OUTPUT_BUDGET_CHARS,
  }), makeSetting({
    timeoutMs: EXTENDED_SUB_AGENT_TIMEOUT_MS,
    outputBudgetChars: EXTENDED_SUB_AGENT_OUTPUT_BUDGET_CHARS,
  }), new Set(['file_read']));
  assert.equal(result.definition.timeoutMs, EXTENDED_SUB_AGENT_TIMEOUT_MS);
  assert.equal(result.definition.outputBudgetChars, EXTENDED_SUB_AGENT_OUTPUT_BUDGET_CHARS);
});

test('settings below Android minima still clamp dynamic and built-in budgets to minima', () => {
  const belowMin = makeSetting({ maxTurns: 0, timeoutMs: 1, outputBudgetChars: 1 });
  const dynamic = subAgentResolveDefinition(
    inputWithCustomSubagent(), belowMin, new Set(['file_read']));
  assert.equal(dynamic.definition.maxTurns, 1);
  assert.equal(dynamic.definition.timeoutMs, 1000);
  assert.equal(dynamic.definition.outputBudgetChars, 1000);
  const builtIn = subAgentResolveDefinition(
    inputWithSubagentId('explorer'), belowMin, new Set<string>());
  assert.equal(builtIn.definition.maxTurns, 1);
  assert.equal(builtIn.definition.timeoutMs, 1000);
  assert.equal(builtIn.definition.outputBudgetChars, 1000);
});

// Dynamic custom model controls are parsed exactly where Android does.
test('dynamic role parses model, temperature, reasoning, and routing fields', () => {
  const result = subAgentResolveDefinition(inputWithCustomSubagent({
    modelId: '550e8400-e29b-41d4-a716-446655440000',
    temperature: '0.35', reasoningLevel: 'XHIGH', routingHint: ' narrow route ',
  }), makeSetting(), new Set(['file_read']));
  assert.equal(result.definition.modelId, '550e8400-e29b-41d4-a716-446655440000');
  assert.equal(result.definition.temperature, Math.fround(0.35));
  assert.equal(result.definition.reasoningLevel, 'xhigh');
  assert.equal(result.definition.routingHint, 'narrow route');
});

test('dynamic role canonicalizes Kotlin compact and uppercase UUID forms', () => {
  const compact = subAgentResolveDefinition(inputWithCustomSubagent({
    modelId: '550E8400E29B41D4A716446655440000',
  }), makeSetting(), new Set(['file_read']));
  assert.equal(compact.definition.modelId, '550e8400-e29b-41d4-a716-446655440000');

  const dashed = subAgentResolveDefinition(inputWithCustomSubagent({
    modelId: '550E8400-E29B-41D4-A716-446655440000',
  }), makeSetting(), new Set(['file_read']));
  assert.equal(dashed.definition.modelId, '550e8400-e29b-41d4-a716-446655440000');
});

test('decimal temperature rounds directly to one IEEE-754 binary32 value', () => {
  const result = subAgentResolveDefinition(inputWithCustomSubagent({
    temperature: '1.0000000596046447753906250000000001',
  }), makeSetting(), new Set(['file_read']));
  assert.equal(result.definition.temperature, 1.0000001192092896);
});

test('large hexadecimal temperature keeps precision before binary32 rounding', () => {
  const result = subAgentResolveDefinition(inputWithCustomSubagent({
    temperature: `0x1${'0'.repeat(399)}p-1596`,
  }), makeSetting(), new Set(['file_read']));
  assert.equal(result.definition.temperature, 1);
});

test('dynamic role follows Kotlin toFloatOrNull temperature forms', () => {
  const scientific = subAgentResolveDefinition(inputWithCustomSubagent({
    temperature: '+1.5e2',
  }), makeSetting(), new Set(['file_read']));
  assert.equal(scientific.definition.temperature, 150);

  const suffixed = subAgentResolveDefinition(inputWithCustomSubagent({
    temperature: '1f',
  }), makeSetting(), new Set(['file_read']));
  assert.equal(suffixed.definition.temperature, 1);

  const hexadecimal = subAgentResolveDefinition(inputWithCustomSubagent({
    temperature: '0x1.0p4',
  }), makeSetting(), new Set(['file_read']));
  assert.equal(hexadecimal.definition.temperature, 16);

  const infinity = subAgentResolveDefinition(inputWithCustomSubagent({
    temperature: '-Infinity',
  }), makeSetting(), new Set(['file_read']));
  assert.equal(infinity.definition.temperature, Number.NEGATIVE_INFINITY);

  const overflow = subAgentResolveDefinition(inputWithCustomSubagent({
    temperature: '3.4028236e38',
  }), makeSetting(), new Set(['file_read']));
  assert.equal(overflow.definition.temperature, Number.POSITIVE_INFINITY);

  const nan = subAgentResolveDefinition(inputWithCustomSubagent({
    temperature: 'NaN',
  }), makeSetting(), new Set(['file_read']));
  assert.equal(Number.isNaN(nan.definition.temperature), true);
  const signedNan = subAgentResolveDefinition(inputWithCustomSubagent({
    temperature: '-NaN',
  }), makeSetting(), new Set(['file_read']));
  assert.equal(Number.isNaN(signedNan.definition.temperature), true);

  assert.equal(caughtError((): void => {
    subAgentResolveDefinition(
      inputWithCustomSubagent({ temperature: '0x10' }), makeSetting(),
      new Set(['file_read']));
  }).message, 'custom_subagent.temperature is not a number: 0x10');
  assert.equal(caughtError((): void => {
    subAgentResolveDefinition(
      inputWithCustomSubagent({ temperature: '1_0' }), makeSetting(),
      new Set(['file_read']));
  }).message, 'custom_subagent.temperature is not a number: 1_0');
});

test('dynamic role rejects invalid model, temperature, and reasoning values exactly', () => {
  assert.equal(caughtError((): void => {
    subAgentResolveDefinition(
      inputWithCustomSubagent({ modelId: 'not-a-uuid' }), makeSetting(),
      new Set(['file_read']));
  }).message, 'custom_subagent.model_id is not a valid UUID: not-a-uuid');
  assert.equal(caughtError((): void => {
    subAgentResolveDefinition(
      inputWithCustomSubagent({ temperature: 'warm' }), makeSetting(),
      new Set(['file_read']));
  }).message, 'custom_subagent.temperature is not a number: warm');
  assert.equal(caughtError((): void => {
    subAgentResolveDefinition(
      inputWithCustomSubagent({ reasoningLevel: 'ultra' }), makeSetting(),
      new Set(['file_read']));
  }).message,
  'custom_subagent.reasoning_level must be one of off, auto, low, medium, high, xhigh, max; got: ultra');
});

test('dynamic role resolution enforces normalized id, invocation, boundary, and report cues', () => {
  const normalized = subAgentResolveDefinition(inputWithCustomSubagent({
    name: '  Micro__Reviewer  ',
    description: 'A narrow reviewer for one issue.',
    systemPrompt: 'Work carefully.',
    toolProfile: 'none',
  }), makeSetting(), new Set<string>());
  assert.equal(normalized.definition.id, 'micro-reviewer');
  assert.equal(normalized.definition.description.includes('Use when'), true);
  assert.equal(normalized.definition.systemPrompt.length >= 80, true);
  assert.equal(normalized.definition.systemPrompt.includes('Boundaries:'), true);
  assert.equal(normalized.definition.systemPrompt.includes('Report output as:'), true);
});
