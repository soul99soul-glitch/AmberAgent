// Google Gemini Chat API 组装层(请求构建 → HttpClient Port → SSE 装配 → MessageChunk 回调)
//
// Android 基准: GoogleProvider.kt
//   buildUrl(:111-119) / transformRequest(:121-145)
//   generateText(:194-281) / streamText(:283-463) / onFailure(:416-448)
//
// 裁剪(登记):
//   - Vertex AI service account(JWT 换 token)= P1;命中即抛错(非静默)
//   - Gemini Code Assist OAuth(v1internal 包装/解包 + Bearer)= P1;
//     纯逻辑层的 isCodeAssistOAuth/response 解包已保留
//   - configureReferHeaders/customHeaders 由 adapter 经 headers() 注入

import type { HttpClient, HttpRequest } from '@amber/deepread-domain';
import { SseAssembler } from '@amber/deepread-domain';
import type { SseEvent } from '@amber/deepread-domain';

import type { MessageChunk, UIMessage } from './message.ts';
import type { JsonObject } from './json.ts';
import type { TextGenerationParams } from './provider_model.ts';
import type { ProviderSettingGoogle } from './provider_settings.ts';
import { buildGoogleCompletionRequestBody } from './google_request.ts';
import type { GoogleImageEncoder } from './google_request.ts';
import {
  createGoogleStreamToolIdAllocator, parseGoogleResponseBody,
  parseGoogleStreamEventData,
} from './google_parse.ts';
import type { ChatStreamProvider, StreamOpts } from './chat_turn.ts';
import type { CallOpts, OpenAIChatApi } from './openai_chat_api.ts';
import { createDefaultKeyRoulette } from '../search/search_service.ts';
import type { KeyRoulette } from '../search/search_service.ts';

// KeyRoulette.default()(KeyRoulette.kt:12;deps 未注入时兜底)
const DEFAULT_ROULETTE: KeyRoulette = createDefaultKeyRoulette();

export interface GoogleChatApiDeps {
  http: HttpClient;
  setting: ProviderSettingGoogle;
  // adapter 注入的额外 header(custom/referer);api key 由本层加
  headers?: () => Record<string, string>;
  encodeImage?: GoogleImageEncoder;
  // D-078:多 key 轮换(GoogleProvider.kt:77/:134 — 每请求 next(apiKey, id));
  //   未提供 → DefaultKeyRoulette(random)
  keyRoulette?: KeyRoulette;
}

// buildUrl(:111-119)
const buildGoogleUrl = (setting: ProviderSettingGoogle, path: string): string => {
  if (!setting.vertexAI) {
    return `${setting.baseUrl}/${path}`;
  }
  if (setting.useServiceAccount) {
    // service account JWT 换 token = P1(登记);抛错而非静默降级
    throw new Error('vertex service account 未落地(P1)');
  }
  return `https://aiplatform.googleapis.com/v1/${path}`;
};

// transformRequest(:121-145) 的 URL/头形态(api key 路径):
//   vertexAI → key 作为 query 参数;否则 x-goog-api-key 头
// D-078:key 由调用方按请求轮换解析(GoogleProvider.kt:134)
const applyGoogleAuth = (
  setting: ProviderSettingGoogle, url: string, headers: Record<string, string>,
  key: string,
): string => {
  if (setting.vertexAI) {
    const sep: string = url.indexOf('?') >= 0 ? '&' : '?';
    return `${url}${sep}key=${encodeURIComponent(key)}`;
  }
  headers['x-goog-api-key'] = key;
  return url;
};

const buildRequest = (
  deps: GoogleChatApiDeps,
  messages: UIMessage[],
  params: TextGenerationParams,
  stream: boolean,
): HttpRequest => {
  if (deps.setting.authMode === 'gemini_code_assist_oauth') {
    // v1internal 包装 + Bearer + onboarding = P1(登记);抛错而非静默发错端点
    throw new Error('Gemini Code Assist OAuth 未落地(P1)');
  }
  const body = buildGoogleCompletionRequestBody({
    messages, params,
    isCodeAssistOAuth: false,
    encodeImage: deps.encodeImage,
  });
  const modelPath: string = deps.setting.vertexAI
    ? `publishers/google/models/${params.model.modelId}:${stream ? 'streamGenerateContent' : 'generateContent'}`
    : `models/${params.model.modelId}:${stream ? 'streamGenerateContent' : 'generateContent'}`;
  let url: string = buildGoogleUrl(deps.setting, modelPath);
  if (stream) {
    url += `${url.indexOf('?') >= 0 ? '&' : '?'}alt=sse`;
  }
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  const extra = deps.headers !== undefined ? deps.headers() : {};
  for (const k of Object.keys(extra)) headers[k] = extra[k];
  // GoogleProvider.kt:134 — 每请求 keyRoulette.next(apiKey, id)
  const roulette: KeyRoulette = deps.keyRoulette ?? DEFAULT_ROULETTE;
  url = applyGoogleAuth(deps.setting, url, headers,
    roulette.next(deps.setting.apiKey, deps.setting.id));
  return { url, method: 'POST', headers, body: JSON.stringify(body) };
};

// onFailure(:416-448):body → error.message 提取;空 body → Unknown error: status
const errorFromFailureBody = (status: number, bodyRaw: string): Error => {
  if (bodyRaw.trim().length > 0) {
    try {
      const parsed = JSON.parse(bodyRaw) as JsonObject;
      const errRaw = parsed['error'];
      if (typeof errRaw === 'object' && errRaw !== null && !Array.isArray(errRaw)) {
        const msg = (errRaw as JsonObject)['message'];
        return new Error(typeof msg === 'string' ? msg : 'unknown');
      }
      return new Error('unknown');
    } catch {
      // body 解析失败 → 落到根因错误
    }
  }
  return new Error(`Failed to get response: ${status} ${bodyRaw}`);
};

export const createGoogleChatApi = (deps: GoogleChatApiDeps): OpenAIChatApi => {
  const streamText = async (
    messages: UIMessage[],
    params: TextGenerationParams,
    onChunk: (chunk: MessageChunk) => void,
    opts?: CallOpts,
  ): Promise<void> => {
    const req = buildRequest(deps, messages, params, true);
    const assembler = new SseAssembler();
    // 流 scope tool id 稳定分配(Android :334)
    const allocator = createGoogleStreamToolIdAllocator();
    const state: { error: Error | null } = { error: null };

    const resp = await deps.http.fetchStream(req, {
      signal: opts?.signal,
      onChunk: (bytes: ArrayBuffer, end: boolean): void => {
        if (state.error !== null) return; // 终止后不再消费
        try {
          const events: SseEvent[] = assembler.feed(bytes);
          if (end) events.push(...assembler.flush());
          for (const evt of events) {
            const chunk: MessageChunk | null =
              parseGoogleStreamEventData(evt.data, params.model.modelId, allocator);
            if (chunk !== null) onChunk(chunk);
          }
        } catch (e) {
          // malformed event 不能只打日志吞掉(Android :407-412):终止消费并抛出
          state.error = e instanceof Error ? e : new Error(String(e));
        }
      },
    });

    if (state.error !== null) throw state.error;
    if (resp.status < 200 || resp.status >= 300) {
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
    return parseGoogleResponseBody(JSON.parse(resp.body) as JsonObject, params.model.modelId);
  };

  return { streamText, generateText };
};

// 适配 chat_turn 的 ChatStreamProvider(D-040 双路径,openai/claude 同构)
export const asGoogleChatStreamProvider = (
  api: OpenAIChatApi,
  paramsFor: () => TextGenerationParams,
): ChatStreamProvider => ({
  streamText: (
    messages: UIMessage[], onChunk: (chunk: MessageChunk) => void, opts?: StreamOpts,
  ): Promise<void> =>
    api.streamText(messages, paramsFor(), onChunk,
      opts?.signal !== undefined ? { signal: opts.signal } : undefined),
  generateText: (messages: UIMessage[], opts?: StreamOpts): Promise<MessageChunk> =>
    api.generateText(messages, paramsFor(),
      opts?.signal !== undefined ? { signal: opts.signal } : undefined),
});
