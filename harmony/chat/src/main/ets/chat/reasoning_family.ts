// reasoning_family — 模型推理能力推断 + 档位段集(ChatInputUsage.kt:509-660)
//
// 比 reasoningLevelsForModel(M2.1,简单 modelId 关键字匹配)更精确:
//   引入 ReasoningFamily 枚举(8 族),按 modelId + provider 类型 + 版本模式匹配。
//   每族映射到不同的推理档位选项集。
//
// Android 基准: ChatInputUsage.kt:509-660(reasoningOptionsOf/reasoningFamily/
//   coerceToReasoningOptions/isQwenPlusBinaryReasoningModel/providerRoutingKey)
//
// 纯逻辑:零 UI/零 IO,可单测。

import type { ReasoningLevel } from './provider_model.ts';
import type { ProviderModel, ProviderSetting } from './provider_settings.ts';

export type ReasoningFamily =
  | 'CLAUDE_OPUS_47' | 'CLAUDE_MAX' | 'CLAUDE_HIGH'
  | 'OPENAI_XHIGH' | 'OPENAI'
  | 'GEMINI' | 'DEEPSEEK' | 'BINARY' | 'GENERIC' | 'NONE';

export interface ReasoningOption {
  level: ReasoningLevel;
  label: string;
}

const option = (level: ReasoningLevel): ReasoningOption => ({ level, label: level });

// providerRoutingKey(ChatInputUsage.kt:646-660)
export const providerRoutingKey = (provider: ProviderSetting | null): string => {
  if (provider === null) return '';
  if (provider.type === 'claude') return 'claude';
  if (provider.type === 'google') return 'gemini';
  // openai 变体:按 baseUrl/name 细分
  const endpoint: string = `${provider.baseUrl} ${provider.name}`.toLowerCase();
  if (endpoint.includes('deepseek')) return 'deepseek';
  if (endpoint.includes('moonshot') || endpoint.includes('kimi')) return 'kimi';
  if (endpoint.includes('bigmodel') || endpoint.includes('zhipu')) return 'zhipu';
  if (endpoint.includes('api.openai.com') || provider.name.toLowerCase() === 'openai') return 'openai';
  return '';
};

// isQwenPlusBinaryReasoningModel(:640-644)
const isQwenPlusBinaryReasoningModel = (id: string): boolean => {
  if (!id.includes('qwen') || !id.includes('plus')) return false;
  return /(^|[^0-9])3[._-]?5([^0-9]|$)/.test(id) || /(^|[^0-9])3[._-]?6([^0-9]|$)/.test(id);
};

// reasoningFamily(:615-638)
export const reasoningFamilyOf = (
  model: ProviderModel,
  provider: ProviderSetting | null,
): ReasoningFamily => {
  const id: string = model.modelId.toLowerCase();
  const providerKey: string = providerRoutingKey(provider);

  if (id.includes('claude') || providerKey === 'claude') {
    if (id.includes('opus') && id.includes('4') && id.includes('7')) return 'CLAUDE_OPUS_47';
    if (id.includes('mythos')) return 'CLAUDE_MAX';
    if (id.includes('opus') && id.includes('4') && (id.includes('5') || id.includes('6'))) return 'CLAUDE_MAX';
    if (id.includes('sonnet') && id.includes('4') && id.includes('6')) return 'CLAUDE_HIGH';
    return 'GENERIC';
  }
  if (id.includes('deepseek') || providerKey === 'deepseek') return 'DEEPSEEK';
  if (id.includes('kimi') || id.includes('moonshot') || providerKey === 'kimi') return 'BINARY';
  if (id.includes('glm') || id.includes('zhipu') || providerKey === 'zhipu') return 'BINARY';
  if (id.includes('mimo')) return 'BINARY';
  if (isQwenPlusBinaryReasoningModel(id)) return 'BINARY';
  if (providerKey === 'gemini') return 'GEMINI';
  if (id.includes('gpt-5.5') || id.includes('gpt-5.4')) return 'OPENAI_XHIGH';
  if (id.includes('gpt-5') || id.includes('codex') || /\bo\d+/.test(id)) return 'OPENAI';
  if (model.abilities.includes('reasoning')) return 'GENERIC';
  return 'NONE';
};

// reasoningOptionsForModel(:530-613)
export const reasoningOptionsForModel = (
  model: ProviderModel | null,
  provider: ProviderSetting | null,
): ReasoningOption[] => {
  if (model === null) {
    return [option('off'), option('auto'), option('low'), option('medium'), option('high'), option('xhigh')];
  }
  const family: ReasoningFamily = reasoningFamilyOf(model, provider);
  switch (family) {
    case 'CLAUDE_OPUS_47':
      return [option('off'), option('auto'), option('low'), option('medium'), option('high'), option('xhigh'), option('max')];
    case 'CLAUDE_MAX':
      return [option('off'), option('auto'), option('low'), option('medium'), option('high'), option('max')];
    case 'CLAUDE_HIGH':
      return [option('off'), option('auto'), option('low'), option('medium'), option('high')];
    case 'OPENAI_XHIGH':
      return [option('low'), option('medium'), option('high'), option('xhigh')];
    case 'OPENAI':
      return [option('low'), option('medium'), option('high'), option('xhigh')];
    case 'GEMINI':
      return [option('off'), option('auto'), option('low'), option('medium'), option('high')];
    case 'DEEPSEEK':
      return [option('off'), option('high'), option('max')];
    case 'BINARY':
      return [option('off'), option('auto')];
    case 'GENERIC':
      return [option('off'), option('auto'), option('low'), option('medium'), option('high'), option('xhigh')];
    case 'NONE':
      return [option('off')];
  }
};

// coerceToReasoningOptions(:513-528)— 将当前 level 规整到可用选项集
export const coerceToReasoningOptions = (
  level: ReasoningLevel,
  options: ReasoningOption[],
): ReasoningLevel => {
  if (options.some((o: ReasoningOption): boolean => o.level === level)) return level;
  if (level === 'auto') {
    const med: ReasoningOption | undefined = options.find((o) => o.level === 'medium');
    if (med !== undefined) return 'medium';
    const high: ReasoningOption | undefined = options.find((o) => o.level === 'high');
    if (high !== undefined) return 'high';
    return options.length > 0 ? options[0].level : 'off';
  }
  if ((level === 'xhigh' || level === 'max') && options.some((o) => o.level === 'max')) {
    return 'max';
  }
  if (level !== 'off' && options.some((o) => o.level === 'auto')) return 'auto';
  return options.length > 0 ? options[0].level : 'off';
};
