// OpenAI Responses API 组装层 — HttpClient Port 上的 streamText/generateText
//
// Android 基准: ResponseAPI.kt streamText/generateText + stored resume 门控
// Official background streams resume the same response through GET with a durable cursor.

import type { HttpClient, HttpRequest, AbortSignalLike } from '@amber/deepread-domain';
import { SseAssembler } from '@amber/deepread-domain';
import type { SseEvent } from '@amber/deepread-domain';
import type { MessageChunk, UIMessage, UIMessageChoice, UIMessagePart } from './message.ts';
import { makeAssistantMessage } from './message.ts';
import { MessageStreamAccumulator } from './stream_accumulator.ts';
import { ProviderResponseError } from './provider_response_error.ts';
import type { ProviderSettingOpenAI, TextGenerationParams } from './provider_model.ts';
import type { ImageEncoder } from './openai_request.ts';
import { hostOf } from './openai_request.ts';
import { normalizeOpenAIStreamDataLines } from './openai_normalize.ts';
import { parseOpenAiErrorDetail } from './openai_parse.ts';
import type { JsonValue } from './json.ts';
import {
  buildResponsesRequestBody,
  supportsResponsesResume,
  responsesResumeUrl,
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
  resumeFrom?: ResponseCursor | null;
  resumeMessages?: UIMessage[];
  // Awaited after publishing this event's raw content, so the owner can atomically persist both.
  onResumeCheckpoint?: (cursor: ResponseCursor) => Promise<void>;
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
  if ((deps.resumeStore === undefined && deps.onResumeCheckpoint === undefined) || deps.runId === undefined) return null;
  return { runId: deps.runId, resumeFrom };
};

const buildHttpRequest = (
  deps: OpenAIResponsesApiDeps,
  messages: UIMessage[],
  params: TextGenerationParams,
  stream: boolean,
  resume: ResponsesResumeRequest | null,
): HttpRequest => {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: stream ? 'text/event-stream' : 'application/json',
  };
  const extra = deps.headers !== undefined ? deps.headers() : {};
  for (const k of Object.keys(extra)) headers[k] = extra[k];
  if (resume?.resumeFrom !== null && resume?.resumeFrom !== undefined) {
    return { url: responsesResumeUrl(deps.setting, resume.resumeFrom), method: 'GET', headers };
  }
  const body = buildResponsesRequestBody({
    messages,
    params,
    setting: deps.setting,
    stream,
    resume,
    encodeImage: deps.encodeImage,
  });
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
        const detail: Error = parseOpenAiErrorDetail(parsed as JsonValue);
        return new Error(`Failed to get response: ${status} ${detail.message}`);
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
    let resumeFrom: ResponseCursor | null = deps.resumeFrom ?? null;
    if (deps.resumeFrom === undefined && (deps.enableResponsesResume ?? false) && deps.resumeStore !== undefined && deps.runId !== undefined
      && supportsResponsesResume(deps.setting)) {
      resumeFrom = await deps.resumeStore.load(deps.runId);
    }
    const resume = resolveResume(deps, resumeFrom);
    if (resumeFrom !== null && resume === null) throw new Error('Responses resume is unavailable for the original provider');
    const request = buildHttpRequest(deps, messages, params, true, resume);
    const reconciler = new ResponseStreamReconciler();
    if (resumeFrom !== null) {
      const partial: UIMessage[] = deps.resumeMessages ?? [];
      const tail: UIMessage | undefined = partial[partial.length - 1];
      if (tail?.role === 'assistant') reconciler.reconcile({ id: `resume-prefix:${resumeFrom.responseId}`, model: tail.modelId ?? '',
        choices: [{ index: 0, delta: tail, message: null, finishReason: null }], usage: null });
    }
    const assembler = new SseAssembler();
    const state: { error: Error | null; done: boolean; sawEvent: boolean; terminalQueued: boolean } =
      { error: null, done: false, sawEvent: false, terminalQueued: false };
    // A raw snapshot can execute complete calls speculatively. Publish calls
    // only after the terminal and transport have both succeeded.
    const pendingTools = new Map<number, MessageStreamAccumulator>();
    const publishVisible = (chunk: MessageChunk): void => {
      const choices: UIMessageChoice[] = chunk.choices.map((choice: UIMessageChoice): UIMessageChoice => {
        const delta = choice.delta;
        if (delta === null) return choice;
        const tools: UIMessagePart[] = delta.parts.filter((part: UIMessagePart): boolean => part.type === 'tool');
        if (tools.length > 0) {
          let accumulator = pendingTools.get(choice.index);
          if (accumulator === undefined) {
            accumulator = new MessageStreamAccumulator([makeAssistantMessage('')]);
            pendingTools.set(choice.index, accumulator);
          }
          accumulator.append({ ...chunk, usage: null, choices: [{ ...choice,
            delta: { ...delta, parts: tools, annotations: [], usage: null }, finishReason: null }] });
        }
        return { ...choice, delta: { ...delta,
          parts: delta.parts.filter((part: UIMessagePart): boolean => part.type !== 'tool') } };
      });
      onChunk({ ...chunk, choices });
    };
    let responseId: string = resumeFrom?.responseId ?? '';
    let lastSequence: number = resumeFrom?.sequence ?? -1;
    let checkpointQueue: Promise<void> = Promise.resolve();
    let terminalCursor: ResponseCursor | null = null;
    const persistCursor = async (cursor: ResponseCursor): Promise<void> => {
      if (deps.onResumeCheckpoint !== undefined) await deps.onResumeCheckpoint(cursor);
      else if (deps.resumeStore !== undefined && resume !== null) {
        await deps.resumeStore.save(resume.runId, cursor.responseId, cursor.sequence, cursor.providerId);
      }
    };
    const processPayload = (obj: Record<string, unknown>, eventType: string): void => {
      if (obj['error'] !== undefined) throw parseOpenAiErrorDetail(obj as JsonValue);
      const terminalType = obj['type'] ?? eventType;
      const terminal: boolean = terminalType === 'response.completed' || terminalType === 'response.incomplete';
      const parsed = parseResponsesStreamEvent(obj as never);
      if (parsed !== null) publishVisible(reconciler.reconcile(parsed));
      if (terminal) state.done = true;
    };
    const queueResumablePayload = (obj: Record<string, unknown>, eventType: string): void => {
      const type: unknown = obj['type'] ?? eventType;
      if (type === 'response.completed' || type === 'response.incomplete' || type === 'response.failed') state.terminalQueued = true;
      checkpointQueue = checkpointQueue.then(async (): Promise<void> => {
        if (state.error !== null || state.done || opts?.signal?.aborted === true) return;
        try {
          const response = obj['response'];
          const eventId: unknown = obj['response_id'] ?? (typeof response === 'object' && response !== null
            ? (response as Record<string, unknown>)['id'] : undefined);
          if (eventId !== undefined) {
            if (typeof eventId !== 'string' || !/^resp_[A-Za-z0-9_-]+$/.test(eventId)
              || (responseId.length > 0 && responseId !== eventId)) throw new Error('Responses event response ID mismatch');
            responseId = eventId;
          }
          const eventSequence: unknown = obj['sequence_number'];
          if (typeof eventSequence !== 'number' || !Number.isSafeInteger(eventSequence) || eventSequence < 0
            || responseId.length === 0) throw new Error('Responses event is missing a valid response ID/sequence');
          if (eventSequence <= lastSequence) return;
          if (type === 'response.completed' && (typeof response !== 'object' || response === null
            || (response as Record<string, unknown>)['status'] !== 'completed')) {
            throw new Error('Responses completed event has an invalid completed status');
          }
          processPayload(obj, eventType);
          const cursor: ResponseCursor = { responseId, sequence: eventSequence, providerId: deps.setting.id };
          if (state.done) {
            if (type === 'response.completed') cursor.terminalStatus = 'completed';
            terminalCursor = cursor;
          }
          else await persistCursor(cursor);
          lastSequence = eventSequence;
        } catch (error) {
          if (error instanceof ProviderResponseError && error.partialChunk !== null) {
            publishVisible(reconciler.reconcile(error.partialChunk));
          }
          state.error = error instanceof Error ? error : new Error(String(error));
        }
      });
    };

    let transportError: Error | null = null;
    const resp = await deps.http.fetchStream(request, {
      signal: opts?.signal,
      onDataEnd: opts?.onDataEnd,
      // R18:response.completed/incomplete/[DONE]/error 为协议终态 → 立即收口
      shouldStop: (): boolean => state.done || state.terminalQueued || state.error !== null,
      onChunk: (chunk: ArrayBuffer, end: boolean): void => {
        if (state.error !== null || state.done || state.terminalQueued) return;
        try {
          const events: SseEvent[] = assembler.feed(chunk);
          if (end) events.push(...assembler.flush());
          if (events.length > 0) state.sawEvent = true;
          for (const event of events) {
            const payloads = normalizeOpenAIStreamDataLines(event.data);
            if (payloads.length === 0 && event.data.indexOf('[DONE]') >= 0) {
              if (resume === null) state.done = true;
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
              if (resume !== null) { queueResumablePayload(obj, event.event ?? ''); continue; }
              const terminalType = obj['type'] ?? event.event;
              const terminal = terminalType === 'response.completed' || terminalType === 'response.incomplete';
              if (obj['error'] !== undefined) {
                throw parseOpenAiErrorDetail(obj as JsonValue);
              }
              if (obj['type'] === 'response.created') continue;
              const parsed = parseResponsesStreamEvent(obj as never);
              if (parsed === null) {
                if (terminal) {
                  state.done = true;
                  return;
                }
                continue;
              }
              const reconciled = reconciler.reconcile(parsed);
              publishVisible(reconciled);
              if (terminal) {
                state.done = true;
                return;
              }
            }
          }
        } catch (e) {
          if (e instanceof ProviderResponseError && e.partialChunk !== null) {
            // Failed envelopes can contain the only text or the latest suffix.
            // Reconcile against already published deltas before checkpointing.
            publishVisible(reconciler.reconcile(e.partialChunk));
          }
          state.error = e instanceof Error ? e : new Error(String(e));
        }
      },
    }).catch((error: unknown) => {
      transportError = error instanceof Error ? error : new Error(String(error));
      return null;
    });
    await checkpointQueue;
    if (state.error !== null) throw state.error;
    if (transportError !== null) throw transportError;
    if (resp === null) throw new Error('Responses transport did not return a response');
    if (resp.status < 200 || resp.status >= 300) {
      throw errorFromFailure(resp.status, resp.body);
    }
    // R06:2xx 但零 SSE 事件(网关 200 回普通 JSON)→ 不是成功流
    if (!state.sawEvent) {
      throw errorFromFailure(resp.status, resp.body);
    }
    if (opts?.signal?.aborted === true) throw new Error('Generation cancelled');
    if (resume !== null && !state.done) throw new Error('Responses stream ended before explicit terminal response');
    if (pendingTools.size > 0 && !state.done) throw new Error('Responses stream ended before terminal response');
    pendingTools.forEach((accumulator: MessageStreamAccumulator, index: number): void => {
      const delta = accumulator.snapshot()[0];
      onChunk({ id: delta.id, model: params.model.modelId, usage: null,
        choices: [{ index, delta, message: null, finishReason: null }] });
    });
    if (terminalCursor !== null) await persistCursor(terminalCursor);
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
