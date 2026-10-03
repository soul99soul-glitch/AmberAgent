// conversation_export — 会话导出为 Markdown / JSON
//
// Android 基准: feature/ui/pages/chat/Export.kt exportToMarkdown / JSON 分支
// 语义:
//   - selectedNodeIds 空 = 全量 messageNodes;否则按树序过滤
//   - Markdown: # title + ## role · time + 正文(text parts);reasoning 折叠为
//     <!-- thinking -->;tool 卡为 fenced json;跳过空消息
//   - JSON: { conversation meta, messages: UIMessage[] } kotlinx 风格 camelCase
//   - 密钥脱敏: tool input/output 中凭据字段值替换为 ***
// 裁剪:
//   - 图片/FileProvider 导出、Bitmap 合成、Highlighter 预览 = UI 层,域层只产字符串

import type { Conversation, MessageNode } from './conversation.ts';
import { currentMessages } from './conversation.ts';
import type { UIMessage, UIMessagePart, UIMessagePartText } from './message.ts';
import type { JsonObject, JsonValue } from './json.ts';

export interface ConversationExportOptions {
  /** 空 = 全量;否则仅导出这些 node id(保持树序) */
  selectedNodeIds?: string[];
  includeReasoning?: boolean;
  includeTools?: boolean;
  /** 标题缺失时的占位,默认「未命名对话」 */
  fallbackTitle?: string;
}

const ROLE_LABEL: Record<string, string> = {
  user: '用户',
  assistant: '助手',
  system: '系统',
  tool: '工具',
};

const SENSITIVE_KEY_RE: RegExp =
  /^(api[_-]?key|x-api-key|x-goog-api-key|authorization|(?:access|refresh|auth|api|session)[_-]?token|token|(?:client[_-]?)?secret|secret[_-]?key|password|passwd|(?:set[_-]?)?cookie|session)$/i;

const maskSensitiveJsonValue = (value: JsonValue): JsonValue => {
  if (Array.isArray(value)) return value.map(maskSensitiveJsonValue);
  if (typeof value === 'object' && value !== null) return maskSensitiveJsonObject(value);
  return value;
};

export const maskSensitiveJsonObject = (value: JsonObject): JsonObject => {
  const out: JsonObject = {};
  // Custom headers/bodies are stored as {name/key, value} pairs rather than a map.
  const pairKey = value['name'] ?? value['key'];
  const sensitivePair = typeof pairKey === 'string' && SENSITIVE_KEY_RE.test(pairKey);
  for (const key of Object.keys(value)) {
    const v = value[key];
    if ((SENSITIVE_KEY_RE.test(key) || (key === 'value' && sensitivePair))
      && typeof v === 'string' && v.length > 0) {
      out[key] = '***';
    } else {
      out[key] = maskSensitiveJsonValue(v);
    }
  }
  return out;
};

/** 工具参数/结果的 JSON 文本走同一脱敏规则；普通文本不改写。 */
export const maskSensitiveJsonText = (text: string): string => {
  try {
    const value: JsonValue = JSON.parse(text);
    if (typeof value === 'object' && value !== null) {
      return JSON.stringify(maskSensitiveJsonValue(value), null, 2);
    }
  } catch { /* 普通文本和未完成 JSON 原样显示。 */ }
  return text;
};

const messagePlainText = (message: UIMessage, includeReasoning: boolean): string => {
  const chunks: string[] = [];
  for (const part of message.parts) {
    if (part.type === 'text') {
      chunks.push((part as UIMessagePartText).text);
    } else if (part.type === 'reasoning' && includeReasoning) {
      chunks.push(`<!-- thinking\n${part.reasoning}\n-->`);
    } else if (part.type === 'tool') {
      chunks.push(`[tool ${part.toolName}] ${maskSensitiveJsonText(part.input)}`);
      for (const outPart of part.output) {
        if (outPart.type === 'text') chunks.push(maskSensitiveJsonText(outPart.text));
      }
    } else if (part.type === 'image') {
      chunks.push('[图片]');
    } else if (part.type === 'document') {
      chunks.push(`[文件] ${(part as { fileName?: string }).fileName ?? ''}`);
    }
  }
  return chunks.join('\n\n').trim();
};

export const messageImageExportText = (message: UIMessage, includeReasoning: boolean): string => {
  const chunks: string[] = [];
  for (const part of message.parts) {
    if (part.type === 'text' && part.text.trim().length > 0) {
      chunks.push(part.text.trim());
    } else if (part.type === 'reasoning' && includeReasoning && part.reasoning.trim().length > 0) {
      chunks.push(`思考过程：\n${part.reasoning.trim()}`);
    }
  }
  return chunks.length > 0 ? chunks.join('\n\n') : '[非文本内容]';
};

const formatTs = (iso: string): string => {
  if (iso.length === 0) return '';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return iso;
  const pad = (n: number): string => (n < 10 ? `0${n}` : `${n}`);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
};

const nodesForExport = (
  conversation: Conversation, selectedNodeIds: string[] | undefined,
): MessageNode[] => {
  if (selectedNodeIds === undefined || selectedNodeIds.length === 0) {
    return conversation.messageNodes;
  }
  const keep = new Set(selectedNodeIds);
  return conversation.messageNodes.filter((n: MessageNode): boolean => keep.has(n.id));
};

export const exportConversationMarkdown = (
  conversation: Conversation,
  opts: ConversationExportOptions = {},
): string => {
  const title: string = conversation.title.trim().length > 0
    ? conversation.title.trim()
    : (opts.fallbackTitle ?? '未命名对话');
  const includeReasoning: boolean = opts.includeReasoning ?? true;
  const nodes = nodesForExport(conversation, opts.selectedNodeIds);
  const lines: string[] = [];
  lines.push(`# ${title}`);
  lines.push('');
  lines.push(`导出时间：${formatTs(new Date().toISOString())}`);
  lines.push('');

  for (const node of nodes) {
    const message: UIMessage | undefined = node.messages[node.selectIndex >= 0 ? node.selectIndex : 0];
    if (message === undefined) continue;
    if (message.role !== 'user' && message.role !== 'assistant') continue;
    const body = messagePlainText(message, includeReasoning);
    if (body.length === 0) continue;
    const roleLabel = ROLE_LABEL[message.role] ?? message.role;
    const when = formatTs(message.createdAt);
    lines.push(`## ${roleLabel}${when.length > 0 ? ` · ${when}` : ''}`);
    lines.push('');
    lines.push(body);
    lines.push('');
  }
  return lines.join('\n');
};

export interface ConversationExportJson {
  id: string;
  title: string;
  assistantId: string;
  createAt: string;
  updateAt: string;
  messages: UIMessage[];
}

export const exportConversationJson = (
  conversation: Conversation,
  opts: ConversationExportOptions = {},
): ConversationExportJson => {
  const nodes = nodesForExport(conversation, opts.selectedNodeIds);
  const messages: UIMessage[] = [];
  for (const node of nodes) {
    const message: UIMessage | undefined = node.messages[node.selectIndex >= 0 ? node.selectIndex : 0];
    if (message === undefined) continue;
    messages.push({
      ...message,
      parts: message.parts.map((part: UIMessagePart): UIMessagePart => part.type === 'tool' ? {
        ...part,
        input: maskSensitiveJsonText(part.input),
        output: part.output.map((output: UIMessagePart): UIMessagePart => output.type === 'text'
          ? { ...output, text: maskSensitiveJsonText(output.text) } : output),
      } : part),
    });
  }
  return {
    id: conversation.id,
    title: conversation.title,
    assistantId: conversation.assistantId,
    createAt: conversation.createAt,
    updateAt: conversation.updateAt,
    messages,
  };
};

export const exportConversationJsonString = (
  conversation: Conversation,
  opts: ConversationExportOptions = {},
): string => JSON.stringify(exportConversationJson(conversation, opts), null, 2);

export interface ChatMessageArchiveSource {
  conversationId: string;
  messageId: string;
  nodeId: string;
}

const CHAT_ARCHIVE_SOURCE_PREFIX = '<!-- amber-chat-source ';

/** 来源随 Markdown 文件持久保存，不增加独立登记库。 */
export const messageArchiveMarkdown = (conversation: Conversation, messageId: string): string => {
  const node = conversation.messageNodes.find((item: MessageNode): boolean =>
    item.messages[item.selectIndex >= 0 ? item.selectIndex : 0]?.id === messageId);
  if (node === undefined) throw new Error('原消息已不在当前对话分支中');
  const source: ChatMessageArchiveSource = { conversationId: conversation.id, messageId, nodeId: node.id };
  return `${CHAT_ARCHIVE_SOURCE_PREFIX}${JSON.stringify(source)} -->\n\n${exportConversationMarkdown(
    conversation, { selectedNodeIds: [node.id], includeReasoning: false })}`;
};

export const messageArchiveSource = (markdown: string): ChatMessageArchiveSource | null => {
  const header = markdown.split('\n', 1)[0];
  if (!header.startsWith(CHAT_ARCHIVE_SOURCE_PREFIX) || !header.endsWith(' -->')) return null;
  try {
    const value = JSON.parse(header.slice(CHAT_ARCHIVE_SOURCE_PREFIX.length, -4)) as JsonObject;
    if (value !== null && typeof value === 'object' &&
      typeof value['conversationId'] === 'string' && typeof value['messageId'] === 'string' &&
      typeof value['nodeId'] === 'string') {
      return { conversationId: value['conversationId'], messageId: value['messageId'], nodeId: value['nodeId'] };
    }
  } catch { /* 普通 Workspace Markdown 没有聊天来源。 */ }
  return null;
};

/** 建议文件名: title-slug + 时间戳 + 扩展名 */
export const exportFileNameFor = (
  conversation: Conversation, kind: 'md' | 'json',
): string => {
  const raw: string = conversation.title.trim().length > 0
    ? conversation.title.trim() : 'conversation';
  const slug: string = raw
    .replace(/[\\/:*?"<>|]/g, '_')
    .replace(/\s+/g, '-')
    .substring(0, 40);
  const stamp: string = new Date().toISOString().replace(/[:.]/g, '-').substring(0, 19);
  return `${slug}-${stamp}.${kind}`;
};
