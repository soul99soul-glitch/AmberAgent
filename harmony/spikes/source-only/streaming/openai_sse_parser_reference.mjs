/**
 * Executable source-only reference for OpenAI-compatible SSE normalization.
 * Keep this behavior aligned with openai_sse_parser.ts until a verified ArkTS
 * implementation exists inside a real DevEco project. This adapts Android's
 * normalization behavior with an explicit `done` event for [DONE].
 */

export function normalizeOpenAIStreamDataLines(rawData) {
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

function stripNestedDataPrefix(value) {
  let current = value;
  while (current.startsWith('data:')) {
    current = current.slice('data:'.length).trimStart();
  }
  return current.trim();
}

function toEvent(value) {
  if (!value) return { kind: 'empty' };
  if (value === '[DONE]') return { kind: 'done' };
  return { kind: 'payload', json: value };
}

function looksLikeJson(value) {
  if (!(value.startsWith('{') || value.startsWith('['))) return false;
  try {
    JSON.parse(value);
    return true;
  } catch {
    return false;
  }
}
