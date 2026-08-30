// Streaming delta folding for the canonical UIMessage part schema.

import type { JsonObject, JsonValue } from './json.ts';
import { nowIso } from './ids.ts';
import type {
  UIMessagePart, UIMessagePartImage, UIMessagePartReasoning,
  UIMessagePartText, UIMessagePartTool,
} from './message.ts';

const STREAM_TOOL_INDEX_METADATA_KEY = 'stream_tool_index';

export type DeltaPart =
  | { type: 'text'; text: string; metadata?: JsonObject | null }
  | { type: 'image'; url: string; metadata?: JsonObject | null }
  | { type: 'reasoning'; reasoning: string; finishedAt?: string | null; metadata?: JsonObject | null }
  | {
      type: 'tool';
      toolCallId?: string;
      toolName?: string;
      input?: string;
      output?: UIMessagePart[];
      approvalState?: UIMessagePartTool['approvalState'];
      metadata?: JsonObject | null;
      streamArgsReplace?: boolean;
    };

export const appendChunk = (parts: UIMessagePart[], delta: DeltaPart): UIMessagePart[] => {
  switch (delta.type) {
    case 'text': return appendText(parts, delta);
    case 'image': return appendImage(parts, delta);
    case 'reasoning': return appendReasoning(parts, delta);
    case 'tool': return appendTool(parts, delta);
  }
};

const appendText = (
  parts: UIMessagePart[], delta: { text: string; metadata?: JsonObject | null },
): UIMessagePart[] => {
  if (delta.text.length === 0) return parts;
  const last: UIMessagePart | undefined = parts[parts.length - 1];
  if (last !== undefined && last.type === 'text') {
    const merged: UIMessagePartText = {
      type: 'text',
      text: last.text + delta.text,
      metadata: delta.metadata !== undefined && delta.metadata !== null ? delta.metadata : last.metadata,
    };
    return [...parts.slice(0, -1), merged];
  }
  return [...parts, { type: 'text', text: delta.text, metadata: delta.metadata ?? null }];
};

const appendImage = (
  parts: UIMessagePart[], delta: { url: string; metadata?: JsonObject | null },
): UIMessagePart[] => {
  if (delta.url.length === 0) return parts;
  const image: UIMessagePartImage = { type: 'image', url: delta.url, metadata: delta.metadata ?? null };
  return [...parts, image];
};

const appendReasoning = (
  parts: UIMessagePart[],
  delta: { reasoning: string; finishedAt?: string | null; metadata?: JsonObject | null },
): UIMessagePart[] => {
  const last: UIMessagePart | undefined = parts[parts.length - 1];
  if (delta.reasoning.length === 0 && delta.finishedAt === undefined) return parts;
  if (last !== undefined && last.type === 'reasoning') {
    const merged: UIMessagePartReasoning = {
      type: 'reasoning',
      reasoning: last.reasoning + delta.reasoning,
      createdAt: last.createdAt,
      finishedAt: delta.finishedAt ?? null,
      metadata: delta.metadata !== undefined && delta.metadata !== null ? delta.metadata : last.metadata,
    };
    return [...parts.slice(0, -1), merged];
  }
  const reasoning: UIMessagePartReasoning = {
    type: 'reasoning',
    reasoning: delta.reasoning,
    createdAt: nowIso(),
    finishedAt: delta.finishedAt ?? null,
    metadata: delta.metadata ?? null,
  };
  return [...parts, reasoning];
};

const metadataStreamToolIndex = (metadata: JsonObject | null | undefined): number | null => {
  if (metadata === null || metadata === undefined) return null;
  const value: JsonValue | undefined = metadata[STREAM_TOOL_INDEX_METADATA_KEY];
  return typeof value === 'number' ? value : null;
};

const appendTool = (parts: UIMessagePart[], delta: DeltaPart & { type: 'tool' }): UIMessagePart[] => {
  const targetIndex: number = findToolMergeTarget(parts, delta);
  if (targetIndex < 0) {
    const tool: UIMessagePartTool = {
      type: 'tool',
      toolCallId: delta.toolCallId ?? '',
      toolName: delta.toolName ?? '',
      input: delta.input ?? '',
      output: delta.output ?? [],
      approvalState: delta.approvalState ?? { type: 'auto' },
      metadata: delta.metadata ?? null,
    };
    return [...parts, tool];
  }
  const existing: UIMessagePartTool = parts[targetIndex] as UIMessagePartTool;
  const merged: UIMessagePartTool = mergeTool(existing, delta);
  return [...parts.slice(0, targetIndex), merged, ...parts.slice(targetIndex + 1)];
};

export const findToolMergeTarget = (
  parts: UIMessagePart[], delta: DeltaPart & { type: 'tool' },
): number => {
  const candidates: { index: number; part: UIMessagePartTool }[] = [];
  for (let i = 0; i < parts.length; i++) {
    const part: UIMessagePart = parts[i];
    if (part.type === 'tool' && part.output.length === 0) candidates.push({ index: i, part });
  }
  if (delta.toolCallId !== undefined && delta.toolCallId.length > 0) {
    const byId = candidates.find(candidate => candidate.part.toolCallId === delta.toolCallId);
    if (byId !== undefined) return byId.index;
  }
  const incomingIndex: number | null = metadataStreamToolIndex(delta.metadata);
  if (incomingIndex !== null) {
    const byIndex = candidates.find(
      candidate => metadataStreamToolIndex(candidate.part.metadata) === incomingIndex,
    );
    if (byIndex !== undefined) return byIndex.index;
  }
  return -1;
};

export const mergeTool = (
  existing: UIMessagePartTool,
  delta: DeltaPart & { type: 'tool' },
): UIMessagePartTool => {
  const replaceArgs: boolean = delta.streamArgsReplace === true;
  return {
    type: 'tool',
    toolCallId: delta.toolCallId !== undefined && delta.toolCallId.length > 0
      ? delta.toolCallId
      : existing.toolCallId,
    toolName: replaceArgs
      ? (delta.toolName ?? existing.toolName)
      : existing.toolName + (delta.toolName ?? ''),
    input: replaceArgs
      ? (delta.input ?? existing.input)
      : existing.input + (delta.input ?? ''),
    output: delta.output !== undefined ? [...existing.output, ...delta.output] : existing.output,
    approvalState: delta.approvalState ?? existing.approvalState,
    metadata: delta.metadata !== undefined && delta.metadata !== null
      ? delta.metadata
      : existing.metadata,
  };
};
