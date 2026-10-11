// smart_ui/binding — 第一份真实只读工具结果的白名单投影。
//
// 当前只接 weather_read。工具输出已经是 UIMessagePartTool 的模型可见文本；
// 这里重新按同一份历史结果解析，只挑出四个稳定标量，不把原始 JSON、凭据、
// 预测数组或隐藏 metadata 传给原生 UI。

import type { JsonObject, JsonValue } from '../json.ts';
import type { UIMessage, UIMessagePart, UIMessagePartText, UIMessagePartTool } from '../message.ts';
import type { SmartUiTextBinding, SmartUiTextBindingPath } from './protocol.ts';
import { toolOutputJson } from '../tool_activity.ts';

export const SMART_UI_WEATHER_TOOL_NAME: string = 'weather_read';
export type SmartUiWeatherPath =
  | 'location'
  | 'temperature_celsius'
  | 'condition'
  | 'observed_at';
export const SMART_UI_WEATHER_PATHS: SmartUiWeatherPath[] = [
  'location', 'temperature_celsius', 'condition', 'observed_at',
];

export type SmartUiBindingStatus = 'ready' | 'missing' | 'failed';

export interface SmartUiToolSource {
  conversationId: string;
  nodeId: string;
  messageId: string;
  toolCallId: string;
}

export interface SmartUiBindingDescriptor {
  ref: string;
  source: 'tool';
  toolName: 'weather_read';
  paths: SmartUiWeatherPath[];
}

export type { SmartUiTextBinding, SmartUiTextBindingPath } from './protocol.ts';

export interface SmartUiProjectedField {
  path: SmartUiWeatherPath;
  value: SmartUiScalarOrNull;
}

export type SmartUiScalarOrNull = string | number | boolean | null;

export interface SmartUiWeatherProjection {
  ref: string;
  source: SmartUiToolSource;
  status: SmartUiBindingStatus;
  fields: SmartUiProjectedField[];
}

const safeHash = (input: string): string => {
  let hash: number = 0x811c9dc5;
  for (let index: number = 0; index < input.length; index++) {
    hash = Math.imul(hash ^ input.charCodeAt(index), 0x01000193);
  }
  return (hash >>> 0).toString(16).padStart(8, '0');
};

const objectValue = (value: JsonValue | undefined): JsonObject | null =>
  value !== undefined && value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as JsonObject : null;

/** A protocol-safe, deterministic ref; the full toolCallId remains in source identity. */
export const weatherBindingRef = (toolCallId: string): string => {
  const clean: string = toolCallId.replace(/[^A-Za-z0-9_-]/g, '_');
  const validAsciiId: boolean = clean === toolCallId && clean.length > 0 && clean.length <= 39;
  const suffix: string = validAsciiId ? clean : `${clean.slice(0, 30)}_${safeHash(toolCallId)}`;
  return `weather_${suffix.length > 0 ? suffix : safeHash(toolCallId)}`.slice(0, 48);
};

export const weatherBindingDescriptor = (toolCallId: string): SmartUiBindingDescriptor => ({
  ref: weatherBindingRef(toolCallId), source: 'tool', toolName: 'weather_read',
  paths: ['location', 'temperature_celsius', 'condition', 'observed_at'],
});

const isBindingDescriptor = (value: JsonValue | undefined, ref: string): boolean => {
  const object: JsonObject | null = objectValue(value);
  if (object === null || object['ref'] !== ref || object['source'] !== 'tool'
    || object['toolName'] !== SMART_UI_WEATHER_TOOL_NAME) return false;
  const paths: JsonValue | undefined = object['paths'];
  if (!Array.isArray(paths) || paths.length !== SMART_UI_WEATHER_PATHS.length) return false;
  for (let index: number = 0; index < SMART_UI_WEATHER_PATHS.length; index++) {
    if (paths[index] !== SMART_UI_WEATHER_PATHS[index]) return false;
  }
  return true;
};

const annotateTool = (tool: UIMessagePartTool): UIMessagePartTool => {
  if (tool.toolName !== SMART_UI_WEATHER_TOOL_NAME || tool.output.length === 0) return tool;
  const payload: JsonObject = toolOutputJson(tool);
  if (payload['ok'] !== true || payload['tool'] !== SMART_UI_WEATHER_TOOL_NAME) return tool;
  const descriptor: SmartUiBindingDescriptor = weatherBindingDescriptor(tool.toolCallId);
  if (isBindingDescriptor(payload['_ui'], descriptor.ref)) return tool;
  const output: UIMessagePart[] = tool.output.slice();
  const textIndex: number = output.findIndex((part: UIMessagePart): boolean => part.type === 'text');
  if (textIndex < 0) return tool;
  const textPart: UIMessagePart = output[textIndex];
  if (textPart.type !== 'text') return tool;
  const descriptorValue: JsonObject = {
    ref: descriptor.ref, source: descriptor.source, toolName: descriptor.toolName, paths: descriptor.paths,
  };
  const nextPayload: JsonObject = { ...payload, _ui: descriptorValue };
  const nextText: UIMessagePartText = { ...textPart, text: JSON.stringify(nextPayload) };
  output[textIndex] = nextText;
  return { ...tool, output };
};

/**
 * Add a model-visible descriptor to prepared context only. The persisted message is never mutated.
 * The descriptor is merged into the existing JSON object so toolOutputJson remains readable.
 */
export const annotateSmartUiWeatherBindings = (messages: UIMessage[]): UIMessage[] =>
  messages.map((message: UIMessage): UIMessage => {
    let changed: boolean = false;
    const parts: UIMessagePart[] = message.parts.map((part: UIMessagePart): UIMessagePart => {
      if (part.type !== 'tool') return part;
      const next: UIMessagePartTool = annotateTool(part);
      if (next !== part) changed = true;
      return next;
    });
    return changed ? { ...message, parts } : message;
  });

const scalarOrNull = (value: JsonValue | undefined): SmartUiScalarOrNull => {
  if (value === null || value === undefined) return null;
  if (typeof value === 'string' || typeof value === 'boolean') return value;
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  return null;
};

const field = (path: SmartUiWeatherPath, payload: JsonObject): SmartUiProjectedField => ({
  path, value: scalarOrNull(payload[path]),
});

const emptyFields = (): SmartUiProjectedField[] => [
  { path: 'location', value: null },
  { path: 'temperature_celsius', value: null },
  { path: 'condition', value: null },
  { path: 'observed_at', value: null },
];

const sourceMatches = (source: SmartUiToolSource, tool: UIMessagePartTool): boolean =>
  source.toolCallId === tool.toolCallId;

/**
 * Rebuild a weather projection from one selected-branch tool part.
 * The caller must provide the conversation/node/message identity from that branch.
 */
export const projectWeatherReadTool = (
  tool: UIMessagePartTool,
  source: SmartUiToolSource,
): SmartUiWeatherProjection | null => {
  if (tool.toolName !== SMART_UI_WEATHER_TOOL_NAME || !sourceMatches(source, tool)) return null;
  const ref: string = weatherBindingRef(tool.toolCallId);
  if (tool.output.length === 0) {
    return { ref, source, status: 'missing', fields: emptyFields() };
  }
  const payload: JsonObject = toolOutputJson(tool);
  if (payload['ok'] !== true || payload['tool'] !== SMART_UI_WEATHER_TOOL_NAME) {
    return { ref, source, status: 'failed', fields: emptyFields() };
  }
  return {
    ref, source, status: 'ready',
    fields: [
      field('location', payload), field('temperature_celsius', payload),
      field('condition', payload), field('observed_at', payload),
    ],
  };
};

export const projectedField = (
  projection: SmartUiWeatherProjection, path: SmartUiWeatherPath,
): SmartUiScalarOrNull => projection.fields.find((item): boolean => item.path === path)?.value ?? null;

/** Host-side check for a text.bind reference; it never resolves arbitrary paths. */
export const resolveWeatherBinding = (
  projection: SmartUiWeatherProjection, ref: string, path: SmartUiWeatherPath,
): SmartUiScalarOrNull => projection.ref === ref && projection.status === 'ready'
  ? projectedField(projection, path) : null;

