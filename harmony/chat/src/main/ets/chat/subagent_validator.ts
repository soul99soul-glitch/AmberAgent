// subagent_validator — 子代理任务/定义验证(D-132a Task 2)
// Android 基准: SmartSubAgentNames.kt + SubAgentValidator.kt(全文)。
// Set<String> → string[] 仅在 SubAgentDefinition 字段处沿用 Harmony D-128 模型适配。

import type {
  SubAgentDefinition, SubAgentRuntimeSetting,
} from './agent_prompt_config.ts';
import {
  makeSubAgentDefinition, subAgentApplyOverride, subAgentFindDefinition,
} from './agent_prompt_config.ts';
import type { JsonObject, JsonValue } from './json.ts';
import type { ReasoningLevel } from './provider_model.ts';
import type {
  SubAgentTaskSpec, SubAgentToolProfile, SubAgentValidationResult,
} from './subagent_models.ts';
import {
  makeSubAgentTaskSpec, makeSubAgentValidationResult,
} from './subagent_models.ts';

const MAX_SUB_AGENT_CONTEXT_CHARS: number = 6000;

const DEFAULT_SUB_AGENT_OUTPUT_FORMAT: string =
  'Brief summary, findings, evidence, risks, and recommended next steps.';
const DEFAULT_SUB_AGENT_TOOLS_AND_SOURCES: string =
  'Use only tools granted to this subagent. If no tools are granted, rely only on the task context.';
const DEFAULT_SUB_AGENT_BOUNDARIES: string =
  'Stay within the assigned objective; do not spawn subagents; report once and stop.';

const SMART_SUB_AGENT_NAMES: string[] = [
  'Alice', 'Henry', 'Oliver', 'Emma', 'Jack', 'Charlotte',
  'William', 'Amelia', 'James', 'Grace', 'George', 'Sophie',
  'Thomas', 'Lucy', 'Daniel', 'Ella', 'Samuel', 'Mia',
];

// 常见的显式英文人名也保留；自动命名仍沿用默认池。
const RECOGNIZED_SUB_AGENT_NAMES: string[] = SMART_SUB_AGENT_NAMES.concat([
  'Alex', 'Chloe', 'Julia', 'Leo', 'Nora', 'Owen', 'Ryan', 'Noah',
  'Ethan', 'Liam', 'Ava', 'Emily', 'Lily', 'Zoe', 'Olivia', 'Isabel',
]);

const GENERIC_NAME_PARTS: string[] = [
  'general', 'helper', 'all-purpose', 'allpurpose', 'fullstack', 'universal',
  '万能', '通用', '全能', '全栈',
];

export const DEFAULT_DYNAMIC_READ_ONLY_TOOLS: ReadonlySet<string> = new Set<string>([
  'tools_list', 'file_list', 'file_read', 'file_search',
  'conversation_search', 'conversation_expand',
  'session_list', 'session_search',
  'officepro_status', 'officepro_dashboard',
  'search_web', 'scrape_web',
  'apps_list', 'apps_installed_list', 'permissions_status', 'skills_list', 'mcp_list',
]);

const REASONING_LEVELS: ReasoningLevel[] = [
  'off', 'auto', 'low', 'medium', 'high', 'xhigh', 'max',
];

const isJsonObject = (value: JsonValue): value is JsonObject =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

const jsonObject = (value: JsonValue): JsonObject => {
  if (!isJsonObject(value)) throw new TypeError();
  return value;
};

const jsonArray = (value: JsonValue): JsonValue[] => {
  if (!Array.isArray(value)) throw new TypeError();
  return value;
};

const primitiveContent = (value: JsonValue): string | null => {
  if (value === null) return null;
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  throw new TypeError();
};

const optionalPrimitiveContent = (object: JsonObject, name: string): string | null => {
  const value: JsonValue | undefined = object[name];
  return value === undefined ? null : primitiveContent(value);
};

const stringOrBlank = (object: JsonObject, name: string): string =>
  (optionalPrimitiveContent(object, name) ?? '').trim();

const requiredString = (object: JsonObject, name: string): string => {
  const value: string = stringOrBlank(object, name);
  if (value.length === 0) throw new Error(`${name} is required`);
  return value;
};

const signedDecimalInRange = (
  value: string, positiveLimit: string, negativeLimit: string,
): boolean => {
  if (!/^[+-]?\d+$/.test(value)) return false;
  const negative: boolean = value.startsWith('-');
  const start: number = value.startsWith('-') || value.startsWith('+') ? 1 : 0;
  let digits: string = value.substring(start).replace(/^0+/, '');
  if (digits.length === 0) digits = '0';
  const limit: string = negative ? negativeLimit : positiveLimit;
  return digits.length < limit.length ||
    (digits.length === limit.length && digits <= limit);
};

const intOrNull = (value: JsonValue | undefined): number | null => {
  if (value === undefined) return null;
  const content: string | null = primitiveContent(value);
  if (content === null || !signedDecimalInRange(content, '2147483647', '2147483648')) {
    return null;
  }
  return Number(content);
};

const longOrNull = (value: JsonValue | undefined): number | null => {
  if (value === undefined) return null;
  const content: string | null = primitiveContent(value);
  if (content === null ||
    !signedDecimalInRange(content, '9223372036854775807', '9223372036854775808')) {
    return null;
  }
  return Number(content);
};

const uniqueStrings = (values: string[]): string[] => {
  const result: string[] = [];
  values.forEach((value: string): void => {
    if (!result.includes(value)) result.push(value);
  });
  return result;
};

const primitiveStringArray = (value: JsonValue): string[] => {
  const result: string[] = [];
  jsonArray(value).forEach((item: JsonValue): void => {
    const content: string | null = primitiveContent(item);
    if (content === null) return;
    const trimmed: string = content.trim();
    if (trimmed.length > 0) result.push(trimmed);
  });
  return result;
};

const kotlinStringHashCode = (value: string): number => {
  let hash: number = 0;
  for (let i: number = 0; i < value.length; i++) {
    hash = (Math.imul(hash, 31) + value.charCodeAt(i)) | 0;
  }
  return hash;
};

const smartSubAgentName = (
  seed: string, usedNames: Set<string> = new Set<string>(),
): string => {
  let available: string[] = SMART_SUB_AGENT_NAMES.filter((name: string): boolean => {
    const lower: string = name.toLowerCase();
    for (const used of usedNames) {
      if (used.toLowerCase() === lower) return false;
    }
    return true;
  });
  if (available.length === 0) available = SMART_SUB_AGENT_NAMES;
  const hash: number = kotlinStringHashCode(seed);
  const absolute: number = hash === -2147483648 ? 0 : Math.abs(hash);
  return available[absolute % available.length];
};

// 数字尾缀仅清理已知人名；角色/任务名称改用英文人名，内部角色 id 不变。
export const subAgentDynamicName = (
  requestedName: string, seed: string, usedNames: Set<string> = new Set<string>(),
): string => {
  const base: string = requestedName.trim().replace(/(?:[_-]?\d+)$/, '').toLowerCase();
  const preferred: string | undefined = RECOGNIZED_SUB_AGENT_NAMES.find(
    (name: string): boolean => name.toLowerCase() === base);
  if (preferred !== undefined) {
    let used: boolean = false;
    usedNames.forEach((name: string): void => {
      if (name.toLowerCase() === base) used = true;
    });
    if (!used) return preferred;
  }
  return smartSubAgentName(seed, usedNames);
};

const looksLikeBulkToolDump = (value: string): boolean => {
  if (value.trim().length === 0) return false;
  const lower: string = value.toLowerCase();
  const markers: string[] = [
    '"tool_call_id"', '"toolcallid"', '"tool_name"', '"toolname"',
    '<tool_result', 'uimessagepart.tool',
  ];
  let markerCount: number = 0;
  markers.forEach((marker: string): void => {
    if (lower.includes(marker)) markerCount++;
  });
  return markerCount >= 2 || (
    value.length > 1500 && lower.includes('tool_result') && lower.includes('output')
  );
};

const subAgentValidateTask = (task: SubAgentTaskSpec): void => {
  if (task.objective.trim().length === 0) throw new Error('task.objective is required');
  if (task.outputFormat.trim().length === 0) throw new Error('task.output_format is required');
  if (task.toolsAndSources.trim().length === 0) throw new Error('task.tools_and_sources is required');
  if (task.boundaries.trim().length === 0) throw new Error('task.boundaries is required');
  if (task.context.length > MAX_SUB_AGENT_CONTEXT_CHARS) {
    throw new Error(
      `task.context is too large (${task.context.length} chars); keep it under ` +
      `${MAX_SUB_AGENT_CONTEXT_CHARS} chars and pass only the minimum evidence needed.`,
    );
  }
  if (looksLikeBulkToolDump(task.context)) {
    throw new Error(
      'task.context must not include raw tool result dumps; summarize the relevant evidence ' +
      'and let the subagent re-read allowed sources.',
    );
  }
};

export const subAgentParseTask = (input: JsonObject): SubAgentTaskSpec => {
  const taskValue: JsonValue | undefined = input['task'];
  if (taskValue === undefined) throw new Error('task object is required');
  const taskObject: JsonObject = jsonObject(taskValue);
  const outputFormat: string = stringOrBlank(taskObject, 'output_format');
  const toolsAndSources: string = stringOrBlank(taskObject, 'tools_and_sources');
  const boundaries: string = stringOrBlank(taskObject, 'boundaries');
  const sourceSessionIdsValue: JsonValue | undefined = taskObject['source_session_ids'];
  const task: SubAgentTaskSpec = makeSubAgentTaskSpec({
    objective: requiredString(taskObject, 'objective'),
    outputFormat: outputFormat.length === 0 ? DEFAULT_SUB_AGENT_OUTPUT_FORMAT : outputFormat,
    toolsAndSources: toolsAndSources.length === 0
      ? DEFAULT_SUB_AGENT_TOOLS_AND_SOURCES
      : toolsAndSources,
    boundaries: boundaries.length === 0 ? DEFAULT_SUB_AGENT_BOUNDARIES : boundaries,
    context: stringOrBlank(taskObject, 'context'),
    sessionGrantId: stringOrBlank(taskObject, 'session_grant_id'),
    sourceSessionIds: sourceSessionIdsValue === undefined
      ? []
      : primitiveStringArray(sourceSessionIdsValue),
    historyQuery: stringOrBlank(taskObject, 'history_query'),
    shardIndex: intOrNull(taskObject['shard_index']) ?? 0,
    shardCount: intOrNull(taskObject['shard_count']) ?? 1,
  });
  subAgentValidateTask(task);
  return task;
};

const isGenericName = (value: string): boolean => {
  const lower: string = value.toLowerCase();
  return GENERIC_NAME_PARTS.some((part: string): boolean => lower.includes(part.toLowerCase()));
};

const hasInvocationCue = (value: string): boolean => {
  const lower: string = value.toLowerCase();
  return ['when', 'invoke', 'use for', '用于', '适合', '何时', '调用']
    .some((cue: string): boolean => lower.includes(cue));
};

const isConciseTemporaryChineseRole = (value: string): boolean => {
  if (!value.includes('临时')) return false;
  let chineseCount: number = 0;
  for (let i: number = 0; i < value.length; i++) {
    const code: number = value.charCodeAt(i);
    if (code >= 0x4e00 && code <= 0x9fff) chineseCount++;
  }
  return chineseCount >= 12 &&
    ['写', '创作', '检查', '审阅', '总结', '分析', '整理', '翻译', '生成', '报道']
      .some((cue: string): boolean => value.includes(cue));
};

const hasBoundaryCue = (value: string): boolean => {
  const lower: string = value.toLowerCase();
  return ['boundary', 'boundaries', 'do not', 'never', '边界', '不要', '禁止']
    .some((cue: string): boolean => lower.includes(cue));
};

const hasReportCue = (value: string): boolean => {
  const lower: string = value.toLowerCase();
  return ['report', 'output', 'return', 'summary', '输出', '返回', '报告', '摘要']
    .some((cue: string): boolean => lower.includes(cue));
};

const normalizeDynamicDescription = (
  value: string, taskObjective: string, displayName: string,
): string => {
  const trimmed: string = value.trim();
  if (trimmed.length === 0) {
    const subject: string = taskObjective.length > 0
      ? taskObjective
      : (displayName.length > 0 ? displayName : 'the assigned subtask');
    return `Use when a bounded temporary subagent should handle: ${subject}.`;
  }
  if (hasInvocationCue(trimmed) || isConciseTemporaryChineseRole(trimmed)) return trimmed;
  return `Use when a bounded temporary subagent should handle this role: ${trimmed.replace(/\n/g, ' ')}`;
};

const normalizeDynamicSystemPrompt = (
  value: string, taskObjective: string, description: string,
): string => {
  let prompt: string = value.trim();
  if (prompt.length === 0) {
    prompt = `You are a temporary subagent for: ${taskObjective.length > 0 ? taskObjective : description}.`;
  }
  if (!hasBoundaryCue(prompt)) {
    prompt += '\n\nBoundaries: stay within the assigned objective; do not spawn subagents; ' +
      'use only granted tools; do not ask the user.';
  }
  if (!hasReportCue(prompt)) {
    prompt += '\n\nReport output as: concise summary, findings, evidence, risks, and recommended ' +
      'next steps. Report once and stop.';
  }
  if (prompt.length < 80) {
    prompt += '\n\nFocus: produce a narrow, verifiable result for the supervisor and avoid broad commentary.';
  }
  return prompt;
};

const subAgentNormalizeId = (value: string): string =>
  value.toLowerCase()
    .replace(/[^a-z0-9\-]+/g, '-')
    .replace(/-+/g, '-')
    .replace(/^-+|-+$/g, '');

const fallbackDynamicId = (seed: string): string =>
  `dynamic-${(kotlinStringHashCode(seed) >>> 0).toString(36)}`;

const subAgentValidateNarrowDynamicRole = (
  id: string, displayName: string, description: string, systemPrompt: string,
): void => {
  if (id.trim().length === 0) throw new Error('custom_subagent.name must produce a non-empty id');
  if (isGenericName(id)) throw new Error(`Dynamic subagent name is too broad: ${id}`);
  if (isGenericName(displayName)) {
    throw new Error(`Dynamic subagent name is too broad: ${displayName}`);
  }
  if (!((description.length >= 24 && hasInvocationCue(description)) ||
    isConciseTemporaryChineseRole(description))) {
    throw new Error('custom_subagent.description must explain when this subagent should be invoked');
  }
  if (systemPrompt.length < 80) throw new Error('custom_subagent.system_prompt is too short');
  if (!hasBoundaryCue(systemPrompt)) {
    throw new Error('custom_subagent.system_prompt must include explicit boundaries');
  }
  if (!hasReportCue(systemPrompt)) {
    throw new Error('custom_subagent.system_prompt must include report/output instructions');
  }
};

export const subAgentValidateToolAllowlist = (
  toolAllowlist: Set<string>, availableToolNames: Set<string>,
): void => {
  for (const toolName of toolAllowlist) {
    if (toolName.startsWith('subagent_')) {
      throw new Error('Subagents cannot call subagent_* tools');
    }
  }
  const missing: string[] = [];
  toolAllowlist.forEach((toolName: string): void => {
    if (!availableToolNames.has(toolName)) missing.push(toolName);
  });
  if (missing.length > 0) {
    missing.sort();
    throw new Error(`Tool allowlist contains unavailable tools: ${missing.join(', ')}`);
  }
};

const parseToolProfile = (custom: JsonObject): SubAgentToolProfile => {
  const raw: string | null = optionalPrimitiveContent(custom, 'tool_profile');
  const normalized: string = (raw ?? '').trim().toLowerCase();
  if (normalized.length === 0) return 'read_only';
  const profiles: SubAgentToolProfile[] = [
    'none', 'read_only', 'workspace_read', 'web_read', 'history_read',
  ];
  const found: SubAgentToolProfile | undefined = profiles.find(
    (profile: SubAgentToolProfile): boolean =>
      profile.toLowerCase() === normalized || profile.replace(/_/g, '-') === normalized,
  );
  if (found !== undefined) return found;
  throw new Error(
    `custom_subagent.tool_profile must be one of ${profiles.join(', ')}; got: ${raw}`,
  );
};

const profileAllowedToolNames = (profile: SubAgentToolProfile): string[] => {
  if (profile === 'none') return [];
  if (profile === 'read_only') return Array.from(DEFAULT_DYNAMIC_READ_ONLY_TOOLS);
  if (profile === 'workspace_read') {
    return ['tools_list', 'file_list', 'file_read', 'file_search'];
  }
  if (profile === 'web_read') return ['tools_list', 'search_web', 'scrape_web'];
  return [
    'tools_list', 'conversation_search', 'conversation_expand',
    'session_list', 'session_search',
  ];
};

const copyDefinition = (
  definition: SubAgentDefinition, toolAllowlist: string[], dynamic: boolean,
  maxTurns: number, timeoutMs: number, outputBudgetChars: number,
): SubAgentDefinition => ({
  id: definition.id,
  name: definition.name,
  description: definition.description,
  systemPrompt: definition.systemPrompt,
  toolAllowlist,
  maxTurns,
  timeoutMs,
  outputBudgetChars,
  dynamic,
  modelId: definition.modelId,
  temperature: definition.temperature,
  reasoningLevel: definition.reasoningLevel,
  routingHint: definition.routingHint,
  supportsModelOverride: definition.supportsModelOverride,
  phaseLabels: definition.phaseLabels,
});

const cappedBy = (
  definition: SubAgentDefinition, setting: SubAgentRuntimeSetting,
): SubAgentDefinition => copyDefinition(
  definition,
  definition.toolAllowlist,
  definition.dynamic,
  // 双向钳制:持久化 custom definition 的非正值(旧数据/手工修改)也回收到下限,
  // 只做上限 cap 会把 timeoutMs<=0/maxTurns<=0 直接放进运行时
  Math.max(Math.min(definition.maxTurns, Math.max(setting.maxTurns, 1)), 1),
  Math.max(Math.min(definition.timeoutMs, Math.max(setting.timeoutMs, 1000)), 1000),
  Math.max(Math.min(definition.outputBudgetChars, Math.max(setting.outputBudgetChars, 1000)), 1000),
);

const safeSavedCustomDefinition = (
  definition: SubAgentDefinition,
  setting: SubAgentRuntimeSetting,
  availableToolNames: Set<string>,
): SubAgentDefinition => {
  const availableTools: string[] = uniqueStrings(definition.toolAllowlist.filter(
    (toolName: string): boolean => availableToolNames.has(toolName)));
  subAgentValidateToolAllowlist(new Set<string>(availableTools), availableToolNames);
  if (setting.mode !== 'smart_dynamic') {
    return copyDefinition(
      definition, availableTools, definition.dynamic,
      definition.maxTurns, definition.timeoutMs, definition.outputBudgetChars,
    );
  }
  const smartTools: string[] = availableTools.filter(
    (toolName: string): boolean => DEFAULT_DYNAMIC_READ_ONLY_TOOLS.has(toolName));
  return copyDefinition(
    definition, smartTools, true,
    definition.maxTurns, definition.timeoutMs, definition.outputBudgetChars,
  );
};

const subAgentValidateBudgets = (
  definition: SubAgentDefinition, setting: SubAgentRuntimeSetting,
): void => {
  if (definition.maxTurns < 1 || definition.maxTurns > Math.max(setting.maxTurns, 1)) {
    throw new Error(`custom_subagent.max_turns exceeds setting limit ${setting.maxTurns}`);
  }
  if (definition.timeoutMs < 1000 || definition.timeoutMs > Math.max(setting.timeoutMs, 1000)) {
    throw new Error(`custom_subagent.timeout_ms exceeds setting limit ${setting.timeoutMs}`);
  }
  if (definition.outputBudgetChars < 1000 ||
    definition.outputBudgetChars > Math.max(setting.outputBudgetChars, 1000)) {
    throw new Error(
      `custom_subagent.output_budget_chars exceeds setting limit ${setting.outputBudgetChars}`,
    );
  }
};

const parsedModelId = (custom: JsonObject): string | null => {
  const raw: string = stringOrBlank(custom, 'model_id');
  if (raw.length === 0) return null;
  let hex: string;
  if (/^[0-9a-fA-F]{32}$/.test(raw)) {
    hex = raw.toLowerCase();
  } else if (/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(raw)) {
    hex = raw.replace(/-/g, '').toLowerCase();
  } else {
    throw new Error(`custom_subagent.model_id is not a valid UUID: ${raw}`);
  }
  return `${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-` +
    `${hex.substring(16, 20)}-${hex.substring(20)}`;
};

const bigIntBitLength = (value: bigint): number => value.toString(2).length;

const floorLog2Ratio = (numerator: bigint, denominator: bigint): number => {
  const difference: number = bigIntBitLength(numerator) - bigIntBitLength(denominator);
  if (difference >= 0) {
    return numerator >= denominator << BigInt(difference) ? difference : difference - 1;
  }
  return numerator << BigInt(-difference) >= denominator ? difference : difference - 1;
};

const roundRatioToEven = (numerator: bigint, denominator: bigint): bigint => {
  const quotient: bigint = numerator / denominator;
  const remainder: bigint = numerator % denominator;
  const doubledRemainder: bigint = remainder * BigInt(2);
  if (doubledRemainder > denominator ||
    (doubledRemainder === denominator && quotient % BigInt(2) !== BigInt(0))) {
    return quotient + BigInt(1);
  }
  return quotient;
};

const roundScaledRatioToEven = (
  numerator: bigint, denominator: bigint, binaryShift: number,
): bigint => binaryShift >= 0
  ? roundRatioToEven(numerator << BigInt(binaryShift), denominator)
  : roundRatioToEven(numerator, denominator << BigInt(-binaryShift));

const exactRatioToFloat = (
  numerator: bigint, denominator: bigint, binaryExponent: number, negative: boolean,
): number => {
  if (numerator === BigInt(0)) return negative ? -0 : 0;
  let exponent: number = floorLog2Ratio(numerator, denominator) + binaryExponent;
  if (exponent > 127) {
    return negative ? Number.NEGATIVE_INFINITY : Number.POSITIVE_INFINITY;
  }

  let magnitude: number;
  if (exponent < -126) {
    const significand: bigint = roundScaledRatioToEven(
      numerator, denominator, binaryExponent + 149);
    magnitude = Number(significand) * Math.pow(2, -149);
  } else {
    let significand: bigint = roundScaledRatioToEven(
      numerator, denominator, binaryExponent - exponent + 23);
    if (significand === BigInt(16777216)) {
      significand = BigInt(8388608);
      exponent++;
    }
    if (exponent > 127) {
      return negative ? Number.NEGATIVE_INFINITY : Number.POSITIVE_INFINITY;
    }
    magnitude = Number(significand) * Math.pow(2, exponent - 23);
  }
  return negative ? -magnitude : magnitude;
};

const parseHexFloat = (raw: string): number => {
  let value: string = raw.replace(/[fFdD]$/, '');
  const negative: boolean = value.startsWith('-');
  if (value.startsWith('+') || negative) value = value.substring(1);
  const parts: string[] = value.substring(2).split(/[pP]/);
  const mantissaParts: string[] = parts[0].split('.');
  const fraction: string = mantissaParts.length > 1 ? mantissaParts[1] : '';
  const numerator: bigint = BigInt(`0x${mantissaParts[0]}${fraction}`);
  if (numerator === BigInt(0)) return negative ? -0 : 0;
  const binaryExponentValue: bigint = BigInt(parts[1]) - BigInt(fraction.length * 4);
  const valueExponent: bigint = BigInt(bigIntBitLength(numerator) - 1) + binaryExponentValue;
  if (valueExponent > BigInt(127)) {
    return negative ? Number.NEGATIVE_INFINITY : Number.POSITIVE_INFINITY;
  }
  if (valueExponent < BigInt(-150)) return negative ? -0 : 0;
  return exactRatioToFloat(numerator, BigInt(1), Number(binaryExponentValue), negative);
};

const parseDecimalFloat = (raw: string): number => {
  let value: string = raw.replace(/[fFdD]$/, '');
  const negative: boolean = value.startsWith('-');
  if (value.startsWith('+') || negative) value = value.substring(1);
  const exponentParts: string[] = value.split(/[eE]/);
  const mantissaParts: string[] = exponentParts[0].split('.');
  const fraction: string = mantissaParts.length > 1 ? mantissaParts[1] : '';
  const digits: string = `${mantissaParts[0]}${fraction}`.replace(/^0+/, '');
  if (digits.length === 0) return negative ? -0 : 0;
  const explicitExponent: bigint = exponentParts.length > 1
    ? BigInt(exponentParts[1])
    : BigInt(0);
  const decimalExponent: bigint = explicitExponent - BigInt(fraction.length);
  const decimalOrder: bigint = BigInt(digits.length - 1) + decimalExponent;
  if (decimalOrder > BigInt(38)) {
    return negative ? Number.NEGATIVE_INFINITY : Number.POSITIVE_INFINITY;
  }
  if (decimalOrder < BigInt(-46)) return negative ? -0 : 0;

  const exponent: number = Number(decimalExponent);
  const numeratorDigits: bigint = BigInt(digits);
  if (exponent >= 0) {
    return exactRatioToFloat(
      numeratorDigits * (BigInt(5) ** BigInt(exponent)), BigInt(1), exponent, negative);
  }
  const denominatorExponent: number = -exponent;
  return exactRatioToFloat(
    numeratorDigits, BigInt(5) ** BigInt(denominatorExponent), exponent, negative);
};

const kotlinFloatOrNull = (raw: string): number | null => {
  if (raw === 'NaN' || raw === '+NaN' || raw === '-NaN') return Number.NaN;
  if (raw === 'Infinity' || raw === '+Infinity') return Number.POSITIVE_INFINITY;
  if (raw === '-Infinity') return Number.NEGATIVE_INFINITY;
  const decimal: RegExp = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?[fFdD]?$/;
  if (decimal.test(raw)) return parseDecimalFloat(raw);
  const hexadecimal: RegExp = /^[+-]?0[xX](?:[0-9a-fA-F]+(?:\.[0-9a-fA-F]*)?|\.[0-9a-fA-F]+)[pP][+-]?\d+[fFdD]?$/;
  return hexadecimal.test(raw) ? parseHexFloat(raw) : null;
};

const parsedTemperature = (custom: JsonObject): number | null => {
  const raw: string = stringOrBlank(custom, 'temperature');
  if (raw.length === 0) return null;
  const parsed: number | null = kotlinFloatOrNull(raw);
  if (parsed === null) {
    throw new Error(`custom_subagent.temperature is not a number: ${raw}`);
  }
  return parsed;
};

const parsedReasoningLevel = (custom: JsonObject): ReasoningLevel | null => {
  const raw: string = stringOrBlank(custom, 'reasoning_level').toLowerCase();
  if (raw.length === 0) return null;
  const found: ReasoningLevel | undefined = REASONING_LEVELS.find(
    (value: ReasoningLevel): boolean => value.toLowerCase() === raw,
  );
  if (found !== undefined) return found;
  throw new Error(
    `custom_subagent.reasoning_level must be one of ${REASONING_LEVELS.join(', ')}; got: ${raw}`,
  );
};

const resolveDynamicDefinition = (
  input: JsonObject,
  custom: JsonObject,
  setting: SubAgentRuntimeSetting,
  availableToolNames: Set<string>,
): SubAgentValidationResult => {
  if (!setting.allowDynamicSubAgents) throw new Error('Dynamic subagents are disabled in settings');

  const requestedName: string = stringOrBlank(custom, 'name');
  const taskValue: JsonValue | undefined = input['task'];
  const taskObjective: string = taskValue === undefined
    ? ''
    : stringOrBlank(jsonObject(taskValue), 'objective');
  const rawName: string = requestedName.length === 0 || isGenericName(requestedName)
    ? smartSubAgentName([
      taskObjective,
      stringOrBlank(custom, 'description'),
      stringOrBlank(custom, 'system_prompt'),
    ].join('|'))
    : requestedName;
  const description: string = normalizeDynamicDescription(
    stringOrBlank(custom, 'description'), taskObjective, rawName);
  const systemPrompt: string = normalizeDynamicSystemPrompt(
    stringOrBlank(custom, 'system_prompt'), taskObjective, description);
  let id: string = subAgentNormalizeId(rawName);
  if (id.length === 0) {
    id = fallbackDynamicId([
      requestedName, taskObjective, description, systemPrompt,
    ].join('|'));
  }
  subAgentValidateNarrowDynamicRole(id, rawName, description, systemPrompt);

  let explicitTools: string[] | null = null;
  const toolAllowlistValue: JsonValue | undefined = custom['tool_allowlist'];
  if (toolAllowlistValue !== undefined) {
    explicitTools = uniqueStrings(primitiveStringArray(toolAllowlistValue));
    subAgentValidateToolAllowlist(new Set<string>(explicitTools), availableToolNames);
  }
  const toolProfile: SubAgentToolProfile = parseToolProfile(custom);
  const profileTools: string[] = profileAllowedToolNames(toolProfile).filter(
    (toolName: string): boolean => availableToolNames.has(toolName));
  const requestedTools: string[] = explicitTools === null
    ? profileTools
    : profileTools.filter((toolName: string): boolean => explicitTools !== null && explicitTools.includes(toolName));
  subAgentValidateToolAllowlist(new Set<string>(requestedTools), availableToolNames);

  const maxTurnsLimit: number = Math.max(setting.maxTurns, 1);
  const timeoutLimitMs: number = Math.max(setting.timeoutMs, 1000);
  const outputBudgetLimitChars: number = Math.max(setting.outputBudgetChars, 1000);
  const maxTurnsRaw: number | null = intOrNull(custom['max_turns']);
  const timeoutRaw: number | null = longOrNull(custom['timeout_ms']);
  const outputBudgetRaw: number | null = intOrNull(custom['output_budget_chars']);
  const maxTurns: number = maxTurnsRaw === null
    ? maxTurnsLimit
    : Math.min(Math.max(maxTurnsRaw, 1), maxTurnsLimit);
  const timeoutMs: number = timeoutRaw === null
    ? timeoutLimitMs
    : Math.min(Math.max(timeoutRaw, 1000), timeoutLimitMs);
  const outputBudgetChars: number = outputBudgetRaw === null
    ? outputBudgetLimitChars
    : Math.min(Math.max(outputBudgetRaw, 1000), outputBudgetLimitChars);

  const definition: SubAgentDefinition = makeSubAgentDefinition({
    id,
    name: subAgentDynamicName(rawName, [taskObjective, description, systemPrompt].join('|')),
    description,
    systemPrompt,
    toolAllowlist: requestedTools,
    maxTurns,
    timeoutMs,
    outputBudgetChars,
    dynamic: true,
    modelId: parsedModelId(custom),
    temperature: parsedTemperature(custom),
    reasoningLevel: parsedReasoningLevel(custom),
    routingHint: stringOrBlank(custom, 'routing_hint'),
  });
  subAgentValidateBudgets(definition, setting);
  return makeSubAgentValidationResult(definition);
};

export const subAgentResolveDefinition = (
  input: JsonObject,
  setting: SubAgentRuntimeSetting,
  availableToolNames: Set<string>,
): SubAgentValidationResult => {
  const requestedSubagentId: string = stringOrBlank(input, 'subagent_id');
  const customValue: JsonValue | undefined = input['custom_subagent'];
  if (requestedSubagentId.length > 0 && customValue !== undefined && isJsonObject(customValue)) {
    throw new Error('Pass either subagent_id or custom_subagent, not both.');
  }
  if (customValue !== undefined) {
    return resolveDynamicDefinition(
      input, jsonObject(customValue), setting, availableToolNames);
  }
  if (requestedSubagentId.length > 0) {
    const builtIn: SubAgentDefinition | null = subAgentFindDefinition(requestedSubagentId);
    if (builtIn !== null) {
      if (setting.mode === 'smart_dynamic') {
        throw new Error(
          'Built-in subagents are disabled in smart dynamic mode; use custom_subagent.',
        );
      }
      const overridden: SubAgentDefinition = subAgentApplyOverride(
        builtIn, setting.overrides.get(builtIn.id) ?? null);
      return makeSubAgentValidationResult(cappedBy(overridden, setting));
    }
    const custom: SubAgentDefinition | undefined = setting.customDefinitions.find(
      (definition: SubAgentDefinition): boolean =>
        definition.id === requestedSubagentId ||
        definition.name.toLowerCase() === requestedSubagentId.toLowerCase(),
    );
    if (custom === undefined) throw new Error(`Unknown subagent_id: ${requestedSubagentId}`);
    const capped: SubAgentDefinition = cappedBy(custom, setting);
    return makeSubAgentValidationResult(
      safeSavedCustomDefinition(capped, setting, availableToolNames));
  }
  throw new Error('subagent_id or custom_subagent is required');
};
