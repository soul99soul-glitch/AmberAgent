// subagent_transcript — subagent JSONL protocol and bounded transcript reader(D-132a Task 5)
// Android baseline: SubAgentTranscriptReader.kt + SubAgentManager.appendEvent.
// Platform file access remains behind SubAgentTranscriptPort; the entry adapter is intentionally later.

import type { JsonObject } from './json.ts';

export interface SubAgentTranscriptTail {
  text: string;
  startsAfterFileStart: boolean;
}

export interface SubAgentTranscriptPort {
  canonicalPath(path: string): Promise<string | null>;
  appendText(path: string, text: string): Promise<void>;
  exists(path: string): Promise<boolean>;
  isRegularFile(path: string): Promise<boolean>;
  isPathInside(root: string, path: string): Promise<boolean>;
  readTail(path: string, maxBytes: number): Promise<SubAgentTranscriptTail>;
}

type SubAgentTranscriptEventName = 'started' | 'finished';

interface SubAgentTranscriptEvent {
  event: SubAgentTranscriptEventName;
  created_at_ms: number;
  payload: JsonObject;
}

type UnknownRecord = Record<string, unknown>;

interface JsonValueRange {
  start: number;
  end: number;
}

const MAX_TRANSCRIPT_TAIL_BYTES: number = 256 * 1024;
const KOTLIN_WHITESPACE: RegExp =
  /^[\u0009-\u000D\u001C-\u001F\u0020\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000]*$/u;

const isRecord = (value: unknown): value is UnknownRecord =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

const isBlank = (value: string): boolean => KOTLIN_WHITESPACE.test(value);

const skipJsonWhitespace = (text: string, from: number): number => {
  let index: number = from;
  while (index < text.length &&
    (text[index] === ' ' || text[index] === '\t' || text[index] === '\n' || text[index] === '\r')) {
    index++;
  }
  return index;
};

const jsonStringEnd = (text: string, from: number): number => {
  let index: number = from + 1;
  while (index < text.length) {
    if (text[index] === '\\') {
      index += 2;
    } else if (text[index] === '"') {
      return index + 1;
    } else {
      index++;
    }
  }
  return text.length;
};

const jsonCompositeEnd = (text: string, from: number): number => {
  const opening: string = text[from];
  const closing: string = opening === '{' ? '}' : ']';
  let depth: number = 1;
  let index: number = from + 1;
  while (index < text.length && depth > 0) {
    if (text[index] === '"') {
      index = jsonStringEnd(text, index);
    } else {
      if (text[index] === opening) depth++;
      if (text[index] === closing) depth--;
      index++;
    }
  }
  return index;
};

const jsonValueRange = (text: string, from: number): JsonValueRange => {
  const start: number = skipJsonWhitespace(text, from);
  let end: number;
  if (text[start] === '"') {
    end = jsonStringEnd(text, start);
  } else if (text[start] === '{' || text[start] === '[') {
    end = jsonCompositeEnd(text, start);
  } else {
    end = start;
    while (end < text.length && text[end] !== ',' && text[end] !== '}' && text[end] !== ']' &&
      text[end] !== ' ' && text[end] !== '\t' && text[end] !== '\n' && text[end] !== '\r') {
      end++;
    }
  }
  return { start, end };
};

const jsonObjectPropertyRange = (
  text: string,
  objectRange: JsonValueRange,
  property: string,
): JsonValueRange | null => {
  let index: number = skipJsonWhitespace(text, objectRange.start);
  if (text[index] !== '{') return null;
  index++;
  let found: JsonValueRange | null = null;
  while (index < objectRange.end) {
    index = skipJsonWhitespace(text, index);
    if (text[index] === '}') break;
    if (text[index] !== '"') return null;
    const keyEnd: number = jsonStringEnd(text, index);
    const key: unknown = JSON.parse(text.substring(index, keyEnd));
    index = skipJsonWhitespace(text, keyEnd);
    if (text[index] !== ':') return null;
    const value: JsonValueRange = jsonValueRange(text, index + 1);
    if (key === property) found = value;
    index = skipJsonWhitespace(text, value.end);
    if (text[index] === ',') {
      index++;
    } else if (text[index] === '}') {
      break;
    } else {
      return null;
    }
  }
  return found;
};

const numericDisplayTextLexeme = (line: string): string | null => {
  const root: JsonValueRange = jsonValueRange(line, 0);
  const payload: JsonValueRange | null = jsonObjectPropertyRange(line, root, 'payload');
  if (payload === null) return null;
  const displayText: JsonValueRange | null =
    jsonObjectPropertyRange(line, payload, 'display_text');
  return displayText !== null ? line.substring(displayText.start, displayText.end) : null;
};

const displayTextFromTranscriptLine = (line: string, includeFinalResult: boolean): string | null => {
  let event: unknown;
  try {
    event = JSON.parse(line);
  } catch {
    return null;
  }
  if (!isRecord(event)) return null;
  const payload: unknown = event['payload'];
  if (!isRecord(payload)) return null;
  const rawDisplayText: unknown = payload['display_text'];
  let displayText: string | null = null;
  if (typeof rawDisplayText === 'string') displayText = rawDisplayText;
  if (typeof rawDisplayText === 'boolean') displayText = String(rawDisplayText);
  if (typeof rawDisplayText === 'number') displayText = numericDisplayTextLexeme(line);
  if (displayText !== null && !isBlank(displayText)) return displayText;
  if (!includeFinalResult || event['event'] !== 'finished') return null;
  let result: unknown = payload['result'];
  if (typeof result === 'string') {
    try { result = JSON.parse(result); } catch { return null; }
  }
  if (!isRecord(result)) return null;
  const summary: unknown = result['summary'];
  if (typeof summary === 'string' && !isBlank(summary)) return summary;
  const error: unknown = result['error'];
  return typeof error === 'string' && !isBlank(error) ? error : null;
};

export const appendSubAgentTranscriptEvent = async (
  files: SubAgentTranscriptPort,
  transcriptPath: string,
  event: SubAgentTranscriptEventName,
  createdAtMs: number,
  payload: JsonObject,
): Promise<void> => {
  const record: SubAgentTranscriptEvent = {
    event,
    created_at_ms: createdAtMs,
    payload,
  };
  await files.appendText(transcriptPath, JSON.stringify(record) + '\n');
};

export const readSubAgentDisplayTextFromTranscript = async (
  transcriptPath: string,
  runRoot: string,
  files: SubAgentTranscriptPort,
  includeFinalResult: boolean = false,
): Promise<string> => {
  if (isBlank(transcriptPath)) return '';
  try {
    const root: string | null = await files.canonicalPath(runRoot);
    if (root === null) return '';
    const transcript: string | null = await files.canonicalPath(transcriptPath);
    if (transcript === null) return '';
    if (!await files.isRegularFile(transcript) || !transcript.endsWith('.jsonl')) return '';
    if (!await files.isPathInside(root, transcript)) return '';

    const tail: SubAgentTranscriptTail =
      await files.readTail(transcript, MAX_TRANSCRIPT_TAIL_BYTES);
    let text: string = tail.text;
    if (tail.startsAfterFileStart) {
      const newline: number = text.indexOf('\n');
      text = newline >= 0 ? text.substring(newline + 1) : '';
    }
    const lines: string[] = text.split(/\r\n|\n|\r/);
    for (let index: number = lines.length - 1; index >= 0; index--) {
      if (isBlank(lines[index])) continue;
      const displayText: string | null = displayTextFromTranscriptLine(lines[index], includeFinalResult);
      if (displayText !== null) return displayText;
    }
    return '';
  } catch {
    return '';
  }
};
