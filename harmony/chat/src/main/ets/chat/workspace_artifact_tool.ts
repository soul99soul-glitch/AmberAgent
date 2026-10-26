import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessagePart } from './message.ts';
import type { AgentToolActivityStore } from './tool_activity.ts';
import { toolInputInt } from './workspace.ts';

// ===== schema/helpers(:792-830) =====

export const stringProp = (description: string): JsonObject => ({ type: 'string', description });

export const booleanProp = (description: string): JsonObject => ({ type: 'boolean', description });

export const integerProp = (description: string): JsonObject => ({ type: 'integer', description });

export const objectProp = (description: string): JsonObject => ({ type: 'object', description });

export const arrayProp = (description: string): JsonObject => ({
  type: 'array', description, items: { type: 'string' },
});

export const textJson = (payload: JsonObject): UIMessagePart[] =>
  [{ type: 'text', text: JSON.stringify(payload), metadata: null }];

// :826-827
export const inputLimit = (input: JsonValue, name: string, def: number, max: number): number => {
  const v: number = toolInputInt(input, name) ?? def;
  return Math.min(Math.max(v, 1), max);
};

// :829-830 — jsonPrimitive.contentOrNull 非 blank 才收
export const jsonArrayStrings = (value: JsonValue | undefined): string[] => {
  if (!Array.isArray(value)) return [];
  const out: string[] = [];
  value.forEach((v: JsonValue): void => {
    if (typeof v === 'string' && v.length > 0 && v.trim().length > 0) out.push(v);
    else if (typeof v === 'number' || typeof v === 'boolean') {
      const s: string = String(v);
      if (s.trim().length > 0) out.push(s);
    }
  });
  return out;
};
// :832-838
const previewText = (parts: UIMessagePart[]): string =>
  parts.map((p: UIMessagePart): string => p.type === 'text' ? p.text : JSON.stringify(p))
    .join('\n')
    .slice(-1600);

export type ArtifactToolTracker = (
  toolName: string, title: string, input: JsonValue, runtime: string,
  block: () => Promise<UIMessagePart[]>,
) => Promise<UIMessagePart[]>;

export const createArtifactToolTracker = (activityStore: AgentToolActivityStore): ArtifactToolTracker => {
  // :408-430
  return async (
    toolName: string, title: string, input: JsonValue, runtime: string,
    block: () => Promise<UIMessagePart[]>,
  ): Promise<UIMessagePart[]> => {
    const toolCallId: string = activityStore.startTool(
      toolName, title, JSON.stringify(input).slice(0, 1200), runtime, '/workspace');
    try {
      const result: UIMessagePart[] = await block();
      activityStore.complete(toolCallId, previewText(result));
      return result;
    } catch (e) {
      activityStore.fail(toolCallId, e instanceof Error ? e : new Error(String(e)));
      throw e;
    }
  };
};
