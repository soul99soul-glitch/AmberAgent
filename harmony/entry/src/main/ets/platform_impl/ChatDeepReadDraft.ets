import type { ChatProviderChoice } from '../di/AppContainer.ets';

export interface ChatDeepReadDraft {
  baseUrl: string;
  apiKey: string;
  model: string;
}

export interface ChatDeepReadDraftResult {
  draft: ChatDeepReadDraft | null;
  message: string;
}

// DeepRead keeps its existing Bearer-key Chat Completions client. This helper
// only checks whether its three editable fields preserve the current choice.
export const chatChoiceToDeepReadDraft = (choice: ChatProviderChoice | null): ChatDeepReadDraftResult => {
  if (choice === null) {
    return { draft: null, message: '尚未配置可用的聊天服务商与模型，请先前往聊天服务商配置。' };
  }
  const provider = choice.provider;
  if (provider.type !== 'openai') {
    return { draft: null, message: '深度阅读目前只支持 OpenAI 兼容 API Key 配置，无法直接填入当前 Google 或 Claude 配置。' };
  }
  if (provider.authMode !== 'api_key') {
    return { draft: null, message: '当前聊天使用账号授权，不能作为深度阅读的 API Key 填入。请手动填写兼容配置。' };
  }
  if (provider.useResponseApi) {
    return { draft: null, message: '当前聊天使用 Responses API；深度阅读使用 Chat Completions，无法直接复用。' };
  }
  // Same split/trim/distinct rule as Chat's search_service splitKey; no roulette call.
  const keys: string[] = [];
  for (const part of provider.apiKey.split(/[\s,]+/)) {
    const key: string = part.trim();
    if (key.length > 0 && keys.indexOf(key) < 0) keys.push(key);
  }
  if (keys.length > 1) {
    return { draft: null, message: '当前聊天使用多个 API Key 轮换，深度阅读只支持单个 Key，请手动填写其中一个。' };
  }
  const apiKey: string = keys.length === 1 ? keys[0] : '';
  const base: string = provider.baseUrl.endsWith('/')
    ? provider.baseUrl.slice(0, provider.baseUrl.length - 1) : provider.baseUrl;
  const deepReadEndpoint: string = /\/v\d+$/.test(base)
    ? `${base}/chat/completions` : `${base}/v1/chat/completions`;
  if (`${provider.baseUrl}${provider.chatCompletionsPath}` !== deepReadEndpoint) {
    return { draft: null, message: '当前聊天的请求路径无法由深度阅读的 Base URL 保留，请手动填写兼容配置。' };
  }
  for (const header of choice.model.customHeaders) {
    const name: string = header.name.toLowerCase();
    if ((name === 'content-type' && header.value === 'application/json')
      || (name === 'authorization' && header.value === `Bearer ${apiKey}`)) continue;
    return { draft: null, message: '当前模型包含深度阅读无法保留的自定义请求头，无法直接复用。' };
  }
  for (const body of choice.model.customBodies) {
    if ((body.key === 'model' && body.value === choice.model.modelId)
      || (body.key === 'stream' && body.value === false)) continue;
    return { draft: null, message: '当前模型包含深度阅读无法保留的自定义请求参数，无法直接复用。' };
  }
  if (apiKey.length === 0 || choice.model.modelId.trim().length === 0) {
    return { draft: null, message: '当前聊天配置缺少 API Key 或模型名称，请先完成聊天配置。' };
  }
  return {
    draft: { baseUrl: provider.baseUrl, apiKey: apiKey, model: choice.model.modelId },
    message: '已填入当前聊天配置的草稿，请检查后点击“保存全部”。',
  };
};
