// ChatRdbRepository — ConversationRepository 的真实 RDB 实现
// @kit.ArkData relationalStore。schema + 行映射复用 chat-domain persistence.ts(D-013,
// 已被 node 测试锁定);本层只做 relationalStore API 桥接,不含业务逻辑。
//
// 失败策略(非静默):getRdbStore 初始化抛错 → hilog.warn 记录错误信息(不含会话内容,
// 宪章隐私条款) + 回退 memory 实现(重启丢失,与 D-019 过渡态一致)。spike B 真机验证
// 后若 API 形态不符,仅改本文件桥接层。

import { relationalStore } from '@kit.ArkData';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type {
  Conversation, ConversationRepository, ConversationSummary,
  ConversationRow, MessageNodeRow, MessageNode, ConversationWindow,
} from '@amber/chat-domain';
import {
  CHAT_SCHEMA_SQL, conversationToRow, rowToConversation,
  messageNodeToRow, rowToMessageNode, createMemoryConversationRepository,
} from '@amber/chat-domain';
import { enqueueConversationFileMutation } from './ManagedFileStore.ets';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.ChatRdbRepository';
const DB_NAME: string = 'amber_chat.db';

// 列序常量(查询投影顺序固定,避免逐列 getColumnIndex)
const CONV_COLUMNS: string[] = [
  'id', 'assistant_id', 'title', 'create_at', 'update_at',
  'suggestions', 'is_pinned', 'auto_approve_tools',
];
const NODE_COLUMNS: string[] = [
  'id', 'conversation_id', 'node_index', 'messages', 'select_index',
];

const convBucket = (row: ConversationRow): relationalStore.ValuesBucket => {
  const bucket: relationalStore.ValuesBucket = {
    'id': row.id,
    'assistant_id': row.assistant_id,
    'title': row.title,
    'create_at': row.create_at,
    'update_at': row.update_at,
    'suggestions': row.suggestions,
    'is_pinned': row.is_pinned,
    'auto_approve_tools': row.auto_approve_tools,
  };
  return bucket;
};

const nodeBucket = (row: MessageNodeRow): relationalStore.ValuesBucket => {
  const bucket: relationalStore.ValuesBucket = {
    'id': row.id,
    'conversation_id': row.conversation_id,
    'node_index': row.node_index,
    'messages': row.messages,
    'select_index': row.select_index,
  };
  return bucket;
};

class ChatRdbRepository implements ConversationRepository {
  private store: relationalStore.RdbStore | null = null;
  private initPromise: Promise<relationalStore.RdbStore | null> | null = null;
  private memory: ConversationRepository = createMemoryConversationRepository();
  // RDB calls are asynchronous; serialize mutations per conversation so an older
  // whole-node replacement cannot finish after a newer save/delete.
  private mutationTails: Map<string, Promise<void>> = new Map();
  private deletedIds: Set<string> = new Set();

  private enqueueMutation(id: string, mutation: () => Promise<void>): Promise<void> {
    const previous: Promise<void> = this.mutationTails.get(id) ?? Promise.resolve();
    const next: Promise<void> = previous.catch((): void => {}).then(mutation);
    this.mutationTails.set(id, next);
    return next.finally((): void => {
      if (this.mutationTails.get(id) === next) this.mutationTails.delete(id);
    });
  }

  private async writeConversation(
    store: relationalStore.RdbStore, conv: Conversation,
  ): Promise<void> {
    let committed: boolean = false;
    store.beginTransaction();
    try {
      await store.insert('conversation', convBucket(conversationToRow(conv)),
        relationalStore.ConflictResolution.ON_CONFLICT_REPLACE);
      const delPredicates = new relationalStore.RdbPredicates('message_node');
      delPredicates.equalTo('conversation_id', conv.id);
      await store.delete(delPredicates);
      for (let i = 0; i < conv.messageNodes.length; i++) {
        await store.insert('message_node', nodeBucket(
          messageNodeToRow(conv.id, conv.messageNodes[i], i)));
      }
      store.commit();
      committed = true;
    } finally {
      if (!committed) {
        try { store.rollBack(); } catch { /* preserve original write failure */ }
      }
    }
  }

  private async initializeStore(): Promise<relationalStore.RdbStore | null> {
    try {
      const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
      const store = await relationalStore.getRdbStore(ctx, {
        name: DB_NAME,
        securityLevel: relationalStore.SecurityLevel.S1,
      });
      const stmts: string[] = CHAT_SCHEMA_SQL.split(';');
      for (const raw of stmts) {
        const sql: string = raw.trim();
        if (sql.length > 0) await store.executeSql(sql);
      }

      // Operations wait on the shared init promise, so the memory snapshot cannot
      // change while it is being copied. Publish the RDB only after migration.
      const fallbackRows: ConversationSummary[] = await this.memory.list();
      for (const summary of fallbackRows) {
        const conv: Conversation | null = await this.memory.getById(summary.id);
        if (conv !== null) await this.writeConversation(store, conv);
      }
      this.memory = createMemoryConversationRepository();
      this.store = store;
      AppStorage.setOrCreate<boolean>('chatStorageDegraded', false);
      return store;
    } catch (e) {
      const msg: string = e instanceof Error ? e.message : String(e);
      AppStorage.setOrCreate<boolean>('chatStorageDegraded', true);
      hilog.warn(DOMAIN, TAG, 'RDB init failed, using temporary memory: %{public}s', msg);
      return null;
    }
  }

  private async ensureStore(): Promise<relationalStore.RdbStore | null> {
    if (this.store !== null) return this.store;
    if (this.initPromise === null) {
      const attempt: Promise<relationalStore.RdbStore | null> = this.initializeStore();
      this.initPromise = attempt;
      attempt.finally((): void => {
        if (this.initPromise === attempt) this.initPromise = null;
      });
    }
    return this.initPromise;
  }

  private async queryConversationRows(
    store: relationalStore.RdbStore, predicates: relationalStore.RdbPredicates,
  ): Promise<ConversationRow[]> {
    const rs = await store.query(predicates, CONV_COLUMNS);
    try {
      const rows: ConversationRow[] = [];
      while (rs.goToNextRow()) {
        rows.push({
          id: rs.getString(0),
          assistant_id: rs.getString(1),
          title: rs.getString(2),
          create_at: rs.getLong(3),
          update_at: rs.getLong(4),
          suggestions: rs.getString(5),
          is_pinned: rs.getLong(6),
          auto_approve_tools: rs.getLong(7),
        });
      }
      return rows;
    } finally {
      rs.close();
    }
  }

  private async queryNodeRows(
    store: relationalStore.RdbStore, conversationId: string,
  ): Promise<MessageNodeRow[]> {
    const predicates = new relationalStore.RdbPredicates('message_node');
    predicates.equalTo('conversation_id', conversationId);
    predicates.orderByAsc('node_index');
    return this.readNodeRows(store, predicates);
  }

  // Phase 5:node 窗口读取(offset/limit 分页,API 12 offsetAs/limitAs;
  //   limit<=0 → 不限,等价全量查询)
  private async queryNodeWindowRows(
    store: relationalStore.RdbStore, conversationId: string, offset: number, limit: number,
  ): Promise<MessageNodeRow[]> {
    const predicates = new relationalStore.RdbPredicates('message_node');
    predicates.equalTo('conversation_id', conversationId);
    predicates.orderByAsc('node_index');
    if (limit > 0) {
      predicates.limitAs(limit);
      predicates.offsetAs(offset);
    }
    return this.readNodeRows(store, predicates);
  }

  private async readNodeRows(
    store: relationalStore.RdbStore, predicates: relationalStore.RdbPredicates,
  ): Promise<MessageNodeRow[]> {
    const rs = await store.query(predicates, NODE_COLUMNS);
    try {
      const rows: MessageNodeRow[] = [];
      while (rs.goToNextRow()) {
        rows.push({
          id: rs.getString(0),
          conversation_id: rs.getString(1),
          node_index: rs.getLong(2),
          messages: rs.getString(3),
          select_index: rs.getLong(4),
        });
      }
      return rows;
    } finally {
      rs.close();
    }
  }

  // Phase 5:会话节点总数(窗口 fullyLoaded 判定)
  private async countNodes(
    store: relationalStore.RdbStore, conversationId: string,
  ): Promise<number> {
    const rs = await store.querySql(
      'SELECT COUNT(*) FROM message_node WHERE conversation_id = ?', [conversationId]);
    try {
      if (rs.goToNextRow()) return rs.getLong(0);
      return 0;
    } finally {
      rs.close();
    }
  }

  async save(conv: Conversation): Promise<void> {
    return this.enqueueMutation(conv.id, async (): Promise<void> => {
      if (this.deletedIds.has(conv.id)) {
        throw new Error(`Conversation was deleted: ${conv.id}`);
      }
      return enqueueConversationFileMutation(async (): Promise<void> => {
        if (this.deletedIds.has(conv.id)) {
          throw new Error(`Conversation was deleted: ${conv.id}`);
        }
        const store = await this.ensureStore();
        if (store === null) {
          await this.memory.save(conv);
        } else {
          await this.writeConversation(store, conv);
        }
      });
    });
  }

  async getById(id: string): Promise<Conversation | null> {
    const store = await this.ensureStore();
    if (store === null) return this.memory.getById(id);
    const predicates = new relationalStore.RdbPredicates('conversation');
    predicates.equalTo('id', id);
    const rows: ConversationRow[] = await this.queryConversationRows(store, predicates);
    if (rows.length === 0) return null;
    const nodeRows: MessageNodeRow[] = await this.queryNodeRows(store, id);
    return rowToConversation(rows[0], nodeRows);
  }

  // Phase 5:尾部窗口读取(会话打开默认只加载最近 limit 个节点)。
  //   limit<=0 → 等价全量(返回整段窗口)。行序 node_index 升序。
  async getConversationTailWindow(id: string, limit: number): Promise<ConversationWindow> {
    const store = await this.ensureStore();
    if (store === null) return this.memory.getConversationTailWindow(id, limit);
    const totalNodeCount: number = await this.countNodes(store, id);
    const size: number = Math.max(0, Math.floor(limit));
    const nodeRows: MessageNodeRow[] = size > 0 && totalNodeCount > 0
      ? await this.queryNodeWindowRows(store, id, Math.max(0, totalNodeCount - size), size)
      : [];
    return this.windowOf(id, nodeRows, totalNodeCount);
  }

  // Phase 5:历史页读取(offset/limit 绝对索引窗口)。
  //   offset 越界 → 空节点窗口 + totalNodeCount(调用方据 oldestLoadedIndex 判定)。
  async getConversationNodePage(
    id: string, offset: number, limit: number,
  ): Promise<ConversationWindow> {
    const store = await this.ensureStore();
    if (store === null) return this.memory.getConversationNodePage(id, offset, limit);
    const totalNodeCount: number = await this.countNodes(store, id);
    const from: number = Math.max(0, Math.floor(offset));
    const size: number = Math.max(0, Math.floor(limit));
    const nodeRows: MessageNodeRow[] = size > 0 && from < totalNodeCount
      ? await this.queryNodeWindowRows(store, id, from, size)
      : [];
    return this.windowOf(id, nodeRows, totalNodeCount);
  }

  // 节点窗口组装:nodeRows 按 node_index 升序;
  //   窗口首节点绝对索引 = rows[0].node_index(空窗口回退 totalNodeCount)。
  private windowOf(
    id: string, nodeRows: MessageNodeRow[], totalNodeCount: number,
  ): ConversationWindow {
    const nodes: MessageNode[] = [];
    for (let i = 0; i < nodeRows.length; i++) {
      nodes.push(rowToMessageNode(nodeRows[i]));
    }
    return {
      conversationId: id,
      nodes,
      oldestLoadedIndex: nodes.length > 0 ? nodeRows[0].node_index : totalNodeCount,
      totalNodeCount,
    };
  }

  async getSummaryById(id: string): Promise<ConversationSummary | null> {
    const store = await this.ensureStore();
    if (store === null) return this.memory.getSummaryById(id);
    const predicates = new relationalStore.RdbPredicates('conversation');
    predicates.equalTo('id', id);
    const rows: ConversationRow[] = await this.queryConversationRows(store, predicates);
    if (rows.length === 0) return null;
    return ChatRdbRepository.summaryOf(rows[0]);
  }

  private static summaryOf(row: ConversationRow): ConversationSummary {
    return {
      id: row.id,
      assistantId: row.assistant_id,
      title: row.title,
      isPinned: row.is_pinned !== 0,
      createAt: new Date(row.create_at).toISOString(),
      updateAt: new Date(row.update_at).toISOString(),
    };
  }

  private async listRows(
    store: relationalStore.RdbStore, assistantId: string | undefined, limit: number,
  ): Promise<ConversationRow[]> {
    const predicates = new relationalStore.RdbPredicates('conversation');
    if (assistantId !== undefined) {
      predicates.equalTo('assistant_id', assistantId);
    }
    // 列表/搜索一律 ORDER BY is_pinned DESC, update_at DESC(D-013)
    predicates.orderByDesc('is_pinned');
    predicates.orderByDesc('update_at');
    if (limit > 0) {
      predicates.limitAs(limit);
    }
    return this.queryConversationRows(store, predicates);
  }

  async list(assistantId?: string): Promise<ConversationSummary[]> {
    const store = await this.ensureStore();
    if (store === null) return this.memory.list(assistantId);
    const rows: ConversationRow[] = await this.listRows(store, assistantId, 0);
    return rows.map((r: ConversationRow): ConversationSummary => ChatRdbRepository.summaryOf(r));
  }

  async listRecent(limit: number, assistantId?: string): Promise<ConversationSummary[]> {
    const store = await this.ensureStore();
    if (store === null) return this.memory.listRecent(limit, assistantId);
    const rows: ConversationRow[] = await this.listRows(store, assistantId, limit);
    return rows.map((r: ConversationRow): ConversationSummary => ChatRdbRepository.summaryOf(r));
  }

  async search(searchText: string, assistantId?: string): Promise<ConversationSummary[]> {
    const store = await this.ensureStore();
    if (store === null) return this.memory.search(searchText, assistantId);
    const predicates = new relationalStore.RdbPredicates('conversation');
    if (assistantId !== undefined) {
      predicates.equalTo('assistant_id', assistantId);
    }
    // SQLite LIKE:ASCII 大小写不敏感 — 与 Android ConversationDAO.search 对齐。
    // RdbPredicates 无 ESCAPE 子句,反斜杠转义不生效(%/_ 仍是通配符):LIKE 只作
    // 粗筛,再用 TS 字面量子串精滤,保证搜索 "%" / "_" 不匹配全部标题
    predicates.like('title', `%${searchText}%`);
    predicates.orderByDesc('is_pinned');
    predicates.orderByDesc('update_at');
    const rows: ConversationRow[] = await this.queryConversationRows(store, predicates);
    const needle: string = searchText.toLowerCase();
    return rows
      .filter((r: ConversationRow): boolean => r.title.toLowerCase().includes(needle))
      .map((r: ConversationRow): ConversationSummary => ChatRdbRepository.summaryOf(r));
  }

  async delete(id: string): Promise<void> {
    this.deletedIds.add(id);
    return this.enqueueMutation(id, async (): Promise<void> =>
      enqueueConversationFileMutation(async (): Promise<void> => {
        const old: Conversation | null = await this.getById(id);
        if (old === null) return;
        const store = await this.ensureStore();
        if (store === null) {
          await this.memory.delete(id);
        } else {
          let committed: boolean = false;
          store.beginTransaction();
          try {
            const nodePredicates = new relationalStore.RdbPredicates('message_node');
            nodePredicates.equalTo('conversation_id', id);
            await store.delete(nodePredicates);
            const convPredicates = new relationalStore.RdbPredicates('conversation');
            convPredicates.equalTo('id', id);
            await store.delete(convPredicates);
            store.commit();
            committed = true;
          } finally {
            if (!committed) {
              try { store.rollBack(); } catch { /* preserve original delete failure */ }
            }
          }
        }
      }).catch((e: Error): void => {
        this.deletedIds.delete(id);
        throw e;
      }));
  }
}

export const createChatRdbRepository = (): ConversationRepository => {
  return new ChatRdbRepository();
};
