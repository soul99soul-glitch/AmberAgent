// Provider 设置模型与生成参数(纯数据层)
//
// Android 基准:
//   ProviderSetting.kt ProviderSetting.OpenAI(:169-)/OpenAIAuthMode/OpenAIBrand
//   Request.kt TextGenerationParams
//   Reasoning.kt ReasoningLevel
//
// 裁剪(D-014):
//   - apiKey / customHeaders 不进纯逻辑层 — secret 与传输 header 属于 adapter 关注点,
//     也避免进入日志/测试快照。buildChatCompletionRequest 只产出请求 body JsonObject。
//   - models 列表/动态拉取逻辑不迁移(本次只迁请求构建所需字段)。

import type { JsonObject, JsonValue } from './json.ts';

// ===== 模型能力/模态 =====

export type ModelAbility = 'tool' | 'reasoning';
export type Modality = 'text' | 'image' | 'audio';  // Android Modality(TEXT/IMAGE/AUDIO)
export type BuiltInTools = 'search' | 'url_context' | 'image_generation';

// 聊天模型默认开启工具调用；显式配置（包括 []）由调用方优先保留。
export const defaultModelAbilities = (modelId: string): ModelAbility[] => {
  switch (modelId.toLowerCase()) {
    case 'deepseek-flash':
    case 'deepseek-v4-pro':
      return ['tool', 'reasoning'];
    default:
      return ['tool'];
  }
};

export interface ChatModel {
  modelId: string;
  displayName: string;
  abilities: ModelAbility[];
  inputModalities: Modality[];
  outputModalities: Modality[];
  tools: BuiltInTools[];
}

export interface ChatModelOpts {
  modelId?: string;
  displayName?: string;
  abilities?: ModelAbility[];
  inputModalities?: Modality[];
  outputModalities?: Modality[];
  tools?: BuiltInTools[];
}

export const makeChatModel = (opts: ChatModelOpts = {}): ChatModel => ({
  modelId: opts.modelId ?? 'test-model',
  displayName: opts.displayName ?? (opts.modelId ?? 'test-model'),
  abilities: opts.abilities ?? defaultModelAbilities(opts.modelId ?? 'test-model'),
  inputModalities: opts.inputModalities ?? ['text'],
  outputModalities: opts.outputModalities ?? ['text'],
  tools: opts.tools ?? [],
});

// ===== ReasoningLevel(Reasoning.kt) =====

export type ReasoningLevel = 'off' | 'auto' | 'low' | 'medium' | 'high' | 'xhigh' | 'max';

interface ReasoningLevelRow {
  budgetTokens: number;
  effort: string;
}

const REASONING_LEVEL_TABLE: Record<ReasoningLevel, ReasoningLevelRow> = {
  off: { budgetTokens: 0, effort: 'none' },
  auto: { budgetTokens: -1, effort: 'auto' },
  low: { budgetTokens: 1000, effort: 'low' },
  medium: { budgetTokens: 2000, effort: 'medium' },
  high: { budgetTokens: 8000, effort: 'high' },
  xhigh: { budgetTokens: 16000, effort: 'xhigh' },
  max: { budgetTokens: 32000, effort: 'max' },
};

export const reasoningLevelIsEnabled = (level: ReasoningLevel): boolean => level !== 'off';
export const reasoningLevelEffort = (level: ReasoningLevel): string => REASONING_LEVEL_TABLE[level].effort;
export const reasoningLevelBudgetTokens = (level: ReasoningLevel): number =>
  REASONING_LEVEL_TABLE[level].budgetTokens;

// ===== ProviderSetting.OpenAI(请求构建子集) =====

export type OpenAIAuthMode =
  | 'api_key'
  | 'codex_oauth'
  | 'grok_oauth'
  | 'zhipu_coding_plan'
  | 'kimi_coding_plan'
  | 'mimo_coding_plan'
  | 'minimax_token_plan';

export type OpenAIBrand = 'generic' | 'openai' | 'deepseek' | 'zhipu' | 'kimi' | 'mimo' | 'minimax';

export interface ProviderSettingOpenAI {
  id: string;
  enabled: boolean;
  name: string;
  baseUrl: string;
  chatCompletionsPath: string;
  useResponseApi: boolean;
  authMode: OpenAIAuthMode;
  brand: OpenAIBrand;
}

export interface ProviderSettingOpenAIOpts {
  id?: string;
  enabled?: boolean;
  name?: string;
  baseUrl?: string;
  chatCompletionsPath?: string;
  useResponseApi?: boolean;
  authMode?: OpenAIAuthMode;
  brand?: OpenAIBrand;
}

export const makeProviderSettingOpenAI = (opts: ProviderSettingOpenAIOpts = {}): ProviderSettingOpenAI => ({
  id: opts.id ?? 'openai-default',
  enabled: opts.enabled ?? true,
  name: opts.name ?? 'OpenAI',
  baseUrl: opts.baseUrl ?? 'https://api.openai.com/v1',
  chatCompletionsPath: opts.chatCompletionsPath ?? '/chat/completions',
  useResponseApi: opts.useResponseApi ?? false,
  authMode: opts.authMode ?? 'api_key',
  brand: opts.brand ?? 'generic',
});

// ===== TextGenerationParams(Request.kt 子集) =====

export interface ChatToolDefinition {
  name: string;
  description: string;
  parameters: JsonObject;
  // Complete MCP root schemas use Google's JSON Schema field rather than its
  // narrower OpenAPI Schema message. Other providers consume parameters.
  parametersJsonSchema?: JsonObject;
}

// Request.kt CustomBody{key,value}
export interface CustomBody {
  key: string;
  value: JsonValue;
}

export interface TextGenerationParams {
  model: ChatModel;
  temperature: number | null;
  topP: number | null;
  maxTokens: number | null;
  tools: ChatToolDefinition[];
  reasoningLevel: ReasoningLevel;
  customBody: CustomBody[];
}

export interface TextGenerationParamsOpts {
  model?: ChatModel;
  temperature?: number | null;
  topP?: number | null;
  maxTokens?: number | null;
  tools?: ChatToolDefinition[];
  reasoningLevel?: ReasoningLevel;
  customBody?: CustomBody[];
}

export const makeTextGenerationParams = (opts: TextGenerationParamsOpts = {}): TextGenerationParams => ({
  model: opts.model ?? makeChatModel({}),
  temperature: opts.temperature ?? null,
  topP: opts.topP ?? null,
  maxTokens: opts.maxTokens ?? null,
  tools: opts.tools ?? [],
  reasoningLevel: opts.reasoningLevel ?? 'auto',
  customBody: opts.customBody ?? [],
});
