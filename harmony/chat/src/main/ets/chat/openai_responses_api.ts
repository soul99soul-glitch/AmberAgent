// OpenAI Responses API 组装层 — HttpClient Port 上的 streamText/generateText
//
// Android 基准: ResponseAPI.kt streamText/generateText + stored resume 门控
// 裁剪: Codex OAuth 流、stored 重连 GET /responses/{id} 完整实现放后续;
//      本切片: store 门控 + resume cursor Port + 无 resume 的标准流式/非流式。

import type { HttpClient, HttpRequest, AbortSignalLike } from '@amber/deepread-domain';
import { SseAssembler } from '@amber/deepread-domain';
import type { SseEvent } from '@amber/deepread-domain';
import type { MessageChunk, UIMessage } from './message.ts';
import type { ProviderSettingOpenAI, TextGenerationParams } from './provider_model.ts';
import type { ImageEncoder } from './openai_request.ts';
import { hostOf } from './openai_request.ts';
import { normalizeOpenAIStreamDataLines } from './openai_normalize.ts';
import { parseOpenAiErrorDetail } from './openai_parse.ts';
import type { JsonValue } from './json.ts';
import {
  buildResponsesRequestBody,
  supportsResponsesResume,
  type ResponseCursor,
  type ResponseResumeStore,
  type ResponsesResumeRequest,
} from './openai_responses_request.ts';
import {
  parseResponsesStreamEvent,
  parseResponsesOutput,
  ResponseStreamReconciler,
} from './openai_responses_parse.ts';
import type { ChatStreamProvider, StreamOpts } from './chat_turn.ts';

export interface OpenAIResponsesApiDeps {
  http: HttpClient;
  setting: ProviderSettingOpenAI;
  headers?: () => Record<string, string>;
  encodeImage?: ImageEncoder;
  resumeStore?: ResponseResumeStore;
  /** P6-01 用户开关；关闭时永不发送 store=true */
  enableResponsesResume?: boolean;
  runId?: string;
}

export interface OpenAIResponsesApi {
  streamText(
    messages: UIMessage[],
    params: TextGenerationParams,
    onChunk: (chunk: MessageChunk) => void,
    opts?: { signal?: AbortSignalLike; onDataEnd?: () => void },
  ): Promise<void>;
  generateText(
    messages: UIMessage[],
    params: TextGenerationParams,
    opts?: { signal?: AbortSignalLike },
  ): Promise<MessageChunk>;
}

const resolveResume = (
  deps: OpenAIResponsesApiDeps,
  resumeFrom: ResponseCursor | null,
): ResponsesResumeRequest | null => {
  if ((deps.enableResponsesResume ?? false) !== true) return null;
  if (!supportsResponsesResume(deps.setting)) return null;
  if (deps.resumeStore === undefined || deps.runId === undefined) return null;
  return { runId: deps.runId, resumeFrom };
};

const buildHttpRequest = (
  deps: OpenAIResponsesApiDeps,
  messages: UIMessage[],
  params: TextGenerationParams,
  stream: boolean,
  resume: ResponsesResumeRequest | null,
): HttpRequest => {
  const body = buildResponsesRequestBody({
    messages,
    params,
    setting: deps.setting,
    stream,
    resume,
    encodeImage: deps.encodeImage,
  });
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: stream ? 'text/event-stream' : 'application/json',
  };
  const extra = deps.headers !== undefined ? deps.headers() : {};
  for (const k of Object.keys(extra)) headers[k] = extra[k];
  return {
    url: `${deps.setting.baseUrl.replace(/\/$/, '')}/responses`,
    method: 'POST',
    headers,
    body: JSON.stringify(body),
  };
};

const errorFromFailure = (status: number, bodyRaw: string): Error => {
  if (bodyRaw.trim().length > 0) {
    try {
      const normalized = normalizeOpenAIStreamDataLines(bodyRaw);
      const candidate = normalized.length > 0 ? normalized[0] : bodyRaw;
      const parsed: unknown = JSON.parse(candidate);
      if (typeof parsed === 'object' && parsed !== null) {
        return parseOpenAiErrorDetail(parsed as JsonValue);
      }
    } catch {
      // fall through
    }
  }
  return new Error(`Failed to get response: ${status} ${bodyRaw}`);
};

export const createOpenAIResponsesApi = (deps: OpenAIResponsesApiDeps): OpenAIResponsesApi => {
  const streamText = async (
    messages: UIMessage[],
    params: TextGenerationParams,
    onChunk: (chunk: MessageChunk) => void,
    opts?: { signal?: AbortSignalLike; onDataEnd?: () => void },
  ): Promise<void> => {
    let resumeFrom: ResponseCursor | null = null;
    if ((deps.enableResponsesResume ?? false) && deps.resumeStore !== undefined && deps.runId !== undefined
      && supportsResponsesResume(deps.setting)) {
      resumeFrom = await deps.resumeStore.load(deps.runId);
    }
    const resume = resolveResume(deps, resumeFrom);
    const request = buildHttpRequest(deps, messages, params, true, resume);
    const reconciler = new ResponseStreamReconciler();
    const assembler = new SseAssembler();
    const state: { error: Error | null; done: boolean } = { error: null, done: false };
    let sequence = 0;

    await deps.http.fetchStream(request, {
      signal: opts?.signal,
      onDataEnd: opts?.onDataEnd,
      onChunk: (chunk: ArrayBuffer, end: boolean): void => {
        if (state.error !== null || state.done) return;
        try {
          const events: SseEvent[] = assembler.feed(chunk);
          if (end) events.push(...assembler.flush());
          for (const event of events) {
            const payloads = normalizeOpenAIStreamDataLines(event.data);
            if (payloads.length === 0 && event.data.indexOf('[DONE]') >= 0) {
              state.done = true;
              return;
            }
            for (const payload of payloads) {
              let json: unknown;
              try {
                json = JSON.parse(payload);
              } catch {
                continue;
              }
              if (typeof json !== 'object' || json === null) continue;
              const obj = json as Record<string, unknown>;
              if (obj['error'] !== undefined) {
                throw parseOpenAiErrorDetail(obj as JsonValue);
              }
              if (obj['type'] === 'response.created') continue;
              sequence += 1;
              const seqNum = typeof obj['sequence_number'] === 'number'
                ? obj['sequence_number'] as number : sequence;
              const parsed = parseResponsesStreamEvent(obj as never);
              if (parsed === null) {
                if (event.event === 'response.completed' || event.event === 'response.incomplete') {
                  state.done = true;
                  return;
                }
                continue;
              }
              const reconciled = reconciler.reconcile(parsed);
              if (resume !== null && deps.resumeStore !== undefined) {
                const respObj = obj['response'];
                const responseId = typeof respObj === 'object' && respObj !== null
                  ? String((respObj as Record<string, unknown>)['id'] ?? '')
                  : '';
                void deps.resumeStore.save(resume.runId, responseId, seqNum, deps.setting.id);
              }
              onChunk(reconciled);
              if (event.event === 'response.completed' || event.event === 'response.incomplete') {
                state.done = true;
                return;
              }
            }
          }
        } catch (e) {
          state.error = e instanceof Error ? e : new Error(String(e));
        }
      },
    });
    if (state.error !== null) throw state.error;
  };

  const generateText = async (
    messages: UIMessage[],
    params: TextGenerationParams,
    opts?: { signal?: AbortSignalLike },
  ): Promise<MessageChunk> => {
    const resume = resolveResume(deps, null);
    const request = buildHttpRequest(deps, messages, params, false, resume);
    const response = await deps.http.fetch(request, { signal: opts?.signal });
    if (response.status < 200 || response.status >= 300) {
      throw errorFromFailure(response.status, response.body);
    }
    const json: unknown = JSON.parse(response.body);
    if (typeof json !== 'object' || json === null) {
      throw new Error('Failed to get response: invalid JSON body');
    }
    return parseResponsesOutput(json as never);
  };

  return { streamText, generateText };
};

/** 绑定到 ChatTurn 的 provider 形状 */
export const asResponsesChatStreamProvider = (
  api: OpenAIResponsesApi,
  params: TextGenerationParams,
): ChatStreamProvider => ({
  streamText: async (
    messages: UIMessage[],
    onChunk: (chunk: MessageChunk) => void,
    opts?: StreamOpts,
  ): Promise<void> => {
    await api.streamText(messages, params, onChunk, opts);
  },
  generateText: async (messages: UIMessage[], opts?: StreamOpts): Promise<MessageChunk> =>
    api.generateText(messages, params, opts),
});

export const responsesEndpointUrl = (baseUrl: string): string =>
  `${baseUrl.replace(/\/$/, '')}/responses`;

export const isOfficialOpenAIHost = (baseUrl: string): boolean =>
  hostOf(baseUrl) === 'api.openai.com';
