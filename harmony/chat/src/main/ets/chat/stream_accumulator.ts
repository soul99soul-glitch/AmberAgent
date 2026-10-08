// MessageStreamAccumulator — HarmonyOS port of ai/ui/MessageStreamAccumulator.kt(主路径)
//        + ai/ui/Message.kt 的 appendChunk / handleMessageChunk(不可变变体)
//
// 语义(STREAMING_SEMANTICS_MATRIX merge_rule):
//   - delta==null && message!=null → replaceActive 整体替换
//   - role 变化 → 旧 active 封存进 prefix,开新 active(带 modelId)
//   - Text append(空跳过,metadata 后者非空优先)
//   - Image append(新建加 data:image/png;base64, 前缀)
//   - Reasoning append + 关闭规则(无 reasoning 内容且有关闭型 part → 全部打 finishedAt)
//   - Tool 合并(findToolMergeTarget + mergeTool,见 tool_merge.ts)
//   - annotations append + distinct,绝不整体替换
//   - usage 合并(mergeUsage)
//   - snapshot 经 coalesceStreamParts 规整
//
// 已知 Android 内部不一致(记 PARITY_DEBT 待核实):
//   reasoning 内容追加时,appendChunk 强制 finishedAt=null;
//   accumulator 采用 delta 的 finishedAt(可能非 null)。两者各自忠实实现。

import type {
  UIMessage, UIMessagePart, UIMessagePartText, UIMessagePartImage,
  UIMessagePartReasoning, UIMessagePartTool, MessageChunk, UIMessageAnnotation,
} from './message.ts';
import {
  makeUIMessage, hasProtocolReasoningContent, reasoningBlocksCanMerge, mergeReasoningMetadata,
  CLAUDE_THINKING_BLOCK_INDEX_METADATA_KEY,
} from './message.ts';
import type { JsonObject } from './json.ts';
import { mergeUsage } from './usage.ts';
import { nowIso } from './ids.ts';
import {
  findToolMergeTarget, mergeTool, withoutStreamArgsReplace,
  hasExplicitReasoningContentField,
} from './tool_merge.ts';
import type { TokenUsage } from './usage.ts';

// ===== 判定助手(MessageStreamAccumulator.kt:253-262) =====

const isReasoningContentDelta = (p: UIMessagePart): boolean =>
  p.type === 'reasoning' && p.reasoning.length > 0;

const isReasoningCloseDelta = (p: UIMessagePart): boolean => {
  switch (p.type) {
    case 'reasoning': return p.finishedAt !== null;
    case 'text': return p.text.length > 0;
    case 'image': return p.url.length > 0;
    case 'tool': return true;
    default: return false;
  }
};

const findReasoningTarget = (parts: UIMessagePart[], incoming: UIMessagePartReasoning): number => {
  for (const key of [CLAUDE_THINKING_BLOCK_INDEX_METADATA_KEY, 'reasoning_id']) {
    const identity = incoming.metadata?.[key];
    if (identity !== undefined) {
      // Claude block indexes restart at zero on each tool continuation. Finished
      // blocks belong to earlier requests; Responses IDs remain unique and may
      // receive encrypted metadata after their visible summary has closed.
      return parts.findIndex((part: UIMessagePart): boolean => part.type === 'reasoning' &&
        (key !== CLAUDE_THINKING_BLOCK_INDEX_METADATA_KEY || part.finishedAt === null) &&
        part.metadata?.[key] === identity && reasoningBlocksCanMerge(part.metadata, incoming.metadata));
    }
  }
  const last = parts[parts.length - 1];
  return last?.type === 'reasoning' && reasoningBlocksCanMerge(last.metadata, incoming.metadata)
    ? parts.length - 1 : -1;
};

// ===== coalesceStreamParts(MessageStreamAccumulator.kt:264-320) =====
// 合并相邻 Text、丢弃空 reasoning(带 explicit 标记的空 reasoning 保留一个占位)
export const coalesceStreamParts = (parts: UIMessagePart[]): UIMessagePart[] => {
  const result: UIMessagePart[] = [];
  // 用 holder 对象而非裸 let: 闭包内的赋值会使 TS 对裸 let 的控制流收窄失效(tsc TS2698)
  const pending: { text: UIMessagePartText | null; emptyReasoning: UIMessagePartReasoning | null } = {
    text: null,
    emptyReasoning: null,
  };

  const flushText = (): void => {
    if (pending.text !== null) result.push(pending.text);
    pending.text = null;
  };

  const flushExplicitEmptyReasoning = (): void => {
    const marker = pending.emptyReasoning;
    if (marker === null) return;
    const already = result.some(
      (p: UIMessagePart): boolean => p.type === 'reasoning' && hasExplicitReasoningContentField(p.metadata),
    );
    if (!already) result.push(marker);
    pending.emptyReasoning = null;
  };

  for (const part of parts) {
    if (part.type === 'text') {
      if (part.text.length === 0) continue;
      const prev: UIMessagePartText | null = pending.text;
      if (prev === null) {
        pending.text = part;
      } else {
        pending.text = {
          ...prev,
          text: prev.text + part.text,
          metadata: part.metadata !== null ? part.metadata : prev.metadata,
        };
      }
    } else if (part.type === 'reasoning') {
      if (part.reasoning.trim().length === 0) {
        if (hasProtocolReasoningContent(part.metadata)) {
          flushText();
          flushExplicitEmptyReasoning();
          result.push(part);
        } else if (hasExplicitReasoningContentField(part.metadata)) {
          if (pending.emptyReasoning === null) pending.emptyReasoning = part;
        }
      } else {
        flushText();
        pending.emptyReasoning = null;
        result.push(part);
      }
    } else {
      flushText();
      flushExplicitEmptyReasoning();
      result.push(part);
    }
  }
  flushText();
  flushExplicitEmptyReasoning();
  return result;
};

// ===== 内部可变消息(对应私有 MutableMessage/MutablePart) =====

interface MutableReasoning {
  reasoning: string;
  createdAt: string;
  finishedAt: string | null;
  metadata: JsonObject | null;
}

class MutableMessage {
  readonly role: UIMessage['role'];
  private readonly source: UIMessage;
  private parts: UIMessagePart[];
  private annotations: UIMessageAnnotation[];
  usage: TokenUsage | null;

  constructor(source: UIMessage) {
    this.source = source;
    this.role = source.role;
    this.parts = [...source.parts];
    this.annotations = [...source.annotations];
    this.usage = source.usage;
  }

  append(delta: UIMessage): void {
    const hadReasoning: boolean = this.parts.some((p: UIMessagePart): boolean => p.type === 'reasoning');
    const deltaHasReasoningContent: boolean = delta.parts.some(isReasoningContentDelta);
    const deltaClosesReasoning: boolean = delta.parts.some(isReasoningCloseDelta);

    for (const deltaPart of delta.parts) {
      switch (deltaPart.type) {
        case 'text': this.appendText(deltaPart); break;
        case 'image': this.appendImage(deltaPart); break;
        case 'reasoning': this.appendReasoning(deltaPart); break;
        case 'tool': this.appendTool(deltaPart); break;
        default: break; // 其余 part 类型流式 append 不支持(对齐 Android println 后跳过)
      }
    }

    if (hadReasoning && !deltaHasReasoningContent && deltaClosesReasoning) {
      this.parts = this.parts.map((p: UIMessagePart): UIMessagePart =>
        p.type === 'reasoning' && p.finishedAt === null
          ? { ...p, finishedAt: nowIso() }
          : p);
    }

    if (delta.annotations.length > 0) {
      // append + dedupe:grounding/citation 可能增量到达或重发全量,整体替换会丢先前条目
      const merged: UIMessageAnnotation[] = [...this.annotations];
      for (const a of delta.annotations) {
        if (!merged.some((x: UIMessageAnnotation): boolean => JSON.stringify(x) === JSON.stringify(a))) {
          merged.push(a);
        }
      }
      this.annotations = merged;
    }
  }

  snapshot(): UIMessage {
    return {
      ...this.source,
      parts: coalesceStreamParts(this.parts),
      annotations: this.annotations,
      usage: this.usage,
    };
  }

  private appendText(deltaPart: UIMessagePartText): void {
    if (deltaPart.text.length === 0) return;
    const last: UIMessagePart | undefined = this.parts[this.parts.length - 1];
    if (last !== undefined && last.type === 'text') {
      const merged: UIMessagePartText = {
        ...last,
        text: last.text + deltaPart.text,
        metadata: deltaPart.metadata !== null ? deltaPart.metadata : last.metadata,
      };
      this.parts[this.parts.length - 1] = merged;
    } else {
      this.parts.push({ ...deltaPart });
    }
  }

  private appendImage(deltaPart: UIMessagePartImage): void {
    const last: UIMessagePart | undefined = this.parts[this.parts.length - 1];
    if (last !== undefined && last.type === 'image') {
      const merged: UIMessagePartImage = {
        ...last,
        url: last.url + deltaPart.url,
        metadata: deltaPart.metadata !== null ? deltaPart.metadata : last.metadata,
      };
      this.parts[this.parts.length - 1] = merged;
    } else {
      this.parts.push({
        ...deltaPart,
        // 已是完整 data URI(如 JPEG/WebP)保持原样,只给裸 base64 补 PNG 前缀,
        // 避免拼出 data:image/png;base64,data:image/jpeg;... 双前缀
        url: deltaPart.url.startsWith('data:')
          ? deltaPart.url
          : `data:image/png;base64,${deltaPart.url}`,
      });
    }
  }

  private appendReasoning(deltaPart: UIMessagePartReasoning): void {
    // 对齐 Android:空 reasoning 且无 metadata → 跳过(finishedAt 由关闭规则统一打 now)
    if (deltaPart.reasoning.length === 0 && deltaPart.metadata === null) return;
    const targetIndex = findReasoningTarget(this.parts, deltaPart);
    const last = this.parts[targetIndex];
    if (last !== undefined && last.type === 'reasoning') {
      const merged: MutableReasoning = {
        reasoning: last.reasoning + deltaPart.reasoning,
        createdAt: last.createdAt,
        finishedAt: last.finishedAt,
        metadata: mergeReasoningMetadata(last.metadata, deltaPart.metadata),
      };
      if (deltaPart.reasoning.length > 0) {
        // accumulator 语义:内容追加采用 delta 的 finishedAt(可能为 null)
        merged.finishedAt = deltaPart.finishedAt;
      } else if (deltaPart.finishedAt !== null) {
        merged.finishedAt = deltaPart.finishedAt;
      }
      this.parts[targetIndex] = {
        type: 'reasoning',
        reasoning: merged.reasoning,
        createdAt: merged.createdAt,
        finishedAt: merged.finishedAt,
        metadata: merged.metadata,
      };
    } else {
      this.parts.push({ ...deltaPart });
    }
  }

  private appendTool(deltaPart: UIMessagePartTool): void {
    const toolParts: UIMessagePartTool[] = this.parts.filter(
      (p: UIMessagePart): p is UIMessagePartTool => p.type === 'tool');
    const target: UIMessagePartTool | null = findToolMergeTarget(deltaPart, toolParts);
    if (target === null) {
      this.parts.push(withoutStreamArgsReplace(deltaPart));
      return;
    }
    const merged: UIMessagePartTool = mergeTool(target, deltaPart);
    this.parts = this.parts.map((p: UIMessagePart): UIMessagePart => (p === target ? merged : p));
  }
}

// ===== 主类 =====

export class MessageStreamAccumulator {
  private readonly prefix: UIMessage[];
  private active: MutableMessage;
  private readonly modelId: string | null;

  constructor(initialMessages: UIMessage[], modelId: string | null = null) {
    if (initialMessages.length === 0) {
      throw new Error('messages must not be empty');
    }
    this.prefix = initialMessages.slice(0, initialMessages.length - 1);
    this.active = new MutableMessage(initialMessages[initialMessages.length - 1]);
    this.modelId = modelId;
  }

  append(chunk: MessageChunk): void {
    const choice = chunk.choices.length > 0 ? chunk.choices[0] : undefined;
    if (choice === undefined) {
      // usage-only 尾块(choices:[] + usage,stream_options.include_usage 的常见形态):
      // 不能整块丢弃,否则流式对话 token 统计丢失
      if (chunk.usage !== null) {
        this.active.usage = mergeUsage(this.active.usage, chunk.usage);
      }
      return;
    }
    const finalMessage = choice.message;
    if (choice.delta === null && finalMessage !== null) {
      this.replaceActive(finalMessage);
      if (chunk.usage !== null) {
        this.active.usage = mergeUsage(this.active.usage, chunk.usage);
      }
      return;
    }
    const delta = choice.delta !== null ? choice.delta : finalMessage;
    if (delta === null) {
      // finish-only chunk(无 delta/message):usage 仍须合并且 active 需收口
      if (chunk.usage !== null) {
        this.active.usage = mergeUsage(this.active.usage, chunk.usage);
      }
      return;
    }

    if (this.active.role !== delta.role) {
      this.prefix.push(this.active.snapshot());
      this.active = new MutableMessage(makeUIMessage(delta.role, [], { modelId: this.modelId }));
    }

    this.active.append(delta);
    if (chunk.usage !== null) {
      this.active.usage = mergeUsage(this.active.usage, chunk.usage);
    }
  }

  snapshot(): UIMessage[] {
    return [...this.prefix, this.active.snapshot()];
  }

  private replaceActive(message: UIMessage): void {
    const replacement: UIMessage = message.modelId !== null ? message : { ...message, modelId: this.modelId };
    if (this.active.role !== replacement.role) {
      this.prefix.push(this.active.snapshot());
    }
    this.active = new MutableMessage(replacement);
  }
}

// ===== 不可变变体 appendChunk(Message.kt:50-153 忠实) =====

export const appendChunkToMessage = (msg: UIMessage, chunk: MessageChunk): UIMessage => {
  const choice = chunk.choices.length > 0 ? chunk.choices[0] : undefined;
  if (choice === undefined) {
    if (chunk.usage === null) return msg;
    return { ...msg, usage: mergeUsage(msg.usage, chunk.usage) };
  }
  const delta = choice.delta !== null ? choice.delta : choice.message;
  if (delta === null) {
    // finish-only chunk:无内容但可能携带 usage,不能整块丢
    if (chunk.usage === null) return msg;
    return { ...msg, usage: mergeUsage(msg.usage, chunk.usage) };
  }

  let newParts: UIMessagePart[] = msg.parts;
  for (const deltaPart of delta.parts) {
    newParts = appendPartImmutable(newParts, deltaPart);
  }

  // reasoning 关闭规则(与 accumulator 相同)
  const hadReasoning: boolean = msg.parts.some((p: UIMessagePart): boolean => p.type === 'reasoning');
  const deltaHasReasoningContent: boolean = delta.parts.some(isReasoningContentDelta);
  const deltaClosesReasoning: boolean = delta.parts.some(isReasoningCloseDelta);
  if (hadReasoning && !deltaHasReasoningContent && deltaClosesReasoning) {
    newParts = newParts.map((p: UIMessagePart): UIMessagePart =>
      p.type === 'reasoning' && p.finishedAt === null
        ? { ...p, finishedAt: nowIso() }
        : p);
  }

  // annotations: append + dedupe
  let newAnnotations: UIMessageAnnotation[] = msg.annotations;
  if (delta.annotations.length > 0) {
    const merged: UIMessageAnnotation[] = [...msg.annotations];
    for (const a of delta.annotations) {
      if (!merged.some((x: UIMessageAnnotation): boolean => JSON.stringify(x) === JSON.stringify(a))) {
        merged.push(a);
      }
    }
    newAnnotations = merged;
  }

  return {
    ...msg,
    parts: newParts,
    annotations: newAnnotations,
    usage: chunk.usage !== null ? mergeUsage(msg.usage, chunk.usage) : msg.usage,
  };
};

const appendPartImmutable = (acc: UIMessagePart[], deltaPart: UIMessagePart): UIMessagePart[] => {
  switch (deltaPart.type) {
    case 'text': {
      if (deltaPart.text.length === 0) return acc;
      const last = acc[acc.length - 1];
      if (last !== undefined && last.type === 'text') {
        return [...acc.slice(0, -1), {
          ...last,
          text: last.text + deltaPart.text,
          metadata: deltaPart.metadata !== null ? deltaPart.metadata : last.metadata,
        }];
      }
      return [...acc, deltaPart];
    }
    case 'image': {
      const last = acc[acc.length - 1];
      if (last !== undefined && last.type === 'image') {
        return [...acc.slice(0, -1), {
          ...last,
          url: last.url + deltaPart.url,
          metadata: deltaPart.metadata !== null ? deltaPart.metadata : last.metadata,
        }];
      }
      return [...acc, { ...deltaPart, url: deltaPart.url.startsWith('data:')
        ? deltaPart.url
        : `data:image/png;base64,${deltaPart.url}` }];
    }
    case 'reasoning': {
      if (deltaPart.reasoning.length === 0 && deltaPart.metadata === null) return acc;
      const targetIndex = findReasoningTarget(acc, deltaPart);
      const last = acc[targetIndex];
      if (last !== undefined && last.type === 'reasoning') {
        // appendChunk 语义(Message.kt:98-104):内容追加强制 finishedAt=null
        const merged: UIMessagePartReasoning = {
          type: 'reasoning',
          reasoning: last.reasoning + deltaPart.reasoning,
          createdAt: last.createdAt,
          finishedAt: deltaPart.reasoning.length > 0 ? null : deltaPart.finishedAt ?? last.finishedAt,
          metadata: mergeReasoningMetadata(last.metadata, deltaPart.metadata),
        };
        return acc.map((part: UIMessagePart, index: number): UIMessagePart => index === targetIndex ? merged : part);
      }
      return [...acc, deltaPart];
    }
    case 'tool': {
      const tools: UIMessagePartTool[] = acc.filter((p: UIMessagePart): p is UIMessagePartTool => p.type === 'tool');
      const target: UIMessagePartTool | null = findToolMergeTarget(deltaPart, tools);
      if (target === null) {
        return [...acc, withoutStreamArgsReplace(deltaPart)];
      }
      return acc.map((p: UIMessagePart): UIMessagePart => (p === target ? mergeTool(target, deltaPart) : p));
    }
    default:
      return acc;
  }
};

// ===== handleMessageChunk(Message.kt:236-248) =====

export const handleMessageChunk = (
  messages: UIMessage[],
  chunk: MessageChunk,
  modelId: string | null = null,
): UIMessage[] => {
  if (messages.length === 0) {
    throw new Error('messages must not be empty');
  }
  const choice = chunk.choices.length > 0 ? chunk.choices[0] : undefined;
  if (choice === undefined) {
    if (chunk.usage === null) return messages;
    const last = messages[messages.length - 1];
    return [...messages.slice(0, -1), { ...last, usage: mergeUsage(last.usage, chunk.usage) }];
  }
  const message = choice.delta !== null ? choice.delta : choice.message;
  if (message === null) return messages;
  const last = messages[messages.length - 1];
  if (last.role !== message.role) {
    const fresh = makeUIMessage(message.role, [], { modelId });
    return [...messages, appendChunkToMessage(fresh, chunk)];
  }
  return [...messages.slice(0, -1), appendChunkToMessage(last, chunk)];
};
