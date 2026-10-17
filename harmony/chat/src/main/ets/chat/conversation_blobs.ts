// conversation_blobs — 会话大图片的内容寻址外置(纯逻辑 + 文件 Port)。
//
// 目标:消息里的 data:image 大字符串不再整段写进 RDB;保存时换成
// `blob://<sha256>` 引用,读取时还原。文件缺失时还原为带标记的占位
// (1x1 PNG),请求期由 tool image budget 转成文本占位,不把坏图发给模型。
//
// 约束:只处理 image part(包括 tool output 内嵌图片);文本、工具输入和
// 其他 part 原样保留。读/写都返回新对象,不修改持久化快照。

import type { JsonObject, JsonValue } from './json.ts';
import type {
  Conversation, MessageNode,
} from './conversation.ts';
import type {
  UIMessage, UIMessagePart, UIMessagePartImage, UIMessagePartTool,
} from './message.ts';

export const CHAT_BLOB_PREFIX: string = 'blob://';
export const CHAT_BLOB_MISSING_URL: string = 'blob://missing';
export const CHAT_BLOB_MISSING_METADATA_KEY: string = 'amber_missing_blob';
export const CHAT_BLOB_REF_METADATA_KEY: string = 'amber_blob_ref';
export const CHAT_BLOB_MIN_CHARS: number = 4096;
// 1x1 透明 PNG:坏 blob 的 UI 占位;请求期不会发送它。
export const CHAT_BLOB_MISSING_DATA_URL: string =
  'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=';

export interface ChatBlobPort {
  hash(dataUrl: string): string;
  write(hash: string, dataUrl: string): Promise<void>;
  read(hash: string): Promise<string | null>;
  list(): Promise<string[]>;
  remove(hash: string): Promise<void>;
}

export interface ExternalizedConversation {
  conversation: Conversation;
  hashes: string[];
}

const isBlobUrl = (url: string): boolean => url.startsWith(CHAT_BLOB_PREFIX);
const isDataImage = (url: string): boolean => url.startsWith('data:image');
const blobHashOf = (url: string): string => url.substring(CHAT_BLOB_PREFIX.length);

const markMissing = (metadata: JsonObject | null, hash: string): JsonObject => {
  const out: JsonObject = {};
  if (metadata !== null) {
    for (const key of Object.keys(metadata)) out[key] = metadata[key];
  }
  out[CHAT_BLOB_MISSING_METADATA_KEY] = true;
  out[CHAT_BLOB_REF_METADATA_KEY] = `${CHAT_BLOB_PREFIX}${hash}`;
  return out;
};

const cleanMetadata = (metadata: JsonObject | null): JsonObject | null => {
  if (metadata === null) return null;
  const out: JsonObject = {};
  let count: number = 0;
  for (const key of Object.keys(metadata)) {
    if (key === CHAT_BLOB_MISSING_METADATA_KEY || key === CHAT_BLOB_REF_METADATA_KEY) continue;
    out[key] = metadata[key];
    count++;
  }
  return count > 0 ? out : null;
};

export const isMissingBlobImage = (part: UIMessagePartImage): boolean =>
  part.url === CHAT_BLOB_MISSING_URL || part.metadata?.[CHAT_BLOB_MISSING_METADATA_KEY] === true;

const collectPartHashes = (part: UIMessagePart, out: Set<string>): void => {
  if (part.type === 'image') {
    if (isBlobUrl(part.url) && !isMissingBlobImage(part)) out.add(blobHashOf(part.url));
    const ref: JsonValue | undefined = part.metadata?.[CHAT_BLOB_REF_METADATA_KEY];
    if (isMissingBlobImage(part) && typeof ref === 'string' && isBlobUrl(ref)) out.add(blobHashOf(ref));
    return;
  }
  if (part.type === 'tool') {
    for (const child of part.output) collectPartHashes(child, out);
  }
};

export const collectBlobHashes = (messages: UIMessage[]): string[] => {
  const out: Set<string> = new Set<string>();
  for (const message of messages) {
    for (const part of message.parts) collectPartHashes(part, out);
  }
  return Array.from(out);
};

export const collectConversationBlobHashes = (conv: Conversation): string[] => {
  const out: Set<string> = new Set<string>();
  for (const node of conv.messageNodes) {
    for (const message of node.messages) {
      for (const part of message.parts) collectPartHashes(part, out);
    }
  }
  return Array.from(out);
};

const externalizePart = async (
  part: UIMessagePart, port: ChatBlobPort, hashes: Set<string>,
): Promise<UIMessagePart> => {
  if (part.type === 'image') {
    if (isMissingBlobImage(part)) {
      const ref: JsonValue | undefined = part.metadata?.[CHAT_BLOB_REF_METADATA_KEY];
      if (typeof ref === 'string' && isBlobUrl(ref)) {
        return { type: 'image', url: ref, metadata: cleanMetadata(part.metadata) };
      }
      return part;
    }
    if (!isDataImage(part.url) || part.url.length < CHAT_BLOB_MIN_CHARS) return part;
    const hash: string = port.hash(part.url);
    await port.write(hash, part.url);
    hashes.add(hash);
    return { type: 'image', url: `${CHAT_BLOB_PREFIX}${hash}`, metadata: part.metadata };
  }
  if (part.type === 'tool') {
    const output: UIMessagePart[] = await Promise.all(
      part.output.map((child: UIMessagePart): Promise<UIMessagePart> =>
        externalizePart(child, port, hashes)));
    return {
      type: 'tool', toolCallId: part.toolCallId, toolName: part.toolName,
      input: part.input, output, approvalState: part.approvalState, metadata: part.metadata,
    };
  }
  return part;
};

const mapMessageParts = async (
  message: UIMessage, port: ChatBlobPort, hashes: Set<string>,
): Promise<UIMessage> => {
  const parts: UIMessagePart[] = await Promise.all(
    message.parts.map((part: UIMessagePart): Promise<UIMessagePart> =>
      externalizePart(part, port, hashes)));
  return {
    id: message.id, role: message.role, parts, annotations: message.annotations,
    createdAt: message.createdAt, finishedAt: message.finishedAt,
    modelId: message.modelId, usage: message.usage, translation: message.translation,
  };
};

export const externalizeConversation = async (
  conv: Conversation, port: ChatBlobPort,
): Promise<ExternalizedConversation> => {
  const hashes: Set<string> = new Set<string>();
  const nodes: MessageNode[] = await Promise.all(conv.messageNodes.map(
    async (node: MessageNode): Promise<MessageNode> => {
      const messages: UIMessage[] = await Promise.all(node.messages.map(
        (message: UIMessage): Promise<UIMessage> => mapMessageParts(message, port, hashes)));
      return { id: node.id, messages, selectIndex: node.selectIndex };
    }));
  return {
    conversation: {
      id: conv.id, assistantId: conv.assistantId, title: conv.title, messageNodes: nodes,
      chatSuggestions: conv.chatSuggestions, isPinned: conv.isPinned,
      autoApproveToolCalls: conv.autoApproveToolCalls,
      createAt: conv.createAt, updateAt: conv.updateAt,
    },
    hashes: Array.from(hashes),
  };
};

const resolvePart = async (part: UIMessagePart, port: ChatBlobPort): Promise<UIMessagePart> => {
  if (part.type === 'image') {
    if (!isBlobUrl(part.url) || part.url === CHAT_BLOB_MISSING_URL) return part;
    const hash: string = blobHashOf(part.url);
    const data: string | null = await port.read(hash);
    if (data === null) {
      return {
        type: 'image', url: CHAT_BLOB_MISSING_DATA_URL,
        metadata: markMissing(part.metadata, hash),
      };
    }
    return { type: 'image', url: data, metadata: part.metadata };
  }
  if (part.type === 'tool') {
    const output: UIMessagePart[] = await Promise.all(
      part.output.map((child: UIMessagePart): Promise<UIMessagePart> => resolvePart(child, port)));
    return {
      type: 'tool', toolCallId: part.toolCallId, toolName: part.toolName,
      input: part.input, output, approvalState: part.approvalState, metadata: part.metadata,
    };
  }
  return part;
};

export const resolveMessages = async (
  messages: UIMessage[], port: ChatBlobPort,
): Promise<UIMessage[]> => Promise.all(messages.map(async (message: UIMessage): Promise<UIMessage> => {
  const parts: UIMessagePart[] = await Promise.all(
    message.parts.map((part: UIMessagePart): Promise<UIMessagePart> => resolvePart(part, port)));
  return {
    id: message.id, role: message.role, parts, annotations: message.annotations,
    createdAt: message.createdAt, finishedAt: message.finishedAt,
    modelId: message.modelId, usage: message.usage, translation: message.translation,
  };
}));

export const resolveConversation = async (
  conv: Conversation, port: ChatBlobPort,
): Promise<Conversation> => {
  const nodes: MessageNode[] = await Promise.all(conv.messageNodes.map(
    async (node: MessageNode): Promise<MessageNode> => {
      const messages: UIMessage[] = await resolveMessages(node.messages, port);
      return { id: node.id, messages, selectIndex: node.selectIndex };
    }));
  return {
    id: conv.id, assistantId: conv.assistantId, title: conv.title, messageNodes: nodes,
    chatSuggestions: conv.chatSuggestions, isPinned: conv.isPinned,
    autoApproveToolCalls: conv.autoApproveToolCalls,
    createAt: conv.createAt, updateAt: conv.updateAt,
  };
};

export const pruneOrphanBlobs = async (
  referenced: Set<string>, port: ChatBlobPort,
): Promise<number> => {
  const files: string[] = await port.list();
  let removed: number = 0;
  for (const hash of files) {
    if (referenced.has(hash)) continue;
    await port.remove(hash);
    removed++;
  }
  return removed;
};

export const removeBlobs = async (hashes: string[], port: ChatBlobPort): Promise<void> => {
  for (const hash of hashes) await port.remove(hash);
};
