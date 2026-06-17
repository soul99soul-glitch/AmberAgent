/**
 * Source-only contract for the future HarmonyOS ArkTS OpenAI-compatible SSE parser.
 *
 * This is intentionally SDK-free and not a verified HarmonyOS implementation.
 * It adapts Android semantics from ChatCompletionsAPI.normalizeOpenAIStreamDataLines.
 * Difference: Android filters [DONE] because OkHttp/EventSource owns stream closure;
 * this source-only contract emits an explicit `done` event because the future ArkTS
 * streaming layer may need to represent completion in-band.
 *
 * Until a real DevEco project exists, the executable behavior authority is
 * openai_sse_parser_reference.mjs. Keep this file aligned with that reference.
 */

export type SseParsedEvent =
  | { kind: 'payload'; json: string }
  | { kind: 'done' }
  | { kind: 'empty' };

export function normalizeOpenAIStreamDataLines(rawData: string): SseParsedEvent[] {
  const lines = rawData
    .split(/\r?\n/)
    .map((line) => stripNestedDataPrefix(line.trim()))
    .filter((line) => line.length > 0);

  if (lines.length === 0) return [{ kind: 'empty' }];
  if (lines.length === 1) return [toEvent(lines[0])];

  const joined = lines.join('\n');
  if (looksLikeJson(joined)) return [{ kind: 'payload', json: joined }];

  return lines.flatMap((line) => {
    const event = toEvent(line);
    return event.kind === 'empty' ? [] : [event];
  });
}

function stripNestedDataPrefix(value: string): string {
  let current = value;
  while (current.startsWith('data:')) {
    current = current.slice('data:'.length).trimStart();
  }
  return current.trim();
}

function toEvent(value: string): SseParsedEvent {
  if (!value) return { kind: 'empty' };
  if (value === '[DONE]') return { kind: 'done' };
  return { kind: 'payload', json: value };
}

function looksLikeJson(value: string): boolean {
  if (!(value.startsWith('{') || value.startsWith('['))) return false;
  try {
    JSON.parse(value);
    return true;
  } catch {
    return false;
  }
}

export interface OpenAIStreamDelta {
  role?: string;
  content?: string;
  reasoning_content?: string;
  tool_calls?: Array<{
    index?: number;
    id?: string;
    type?: string;
    function?: {
      name?: string;
      arguments?: string;
    };
  }>;
}

export interface OpenAIStreamChoice {
  index: number;
  delta?: OpenAIStreamDelta;
  finish_reason?: string | null;
}

export interface OpenAIStreamPayload {
  id?: string;
  model?: string;
  choices?: OpenAIStreamChoice[];
  error?: {
    message?: string;
    type?: string;
    code?: string | number;
  };
}
