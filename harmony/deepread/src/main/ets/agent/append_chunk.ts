// appendChunk — streaming 增量合并,对应 Android UIMessage.appendChunk
// 一次 LLM 流式响应由多个 chunk 组成,每个 chunk 含 delta parts
// appendChunk 把 delta fold 进现有 message parts,保持不可变(返回新数组)

import {
  UIMessage, UIMessagePart, UIMessagePartText, UIMessagePartImage,
  UIMessagePartReasoning, UIMessagePartTool,
} from './message.ts';

// 一次 delta 的 part(和正常 part 同形,字段可为部分)
// 流式协议(OpenAI):每个 delta 含部分字段(如只有 text 增量、toolCallId 后到等)
export type DeltaPart =
  | { kind: 'text'; text: string }
  | { kind: 'image'; url: string }
  | { kind: 'reasoning'; reasoning: string; finishedAt?: number | null }
  | {
      kind: 'tool';
      toolCallId?: string;       // 可能后续 chunk 才到
      toolName?: string;          // 可能流式追加
      input?: string;             // 可能流式追加
      output?: UIMessagePartText[];
      approvalState?: UIMessagePartTool['approvalState'];
      // streamToolIndex 用于早期没 id 时按顺序匹配(Android findToolMergeTarget)
      streamToolIndex?: number;
      // replace 语义:整个 input 替换(arguments.done 事件)
      streamArgsReplace?: boolean;
    };

// 把 delta fold 进 parts 数组,返回新数组(不可变)
export const appendChunk = (parts: UIMessagePart[], delta: DeltaPart): UIMessagePart[] => {
  switch (delta.kind) {
    case 'text':
      return appendText(parts, delta);
    case 'image':
      return appendImage(parts, delta);
    case 'reasoning':
      return appendReasoning(parts, delta);
    case 'tool':
      return appendTool(parts, delta);
  }
};

const appendText = (parts: UIMessagePart[], delta: { text: string }): UIMessagePart[] => {
  if (delta.text.length === 0) return parts;
  const last = parts[parts.length - 1];
  if (last && last.kind === 'text') {
    // 追加到最后一个 Text part
    return [...parts.slice(0, -1), { kind: 'text', text: last.text + delta.text }];
  }
  return [...parts, { kind: 'text', text: delta.text }];
};

const appendImage = (parts: UIMessagePart[], delta: { url: string }): UIMessagePart[] => {
  if (delta.url.length === 0) return parts;
  // Android:连续 image 拼接 url;这里简化为新建 part(Deep Read 流式 image 罕见)
  return [...parts, { kind: 'image', url: delta.url }];
};

const appendReasoning = (
  parts: UIMessagePart[],
  delta: { reasoning: string; finishedAt?: number | null },
): UIMessagePart[] => {
  const last = parts[parts.length - 1];
  // 空推理 + 无关闭信号 = skip(Android 同样行为)
  if (delta.reasoning.length === 0 && delta.finishedAt === undefined) return parts;
  if (last && last.kind === 'reasoning') {
    // 追加 reasoning,保留 createdAt,处理 finishedAt
    return [
      ...parts.slice(0, -1),
      {
        kind: 'reasoning',
        reasoning: last.reasoning + delta.reasoning,
        createdAt: last.createdAt,
        finishedAt: delta.finishedAt ?? null,
      },
    ];
  }
  // 新建 reasoning part
  return [
    ...parts,
    {
      kind: 'reasoning',
      reasoning: delta.reasoning,
      createdAt: Date.now(),
      finishedAt: delta.finishedAt ?? null,
    },
  ];
};

const appendTool = (parts: UIMessagePart[], delta: DeltaPart & { kind: 'tool' }): UIMessagePart[] => {
  if (delta.kind !== 'tool') return parts;
  // 找合并目标:已存在且未执行的 Tool part,按 toolCallId 匹配(或 streamToolIndex 兜底)
  const targetIdx = findToolMergeTarget(parts, delta);
  if (targetIdx < 0) {
    // 新 tool part
    const newPart: UIMessagePartTool = {
      kind: 'tool',
      toolCallId: delta.toolCallId ?? '',
      toolName: delta.streamArgsReplace ? (delta.toolName ?? '') : (delta.toolName ?? ''),
      input: delta.streamArgsReplace ? (delta.input ?? '') : (delta.input ?? ''),
      output: delta.output ?? [],
      approvalState: delta.approvalState ?? 'auto',
    };
    return [...parts, newPart];
  }
  // 合并到现有
  const existing = parts[targetIdx] as UIMessagePartTool;
  const merged = mergeTool(existing, delta);
  return [...parts.slice(0, targetIdx), merged, ...parts.slice(targetIdx + 1)];
};

// 找合并目标(对应 Android findToolMergeTarget)
// 规则:
// 1. 跳过已 executed 的 tool part
// 2. 优先按 toolCallId 匹配(delta id 非空时)
// 3. fallback 按 streamToolIndex
export const findToolMergeTarget = (parts: UIMessagePart[], delta: DeltaPart & { kind: 'tool' }): number => {
  const candidates: { idx: number; part: UIMessagePartTool }[] = [];
  for (let i = 0; i < parts.length; i++) {
    const p = parts[i];
    if (p.kind === 'tool' && p.output.length === 0) {
      candidates.push({ idx: i, part: p });
    }
  }
  // 按 toolCallId
  if (delta.toolCallId && delta.toolCallId.length > 0) {
    const hit = candidates.find(c => c.part.toolCallId === delta.toolCallId);
    if (hit) return hit.idx;
  }
  // 按 streamToolIndex
  if (delta.streamToolIndex !== undefined) {
    const hit = candidates.find(c => extractStreamToolIndex(c.part) === delta.streamToolIndex);
    if (hit) return hit.idx;
  }
  // delta 无 id 无 index:不合并(让上层决定是否新建)
  return -1;
};

// streamToolIndex:OpenAI 流式响应里 tool_calls 数组的索引
// 我们简化:不显式存,推断为"未 executed tool parts 中的位置序号"
const extractStreamToolIndex = (_part: UIMessagePartTool): number => {
  // 简化版:返回 0(单 tool 场景);多 tool 时需要外部维护 index→part 映射
  // 生产实现会维护单独的 index 字段,这里 MVP 兼容单 tool 流式
  return 0;
};

// 合并 tool delta 到现有 tool part(对应 Android Tool.merge)
export const mergeTool = (
  existing: UIMessagePartTool,
  delta: DeltaPart & { kind: 'tool' },
): UIMessagePartTool => {
  const replaceArgs = delta.streamArgsReplace === true;
  return {
    kind: 'tool',
    // 后到的 id 覆盖(OpenAI 行为:首个 chunk 无 id,后续才有)
    toolCallId: (delta.toolCallId && delta.toolCallId.length > 0)
      ? delta.toolCallId
      : existing.toolCallId,
    toolName: replaceArgs
      ? (delta.toolName ?? existing.toolName)
      : existing.toolName + (delta.toolName ?? ''),
    input: replaceArgs
      ? (delta.input ?? existing.input)
      : existing.input + (delta.input ?? ''),
    output: delta.output ? [...existing.output, ...delta.output] : existing.output,
    approvalState: delta.approvalState ?? existing.approvalState,
  };
};
