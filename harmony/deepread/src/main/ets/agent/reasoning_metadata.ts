import type { JsonObject, JsonValue } from './json.ts';

export const CLAUDE_REDACTED_THINKING_METADATA_KEY = 'claude_redacted_thinking';
export const CLAUDE_THINKING_BLOCK_INDEX_METADATA_KEY = 'claude_thinking_block_index';

const nonblankString = (value: JsonValue | undefined): boolean =>
  typeof value === 'string' && value.trim().length > 0;

export const hasProtocolReasoningContent = (metadata: JsonObject | null): boolean => {
  if (metadata === null) return false;
  if (['signature', 'encrypted_content', 'reasoning_id'].some((key: string): boolean =>
    nonblankString(metadata[key]))) return true;
  const redacted = metadata[CLAUDE_REDACTED_THINKING_METADATA_KEY];
  return typeof redacted === 'object' && redacted !== null && !Array.isArray(redacted) &&
    nonblankString(redacted['data']);
};

export const reasoningBlocksCanMerge = (previous: JsonObject | null, incoming: JsonObject | null): boolean => {
  if (previous?.[CLAUDE_REDACTED_THINKING_METADATA_KEY] !== undefined ||
    incoming?.[CLAUDE_REDACTED_THINKING_METADATA_KEY] !== undefined) return false;
  return [CLAUDE_THINKING_BLOCK_INDEX_METADATA_KEY, 'reasoning_id'].every((key: string): boolean => {
    const before = previous?.[key];
    const after = incoming?.[key];
    return before === undefined || after === undefined || before === after;
  });
};

export const mergeReasoningMetadata = (previous: JsonObject | null, incoming: JsonObject | null): JsonObject | null => {
  if (incoming === null) return previous;
  if (previous === null) return incoming;
  const merged: JsonObject = { ...previous, ...incoming };
  const blockIndex = incoming[CLAUDE_THINKING_BLOCK_INDEX_METADATA_KEY];
  if (blockIndex !== undefined && blockIndex === previous[CLAUDE_THINKING_BLOCK_INDEX_METADATA_KEY] &&
    typeof previous['signature'] === 'string' && typeof incoming['signature'] === 'string') {
    merged['signature'] = previous['signature'] + incoming['signature'];
  }
  return merged;
};
