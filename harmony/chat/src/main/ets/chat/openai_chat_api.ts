// OpenAI Chat API 组装层(请求构建 → HttpClient Port → SSE 装配 → MessageChunk 回调)
//
// Android 基准: ChatCompletionsAPI.kt
//   streamText(:165-291) / generateText(:107-163) / onFailure(:258-278)
//
// 裁剪(D-014 延续):
//   - bearerResolver/customHeaders/configureReferHeaders 由 adapter 经 headers() 注入;
//     secret 不进纯逻辑层(宪章:日志/快照不落 token)。
//   - OkHttp EventSource.cancel 在本 Port 无对应:解析失败抛错即终止消费,
//     底层流由 adapter 的 signal 收尾(注释对齐 :205-206 的主动 cancel 语义)。

import type { HttpClient, HttpRequest, AbortSignalLike } from '@amber/deepread-domain';
import { SseAssembler } from '@amber/deepread-domain';
import type { SseEvent } from '@amber/deepread-domain';

import type { MessageChunk, UIMessage, UIMessageChoice } from './message.ts';
import type { JsonObject, JsonValue } from './json.ts';
import type { OpenAIAuthMode, ProviderSettingOpenAI, TextGenerationParams } from './provider_model.ts';
import { buildChatCompletionRequest, hostOf } from './openai_request.ts';
import type { ImageEncoder } from './openai_request.ts';
import { normalizeOpenAIStreamDataLines } from './openai_normalize.ts';
import {
  parseChatCompletionMessage, parseOpenAiTokenUsage, parseOpenAiErrorDetail,
  parseOpenAiStreamEventData,
} from './openai_parse.ts';
import type { ChatStreamProvider, StreamOpts } from './chat_turn.ts';
import { createOpenAIResponsesApi } from './openai_responses_api.ts';

export interface OpenAIChatApiDeps {
  http: HttpClient;
  setting: ProviderSettingOpenAI;
  // adapter 注入的额外 header(auth/custom/referer);secret 由 adapter 持有
  headers?: () => Record<string, string>;
  encodeImage?: ImageEncoder;
  /** P6-01:官方端点 + 用户开关时注入;chat_completions 路径忽略 */
  resumeStore?: import('./openai_responses_request.ts').ResponseResumeStore;
  enableResponsesResume?: boolean;
  runId?: string;
  resumeFrom?: import('./openai_responses_request.ts').ResponseCursor | null;
  resumeMessages?: UIMessage[];
  onResumeCheckpoint?: (cursor: import('./openai_responses_request.ts').ResponseCursor) => Promise<void>;
}

export interface CallOpts {
  signal?: AbortSignalLike;
  onDataEnd?: () => void;
}

export interface OpenAIChatApi {
  streamText(
    messages: UIMessage[],
    params: TextGenerationParams,
    onChunk: (chunk: MessageChunk) => void,
    opts?: CallOpts,
  ): Promise<void>;
  generateText(messages: UIMessage[], params: TextGenerationParams, opts?: CallOpts): Promise<MessageChunk>;
}

const buildRequest = (
  deps: OpenAIChatApiDeps,
  messages: UIMessage[],
  params: TextGenerationParams,
  stream: boolean,
): HttpRequest => {
  // useResponseApi=true 时由 createOpenAIChatApi 分流到 Responses 实现,
  // 本函数仅服务 chat_completions 路径。
  const body = buildChatCompletionRequest({
    messages, params, setting: deps.setting, stream, encodeImage: deps.encodeImage,
  });
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  const extra = deps.headers !== undefined ? deps.headers() : {};
  for (const k of Object.keys(extra)) headers[k] = extra[k];
  return {
    url: `${deps.setting.baseUrl}${deps.setting.chatCompletionsPath}`,
    method: 'POST',
    headers,
    body: JSON.stringify(body),
  };
};

// onFailure(:258-278):body 非空 → 尝试 parseErrorDetail;解析失败保留根因
const errorFromFailureBody = (status: number, bodyRaw: string): Error => {
  if (bodyRaw.trim().length > 0) {
    try {
      const normalized = normalizeOpenAIStreamDataLines(bodyRaw);
      const candidate = normalized.length > 0 ? normalized[0] : bodyRaw;
      const parsed: unknown = JSON.parse(candidate);
      const detail: Error = parseOpenAiErrorDetail(parsed as JsonValue);
      return new Error(`Failed to get response: ${status} ${detail.message}`);
    } catch {
      // body 解析失败 → 落到根因错误(不要用 parse 异常覆盖)
    }
  }
  return new Error(`Failed to get response: ${status} ${bodyRaw}`);
};

export const createOpenAIChatApi = (deps: OpenAIChatApiDeps): OpenAIChatApi => {
  // useResponseApi=true → Responses 实现;chat_completions 路径不变
  if (deps.setting.useResponseApi === true) {
    const responses = createOpenAIResponsesApi({
      http: deps.http,
      setting: deps.setting,
      headers: deps.headers,
      encodeImage: deps.encodeImage,
      resumeStore: deps.resumeStore,
      enableResponsesResume: deps.enableResponsesResume,
      runId: deps.runId,
      resumeFrom: deps.resumeFrom,
      resumeMessages: deps.resumeMessages,
      onResumeCheckpoint: deps.onResumeCheckpoint,
    });
    return {
      streamText: (messages, params, onChunk, opts) =>
        responses.streamText(messages, params, onChunk, opts),
      generateText: (messages, params, opts) =>
        responses.generateText(messages, params, opts),
    };
  }

  const streamText = async (
    messages: UIMessage[],
    params: TextGenerationParams,
    onChunk: (chunk: MessageChunk) => void,
    opts?: CallOpts,
  ): Promise<void> => {
    const req = buildRequest(deps, messages, params, true);
    const assembler = new SseAssembler();
    // holder:闭包赋值不破坏 CFA(见 D-010 模式)
    const state: { error: Error | null; done: boolean; sawEvent: boolean } =
      { error: null, done: false, sawEvent: false };

    const resp = await deps.http.fetchStream(req, {
      signal: opts?.signal,
      onDataEnd: opts?.onDataEnd,
      // R18:done/error 为协议终态 → 通知 Port 立即关闭底层并 settle
      shouldStop: (): boolean => state.done || state.error !== null,
      onChunk: (bytes: ArrayBuffer, end: boolean): void => {
        if (state.error !== null || state.done) return; // 终止后不再消费
        try {
          const events: SseEvent[] = assembler.feed(bytes);
          if (end) events.push(...assembler.flush());
          if (events.length > 0) state.sawEvent = true;
          for (const evt of events) {
            const result = parseOpenAiStreamEventData(evt.data);
            if (result.done) {
              state.done = true;
              return;
            }
            for (const chunk of result.chunks) onChunk(chunk);
          }
        } catch (e) {
          // parse 异常/error payload → 记录,终止消费,统一在 await 后抛出
          state.error = e instanceof Error ? e : new Error(String(e));
        }
      },
    });

    if (state.error !== null) throw state.error;
    // 协议 [DONE] 已达成:即使 Port 因提前收口回传 status=0 也算成功
    if (state.done) return;
    if (resp.status < 200 || resp.status >= 300) {
      throw errorFromFailureBody(resp.status, resp.body);
    }
    // R06:2xx 但没有任何 SSE 事件(网关按普通 JSON 回错误/空体)→ 不是成功流
    if (!state.sawEvent) {
      throw errorFromFailureBody(resp.status, resp.body);
    }
  };

  const generateText = async (
    messages: UIMessage[],
    params: TextGenerationParams,
    opts?: CallOpts,
  ): Promise<MessageChunk> => {
    const req = buildRequest(deps, messages, params, false);
    const resp = await deps.http.fetch(req, { signal: opts?.signal });
    if (resp.status < 200 || resp.status >= 300) {
      throw new Error(`Failed to get response: ${resp.status} ${resp.body}`);
    }
    const bodyJson = JSON.parse(resp.body) as JsonObject;
    const id = typeof bodyJson['id'] === 'string' ? bodyJson['id'] : '';
    const model = typeof bodyJson['model'] === 'string' ? bodyJson['model'] : '';
    const choices = Array.isArray(bodyJson['choices']) ? bodyJson['choices'] : [];
    if (choices.length === 0) throw new Error('choices is null');
    const choice = choices[0] as JsonObject;
    const message = choice['message'];
    if (message === null || typeof message !== 'object' || Array.isArray(message)) {
      throw new Error('message is null');
    }
    const finishReason = typeof choice['finish_reason'] === 'string'
      ? choice['finish_reason'] : 'unknown';
    const usageRaw = bodyJson['usage'];
    const usage = parseOpenAiTokenUsage(
      usageRaw !== null && typeof usageRaw === 'object' && !Array.isArray(usageRaw)
        ? usageRaw : null);

    const uiChoice: UIMessageChoice = {
      index: 0,
      delta: null,
      message: parseChatCompletionMessage(message),
      finishReason,
    };
    return { id, model, choices: [uiChoice], usage };
  };

  return { streamText, generateText };
};

// 适配 chat_turn 的 ChatStreamProvider(params 由调用方按会话供给)
// D-040:generateText 透传 → assistant.streamOutput=false 走非流式单次补全
export const asChatStreamProvider = (
  api: OpenAIChatApi,
  paramsFor: () => TextGenerationParams,
): ChatStreamProvider => ({
  streamText: (
    messages: UIMessage[], onChunk: (chunk: MessageChunk) => void, opts?: StreamOpts,
  ): Promise<void> =>
    api.streamText(messages, paramsFor(), onChunk, opts === undefined ? undefined : {
      signal: opts.signal,
      onDataEnd: opts.onDataEnd,
    }),
  generateText: (messages: UIMessage[], opts?: StreamOpts): Promise<MessageChunk> =>
    api.generateText(messages, paramsFor(),
      opts?.signal !== undefined ? { signal: opts.signal } : undefined),
});

// addOpenAICompatibleAuthHeader(ChatCompletionsAPI.kt:545-557)
// mimo_coding_plan 或 token-plan-*.xiaomimimo.com → api-key 头;其余 Bearer
export const openAIAuthHeaders = (
  authMode: OpenAIAuthMode,
  baseUrl: string,
  token: string,
): Record<string, string> => {
  const host = hostOf(baseUrl);
  if (authMode === 'mimo_coding_plan' ||
    (host.startsWith('token-plan-') && host.endsWith('xiaomimimo.com'))) {
    return { 'api-key': token };
  }
  return { Authorization: `Bearer ${token}` };
};
