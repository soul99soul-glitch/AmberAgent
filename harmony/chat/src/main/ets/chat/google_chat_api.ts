// Google Gemini Chat API 组装层(请求构建 → HttpClient Port → SSE 装配 → MessageChunk 回调)
//
// Android 基准: GoogleProvider.kt
//   buildUrl(:111-119) / transformRequest(:121-145)
//   generateText(:194-281) / streamText(:283-463) / onFailure(:416-448)
//
// Token resolution is injected by Entry; this layer keeps the existing body/SSE engine.

import type { AbortSignalLike, HttpClient, HttpRequest } from '@amber/deepread-domain';
import { SseAssembler } from '@amber/deepread-domain';
import type { SseEvent } from '@amber/deepread-domain';

import type { MessageChunk, UIMessage, UIMessageChoice, UIMessagePart } from './message.ts';
import { makeAssistantMessage } from './message.ts';
import { MessageStreamAccumulator } from './stream_accumulator.ts';
import type { JsonObject } from './json.ts';
import type { TextGenerationParams } from './provider_model.ts';
import type { ProviderSettingGoogle } from './provider_settings.ts';
import { buildGoogleCompletionRequestBody } from './google_request.ts';
import type { GoogleImageEncoder } from './google_request.ts';
import {
  createGoogleStreamToolIdAllocator, parseGoogleResponseBody,
  parseGoogleStreamEventData, unwrapGoogleResponse, googleFailedFinishReason,
} from './google_parse.ts';
import { parseOpenAiErrorDetail } from './openai_parse.ts';
import type { JsonValue } from './json.ts';
import type { ChatStreamProvider, StreamOpts } from './chat_turn.ts';
import type { CallOpts, OpenAIChatApi } from './openai_chat_api.ts';
import { createDefaultKeyRoulette } from '../search/search_service.ts';
import type { KeyRoulette } from '../search/search_service.ts';
import { newId } from './ids.ts';
import { GoogleAuthError, googleCloudCodeHeaders, googleCloudCodeMethodUrl } from './google_auth.ts';
import type { GoogleAuthResolver, GoogleRequestAuth } from './google_auth.ts';

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
  resolveAuth?: GoogleAuthResolver;
}

// buildUrl(:111-119)
const buildGoogleUrl = (setting: ProviderSettingGoogle, path: string): string => {
  if (!setting.vertexAI) {
    return `${setting.baseUrl}/${path}`;
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

const throwIfCancelled = (signal: AbortSignalLike | undefined): void => {
  if (signal?.aborted === true) {
    const error: Error = new Error('Google request aborted');
    error.name = 'AbortError';
    throw error;
  }
};

const buildRequest = async (
  deps: GoogleChatApiDeps,
  messages: UIMessage[],
  params: TextGenerationParams,
  stream: boolean,
  signal?: AbortSignalLike,
): Promise<HttpRequest> => {
  throwIfCancelled(signal);
  const setting: ProviderSettingGoogle = deps.setting;
  const oauth: boolean = setting.authMode !== 'api_key';
  const bearer: boolean = oauth || setting.useServiceAccount;
  let auth: GoogleRequestAuth | null = null;
  if (bearer) {
    if (!oauth && !setting.vertexAI) throw new GoogleAuthError('auth_configuration', '服务账号需要 Vertex AI 模式。');
    if (deps.resolveAuth === undefined) throw new GoogleAuthError('auth_unavailable', 'Google 认证 resolver 未配置。');
    auth = await deps.resolveAuth(setting, signal);
    throwIfCancelled(signal);
    const expected: GoogleRequestAuth['kind'] = setting.authMode === 'antigravity_oauth' ? 'antigravity'
      : setting.authMode === 'gemini_code_assist_oauth' ? 'code_assist' : 'service_account';
    if (auth.kind !== expected) throw new GoogleAuthError('auth_identity', 'Google 认证身份与当前模式不一致。');
    if (auth.accessToken.trim().length === 0 || auth.projectId.trim().length === 0
      || (auth.kind === 'service_account' && auth.location.trim().length === 0)) {
      throw new GoogleAuthError('auth_incomplete', 'Google 认证缺少有效 token、项目或区域。');
    }
  }
  const body = buildGoogleCompletionRequestBody({
    messages, params,
    isCodeAssistOAuth: oauth,
    encodeImage: deps.encodeImage,
  });
  const method: string = stream ? 'streamGenerateContent' : 'generateContent';
  const modelPath: string = setting.vertexAI
    ? `publishers/google/models/${params.model.modelId}:${stream ? 'streamGenerateContent' : 'generateContent'}`
    : `models/${params.model.modelId}:${stream ? 'streamGenerateContent' : 'generateContent'}`;
  let url: string;
  let requestBody: JsonObject = body;
  if (auth !== null && auth.kind === 'service_account') {
    url = 'https://aiplatform.googleapis.com/v1/projects/' + encodeURIComponent(auth.projectId)
      + '/locations/' + encodeURIComponent(auth.location) + '/publishers/google/models/'
      + encodeURIComponent(params.model.modelId) + ':' + method;
  } else if (auth !== null && setting.authMode !== 'api_key') {
    url = googleCloudCodeMethodUrl(setting.authMode, method);
    requestBody = auth.kind === 'antigravity'
      ? { model: params.model.modelId, project: auth.projectId, userAgent: 'antigravity',
        requestType: 'agent', requestId: `agent-${newId()}`, request: body }
      : { model: params.model.modelId, project: auth.projectId, user_prompt_id: newId(), request: body };
  } else {
    url = buildGoogleUrl(setting, modelPath);
  }
  if (stream) {
    url += `${url.indexOf('?') >= 0 ? '&' : '?'}alt=sse`;
  }
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  const extra = deps.headers !== undefined ? deps.headers() : {};
  for (const k of Object.keys(extra)) headers[k] = extra[k];
  if (auth !== null) {
    for (const key of Object.keys(headers)) {
      if (key.toLowerCase() === 'authorization' || key.toLowerCase() === 'x-goog-api-key') delete headers[key];
    }
    if (setting.authMode !== 'api_key') {
      const authHeaders: Record<string, string> = googleCloudCodeHeaders(setting.authMode, auth.accessToken);
      for (const key of Object.keys(authHeaders)) headers[key] = authHeaders[key];
    } else {
      headers['Authorization'] = `Bearer ${auth.accessToken}`;
    }
    return { url, method: 'POST', headers, body: JSON.stringify(requestBody) };
  }
  // GoogleProvider.kt:134 — 每请求 keyRoulette.next(apiKey, id)
  const roulette: KeyRoulette = deps.keyRoulette ?? DEFAULT_ROULETTE;
  url = applyGoogleAuth(deps.setting, url, headers,
    roulette.next(deps.setting.apiKey, deps.setting.id));
  return { url, method: 'POST', headers, body: JSON.stringify(body) };
};

// Google 无标准 [DONE];流内错误事件 {"error":{...}} 会被 parseGoogleStreamEventData
//   当「无 candidates」静默跳过 → 在此显式收口,错误才可见、底层流才能立即关闭(R18)
const googleEventError = (data: string): Error | null => {
  if (data.indexOf('"error"') < 0) return null;
  try {
    const parsed: unknown = JSON.parse(data);
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return null;
    const root: JsonObject = parsed as JsonObject;
    const errValue: unknown = root['error'] ?? unwrapGoogleResponse(root)['error'];
    if (errValue === undefined || errValue === null) return null;
    return parseOpenAiErrorDetail(errValue as JsonValue);
  } catch {
    return null; // 非 JSON 正文交给 parseGoogleStreamEventData 的原语义
  }
};

// onFailure(:416-448):body → error.message 提取;空 body → Unknown error: status
const errorFromFailureBody = (status: number, bodyRaw: string): Error => {
  if (bodyRaw.trim().length > 0) {
    try {
      const parsed = JSON.parse(bodyRaw) as JsonObject;
      const errRaw = parsed['error'] ?? unwrapGoogleResponse(parsed)['error'];
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
    const req = await buildRequest(deps, messages, params, true, opts?.signal);
    const assembler = new SseAssembler();
    // 流 scope tool id 稳定分配(Android :334)
    const allocator = createGoogleStreamToolIdAllocator();
    const state: { error: Error | null; sawEvent: boolean } = { error: null, sawEvent: false };
    // Do not expose executable calls before the whole response succeeds. The
    // tool loop can speculatively execute a call as soon as it enters a snapshot.
    const pendingTools: Map<number, MessageStreamAccumulator> = new Map();

    const resp = await deps.http.fetchStream(req, {
      signal: opts?.signal,
      onDataEnd: opts?.onDataEnd,
      // R18:Google 无标准 [DONE],但 malformed/错误事件收口仍需立即关流
      shouldStop: (): boolean => state.error !== null,
      onChunk: (bytes: ArrayBuffer, end: boolean): void => {
        if (state.error !== null) return; // 终止后不再消费
        try {
          const events: SseEvent[] = assembler.feed(bytes);
          if (end) events.push(...assembler.flush());
          if (events.length > 0) state.sawEvent = true;
          for (const evt of events) {
            const evtError: Error | null = googleEventError(evt.data);
            if (evtError !== null) throw evtError;
            const chunk: MessageChunk | null =
              parseGoogleStreamEventData(evt.data, params.model.modelId, allocator);
            if (chunk === null) continue;
            const failure: string | null = googleFailedFinishReason(chunk);
            const visibleChoices: UIMessageChoice[] = chunk.choices.map((choice: UIMessageChoice): UIMessageChoice => {
              const delta: UIMessage | null = choice.delta;
              if (delta === null) return choice;
              const tools: UIMessagePart[] = delta.parts.filter((part: UIMessagePart): boolean => part.type === 'tool');
              if (tools.length > 0 && failure === null) {
                let accumulator: MessageStreamAccumulator | undefined = pendingTools.get(choice.index);
                if (accumulator === undefined) {
                  accumulator = new MessageStreamAccumulator([makeAssistantMessage('')]);
                  pendingTools.set(choice.index, accumulator);
                }
                accumulator.append({ ...chunk, usage: null, choices: [{ ...choice,
                  delta: { ...delta, parts: tools }, finishReason: null }] });
              }
              return { ...choice, delta: { ...delta,
                parts: delta.parts.filter((part: UIMessagePart): boolean => part.type !== 'tool') },
                finishReason: failure === null ? choice.finishReason : null };
            });
            onChunk({ ...chunk, choices: visibleChoices });
            if (failure !== null) throw new Error(`Google generation failed: ${failure}`);
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
    // R06:2xx 但零 SSE 事件(网关 200 回普通 JSON)→ 不是成功流
    if (!state.sawEvent) {
      throw errorFromFailureBody(resp.status, resp.body);
    }
    throwIfCancelled(opts?.signal);
    pendingTools.forEach((accumulator: MessageStreamAccumulator, index: number): void => {
      const delta: UIMessage | undefined = accumulator.snapshot()[0];
      if (delta !== undefined) onChunk({ id: newId(), model: params.model.modelId, usage: null,
        choices: [{ index, delta, message: null, finishReason: null }] });
    });
  };

  const generateText = async (
    messages: UIMessage[],
    params: TextGenerationParams,
    opts?: CallOpts,
  ): Promise<MessageChunk> => {
    const req = await buildRequest(deps, messages, params, false, opts?.signal);
    const resp = await deps.http.fetch(req, { signal: opts?.signal });
    if (resp.status < 200 || resp.status >= 300) {
      throw errorFromFailureBody(resp.status, resp.body);
    }
    const eventError: Error | null = googleEventError(resp.body);
    if (eventError !== null) throw eventError;
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
    api.streamText(messages, paramsFor(), onChunk, opts === undefined ? undefined : {
      signal: opts.signal,
      onDataEnd: opts.onDataEnd,
    }),
  generateText: (messages: UIMessage[], opts?: StreamOpts): Promise<MessageChunk> =>
    api.generateText(messages, paramsFor(),
      opts?.signal !== undefined ? { signal: opts.signal } : undefined),
});
