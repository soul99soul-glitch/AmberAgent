// OpenAI Responses API 流式解析 + Reconciler
//
// Android 基准:
//   ResponseAPI.kt parseResponseDelta(:867-1196) / parseResponseOutput(:1210-)
//   parseDoneMessageItem / parseTokenUsage(:1371)
//   ResponseStreamReconciler.kt 全文
//   OPENAI_TOOL_CALL_ID_METADATA_KEY = 'openai_call_id'

import type { JsonObject, JsonValue } from './json.ts';
import {
  makeUIMessage, makeAssistantMessage,
} from './message.ts';
import type {
  MessageChunk, UIMessage, UIMessageAnnotation, UIMessageChoice, UIMessagePart,
  UIMessagePartTool,
} from './message.ts';
import { nowIso } from './ids.ts';
import type { TokenUsage } from './usage.ts';
import { withStreamArgsReplace, isStreamArgsReplace } from './tool_merge.ts';
import { parseOpenAiErrorDetail } from './openai_parse.ts';
import { ProviderResponseError } from './provider_response_error.ts';

export const OPENAI_TOOL_CALL_ID_METADATA_KEY = 'openai_call_id';
export const OPENAI_IMAGE_CALL_ID_METADATA_KEY = 'openai_image_call_id';
export const ENCRYPTED_CONTENT_METADATA_KEY = 'encrypted_content';
export const REASONING_ID_METADATA_KEY = 'reasoning_id';

const makeTextPart = (text: string): UIMessagePart =>
  ({ type: 'text', text, metadata: null });

const makeToolPart = (
  toolCallId: string, toolName: string, input: string,
  metadata?: JsonObject,
): UIMessagePartTool => ({
  type: 'tool',
  toolCallId,
  toolName,
  input,
  output: [],
  approvalState: { type: 'auto' },
  metadata: metadata ?? null,
});

const makeReasoningPart = (
  reasoning: string, finishedAt: string | null, metadata?: JsonObject,
): UIMessagePart => ({
  type: 'reasoning',
  reasoning,
  createdAt: nowIso(),
  finishedAt,
  metadata: metadata ?? null,
});

const makeImagePart = (url: string, metadata?: JsonObject): UIMessagePart => ({
  type: 'image',
  url,
  metadata: metadata ?? null,
});

const isObj = (v: JsonValue | undefined): v is JsonObject =>
  typeof v === 'object' && v !== null && !Array.isArray(v);

const strOrNull = (v: JsonValue | undefined): string | null =>
  typeof v === 'string' ? v : null;

const intOrNull = (v: JsonValue | undefined): number | null =>
  typeof v === 'number' && Number.isInteger(v) ? v : null;

export const parseResponsesTokenUsage = (json: JsonObject | null | undefined): TokenUsage | null => {
  if (json === null || json === undefined) return null;
  const detailsVal = json['input_tokens_details'];
  const details = isObj(detailsVal) ? detailsVal : null;
  return {
    promptTokens: intOrNull(json['input_tokens']) ?? 0,
    completionTokens: intOrNull(json['output_tokens']) ?? 0,
    totalTokens: intOrNull(json['total_tokens']) ?? 0,
    cachedTokens: details !== null ? (intOrNull(details['cached_tokens']) ?? 0) : 0,
  };
};

const extractMessageOutputText = (item: JsonObject): string => {
  const content = item['content'];
  if (!Array.isArray(content)) return '';
  const texts: string[] = [];
  for (const part of content) {
    if (!isObj(part)) continue;
    const t = strOrNull(part['text']);
    if (t !== null && t.length > 0) texts.push(t);
  }
  return texts.join('');
};

const parseDoneMessageItem = (item: JsonObject, id: string): MessageChunk | null => {
  const text = extractMessageOutputText(item);
  if (text.length === 0) return null;
  return {
    id,
    model: '',
    choices: [{
      index: 0,
      delta: null,
      message: makeAssistantMessage(text),
      finishReason: null,
    }],
    usage: null,
  };
};

// parseResponseOutput — final response object → MessageChunk (message=full, delta=null)
export const parseResponsesOutput = (jsonObject: JsonObject, terminalStatus?: string): MessageChunk => {
  const outputsRaw = jsonObject['output'];
  const outputs: JsonValue[] = Array.isArray(outputsRaw) ? outputsRaw : [];
  const parts: UIMessagePart[] = [];
  const annotations: UIMessageAnnotation[] = [];

  for (const outputItem of outputs) {
    if (!isObj(outputItem)) continue;
    const type = strOrNull(outputItem['type']);
    const id = strOrNull(outputItem['id']);
    if (type === 'reasoning') {
      const encrypted = strOrNull(outputItem['encrypted_content']);
      const metadata: JsonObject = {};
      if (encrypted !== null) metadata[ENCRYPTED_CONTENT_METADATA_KEY] = encrypted;
      if (id !== null) metadata[REASONING_ID_METADATA_KEY] = id;
      const summary = outputItem['summary'];
      let hasSummary = false;
      if (Array.isArray(summary)) {
        for (const part of summary) {
          if (!isObj(part)) continue;
          if (strOrNull(part['type']) !== 'summary_text') continue;
          const text = strOrNull(part['text']) ?? '';
          hasSummary = true;
          parts.push(makeReasoningPart(text, nowIso(),
            Object.keys(metadata).length > 0 ? metadata : undefined));
        }
      }
      if (!hasSummary && Object.keys(metadata).length > 0) {
        parts.push(makeReasoningPart('', nowIso(), metadata));
      }
    } else if (type === 'function_call') {
      const callId = strOrNull(outputItem['call_id']);
      const name = strOrNull(outputItem['name']);
      if (callId === null || name === null) continue;
      parts.push(makeToolPart(callId, name, strOrNull(outputItem['arguments']) ?? ''));
    } else if (type === 'message') {
      const content = outputItem['content'];
      if (Array.isArray(content)) {
        for (const part of content) {
          if (!isObj(part)) continue;
          const t = strOrNull(part['text']);
          if (t !== null) {
            parts.push(makeTextPart(t));
          }
          const ann = part['annotations'];
          if (Array.isArray(ann)) {
            for (const a of ann) {
              if (!isObj(a)) continue;
              if (strOrNull(a['type']) !== 'url_citation') continue;
              const url = strOrNull(a['url']);
              if (url === null) continue;
              annotations.push({
                type: 'url_citation',
                title: strOrNull(a['title']) ?? url,
                url,
              });
            }
          }
        }
      }
    }
  }

  const usage = parseResponsesTokenUsage(
    isObj(jsonObject['usage']) ? jsonObject['usage'] as JsonObject : null,
  );
  const status = terminalStatus ?? strOrNull(jsonObject['status']);
  const incomplete = status === 'incomplete';
  const incompleteDetails = isObj(jsonObject['incomplete_details'])
    ? jsonObject['incomplete_details'] as JsonObject : null;
  const finishReason = incomplete
    ? (incompleteDetails !== null ? (strOrNull(incompleteDetails['reason']) ?? 'incomplete') : 'incomplete')
    : null;

  const message = makeUIMessage('assistant', parts, {
    annotations: annotations.length > 0 ? annotations : undefined,
    finishedAt: nowIso(),
  });

  const chunk: MessageChunk = {
    id: strOrNull(jsonObject['id']) ?? '',
    model: strOrNull(jsonObject['model']) ?? '',
    choices: [{
      index: 0,
      delta: null,
      message,
      finishReason,
    }],
    usage,
  };
  if (status === 'failed' || (incomplete && finishReason !== 'max_output_tokens')) {
    const error = jsonObject['error'];
    const detail = status === 'failed'
      ? (error !== undefined && error !== null ? parseOpenAiErrorDetail(error).message : 'unknown error')
      : finishReason ?? 'unknown reason';
    const displayParts = parts.filter((part: UIMessagePart): boolean => part.type !== 'tool');
    const partialChunk: MessageChunk | null = displayParts.length > 0 ? {
      ...chunk,
      choices: [{ ...chunk.choices[0], message: { ...message, parts: displayParts } }],
    } : null;
    throw new ProviderResponseError(`OpenAI Responses ${status}: ${detail}`, partialChunk);
  }
  return chunk;
};

// parseResponseDelta — SSE event → MessageChunk | null
export const parseResponsesStreamEvent = (jsonObject: JsonObject): MessageChunk | null => {
  const chunkType = strOrNull(jsonObject['type']);
  if (chunkType === null) {
    throw new Error('chunk type not found');
  }

  if (chunkType === 'response.output_text.delta' || chunkType === 'response.refusal.delta') {
    return {
      id: strOrNull(jsonObject['item_id']) ?? '',
      model: '',
      choices: [{
        index: 0,
        delta: makeAssistantMessage(strOrNull(jsonObject['delta']) ?? ''),
        message: null,
        finishReason: null,
      }],
      usage: null,
    };
  }

  if (chunkType === 'response.output_text.annotation.added') {
    const annotation = jsonObject['annotation'];
    if (!isObj(annotation) || strOrNull(annotation['type']) !== 'url_citation') return null;
    const url = strOrNull(annotation['url']);
    if (url === null) return null;
    return {
      id: strOrNull(jsonObject['item_id']) ?? '',
      model: '',
      choices: [{
        index: 0,
        delta: makeUIMessage('assistant', [], {
          annotations: [{
            type: 'url_citation',
            title: strOrNull(annotation['title']) ?? url,
            url,
          }],
        }),
        message: null,
        finishReason: null,
      }],
      usage: null,
    };
  }

  if (chunkType === 'response.output_text.done') {
    return {
      id: strOrNull(jsonObject['item_id']) ?? '',
      model: '',
      choices: [{
        index: 0,
        delta: null,
        message: makeAssistantMessage(strOrNull(jsonObject['text']) ?? ''),
        finishReason: null,
      }],
      usage: null,
    };
  }

  if (chunkType === 'response.reasoning_summary_text.delta' || chunkType === 'response.reasoning_text.delta') {
    const itemId = strOrNull(jsonObject['item_id']);
    return {
      id: strOrNull(jsonObject['item_id']) ?? '',
      model: '',
      choices: [{
        index: 0,
        delta: makeUIMessage('assistant', [makeReasoningPart(
          strOrNull(jsonObject['delta']) ?? '', null,
          itemId !== null ? { [REASONING_ID_METADATA_KEY]: itemId } : undefined)]),
        message: null,
        finishReason: null,
      }],
      usage: null,
    };
  }

  if (chunkType === 'response.output_item.added') {
    const item = jsonObject['item'];
    if (!isObj(item)) return null;
    const type = strOrNull(item['type']);
    const id = strOrNull(item['id']);
    if (type === 'function_call' && id !== null) {
      const callId = strOrNull(item['call_id']);
      const toolPart = makeToolPart(
        id,
        strOrNull(item['name']) ?? '',
        strOrNull(item['arguments']) ?? '',
        callId !== null ? { [OPENAI_TOOL_CALL_ID_METADATA_KEY]: callId } : undefined,
      );
      return {
        id,
        model: '',
        choices: [{
          index: 0,
          delta: makeUIMessage('assistant', [toolPart]),
          message: null,
          finishReason: null,
        }],
        usage: null,
      };
    }
    if (type === 'reasoning' && id !== null) {
      const encrypted = strOrNull(item['encrypted_content']);
      return {
        id,
        model: '',
        choices: [{
          index: 0,
          delta: makeUIMessage('assistant', [makeReasoningPart('', null, {
            [ENCRYPTED_CONTENT_METADATA_KEY]: encrypted ?? '',
            [REASONING_ID_METADATA_KEY]: id,
          })]),
          message: null,
          finishReason: null,
        }],
        usage: null,
      };
    }
    if (type === 'image_generation_call' && id !== null) {
      return {
        id,
        model: '',
        choices: [{
          index: 0,
          delta: makeUIMessage('assistant', [makeImagePart('', {
            [OPENAI_IMAGE_CALL_ID_METADATA_KEY]: id,
          })]),
          message: null,
          finishReason: null,
        }],
        usage: null,
      };
    }
    return null;
  }

  if (chunkType === 'response.output_item.done') {
    const item = jsonObject['item'];
    if (!isObj(item)) return null;
    const type = strOrNull(item['type']);
    const id = strOrNull(item['id']);
    if (type === 'reasoning' && id !== null) {
      const encrypted = strOrNull(item['encrypted_content']);
      return {
        id,
        model: '',
        choices: [{
          index: 0,
          delta: makeUIMessage('assistant', [makeReasoningPart('', nowIso(), {
            [ENCRYPTED_CONTENT_METADATA_KEY]: encrypted ?? '',
            [REASONING_ID_METADATA_KEY]: id,
          })]),
          message: null,
          finishReason: null,
        }],
        usage: null,
      };
    }
    if (type === 'message' && id !== null) {
      return parseDoneMessageItem(item, id);
    }
    if (type === 'image_generation_call') {
      const result = strOrNull(item['result']);
      const callId = strOrNull(item['id']) ?? '';
      if (result === null) return null;
      return {
        id: callId,
        model: '',
        choices: [{
          index: 0,
          delta: makeUIMessage('assistant', [makeImagePart(result, {
            [OPENAI_IMAGE_CALL_ID_METADATA_KEY]: callId,
          })]),
          message: null,
          finishReason: null,
        }],
        usage: null,
      };
    }
    return null;
  }

  if (chunkType === 'response.function_call_arguments.delta') {
    const toolCallId = strOrNull(jsonObject['item_id']);
    if (toolCallId === null) throw new Error('item_id not found');
    return {
      id: toolCallId,
      model: '',
      choices: [{
        index: 0,
        delta: makeUIMessage('assistant', [makeToolPart(
          toolCallId, '', strOrNull(jsonObject['delta']) ?? '')]),
        message: null,
        finishReason: null,
      }],
      usage: null,
    };
  }

  if (chunkType === 'response.function_call_arguments.done') {
    const toolCallId = strOrNull(jsonObject['item_id']);
    if (toolCallId === null) throw new Error('item_id not found');
    const argumentsStr = strOrNull(jsonObject['arguments']);
    if (argumentsStr === null) throw new Error('arguments not found');
    const toolPart = makeToolPart(toolCallId, '', argumentsStr);
    return {
      id: toolCallId,
      model: '',
      choices: [{
        index: 0,
        delta: makeUIMessage('assistant', [withStreamArgsReplace(toolPart)]),
        message: null,
        finishReason: null,
      }],
      usage: null,
    };
  }

  if (chunkType === 'response.failed') {
    const response = jsonObject['response'];
    return parseResponsesOutput(isObj(response) ? response : jsonObject, 'failed');
  }

  if (chunkType === 'response.completed' || chunkType === 'response.incomplete') {
    const response = jsonObject['response'];
    if (isObj(response)) {
      return parseResponsesOutput(response, chunkType === 'response.incomplete' ? 'incomplete' : undefined);
    }
    if (chunkType === 'response.incomplete') return parseResponsesOutput(jsonObject, 'incomplete');
    return {
      id: strOrNull(jsonObject['item_id']) ?? '',
      model: '',
      choices: [],
      usage: parseResponsesTokenUsage(
        isObj(jsonObject['usage']) ? jsonObject['usage'] as JsonObject : null,
      ),
    };
  }

  return null;
};

// ===== ResponseStreamReconciler =====

interface StreamedTool {
  id: string;
  args: string;
  name: string;
  callId: string | null;
}

export class ResponseStreamReconciler {
  private readonly streamAssistantId = `resp-stream-${Math.random().toString(36).slice(2)}`;
  private readonly itemTexts = new Map<string, string>();
  private readonly streamedTools = new Map<string, StreamedTool>();
  private hasStreamedReasoning = false;
  private readonly streamedReasoningIds = new Set<string>();

  reconcile(chunk: MessageChunk): MessageChunk {
    const choices: UIMessageChoice[] = [];
    for (const choice of chunk.choices) {
      choices.push(this.reconcileChoice(chunk.id, choice));
    }
    return { ...chunk, choices };
  }

  private reconcileChoice(chunkId: string, choice: UIMessageChoice): UIMessageChoice {
    const delta = choice.delta;
    const message = choice.message;
    if (delta !== null && delta !== undefined && delta.role === 'assistant') {
      this.trackDelta(chunkId, delta);
      return { ...choice, delta: { ...delta, id: this.streamAssistantId } };
    }
    if ((delta === null || delta === undefined) && message !== undefined && message !== null && message.role === 'assistant') {
      return {
        ...choice,
        delta: this.buildReconciledDelta(chunkId, message),
        message: null,
      };
    }
    return choice;
  }

  private trackDelta(chunkId: string, delta: UIMessage): void {
    for (const part of delta.parts) {
      if (part.type === 'text' && part.text.length > 0) {
        const prev = this.itemTexts.get(chunkId) ?? '';
        this.itemTexts.set(chunkId, prev + part.text);
      } else if (part.type === 'tool') {
        this.trackTool(part as UIMessagePartTool);
      } else if (part.type === 'reasoning' && part.reasoning.length > 0) {
        const reasoningId = part.metadata !== null ? strOrNull(part.metadata[REASONING_ID_METADATA_KEY]) : null;
        if (reasoningId !== null) this.streamedReasoningIds.add(reasoningId);
        else this.hasStreamedReasoning = true;
      }
    }
  }

  private trackTool(part: UIMessagePartTool): void {
    if (part.toolCallId.length === 0) return;
    let state = this.streamedTools.get(part.toolCallId);
    if (state === undefined) {
      state = { id: part.toolCallId, args: '', name: '', callId: null };
      this.streamedTools.set(part.toolCallId, state);
    }
    if (isStreamArgsReplace(part)) {
      if (part.input.length > 0) state.args = part.input;
      if (part.toolName.length > 0) state.name = part.toolName;
    } else {
      state.args += part.input;
      state.name += part.toolName;
    }
    const meta = part.metadata;
    const callId = meta !== null && meta !== undefined
      ? strOrNull(meta[OPENAI_TOOL_CALL_ID_METADATA_KEY]) : null;
    if (callId !== null) state.callId = callId;
  }

  private reconcileText(chunkId: string, message: UIMessage): string | null {
    const finalText = message.parts
      .filter((p: UIMessagePart): boolean => p.type === 'text')
      .map((p: UIMessagePart): string => (p as { text: string }).text)
      .join('');
    if (finalText.length === 0) return null;

    // item 级终止事件按 item_id 取累积;response 级 chunk.id 是 response id → 聚合比较
    let accumulated = this.itemTexts.get(chunkId);
    if (accumulated === undefined) {
      accumulated = [...this.itemTexts.values()].join('');
    }
    if (accumulated.length === 0) return finalText;
    if (finalText === accumulated) return null;
    if (finalText.startsWith(accumulated)) return finalText.slice(accumulated.length);
    if (accumulated.indexOf(finalText) >= 0) return null;
    // 冲突:保留流式累积,避免重复
    return null;
  }

  private reconcileTools(message: UIMessage): UIMessagePart[] {
    const finalTools = message.parts.filter(
      (p: UIMessagePart): boolean => p.type === 'tool',
    ) as UIMessagePartTool[];
    if (finalTools.length === 0) return [];

    const streamedList = [...this.streamedTools.values()];
    const matches: Array<StreamedTool | null> = finalTools.map(() => null);
    const matched = new Set<StreamedTool>();

    for (let index = 0; index < finalTools.length; index++) {
      const finalTool = finalTools[index];
      const state = streamedList.find((st: StreamedTool): boolean =>
        (st.callId !== null && st.callId === finalTool.toolCallId) || st.id === finalTool.toolCallId);
      if (state !== undefined && !matched.has(state)) {
        matched.add(state);
        matches[index] = state;
      }
    }
    const unmatched = streamedList.filter((st: StreamedTool): boolean => !matched.has(st));
    for (let index = 0; index < finalTools.length; index++) {
      if (matches[index] === null && unmatched.length > 0) {
        matches[index] = unmatched.shift() ?? null;
      }
    }

    const parts: UIMessagePart[] = [];
    for (let index = 0; index < finalTools.length; index++) {
      const finalTool = finalTools[index];
      const state = matches[index];
      if (state === null) {
        parts.push(finalTool);
        continue;
      }
      const argsDiffer = finalTool.input.length > 0 && finalTool.input !== state.args;
      const nameMissing = state.name.length === 0 && finalTool.toolName.length > 0;
      const callIdMissing = state.callId !== finalTool.toolCallId;
      if (argsDiffer || nameMissing || callIdMissing) {
        parts.push(withStreamArgsReplace(makeToolPart(
          state.id, finalTool.toolName, finalTool.input,
          { [OPENAI_TOOL_CALL_ID_METADATA_KEY]: finalTool.toolCallId },
        )));
      }
    }
    return parts;
  }

  private buildReconciledDelta(chunkId: string, message: UIMessage): UIMessage {
    const parts: UIMessagePart[] = [];

    const suffix = this.reconcileText(chunkId, message);
    if (suffix !== null && suffix.length > 0) {
      parts.push(makeTextPart(suffix));
      const prev = this.itemTexts.get(chunkId) ?? '';
      this.itemTexts.set(chunkId, prev + suffix);
    }

    for (const p of message.parts) {
      if (p.type !== 'reasoning') continue;
      const reasoningId = p.metadata !== null ? strOrNull(p.metadata[REASONING_ID_METADATA_KEY]) : null;
      const streamed = this.hasStreamedReasoning || (reasoningId !== null && this.streamedReasoningIds.has(reasoningId));
      if (!streamed) {
        if (p.reasoning.length > 0 || p.metadata !== null) parts.push(p);
      } else if (p.metadata !== null) {
        // The summary has already streamed; retain terminal opaque data without
        // appending the full summary a second time.
        parts.push({ ...p, reasoning: '' });
      }
    }

    for (const toolPart of this.reconcileTools(message)) {
      parts.push(toolPart);
    }

    return makeUIMessage('assistant', parts, {
      annotations: message.annotations.length > 0 ? message.annotations : undefined,
      finishedAt: message.finishedAt ?? undefined,
    });
  }
}
