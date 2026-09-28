import type { MessageNode } from './conversation.ts';
import { nodeCurrentMessage, nodeRole } from './conversation.ts';
import type { UIMessage } from './message.ts';

export interface ChatTimelineRow {
  nodeId: string;
  message: UIMessage;
  branchCount: number;
  selectIndex: number;
  isAssistant: boolean;
  isLastAssistant: boolean;
  displayRevision: number;
}

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
    const row: ChatTimelineRow = {
      nodeId: node.id,
      message: nodeCurrentMessage(node),
      branchCount: node.messages.length,
      selectIndex: node.selectIndex,
      isAssistant: nodeRole(node) === 'assistant',
      isLastAssistant: index === lastAssistantIndex,
      displayRevision: old?.displayRevision ?? 0,
    };
    // ForEach reuses an existing item closure while its key stays unchanged.
    // Rebuild only this immutable row when its displayed message or controls change.
    if (old !== undefined && (old.branchCount !== row.branchCount || old.selectIndex !== row.selectIndex ||
      old.isAssistant !== row.isAssistant || old.isLastAssistant !== row.isLastAssistant ||
      (old.message !== row.message && JSON.stringify(old.message) !== JSON.stringify(row.message)))) {
      row.displayRevision++;
    }
    return row;
  });
};
