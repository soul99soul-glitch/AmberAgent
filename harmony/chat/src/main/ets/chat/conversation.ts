// MessageNode / Conversation — HarmonyOS port of
// core/model/src/main/kotlin/app/amber/core/model/Conversation.kt
//        + ai/ui/Message.kt 的 limitContext/finishReasoning/finishPendingTools
//
// 分支语义(DATA_SCHEMA_MATRIX J02):
//   一个 node 的 messages 数组 = 该位置的全部替代分支(alternatives),
//   selectIndex = 当前选中分支;regenerate 追加不覆盖。
// 持久化注意:isFavorite/newConversation 在 Android 是 @Transient(不落库),此处不建模。

import type { UIMessage, UIMessagePart, UIMessagePartTool, MessageRole } from './message.ts';
import { getTools, isToolExecuted } from './message.ts';
import { newId, nowIso } from './ids.ts';

// Android Assistant.kt:290 固定值
export const DEFAULT_ASSISTANT_ID: string = '0950e2dc-9bd5-4801-afa3-aa887aa36b4e';

// ===== MessageNode =====

export interface MessageNode {
  id: string;
  messages: UIMessage[];
  selectIndex: number;
}

export const makeMessageNode = (messages: UIMessage[], selectIndex: number = 0, id?: string): MessageNode => ({
  id: id ?? newId(),
  messages,
  selectIndex,
});

export const toMessageNode = (msg: UIMessage): MessageNode => makeMessageNode([msg], 0);

// currentMessage(Conversation.kt:146):无效状态抛异常(Android IllegalStateException)
export const nodeCurrentMessage = (node: MessageNode): UIMessage => {
  if (node.messages.length === 0 || node.selectIndex < 0 || node.selectIndex >= node.messages.length) {
    throw new Error(
      `MessageNode has no valid current message: messages.size=${node.messages.length}, selectIndex=${node.selectIndex}`,
    );
  }
  return node.messages[node.selectIndex];
};

// role(Conversation.kt:152):首条消息 role,空节点默认 user
export const nodeRole = (node: MessageNode): MessageRole =>
  node.messages.length > 0 ? node.messages[0].role : 'user';

// ===== Conversation =====

export interface Conversation {
  id: string;
  assistantId: string;
  title: string;
  messageNodes: MessageNode[];
  chatSuggestions: string[];
  isPinned: boolean;
  autoApproveToolCalls: boolean;
  createAt: string;
  updateAt: string;
}

export const makeConversation = (
  id: string,
  messageNodes: MessageNode[] = [],
  opts: Partial<Omit<Conversation, 'id' | 'messageNodes'>> = {},
): Conversation => ({
  id,
  assistantId: opts.assistantId ?? DEFAULT_ASSISTANT_ID,
  title: opts.title ?? '',
  messageNodes,
  chatSuggestions: opts.chatSuggestions ?? [],
  isPinned: opts.isPinned ?? false,
  autoApproveToolCalls: opts.autoApproveToolCalls ?? false,
  createAt: opts.createAt ?? nowIso(),
  updateAt: opts.updateAt ?? nowIso(),
});

// patchConversation:不可变更新(语义 = Kotlin data class copy)
// 鸿蒙增补工具:ArkTS entry(.ets)禁用接口对象展开(arkts-no-spread),
// 与 patchAssistant(D-027)同一系统性修法
export const patchConversation = (
  base: Conversation, patch: Partial<Omit<Conversation, 'id'>>,
): Conversation => ({
  ...base,
  ...patch,
});

// currentMessages(Conversation.kt:43):每个 node 取 selectIndex 处消息
export const currentMessages = (conv: Conversation): UIMessage[] =>
  conv.messageNodes.map((n: MessageNode): UIMessage => n.messages[n.selectIndex]);

export const getMessageNodeByMessageId = (conv: Conversation, messageId: string): MessageNode | null =>
  conv.messageNodes.find((n: MessageNode): boolean =>
    n.messages.some((m: UIMessage): boolean => m.id === messageId)) ?? null;

// updateCurrentMessages(Conversation.kt:55):
//   - 新 index → 追加新 node
//   - 同 id → 原位替换,selectIndex 不变
//   - 未知 id → 追加为新分支,selectIndex 指向新分支
//   - 同引用同 selectIndex → 跳过(引用保持短路,流式刷新时历史节点引用稳定,
//     对应 Android @Immutable 跳过重组的语义;ArkUI 侧配合扁平快照使用)
//   - 全部无变化 → 返回 this(Conversation 引用保持)
export const updateCurrentMessages = (conv: Conversation, messages: UIMessage[]): Conversation => {
  const newNodes: MessageNode[] = [...conv.messageNodes];
  let anyNodeChanged = false;

  messages.forEach((message: UIMessage, index: number): void => {
    const existingNode: MessageNode | undefined = newNodes[index];
    if (existingNode === undefined) {
      newNodes.push(toMessageNode(message));
      anyNodeChanged = true;
      return;
    }
    const existingIdx: number = existingNode.messages.findIndex((m: UIMessage): boolean => m.id === message.id);
    if (existingIdx >= 0
      && existingNode.messages[existingIdx] === message
      && existingNode.selectIndex === existingIdx) {
      return; // 引用短路:node 无任何可观察变化
    }
    const newMessages: UIMessage[] = [...existingNode.messages];
    let newMessageIndex: number = existingNode.selectIndex;
    if (existingIdx >= 0) {
      newMessages[existingIdx] = message;
    } else {
      newMessages.push(message);
      newMessageIndex = newMessages.length - 1;
    }
    newNodes[index] = { ...existingNode, messages: newMessages, selectIndex: newMessageIndex };
    anyNodeChanged = true;
  });

  if (!anyNodeChanged && newNodes.length === conv.messageNodes.length) {
    return conv;
  }
  return { ...conv, messageNodes: newNodes };
};

// ===== limitContext(ai/ui/Message.kt:287) =====
// 截断到尾部 size 条,但起点不能孤儿化 tool 依赖:
//   - 起点含已执行 tool(有 output)→ 前扩到对应未执行 tool call 所在消息
//   - 起点含未执行 tool call → 前扩到最近 user 消息
export const limitContext = (messages: UIMessage[], size: number): UIMessage[] => {
  if (size <= 0 || messages.length <= size) return messages;

  const startIndex: number = messages.length - size;
  let adjustedStartIndex: number = startIndex;
  let needsAdjustment = true;
  const visitedIndices = new Set<number>();

  while (needsAdjustment && adjustedStartIndex > 0) {
    needsAdjustment = false;
    if (visitedIndices.has(adjustedStartIndex)) break;
    visitedIndices.add(adjustedStartIndex);

    const currentMessage: UIMessage = messages[adjustedStartIndex];
    const tools: UIMessagePartTool[] = getTools(currentMessage);

    if (tools.some((t: UIMessagePartTool): boolean => isToolExecuted(t))) {
      for (let i = adjustedStartIndex - 1; i >= 0; i--) {
        if (getTools(messages[i]).some((t: UIMessagePartTool): boolean => !isToolExecuted(t))) {
          adjustedStartIndex = i;
          needsAdjustment = true;
          break;
        }
      }
    }

    if (tools.some((t: UIMessagePartTool): boolean => !isToolExecuted(t))) {
      for (let i = adjustedStartIndex - 1; i >= 0; i--) {
        if (messages[i].role === 'user') {
          adjustedStartIndex = i;
          needsAdjustment = true;
          break;
        }
      }
    }
  }

  return messages.slice(adjustedStartIndex);
};

// ===== finishReasoning / finishPendingTools(Message.kt:551-590) =====

export const finishReasoning = (msg: UIMessage): UIMessage => ({
  ...msg,
  parts: msg.parts.map((p: UIMessagePart): UIMessagePart =>
    p.type === 'reasoning' && p.finishedAt === null
      ? { ...p, finishedAt: nowIso() }
      : p),
});

// 对所有未执行 tool 应用 transform;有变化时同时设置 finishedAt 并关闭 reasoning
export const finishPendingTools = (
  msg: UIMessage,
  transform: (t: UIMessagePartTool) => UIMessagePartTool,
): UIMessage => {
  let changed = false;
  const updatedParts: UIMessagePart[] = msg.parts.map((p: UIMessagePart): UIMessagePart => {
    if (p.type === 'tool' && !isToolExecuted(p)) {
      changed = true;
      return transform(p);
    }
    return p;
  });
  if (!changed) return msg;
  return finishReasoning({ ...msg, parts: updatedParts, finishedAt: nowIso() });
};

// ===== 文件引用收集(Conversation.kt:172-184) =====
// 递归展开所有 parts(含 tool output 嵌套),收集 file:// 前缀的 url

const collectAllParts = (parts: UIMessagePart[]): UIMessagePart[] => {
  const nested: UIMessagePart[] = parts
    .filter((p: UIMessagePart): p is UIMessagePartTool => p.type === 'tool')
    .flatMap((t: UIMessagePartTool): UIMessagePart[] => collectAllParts(t.output));
  return [...parts, ...nested];
};

const partFileUrl = (p: UIMessagePart): string | null => {
  switch (p.type) {
    case 'image':
    case 'document':
    case 'video':
    case 'audio':
      return p.url.startsWith('file://') ? p.url : null;
    default:
      return null;
  }
};

export const collectFileUrls = (conv: Conversation): string[] => {
  const allParts: UIMessagePart[] = collectAllParts(
    conv.messageNodes.flatMap((n: MessageNode): UIMessagePart[] =>
      n.messages.flatMap((m: UIMessage): UIMessagePart[] => m.parts)),
  );
  const urls: string[] = [];
  for (const p of allParts) {
    const u = partFileUrl(p);
    if (u !== null) urls.push(u);
  }
  return urls;
};
