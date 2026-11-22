// model_registry — 主流前沿与国产开源模型默认参数注册表（截至 2026-11 官方规格核对）
//
// 职责：当用户未显式覆盖模型属性时，按 modelId 词元打分推断准确的默认参数：
//   - inputModalities (text / image / audio)
//   - outputModalities (text / image / audio)
//   - abilities (tool / reasoning)
//   - contextWindowTokens (上下文窗口)
//   - type (chat / image / embedding)
//
// 匹配规则对齐 KMP ModelDsl.kt：
//   - tokenize(modelId) 将字母段、数字段与符号切为小写 token 序列
//   - tokens(...) 按有序子序列匹配，分值 = 词元数；exact(...) 分值 = 1000 + 词元数
//   - 多条规则命中时仅取最高分规则集合，确保更细型号（如 glm-5.3-flash / claude-haiku-4-5 /
//     deepseek-v4.1-flash / qwen3.8-omni-flash）稳定覆盖粗粒度家族规则。

import type { Modality, ModelAbility } from './provider_model.ts';
import type { ModelType } from './provider_settings.ts';

export interface ModelRegistryDefaults {
  matched: boolean;
  type: ModelType;
  inputModalities: Modality[];
  outputModalities: Modality[];
  abilities: ModelAbility[];
  contextWindowTokens: number | null;
}

interface TokenSpec {
  matches: (token: string) => boolean;
}

interface TokenMatcher {
  score: (modelId: string, tokens: string[]) => number | null;
}

interface ModelDefinition {
  matcher: TokenMatcher;
  type: ModelType;
  inputModalities: Modality[];
  outputModalities: Modality[];
  abilities: ModelAbility[];
  contextWindowTokens: number | null;
}

interface ModelRuleSpec {
  tokens?: (string | RegExp)[];
  notTokens?: (string | RegExp)[][];
  exact?: string;
  type?: ModelType;
  input?: Modality[];
  output?: Modality[];
  abilities?: ModelAbility[];
  contextWindow?: number | null;
}

const EXACT_ID_BONUS: number = 1000;

const isAsciiLetter = (code: number): boolean => (code >= 97 && code <= 122);
const isAsciiDigit = (code: number): boolean => (code >= 48 && code <= 57);

const tokenizeModelId = (modelId: string): string[] => {
  const tokens: string[] = [];
  const input: string = modelId.toLowerCase();
  let index: number = 0;
  while (index < input.length) {
    const code: number = input.charCodeAt(index);
    if (isAsciiLetter(code)) {
      const start: number = index++;
      while (index < input.length && isAsciiLetter(input.charCodeAt(index))) index++;
      tokens.push(input.substring(start, index));
    } else if (isAsciiDigit(code)) {
      const start: number = index++;
      while (index < input.length && isAsciiDigit(input.charCodeAt(index))) index++;
      tokens.push(input.substring(start, index));
    } else {
      tokens.push(input.charAt(index));
      index++;
    }
  }
  return tokens;
};

const toTokenSpec = (raw: string | RegExp): TokenSpec => {
  if (raw instanceof RegExp) {
    return { matches: (token: string): boolean => raw.test(token) };
  }
  const options: string[] = raw.split('|')
    .map((s: string): string => s.trim().toLowerCase())
    .filter((s: string): boolean => s.length > 0);
  return { matches: (token: string): boolean => options.indexOf(token) >= 0 };
};

const sequenceMatcher = (rawSpecs: (string | RegExp)[]): TokenMatcher => {
  const specs: TokenSpec[] = rawSpecs.map(toTokenSpec);
  return {
    score: (_modelId: string, tokens: string[]): number | null => {
      if (specs.length === 0) return null;
      let specIdx: number = 0;
      for (const token of tokens) {
        if (specs[specIdx].matches(token)) {
          specIdx++;
          if (specIdx === specs.length) return specs.length;
        }
      }
      return null;
    },
  };
};

const notSequenceMatcher = (rawSpecs: (string | RegExp)[]): TokenMatcher => {
  const inner: TokenMatcher = sequenceMatcher(rawSpecs);
  return {
    score: (modelId: string, tokens: string[]): number | null =>
      inner.score(modelId, tokens) === null ? 0 : null,
  };
};

const exactMatcher = (id: string): TokenMatcher => {
  const expected: string = id.toLowerCase();
  return {
    score: (modelId: string, tokens: string[]): number | null =>
      modelId.trim().toLowerCase() === expected ? EXACT_ID_BONUS + tokens.length : null,
  };
};

const andMatchers = (matchers: TokenMatcher[]): TokenMatcher => ({
  score: (modelId: string, tokens: string[]): number | null => {
    let total: number = 0;
    for (const m of matchers) {
      const s: number | null = m.score(modelId, tokens);
      if (s === null) return null;
      total += s;
    }
    return total;
  },
});

const defineModel = (spec: ModelRuleSpec): ModelDefinition => {
  const matchers: TokenMatcher[] = [];
  if (spec.exact !== undefined) matchers.push(exactMatcher(spec.exact));
  if (spec.tokens !== undefined) matchers.push(sequenceMatcher(spec.tokens));
  if (spec.notTokens !== undefined) {
    for (const nt of spec.notTokens) matchers.push(notSequenceMatcher(nt));
  }
  const matcher: TokenMatcher = matchers.length === 1
    ? matchers[0]
    : andMatchers(matchers);
  return {
    matcher,
    type: spec.type ?? 'chat',
    inputModalities: spec.input ?? ['text'],
    outputModalities: spec.output ?? ['text'],
    abilities: spec.abilities ?? ['tool'],
    contextWindowTokens: spec.contextWindow !== undefined ? spec.contextWindow : null,
  };
};

const TEXT: Modality[] = ['text'];
const VISION: Modality[] = ['text', 'image'];
const OMNI: Modality[] = ['text', 'image', 'audio'];
const IMG_OUT: Modality[] = ['text', 'image'];
const TOOL: ModelAbility[] = ['tool'];
const TOOL_REASONING: ModelAbility[] = ['tool', 'reasoning'];
const REASONING_ONLY: ModelAbility[] = ['reasoning'];
const NO_ABILITY: ModelAbility[] = [];

const ALL_MODELS: ModelDefinition[] = [
  // ===== OpenAI (GPT-4o / 4.1 / o-series / GPT-5 ~ 5.6 / GPT-6 ~ 6.2 / GPT-Image) =====
  defineModel({ tokens: ['gpt', '4', 'o'], input: VISION, abilities: TOOL, contextWindow: 128_000 }),
  defineModel({ tokens: ['gpt', '4', '1'], input: VISION, abilities: TOOL, contextWindow: 1_000_000 }),
  defineModel({ tokens: [/^o$/, /^\d+$/], input: VISION, abilities: TOOL_REASONING, contextWindow: 200_000 }),
  defineModel({ tokens: ['gpt', 'oss'], input: TEXT, abilities: TOOL_REASONING, contextWindow: 131_072 }),
  defineModel({
    tokens: ['gpt', '5'], notTokens: [['gpt', '5', '.'], ['gpt', '5', 'chat']],
    input: VISION, abilities: TOOL_REASONING, contextWindow: 400_000,
  }),
  defineModel({ tokens: ['gpt', '5', 'chat'], input: VISION, abilities: TOOL, contextWindow: 400_000 }),
  defineModel({ tokens: ['gpt', '5', '1'], input: VISION, abilities: TOOL_REASONING, contextWindow: 400_000 }),
  defineModel({ tokens: ['gpt', '5', '2'], input: VISION, abilities: TOOL_REASONING, contextWindow: 400_000 }),
  defineModel({ tokens: ['gpt', '5', '3'], input: VISION, abilities: TOOL, contextWindow: 400_000 }),
  defineModel({ tokens: ['gpt', '5', '4'], input: VISION, abilities: TOOL_REASONING, contextWindow: 1_050_000 }),
  defineModel({ tokens: ['gpt', '5', '4', 'mini|nano'], input: VISION, abilities: TOOL_REASONING, contextWindow: 400_000 }),
  defineModel({ tokens: ['gpt', '5', '5'], input: VISION, abilities: TOOL_REASONING, contextWindow: 1_050_000 }),
  defineModel({ tokens: ['gpt', '5', '6'], input: VISION, abilities: TOOL_REASONING, contextWindow: 1_050_000 }),
  defineModel({ tokens: ['gpt', '6'], input: VISION, abilities: TOOL_REASONING, contextWindow: 1_047_576 }),
  defineModel({ tokens: ['gpt', 'image'], type: 'image', input: VISION, output: IMG_OUT, abilities: NO_ABILITY }),

  // ===== Anthropic Claude (3.5 / 3.7 / 4 / 4.5 / 4.6 / 4.7 / 5.2 Fable·Mythos / 5.5) =====
  defineModel({ tokens: ['claude', '3', '5', 'sonnet|haiku'], input: VISION, abilities: TOOL_REASONING, contextWindow: 200_000 }),
  defineModel({ tokens: ['claude', '3', '7', 'sonnet'], input: VISION, abilities: TOOL_REASONING, contextWindow: 200_000 }),
  defineModel({ tokens: ['claude', '4'], input: VISION, abilities: TOOL_REASONING, contextWindow: 200_000 }),
  defineModel({ tokens: ['claude', '4', '5'], input: VISION, abilities: TOOL_REASONING, contextWindow: 1_000_000 }),
  defineModel({ tokens: ['claude', 'haiku', '4', '5'], input: VISION, abilities: TOOL_REASONING, contextWindow: 200_000 }),
  defineModel({ tokens: ['claude', '4', '6'], input: VISION, abilities: TOOL_REASONING, contextWindow: 1_000_000 }),
  defineModel({ tokens: ['claude', '4', '7'], input: VISION, abilities: TOOL_REASONING, contextWindow: 1_000_000 }),
  defineModel({ tokens: ['claude', '5'], input: VISION, abilities: TOOL_REASONING, contextWindow: 1_000_000 }),
  defineModel({ tokens: ['claude', 'fable|mythos', '5'], input: VISION, abilities: TOOL_REASONING, contextWindow: 1_000_000 }),

  // ===== Google Gemini (2.0 / 2.5 / 3 / 3.1 / 3.5 / 3.8 / 4 Carbon·Neon·Argon·Hydrogen / Decision) =====
  defineModel({ tokens: ['gemini', '2', '0', 'flash'], input: VISION, abilities: TOOL, contextWindow: 1_000_000 }),
  defineModel({
    tokens: ['gemini', '2', '5', 'flash|pro'], notTokens: [['image']],
    input: OMNI, abilities: TOOL_REASONING, contextWindow: 1_048_576,
  }),
  defineModel({
    tokens: ['gemini', '3'], notTokens: [['image']],
    input: OMNI, abilities: TOOL_REASONING, contextWindow: 1_048_576,
  }),
  defineModel({
    tokens: ['gemini', '3', '1', 'pro|flash'], notTokens: [['image']],
    input: OMNI, abilities: TOOL_REASONING, contextWindow: 1_048_576,
  }),
  defineModel({
    tokens: ['gemini', '3', '5|8'], notTokens: [['image']],
    input: OMNI, abilities: TOOL_REASONING, contextWindow: 1_048_576,
  }),
  defineModel({
    tokens: ['gemini', '4'], notTokens: [['image']],
    input: OMNI, abilities: TOOL_REASONING, contextWindow: 1_048_576,
  }),
  defineModel({ tokens: ['gemini', 'decision', '1'], input: OMNI, abilities: TOOL_REASONING, contextWindow: 256_000 }),
  defineModel({ exact: 'gemini-flash-latest', input: OMNI, abilities: TOOL_REASONING, contextWindow: 1_048_576 }),
  defineModel({ exact: 'gemini-pro-latest', input: OMNI, abilities: TOOL_REASONING, contextWindow: 1_048_576 }),
  defineModel({ tokens: ['gemini', '2', '5', 'flash', 'image'], type: 'image', input: VISION, output: IMG_OUT, abilities: NO_ABILITY }),
  defineModel({ tokens: ['gemini', '3', 'pro', 'image'], type: 'image', input: VISION, output: IMG_OUT, abilities: NO_ABILITY }),
  defineModel({ tokens: ['gemini', '3', '1', 'flash', 'image'], type: 'image', input: VISION, output: IMG_OUT, abilities: REASONING_ONLY }),
  defineModel({ tokens: ['nano', 'banana'], type: 'image', input: VISION, output: IMG_OUT, abilities: NO_ABILITY }),

  // ===== xAI Grok (3 / 4 / 4.1 / 4.2·4.20·4.3 / 4.5·4.6·4.7 / build) =====
  defineModel({ tokens: ['grok', '3'], input: TEXT, abilities: TOOL_REASONING, contextWindow: 131_072 }),
  defineModel({ tokens: ['grok', '4'], input: VISION, abilities: TOOL_REASONING, contextWindow: 256_000 }),
  defineModel({ tokens: ['grok', '4', '2|20|3'], input: VISION, abilities: TOOL_REASONING, contextWindow: 1_000_000 }),
  defineModel({ tokens: ['grok', '4', '5|6|7'], input: VISION, abilities: TOOL_REASONING, contextWindow: 500_000 }),
  defineModel({ tokens: ['grok', 'build'], input: VISION, abilities: TOOL_REASONING, contextWindow: 256_000 }),

  // ===== Alibaba Qwen (3 / 3.5 / 3.6 / 3.7 / 3.8 / 4 / VL / Omni / Coder / Image) =====
  defineModel({ tokens: ['qwen', 'mt'], input: TEXT, abilities: NO_ABILITY, contextWindow: 16_000 }),
  defineModel({ tokens: ['qwen', 'max'], input: TEXT, abilities: TOOL, contextWindow: 32_000 }),
  defineModel({ tokens: ['qwen', 'turbo'], input: TEXT, abilities: TOOL_REASONING, contextWindow: 128_000 }),
  defineModel({ tokens: ['qwen', 'plus|flash'], input: TEXT, abilities: TOOL_REASONING, contextWindow: 1_000_000 }),
  defineModel({ tokens: ['qwen', 'vl'], input: VISION, abilities: TOOL, contextWindow: 128_000 }),
  defineModel({ tokens: ['qwen', '3'], input: TEXT, abilities: TOOL_REASONING, contextWindow: 128_000 }),
  defineModel({ tokens: ['qwen', '3', 'max'], input: TEXT, abilities: TOOL_REASONING, contextWindow: 256_000 }),
  defineModel({ tokens: ['qwen', '3', 'vl'], input: VISION, abilities: TOOL_REASONING, contextWindow: 256_000 }),
  defineModel({ tokens: ['qwen', '3', 'coder'], input: TEXT, abilities: TOOL, contextWindow: 256_000 }),
  defineModel({ tokens: ['qwen', '3', 'coder', 'plus|flash'], input: TEXT, abilities: TOOL, contextWindow: 1_000_000 }),
  defineModel({ tokens: ['qwen', '3', '5'], input: VISION, abilities: TOOL_REASONING, contextWindow: 256_000 }),
  defineModel({ tokens: ['qwen', '3', '5', 'plus|flash'], input: OMNI, abilities: TOOL_REASONING, contextWindow: 1_000_000 }),
  defineModel({ tokens: ['qwen', '3', '6'], input: VISION, abilities: TOOL_REASONING, contextWindow: 256_000 }),
  defineModel({ tokens: ['qwen', '3', '6', 'max'], input: VISION, abilities: TOOL_REASONING, contextWindow: 256_000 }),
  defineModel({ tokens: ['qwen', '3', '6', 'plus|flash'], input: OMNI, abilities: TOOL_REASONING, contextWindow: 1_000_000 }),
  defineModel({
    tokens: ['qwen', '3', '7'], notTokens: [['omni']],
    input: VISION, abilities: TOOL_REASONING, contextWindow: 1_000_000,
  }),
  defineModel({
    tokens: ['qwen', '3', '8'], notTokens: [['omni']],
    input: VISION, abilities: TOOL_REASONING, contextWindow: 1_000_000,
  }),
  defineModel({
    tokens: ['qwen', '3', '8', 'max|plus|flash'], notTokens: [['omni']],
    input: VISION, abilities: TOOL_REASONING, contextWindow: 1_000_000,
  }),
  defineModel({ tokens: ['qwen', '3', '8', 'omni'], input: OMNI, abilities: TOOL_REASONING, contextWindow: 65_536 }),
  defineModel({
    tokens: ['qwen', '4'], notTokens: [['omni']],
    input: VISION, abilities: TOOL_REASONING, contextWindow: 1_000_000,
  }),
  defineModel({
    tokens: ['qwen', '4', 'max|plus|flash'], notTokens: [['omni']],
    input: VISION, abilities: TOOL_REASONING, contextWindow: 1_000_000,
  }),
  defineModel({ tokens: ['qwen', 'omni'], input: OMNI, abilities: TOOL_REASONING, contextWindow: 65_536 }),
  defineModel({ tokens: ['qwen', 'image'], type: 'image', input: VISION, output: IMG_OUT, abilities: NO_ABILITY }),
  defineModel({ tokens: ['wan', 'image'], type: 'image', input: VISION, output: IMG_OUT, abilities: NO_ABILITY }),

  // ===== DeepSeek (V3 / V3.1 / V3.2 / R1 / V4 / V4-Pro / V4.1-Flash) =====
  defineModel({ tokens: ['deepseek', 'v', '3'], input: TEXT, abilities: TOOL, contextWindow: 128_000 }),
  defineModel({ tokens: ['deepseek', 'v', '3', '1|2'], input: TEXT, abilities: TOOL_REASONING, contextWindow: 128_000 }),
  defineModel({ tokens: ['deepseek', 'r', '1'], input: TEXT, abilities: TOOL_REASONING, contextWindow: 128_000 }),
  defineModel({ tokens: ['deepseek', 'chat'], input: TEXT, abilities: TOOL, contextWindow: 1_000_000 }),
  defineModel({ tokens: ['deepseek', 'reasoner'], input: TEXT, abilities: TOOL_REASONING, contextWindow: 1_000_000 }),
  defineModel({
    tokens: ['deepseek', 'v', '4'], notTokens: [['deepseek', 'v', '4', '1']],
    input: TEXT, abilities: TOOL_REASONING, contextWindow: 1_000_000,
  }),
  defineModel({
    tokens: ['deepseek', 'v', '4', 'pro'],
    input: TEXT, abilities: TOOL_REASONING, contextWindow: 1_000_000,
  }),
  defineModel({
    tokens: ['deepseek', 'v', '4', 'flash'], notTokens: [['deepseek', 'v', '4', '1']],
    input: TEXT, abilities: TOOL_REASONING, contextWindow: 1_000_000,
  }),
  // DeepSeek-V4.1-Flash 与官方别名 deepseek-flash 为原生多模态（视觉+文本）
  defineModel({ tokens: ['deepseek', 'v', '4', '1'], input: VISION, abilities: TOOL_REASONING, contextWindow: 1_000_000 }),
  defineModel({ tokens: ['deepseek', 'v', '4', '1', 'flash'], input: VISION, abilities: TOOL_REASONING, contextWindow: 1_000_000 }),
  defineModel({ exact: 'deepseek-flash', input: VISION, abilities: TOOL_REASONING, contextWindow: 1_000_000 }),

  // ===== Zhipu GLM (4.5 ~ 4.7 / 4.5V·4.6V / 5 ~ 5.3 / 5.3-Flash / 5V) =====
  defineModel({ tokens: ['glm', '4', '5'], input: TEXT, abilities: TOOL_REASONING, contextWindow: 128_000 }),
  defineModel({ tokens: ['glm', '4', '5', 'v'], input: VISION, abilities: TOOL_REASONING, contextWindow: 128_000 }),
  defineModel({ tokens: ['glm', '4', '6|7'], input: TEXT, abilities: TOOL_REASONING, contextWindow: 200_000 }),
  defineModel({ tokens: ['glm', '4', '6', 'v'], input: VISION, abilities: TOOL_REASONING, contextWindow: 200_000 }),
  defineModel({ tokens: ['glm', '5'], input: TEXT, abilities: TOOL_REASONING, contextWindow: 200_000 }),
  defineModel({ tokens: ['glm', '5', 'v'], input: VISION, abilities: TOOL_REASONING, contextWindow: 200_000 }),
  defineModel({ tokens: ['glm', '5', '1'], input: TEXT, abilities: TOOL_REASONING, contextWindow: 200_000 }),
  defineModel({ tokens: ['glm', '5', '2'], input: TEXT, abilities: TOOL_REASONING, contextWindow: 1_000_000 }),
  defineModel({ tokens: ['glm', '5', '3'], input: TEXT, abilities: TOOL_REASONING, contextWindow: 1_000_000 }),
  defineModel({ tokens: ['glm', '5', '3', 'flash|flashx'], input: VISION, abilities: TOOL_REASONING, contextWindow: 1_000_000 }),

  // ===== Moonshot Kimi (K2 / K2.5~2.7 / Coding / K3 / K3.1) =====
  defineModel({ tokens: ['kimi', 'k', '2'], input: TEXT, abilities: TOOL_REASONING, contextWindow: 256_000 }),
  defineModel({ tokens: ['kimi', 'k', '2', '5|6|7'], input: VISION, abilities: TOOL_REASONING, contextWindow: 262_144 }),
  defineModel({ tokens: ['kimi', 'coding'], input: VISION, abilities: TOOL_REASONING, contextWindow: 262_144 }),
  defineModel({ tokens: ['kimi', 'k', '3'], input: VISION, abilities: TOOL_REASONING, contextWindow: 1_048_576 }),

  // ===== MiniMax (M2 / M2.5 / M2.7 / M3 / M3.1) =====
  defineModel({ tokens: ['minimax', 'm', '2'], input: TEXT, abilities: TOOL_REASONING, contextWindow: 200_000 }),
  defineModel({ tokens: ['minimax', 'm', '2', '5'], input: TEXT, abilities: TOOL_REASONING, contextWindow: 200_000 }),
  defineModel({ tokens: ['minimax', 'm', '2', '7'], input: TEXT, abilities: TOOL_REASONING, contextWindow: 204_800 }),
  defineModel({ tokens: ['minimax', 'm', '3'], input: VISION, abilities: TOOL_REASONING, contextWindow: 1_000_000 }),

  // ===== Xiaomi MiMo (V2 / V2.5 / V2.6) =====
  defineModel({ tokens: ['mimo', 'v', '2'], input: TEXT, abilities: TOOL_REASONING, contextWindow: 256_000 }),
  defineModel({ tokens: ['mimo', 'v', '2', 'pro'], input: TEXT, abilities: TOOL_REASONING, contextWindow: 1_000_000 }),
  defineModel({ tokens: ['mimo', 'v', '2', 'omni|flash'], input: VISION, abilities: TOOL_REASONING, contextWindow: 256_000 }),
  defineModel({ tokens: ['mimo', 'v', '2', '5'], input: OMNI, abilities: TOOL_REASONING, contextWindow: 1_000_000 }),
  defineModel({ tokens: ['mimo', 'v', '2', '6'], input: OMNI, abilities: TOOL_REASONING, contextWindow: 1_048_576 }),

  // ===== ByteDance Doubao / Seed (1.6 / 1.8 / Seed 2.0 / Seed 2.1) =====
  defineModel({ tokens: ['doubao', '1', '6|8'], input: VISION, abilities: TOOL_REASONING, contextWindow: 256_000 }),
  defineModel({ tokens: ['seed', '2', '0'], input: VISION, abilities: TOOL_REASONING, contextWindow: 256_000 }),
  defineModel({ tokens: ['seed', '2', '1'], input: OMNI, abilities: TOOL_REASONING, contextWindow: 1_000_000 }),
  defineModel({ tokens: ['seed', '2', '1', 'turbo'], input: VISION, abilities: TOOL_REASONING, contextWindow: 256_000 }),
  defineModel({ tokens: ['doubao', 'seed', 'evolving'], input: OMNI, abilities: TOOL_REASONING, contextWindow: 1_000_000 }),

  // ===== 其他开源多模态模型 (StepFun / InternLM) =====
  defineModel({ tokens: ['step', '3'], input: VISION, abilities: TOOL_REASONING }),
  defineModel({ tokens: ['intern', 's', '1'], input: VISION, abilities: TOOL_REASONING }),
];

const resolveMatchedDefinitions = (modelId: string): ModelDefinition[] => {
  const trimmed: string = modelId.trim();
  if (trimmed.length === 0) return [];
  const tokens: string[] = tokenizeModelId(trimmed);
  let bestScore: number | null = null;
  const matches: ModelDefinition[] = [];
  for (const model of ALL_MODELS) {
    const score: number | null = model.matcher.score(trimmed, tokens);
    if (score === null) continue;
    if (bestScore === null || score > bestScore) {
      bestScore = score;
      matches.length = 0;
      matches.push(model);
    } else if (score === bestScore) {
      matches.push(model);
    }
  }
  return matches;
};

const mergeModalities = (lists: Modality[][]): Modality[] => {
  const set: Set<Modality> = new Set<Modality>();
  for (const list of lists) {
    for (const m of list) set.add(m);
  }
  if (set.size === 0) return ['text'];
  const order: Modality[] = ['text', 'image', 'audio'];
  return order.filter((m: Modality): boolean => set.has(m));
};

const mergeAbilities = (lists: ModelAbility[][]): ModelAbility[] => {
  const set: Set<ModelAbility> = new Set<ModelAbility>();
  for (const list of lists) {
    for (const a of list) set.add(a);
  }
  const out: ModelAbility[] = [];
  if (set.has('tool')) out.push('tool');
  if (set.has('reasoning')) out.push('reasoning');
  return out;
};

export const resolveModelDefaults = (modelId: string): ModelRegistryDefaults => {
  const matches: ModelDefinition[] = resolveMatchedDefinitions(modelId);
  if (matches.length === 0) {
    return {
      matched: false,
      type: 'chat',
      inputModalities: ['text'],
      outputModalities: ['text'],
      abilities: ['tool'],
      contextWindowTokens: null,
    };
  }
  let maxContext: number | null = null;
  let resolvedType: ModelType = 'chat';
  for (const m of matches) {
    if (m.type !== 'chat') resolvedType = m.type;
    if (m.contextWindowTokens !== null && (maxContext === null || m.contextWindowTokens > maxContext)) {
      maxContext = m.contextWindowTokens;
    }
  }
  return {
    matched: true,
    type: resolvedType,
    inputModalities: mergeModalities(matches.map((m: ModelDefinition): Modality[] => m.inputModalities)),
    outputModalities: mergeModalities(matches.map((m: ModelDefinition): Modality[] => m.outputModalities)),
    abilities: mergeAbilities(matches.map((m: ModelDefinition): ModelAbility[] => m.abilities)),
    contextWindowTokens: maxContext,
  };
};

export const resolveModelInputModalities = (modelId: string): Modality[] =>
  resolveModelDefaults(modelId).inputModalities;

export const resolveModelOutputModalities = (modelId: string): Modality[] =>
  resolveModelDefaults(modelId).outputModalities;

export const resolveModelAbilities = (modelId: string): ModelAbility[] =>
  resolveModelDefaults(modelId).abilities;

export const resolveModelContextWindow = (modelId: string): number | null =>
  resolveModelDefaults(modelId).contextWindowTokens;

export const resolveEffectiveModelContextWindow = (
  model: { modelId: string; contextWindowTokens: number | null },
): number | null =>
  model.contextWindowTokens !== null ? model.contextWindowTokens : resolveModelContextWindow(model.modelId);
