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
import { toText } from './message.ts';
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

// ===== 分页窗口(Phase 5 历史懒加载) =====

// 对齐 Android ConversationTimelineLoadState + loadOlderTimelinePage:
//   会话打开默认只加载尾部窗口,向上滚动加载更早历史。
//   nodes 按 node_index 升序,oldestLoadedIndex 为窗口首节点绝对索引。
export interface ConversationWindow {
  conversationId: string;
  /** 窗口内节点(node_index 升序),长度 <= limit */
  nodes: MessageNode[];
  /** 窗口首个节点的绝对 node 索引(0-based;空窗口 = totalNodeCount) */
  oldestLoadedIndex: number;
  /** 会话节点总数(fullyLoaded 判定:oldestLoadedIndex === 0) */
  totalNodeCount: number;
}

// rowToMessageNode 的窗口切片(内存实现与 RDB 共用语义)
const windowOf = (
  conversationId: string,
  rows: MessageNodeRow[],
  start: number,
  end: number,
  total: number,
  now: () => string = nowIso,
): ConversationWindow => {
  const nodes: MessageNode[] = [];
  for (let i = start; i < end; i++) {
    nodes.push(rowToMessageNode(rows[i], now));
  }
  return {
    conversationId,
    nodes,
    oldestLoadedIndex: start,
    totalNodeCount: total,
  };
};

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
  // 批量恢复(备份还原):同一批次内先全部序列化/校验再落库，单事务语义——失败不落
  // 任何覆盖，成功前不改变原数据(R03)。RDB 实现须在单事务内完成。
  restoreConversations(convs: Conversation[]): Promise<void>;
  getById(id: string): Promise<Conversation | null>;
  getSummaryById(id: string): Promise<ConversationSummary | null>;
  list(assistantId?: string): Promise<ConversationSummary[]>;
  listRecent(limit: number, assistantId?: string): Promise<ConversationSummary[]>;
  search(searchText: string, assistantId?: string): Promise<ConversationSummary[]>;
  // 消息全文搜索(按会话去重取最新命中,updateAt 倒序;Android MessageSearch+FTS 的
  // 无 FTS 等价实现:LIKE 粗筛 + 解析后纯文本精滤)
  searchMessages(searchText: string, limit: number): Promise<MessageSearchHit[]>;
  delete(id: string): Promise<void>;                          // 级联 nodes
  // Phase 5:分页窗口(尾窗 + 历史页;getById 恒全量不变)
  getConversationTailWindow(id: string, limit: number): Promise<ConversationWindow>;
  getConversationNodePage(id: string, offset: number, limit: number): Promise<ConversationWindow>;
}

// 消息全文搜索命中
export interface MessageSearchHit {
  conversationId: string;
  conversationTitle: string;
  snippet: string;
  updateAt: string;
}

// node 行 messages JSON → 纯文本(UIMessage text part 拼接;解析失败返回空串)
export const messageNodeRowPlainText = (messagesJson: string): string => {
  try {
    const msgs = parseMessageList(messagesJson);
    let out = '';
    for (const m of msgs) {
      const text = toText(m);
      if (text.length > 0) out += text + '\n';
    }
    return out;
  } catch {
    return '';
  }
};

// 命中片段:大小写不敏感定位,但切原文保大小写;越界省略号
export const snippetAround = (text: string, needle: string): string => {
  const i: number = text.toLowerCase().indexOf(needle.toLowerCase());
  if (i < 0) return text.slice(0, 40);
  const start = Math.max(0, i - 12);
  const head = start > 0 ? '…' : '';
  const rest = text.slice(start, i + needle.length + 28).trim();
  return `${head}${rest}…`;
};

// 命中列表构造:每会话取 node_index 最大的命中行(最新消息),按会话 updateAt 倒序
export const searchMessageHitsIn = (
  convs: ConversationSummary[],
  nodeRowsOf: (convId: string) => MessageNodeRow[],
  searchText: string,
  limit: number,
): MessageSearchHit[] => {
  const needle = asciiLower(searchText.trim());
  if (needle.length === 0) return [];
  const hits: MessageSearchHit[] = [];
  for (const conv of convs) {
    const rows = nodeRowsOf(conv.id);
    for (let i = rows.length - 1; i >= 0; i--) {
      const plain = messageNodeRowPlainText(rows[i].messages);
      if (asciiLower(plain).indexOf(needle) < 0) continue;
      hits.push({
        conversationId: conv.id,
        conversationTitle: conv.title,
        snippet: snippetAround(plain, needle),
        updateAt: conv.updateAt,
      });
      break;
    }
  }
  hits.sort((a: MessageSearchHit, b: MessageSearchHit): number =>
    b.updateAt.localeCompare(a.updateAt));
  return hits.slice(0, Math.max(0, limit));
};

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

// 同毫秒更新时按 id 升序兜底,保证列表顺序稳定(分页边界不重复/遗漏)
const byPinnedThenUpdated = (a: ConversationRow, b: ConversationRow): number =>
  (b.is_pinned - a.is_pinned) || (b.update_at - a.update_at) ||
  (a.id < b.id ? -1 : (a.id > b.id ? 1 : 0));

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

    // 先整体序列化(staging)再落库：任一条序列化失败都不改变现有数据(原子批语义)
    restoreConversations(convs: Conversation[]): Promise<void> {
      const staged: Array<{ row: ConversationRow; nodes: MessageNodeRow[] }> = [];
      for (const conv of convs) {
        staged.push({
          row: conversationToRow(conv),
          nodes: conv.messageNodes.map((n: MessageNode, i: number): MessageNodeRow =>
            messageNodeToRow(conv.id, n, i, now)),
        });
      }
      for (const item of staged) {
        convRows.set(item.row.id, item.row);
        nodeRowsByConv.set(item.row.id, item.nodes);
      }
      return Promise.resolve();
    },

    getById(id: string): Promise<Conversation | null> {
      const row: ConversationRow | undefined = convRows.get(id);
      if (row === undefined) return Promise.resolve(null);
      const nodes: MessageNodeRow[] = nodeRowsByConv.get(id) ?? [];
      return Promise.resolve(rowToConversation(row, nodes, now));
    },

    getConversationTailWindow(id: string, limit: number): Promise<ConversationWindow> {
      const rows: MessageNodeRow[] = nodeRowsByConv.get(id) ?? [];
      const total: number = rows.length;
      const size: number = Math.max(0, Math.floor(limit));
      const start: number = Math.max(0, total - size);
      return Promise.resolve(windowOf(id, rows, start, total, total, now));
    },

    getConversationNodePage(id: string, offset: number, limit: number): Promise<ConversationWindow> {
      const rows: MessageNodeRow[] = nodeRowsByConv.get(id) ?? [];
      const total: number = rows.length;
      const from: number = Math.max(0, Math.floor(offset));
      const size: number = Math.max(0, Math.floor(limit));
      const start: number = Math.min(from, total);
      const end: number = Math.min(total, start + size);
      return Promise.resolve(windowOf(id, rows, start, end, total, now));
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

    searchMessages(searchText: string, limit: number): Promise<MessageSearchHit[]> {
      return Promise.resolve(searchMessageHitsIn(
        filtered(undefined).map(summaryOf),
        (convId: string): MessageNodeRow[] => nodeRowsByConv.get(convId) ?? [],
        searchText, limit));
    },

    delete(id: string): Promise<void> {
      convRows.delete(id);
      nodeRowsByConv.delete(id); // FK CASCADE 对齐
      return Promise.resolve();
    },
  };
};
