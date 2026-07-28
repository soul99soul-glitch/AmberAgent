// Chat 持久化 — RDB schema + 行映射 + Repository Port
//
// Android 基准(DATA_SCHEMA_MATRIX T01/T02 + ConversationDAO/MessageNodeDAO):
//   conversation: id/assistant_id/title/create_at/update_at/suggestions/is_pinned/auto_approve_tools
//   message_node: id/conversation_id(FK CASCADE)/node_index/messages(UIMessage JSON blob)/select_index
//   列表/搜索一律 ORDER BY is_pinned DESC, update_at DESC;搜索 title LIKE(ASCII 大小写不敏感)
//
// 决定(D-013):
//   - 丢弃 Android 废弃 nodes 列(矩阵注明"迁移时可丢弃")
//   - 表名 conversation/message_node(Android Room 实际表名 conversationentity;
//     未来 Android DB 导入工具需做表名映射)
//   - create_at/update_at: epoch ms INTEGER ↔ 领域层 ISO 字符串
//   - RDB 真机 adapter 在设备阶段实现;本层提供 SQL 常量 + 行映射 + memory repository

import type { Conversation, MessageNode } from './conversation.ts';
import { serializeMessageList, parseMessageList } from './serialize.ts';
import { nowIso } from './ids.ts';

// ===== RDB schema(SQLite 子集,Harmony RDB 兼容) =====

export const CHAT_SCHEMA_SQL = `
CREATE TABLE IF NOT EXISTS conversation (
  id TEXT PRIMARY KEY,
  assistant_id TEXT NOT NULL DEFAULT '0950e2dc-9bd5-4801-afa3-aa887aa36b4e',
  title TEXT NOT NULL,
  create_at INTEGER NOT NULL,
  update_at INTEGER NOT NULL,
  suggestions TEXT NOT NULL DEFAULT '[]',
  is_pinned INTEGER NOT NULL DEFAULT 0,
  auto_approve_tools INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_conversation_assistant_pinned_updated
  ON conversation(assistant_id, is_pinned, update_at);
CREATE INDEX IF NOT EXISTS idx_conversation_pinned_updated
  ON conversation(is_pinned, update_at);

CREATE TABLE IF NOT EXISTS message_node (
  id TEXT PRIMARY KEY,
  conversation_id TEXT NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
  node_index INTEGER NOT NULL,
  messages TEXT NOT NULL,
  select_index INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_message_node_conversation
  ON message_node(conversation_id);
CREATE INDEX IF NOT EXISTS idx_message_node_conversation_index
  ON message_node(conversation_id, node_index);
`;

// ===== 行类型 =====

export interface ConversationRow {
  id: string;
  assistant_id: string;
  title: string;
  create_at: number;      // epoch ms
  update_at: number;      // epoch ms
  suggestions: string;    // JSON string[]
  is_pinned: number;      // 0/1
  auto_approve_tools: number; // 0/1
}

export interface MessageNodeRow {
  id: string;
  conversation_id: string;
  node_index: number;
  messages: string;       // UIMessage JSON blob
  select_index: number;
}

// ===== 行映射 =====

export const conversationToRow = (conv: Conversation): ConversationRow => ({
  id: conv.id,
  assistant_id: conv.assistantId,
  title: conv.title,
  create_at: Date.parse(conv.createAt),
  update_at: Date.parse(conv.updateAt),
  suggestions: JSON.stringify(conv.chatSuggestions),
  is_pinned: conv.isPinned ? 1 : 0,
  auto_approve_tools: conv.autoApproveToolCalls ? 1 : 0,
});

export const rowToConversation = (
  row: ConversationRow, nodeRows: MessageNodeRow[], now: () => string = nowIso,
): Conversation => {
  const sorted: MessageNodeRow[] = [...nodeRows].sort(
    (a: MessageNodeRow, b: MessageNodeRow): number => a.node_index - b.node_index);
  return {
    id: row.id,
    assistantId: row.assistant_id,
    title: row.title,
    messageNodes: sorted.map((r: MessageNodeRow): MessageNode => rowToMessageNode(r, now)),
    chatSuggestions: JSON.parse(row.suggestions) as string[],
    isPinned: row.is_pinned !== 0,
    autoApproveToolCalls: row.auto_approve_tools !== 0,
    createAt: new Date(row.create_at).toISOString(),
    updateAt: new Date(row.update_at).toISOString(),
  };
};

export const messageNodeToRow = (
  conversationId: string, node: MessageNode, nodeIndex: number, _now: () => string = nowIso,
): MessageNodeRow => ({
  id: node.id,
  conversation_id: conversationId,
  node_index: nodeIndex,
  messages: serializeMessageList(node.messages),
  select_index: node.selectIndex,
});

export const rowToMessageNode = (row: MessageNodeRow, now: () => string = nowIso): MessageNode => ({
  id: row.id,
  messages: parseMessageList(row.messages, now),
  selectIndex: row.select_index,
});

// ===== Repository Port(DAO 语义) =====

// LightConversationEntity 对齐(领域层用 ISO 时间)
export interface ConversationSummary {
  id: string;
  assistantId: string;
  title: string;
  isPinned: boolean;
  createAt: string;
  updateAt: string;
}

export interface ConversationRepository {
  save(conv: Conversation): Promise<void>;                    // upsert + 整体替换 nodes
  getById(id: string): Promise<Conversation | null>;
  getSummaryById(id: string): Promise<ConversationSummary | null>;
  list(assistantId?: string): Promise<ConversationSummary[]>;
  listRecent(limit: number, assistantId?: string): Promise<ConversationSummary[]>;
  search(searchText: string, assistantId?: string): Promise<ConversationSummary[]>;
  delete(id: string): Promise<void>;                          // 级联 nodes
}

// SQLite LIKE:ASCII 大小写不敏感(非 ASCII 严格) — 用 ASCII-only 折叠对齐
const asciiLower = (s: string): string =>
  s.replace(/[A-Z]/g, (c: string): string => c.toLowerCase());

const summaryOf = (row: ConversationRow): ConversationSummary => ({
  id: row.id,
  assistantId: row.assistant_id,
  title: row.title,
  isPinned: row.is_pinned !== 0,
  createAt: new Date(row.create_at).toISOString(),
  updateAt: new Date(row.update_at).toISOString(),
});

const byPinnedThenUpdated = (a: ConversationRow, b: ConversationRow): number =>
  (b.is_pinned - a.is_pinned) || (b.update_at - a.update_at);

export const createMemoryConversationRepository = (
  now: () => string = nowIso,
): ConversationRepository => {
  const convRows = new Map<string, ConversationRow>();
  const nodeRowsByConv = new Map<string, MessageNodeRow[]>();

  const filtered = (assistantId: string | undefined): ConversationRow[] => {
    const rows: ConversationRow[] = [];
    for (const row of convRows.values()) {
      if (assistantId === undefined || row.assistant_id === assistantId) rows.push(row);
    }
    return rows.sort(byPinnedThenUpdated);
  };

  return {
    save(conv: Conversation): Promise<void> {
      convRows.set(conv.id, conversationToRow(conv));
      nodeRowsByConv.set(
        conv.id,
        conv.messageNodes.map((n: MessageNode, i: number): MessageNodeRow =>
          messageNodeToRow(conv.id, n, i, now)),
      );
      return Promise.resolve();
    },

    getById(id: string): Promise<Conversation | null> {
      const row: ConversationRow | undefined = convRows.get(id);
      if (row === undefined) return Promise.resolve(null);
      const nodes: MessageNodeRow[] = nodeRowsByConv.get(id) ?? [];
      return Promise.resolve(rowToConversation(row, nodes, now));
    },

    getSummaryById(id: string): Promise<ConversationSummary | null> {
      const row: ConversationRow | undefined = convRows.get(id);
      return Promise.resolve(row === undefined ? null : summaryOf(row));
    },

    list(assistantId?: string): Promise<ConversationSummary[]> {
      return Promise.resolve(filtered(assistantId).map(summaryOf));
    },

    listRecent(limit: number, assistantId?: string): Promise<ConversationSummary[]> {
      return Promise.resolve(filtered(assistantId).slice(0, limit).map(summaryOf));
    },

    search(searchText: string, assistantId?: string): Promise<ConversationSummary[]> {
      const needle: string = asciiLower(searchText);
      return Promise.resolve(
        filtered(assistantId)
          .filter((r: ConversationRow): boolean => asciiLower(r.title).includes(needle))
          .map(summaryOf),
      );
    },

    delete(id: string): Promise<void> {
      convRows.delete(id);
      nodeRowsByConv.delete(id); // FK CASCADE 对齐
      return Promise.resolve();
    },
  };
};
