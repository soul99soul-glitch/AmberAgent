// Novel 管理上下文、模型、输出上限和工具目录；共享 Chat 配置只提供非冲突参数。
import type { JsonObject } from './json.ts';
import type { ChatToolDefinition, CustomBody, TextGenerationParams } from './provider_model.ts';
import { makeTextGenerationParams } from './provider_model.ts';
import { estimateContextWindow } from './context_compact.ts';
import type { NovelStructuredTaskOptions } from '@amber/deepread-domain';

// 未配置时最多 8192 输出，并保留至少 75% 窗口给小说资料与来源正文。
export const resolveNovelMaxOutputTokens = (
  requested: number | null | undefined, configured: number | null | undefined,
  contextWindowTokens: number | null,
): number => requested ?? configured ?? Math.max(1, Math.min(
  8192, Math.floor(estimateContextWindow(contextWindowTokens) / 4)));

const MANAGED_BODY_FIELDS: string[] = [
  'model', 'messages', 'system', 'systemInstruction', 'input', 'instructions', 'contents',
  'tools', 'tool_choice', 'toolConfig', 'max_tokens', 'max_completion_tokens', 'max_output_tokens',
];
const STATE_REASONING_BODY_FIELDS: string[] = [
  'thinking', 'reasoning', 'reasoning_effort', 'enable_thinking', 'thinking_budget', 'thinking_mode',
];

// 状态任务的显式开关覆盖模型自定义推理参数；其余请求仍保留全局配置。
const stateCustomBody = (items: CustomBody[]): CustomBody[] => items
  .filter(item => !STATE_REASONING_BODY_FIELDS.includes(item.key))
  .map((item): CustomBody => {
    if ((item.key !== 'generationConfig' && item.key !== 'output_config') || item.value === null
      || typeof item.value !== 'object' || Array.isArray(item.value)) return item;
    const value: JsonObject = { ...(item.value as JsonObject) };
    if (item.key === 'generationConfig') delete value.thinkingConfig;
    else delete value.effort;
    return { key: item.key, value };
  });

const novelCustomBody = (
  items: CustomBody[], maxOutputTokens: number,
): CustomBody[] => items.map((item): CustomBody => {
  if (MANAGED_BODY_FIELDS.includes(item.key)) {
    throw new Error(`小说请求不能使用自定义参数 ${item.key} 覆盖上下文、输出上限或工具目录；请在模型设置中移除此项。`);
  }
  if (item.key === 'generationConfig') {
    if (item.value === null || typeof item.value !== 'object' || Array.isArray(item.value)
      || Object.prototype.hasOwnProperty.call(item.value, 'maxOutputTokens')) {
      throw new Error('小说请求的自定义 generationConfig 必须是对象且不能覆盖 maxOutputTokens；请在模型设置中修改此项。');
    }
    return { key: item.key, value: { ...(item.value as JsonObject), maxOutputTokens } };
  }
  return { key: item.key, value: item.value };
});

export const makeNovelTextGenerationParams = (
  source: TextGenerationParams, tools: ChatToolDefinition[], maxOutputTokens: number,
  taskOptions?: NovelStructuredTaskOptions,
): TextGenerationParams => makeTextGenerationParams({
  // 模型内置搜索不在 Novel 的审批目录内，不能绕过 toolProfile。
  // 小说只生成文本，Google 的 image-output 分支不会发送 systemInstruction。
  model: { ...source.model, tools: [], outputModalities: ['text'] },
  temperature: source.temperature,
  topP: source.topP,
  maxTokens: maxOutputTokens,
  tools,
  reasoningLevel: taskOptions === undefined ? source.reasoningLevel : taskOptions.reasoningEnabled ? 'auto' : 'off',
  customBody: novelCustomBody(taskOptions === undefined ? source.customBody : stateCustomBody(source.customBody), maxOutputTokens),
});
