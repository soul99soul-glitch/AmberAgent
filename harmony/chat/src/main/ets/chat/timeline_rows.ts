import type { MessageNode } from './conversation.ts';
import { nodeCurrentMessage, nodeRole } from './conversation.ts';
import type { UIMessage, UIMessagePart } from './message.ts';

export interface ChatTimelineRow {
  nodeId: string;
  message: UIMessage;
  branchCount: number;
  selectIndex: number;
  isAssistant: boolean;
  isLastAssistant: boolean;
  displayRevision: number;
  // 行内容的有界签名:不含完整 base64,避免每次流式更新 stringify 整段图片。
  displaySignature: string;
}

const SIGNATURE_TEXT_LIMIT: number = 192;
const SIGNATURE_JSON_LIMIT: number = 256;

const bounded = (value: string, limit: number = SIGNATURE_TEXT_LIMIT): string =>
  value.length <= limit ? value : `${value.length}:${value.substring(0, limit - 64)}:${value.substring(value.length - 64)}`;

const jsonSignature = (value: unknown): string => {
  if (value === null || value === undefined) return '';
  try {
    return bounded(JSON.stringify(value), SIGNATURE_JSON_LIMIT);
  } catch {
    return '[unserializable]';
  }
};

const partSignature = (part: UIMessagePart): string => {
  switch (part.type) {
    case 'text': return `t:${part.text}`;
    case 'reasoning': return `r:${part.reasoning}:${part.finishedAt ?? ''}`;
    case 'image': return `i:${bounded(part.url)}:${jsonSignature(part.metadata)}`;
    case 'video': return `v:${bounded(part.url)}:${part.mime}`;
    case 'audio': return `a:${bounded(part.url)}:${part.fileName}:${part.mime}`;
    case 'document': return `d:${bounded(part.url)}:${part.fileName}:${part.mime}`;
    case 'mini_app':
      return `m:${part.appId}:${part.title}:${part.description}:${part.version}:${jsonSignature(part.metadata)}`;
    case 'tool': {
      const output: string = part.output.map((child: UIMessagePart): string => partSignature(child)).join(',');
      return `x:${part.toolCallId}:${part.toolName}:${bounded(part.input)}:${jsonSignature(part.approvalState)}:${output}`;
    }
    default: return '';
  }
};

export const messageSignature = (message: UIMessage): string =>
  `${message.role}|${message.finishedAt ?? ''}|${bounded(message.translation ?? '')}|` +
  `${jsonSignature(message.annotations)}|${jsonSignature(message.usage)}|` +
  message.parts.map((part: UIMessagePart): string => partSignature(part)).join('|');

export const buildChatTimelineRows = (
  nodes: MessageNode[], previousRows: ChatTimelineRow[] = [],
): ChatTimelineRow[] => {
  const previous: Map<string, ChatTimelineRow> = new Map();
  for (const row of previousRows) previous.set(row.nodeId, row);
  let lastAssistantIndex: number = -1;
  for (let index: number = nodes.length - 1; index >= 0; index--) {
    if (nodeRole(nodes[index]) === 'assistant') { lastAssistantIndex = index; break; }
  }
  return nodes.map((node: MessageNode, index: number): ChatTimelineRow => {
    const old: ChatTimelineRow | undefined = previous.get(node.id);
    const message: UIMessage = nodeCurrentMessage(node);
    const row: ChatTimelineRow = {
      nodeId: node.id,
      message,
      branchCount: node.messages.length,
      selectIndex: node.selectIndex,
      isAssistant: nodeRole(node) === 'assistant',
      isLastAssistant: index === lastAssistantIndex,
      displayRevision: old?.displayRevision ?? 0,
      displaySignature: messageSignature(message),
    };
    // ForEach reuses an existing item closure while its key stays unchanged.
    // Rebuild only this immutable row when its displayed message or controls change.
    const oldSignature: string = old === undefined
      ? '' : (old.displaySignature ?? messageSignature(old.message));
    if (old !== undefined && (old.branchCount !== row.branchCount || old.selectIndex !== row.selectIndex ||
      old.isAssistant !== row.isAssistant || old.isLastAssistant !== row.isLastAssistant ||
      oldSignature !== row.displaySignature)) {
      row.displayRevision++;
    }
    return row;
  });
};
