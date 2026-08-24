// Tool 流式合并 — HarmonyOS port of ai/ui/Message.kt:457-549 + ReasoningMetadata.kt
//
// 规格(STREAMING_SEMANTICS_MATRIX merge_rule):
//   - findToolMergeTarget: 排除已执行;非空 id 先按 id 失败按 streamToolIndex;
//     blank id 只按 index;无 id 无 index 回退最后一个未执行;blank+index 找不到 → null(新建)
//   - merge: 默认 append(toolName/input 拼接,后到非空 id 采纳);
//     stream_tool_args_replace → replace 整体替换 input(空 input 不覆盖);
//     replace 标记随合并剥离不持久化

import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessagePartTool } from './message.ts';
import { isToolExecuted } from './message.ts';

// 元数据 key(Message.kt:24-25)
export const STREAM_TOOL_INDEX_METADATA_KEY = 'stream_tool_index';
export const STREAM_TOOL_ARGS_REPLACE_METADATA_KEY = 'stream_tool_args_replace';

// reasoning 显式空标记(ReasoningMetadata.kt:8)
export const REASONING_CONTENT_PRESENT_METADATA_KEY = 'reasoning_content_present';

const metadataNumber = (m: JsonObject | null, key: string): number | null => {
  if (m === null) return null;
  const v: JsonValue | undefined = m[key];
  return typeof v === 'number' ? v : null;
};

const metadataBoolean = (m: JsonObject | null, key: string): boolean => {
  if (m === null) return false;
  const v: JsonValue | undefined = m[key];
  return v === true;
};

// streamToolIndex:tool 卡片稳定 key(toolCallId 为空时的回退)
export const streamToolIndex = (t: UIMessagePartTool): number | null =>
  metadataNumber(t.metadata, STREAM_TOOL_INDEX_METADATA_KEY);


const withMetadata = (t: UIMessagePartTool, key: string, value: JsonValue): UIMessagePartTool => ({
  ...t,
  metadata: { ...(t.metadata ?? {}), [key]: value },
});

// 标记并行 tool delta 的归属(provider parser 用)
export const withStreamToolIndex = (t: UIMessagePartTool, index: number): UIMessagePartTool =>
  withMetadata(t, STREAM_TOOL_INDEX_METADATA_KEY, index);

// 标记该 delta 的 input 为 replace 语义(arguments.done / final message 全量 args)
export const withStreamArgsReplace = (t: UIMessagePartTool): UIMessagePartTool =>
  withMetadata(t, STREAM_TOOL_ARGS_REPLACE_METADATA_KEY, true);

export const isStreamArgsReplace = (t: UIMessagePartTool): boolean =>
  metadataBoolean(t.metadata, STREAM_TOOL_ARGS_REPLACE_METADATA_KEY);

// 剥离 replace 控制标记:merge 结果与新建 part 都不应携带流式控制元数据
export const withoutStreamArgsReplace = (t: UIMessagePartTool): UIMessagePartTool => {
  if (t.metadata === null) return { ...t };
  if (!(STREAM_TOOL_ARGS_REPLACE_METADATA_KEY in t.metadata)) return { ...t };
  const cleaned: JsonObject = {};
  for (const k of Object.keys(t.metadata)) {
    if (k !== STREAM_TOOL_ARGS_REPLACE_METADATA_KEY) cleaned[k] = t.metadata[k];
  }
  return { ...t, metadata: Object.keys(cleaned).length > 0 ? cleaned : null };
};

// findToolMergeTarget(Message.kt:533-549)
export const findToolMergeTarget = (
  delta: UIMessagePartTool,
  tools: UIMessagePartTool[],
): UIMessagePartTool | null => {
  // 已执行(有 output)的 tool 已封口:多轮 tool 循环共用同一条 assistant 消息,
  // 第二轮 stream index 从 0 重计,不排除已执行项会把新一轮 delta 串进上一轮
  const candidates: UIMessagePartTool[] = tools.filter((t: UIMessagePartTool): boolean => !isToolExecuted(t));
  const index: number | null = streamToolIndex(delta);
  const byStreamIndex: UIMessagePartTool | null = index !== null
    ? (candidates.find((t: UIMessagePartTool): boolean => streamToolIndex(t) === index) ?? null)
    : null;
  if (delta.toolCallId.trim().length === 0) {
    if (byStreamIndex !== null) return byStreamIndex;
    return index === null ? (candidates.length > 0 ? candidates[candidates.length - 1] : null) : null;
  }
  return candidates.find((t: UIMessagePartTool): boolean => t.toolCallId === delta.toolCallId) ?? byStreamIndex;
};

// Tool.merge(Message.kt:457-477)
export const mergeTool = (target: UIMessagePartTool, other: UIMessagePartTool): UIMessagePartTool => {
  const replaceArgs: boolean = isStreamArgsReplace(other);
  const incoming: UIMessagePartTool = replaceArgs ? withoutStreamArgsReplace(other) : other;
  let input: string;
  if (!replaceArgs) {
    input = target.input + incoming.input;
  } else if (incoming.input.trim().length > 0) {
    // replace 不允许空内容抹掉已累积参数(异常 provider 兜底)
    input = incoming.input;
  } else {
    input = target.input;
  }
  return {
    type: 'tool',
    // OpenAI 流式 tool delta 真实 id 可能晚于首个(blank id)delta 到达,必须采纳后到的非空 id
    toolCallId: target.toolCallId.trim().length > 0 ? target.toolCallId : incoming.toolCallId,
    toolName: replaceArgs
      ? (incoming.toolName.trim().length > 0 ? incoming.toolName : target.toolName)
      : target.toolName + incoming.toolName,
    input,
    output: [...target.output, ...incoming.output],
    approvalState: target.approvalState,
    metadata: incoming.metadata !== null ? incoming.metadata : target.metadata,
  };
};

// hasExplicitReasoningContentField(ReasoningMetadata.kt:14)
export const hasExplicitReasoningContentField = (metadata: JsonObject | null): boolean =>
  metadataBoolean(metadata, REASONING_CONTENT_PRESENT_METADATA_KEY);
