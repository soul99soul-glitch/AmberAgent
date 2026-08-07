// reasoning_levels — 模型感知的推理档位段集 + 标签
//
// Android 基准: feature/ui/components/ai/ModelList.kt:1097-1133
//   reasoningLevelsForModel(model): List<Pair<ReasoningLevel, String>>
//   按 model.modelId 关键字匹配 → 返回该模型支持的推理档位段集 + 短标签。
//   来源(Android 注释 :1090-1095):
//     - DeepSeek: off/high/max(3 段)
//     - OpenAI gpt-5/o-系列/codex: low/medium/high/xhigh(4 段)
//     - Anthropic claude: auto/low/medium/high/xhigh/max(6 段)
//     - Kimi / GLM / Zhipu: off/auto(2 段)
//     - 默认: off/auto(2 段)
//
// 用途:聊天输入框 ReasoningPicker 段集选择器(ChatInputComposers.kt:293-314
//   "thinking footer");选档位 → withReasoningLevelForModel 持久化。
//
// 纯逻辑:零 UI/零 IO,可单测。

import type { ReasoningLevel } from './provider_model.ts';
import type { ProviderModel } from './provider_settings.ts';

export interface ReasoningLevelOption {
  level: ReasoningLevel;
  label: string;
}

// reasoningLevelsForModel(ModelList.kt:1097-1133 逐字)
//   按 modelId 关键字匹配返回段集;无 reasoning 能力的模型也返回默认 off/auto
//   (调用方应先用 model.abilities 含 'reasoning' 做门控)
export const reasoningLevelsForModel = (model: ProviderModel): ReasoningLevelOption[] => {
  if (model.abilities.indexOf('reasoning') < 0) return [];
  const id: string = model.modelId.toLowerCase();
  if (id.includes('deepseek')) {
    return [
      { level: 'off', label: 'off' },
      { level: 'high', label: 'high' },
      { level: 'max', label: 'max' },
    ];
  }
  // Anthropic claude:首段 auto(让 Claude 自决 thinking_budget)
  if (id.includes('claude')) {
    return [
      { level: 'auto', label: 'auto' },
      { level: 'low', label: 'low' },
      { level: 'medium', label: 'med' },
      { level: 'high', label: 'high' },
      { level: 'xhigh', label: 'xhigh' },
      { level: 'max', label: 'max' },
    ];
  }
  if (id.includes('gpt') || id.includes('codex') || id.includes('o1')
    || id.includes('o3') || id.includes('o4')) {
    return [
      { level: 'low', label: 'low' },
      { level: 'medium', label: 'med' },
      { level: 'high', label: 'high' },
      { level: 'xhigh', label: 'xhigh' },
    ];
  }
  if (id.includes('kimi') || id.includes('glm') || id.includes('zhipu')) {
    return [
      { level: 'off', label: 'off' },
      { level: 'auto', label: 'auto' },
    ];
  }
  return [
    { level: 'off', label: 'off' },
    { level: 'auto', label: 'auto' },
  ];
};
