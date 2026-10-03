import type { NovelStructuredTaskOptions } from '@amber/deepread-domain';
import type { TextGenerationParams } from './provider_model.ts';
import type { ProviderSetting } from './provider_settings.ts';
import { hostOf } from './openai_request.ts';

// 官方模型文档确认支持 none 的型号。仅状态抽取覆写；其它调用沿既有配置。
// https://developers.openai.com/api/docs/models/gpt-5.5
// https://developers.openai.com/api/docs/models/gpt-6-sol
export const applyNovelStateReasoning = (
  params: TextGenerationParams, provider: ProviderSetting, options?: NovelStructuredTaskOptions,
): TextGenerationParams => {
  if (options === undefined || options.reasoningEnabled || provider.type !== 'openai'
    || hostOf(provider.baseUrl) !== 'api.openai.com' || !params.model.abilities.includes('reasoning')
    || !/^gpt-(5\.(1|2|4|5)|6-(sol|luna))(-\d{4}-\d{2}-\d{2})?$/.test(params.model.modelId)) return params;
  return { ...params, customBody: [...params.customBody, provider.useResponseApi
    ? { key: 'reasoning', value: { effort: 'none' } }
    : { key: 'reasoning_effort', value: 'none' }] };
};
