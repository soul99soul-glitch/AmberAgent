// Claude Chat API 组装层(请求构建 → HttpClient Port → SSE 装配 → MessageChunk 回调)
//
// Android 基准: ClaudeProvider.kt
//   generateText(:107-152) / streamText(:154-287) / onFailure(:256-273)
//
// 裁剪(D-014 同构延续):
//   - configureReferHeaders 由 adapter 经 headers() 注入;secret 不进纯逻辑层
//   - OkHttp EventSource.cancel 无对应:解析失败抛错即终止消费(同 openai_chat_api)

import type { HttpClient, HttpRequest } from '@amber/deepread-domain';
import { SseAssembler } from '@amber/deepread-domain';
import type { SseEvent } from '@amber/deepread-domain';

import type { MessageChunk, UIMessage } from './message.ts';
import type { JsonObject } from './json.ts';
import type { TextGenerationParams } from './provider_model.ts';
import type { ProviderSettingClaude } from './provider_settings.ts';
import { buildClaudeMessageRequest } from './claude_request.ts';
import type { ClaudeImageEncoder } from './claude_request.ts';
import { parseClaudeResponseBody, parseClaudeStreamEvent } from './claude_parse.ts';
import { parseOpenAiErrorDetail } from './openai_parse.ts';
import type { ChatStreamProvider, StreamOpts } from './chat_turn.ts';
import type { CallOpts, OpenAIChatApi } from './openai_chat_api.ts';
import { createDefaultKeyRoulette } from '../search/search_service.ts';
import type { KeyRoulette } from '../search/search_service.ts';

// ClaudeProvider.kt:65
export const ANTHROPIC_VERSION: string = '2023-06-01';

// KeyRoulette.default()(KeyRoulette.kt:12;deps 未注入时兜底)
const DEFAULT_ROULETTE: KeyRoulette = createDefaultKeyRoulette();

export interface ClaudeChatApiDeps {
  http: HttpClient;
  setting: ProviderSettingClaude;
  // adapter 注入的额外 header(custom/referer);x-api-key/anthropic-version 由本层加
  headers?: () => Record<string, string>;
  encodeImage?: ClaudeImageEncoder;
  // D-078:多 key 轮换(ClaudeProvider.kt:68/:74 — 每请求 next(apiKey, id));
  //   未提供 → DefaultKeyRoulette(random)
  keyRoulette?: KeyRoulette;
}

const buildRequest = (
  deps: ClaudeChatApiDeps,
  messages: UIMessage[],
  params: TextGenerationParams,
  stream: boolean,
): HttpRequest => {
  const body = buildClaudeMessageRequest({
    messages, params, setting: deps.setting, stream, encodeImage: deps.encodeImage,
  });
  const roulette: KeyRoulette = deps.keyRoulette ?? DEFAULT_ROULETTE;
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    // ClaudeProvider.kt:74 — 每请求 keyRoulette.next(apiKey, id)
    'x-api-key': roulette.next(deps.setting.apiKey, deps.setting.id),
    'anthropic-version': ANTHROPIC_VERSION,
  };
  const extra = deps.headers !== undefined ? deps.headers() : {};
  for (const k of Object.keys(extra)) headers[k] = extra[k];
  return {
    url: `${deps.setting.baseUrl}/messages`,
    method: 'POST',
    headers,
    body: JSON.stringify(body),
  };
};

// onFailure(:256-273):body 非空 → parseErrorDetail;解析失败保留根因
const errorFromFailureBody = (status: number, bodyRaw: string): Error => {
  if (bodyRaw.trim().length > 0) {
    try {
      return parseOpenAiErrorDetail(JSON.parse(bodyRaw) as JsonObject);
    } catch {
      // body 解析失败 → 落到根因错误(不要用 parse 异常覆盖)
    }
  }
  return new Error(`Failed to get response: ${status} ${bodyRaw}`);
};

export const createClaudeChatApi = (deps: ClaudeChatApiDeps): OpenAIChatApi => {
  const streamText = async (
    messages: UIMessage[],
    params: TextGenerationParams,
    onChunk: (chunk: MessageChunk) => void,
    opts?: CallOpts,
  ): Promise<void> => {
    const req = buildRequest(deps, messages, params, true);
    const assembler = new SseAssembler();
    const state: { error: Error | null; done: boolean; sawEvent: boolean } =
      { error: null, done: false, sawEvent: false };

    const resp = await deps.http.fetchStream(req, {
      signal: opts?.signal,
      onDataEnd: opts?.onDataEnd,
      // R18:message_stop/error 为协议终态 → 立即关闭底层并 settle
      shouldStop: (): boolean => state.done || state.error !== null,
      onChunk: (bytes: ArrayBuffer, end: boolean): void => {
        if (state.error !== null || state.done) return; // 终止后不再消费
        try {
          const events: SseEvent[] = assembler.feed(bytes);
          if (end) events.push(...assembler.flush());
          if (events.length > 0) state.sawEvent = true;
          for (const evt of events) {
            // Claude 依赖 event: 字段分发(SseAssembler 已支持)
            const result = parseClaudeStreamEvent(evt.event, evt.id, evt.data);
            if (result.done) {
              state.done = true;
              return;
            }
            if (result.chunk !== null) onChunk(result.chunk);
          }
        } catch (e) {
          state.error = e instanceof Error ? e : new Error(String(e));
        }
      },
    });

    if (state.error !== null) throw state.error;
    if (state.done) return;
    if (resp.status < 200 || resp.status >= 300) {
      throw errorFromFailureBody(resp.status, resp.body);
    }
    // R06:2xx 但零 SSE 事件(网关 200 回普通 JSON)→ 不是成功流
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
    return parseClaudeResponseBody(JSON.parse(resp.body) as JsonObject);
  };

  return { streamText, generateText };
};

// 适配 chat_turn 的 ChatStreamProvider(D-040 双路径,openai 同构)
export const asClaudeChatStreamProvider = (
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
