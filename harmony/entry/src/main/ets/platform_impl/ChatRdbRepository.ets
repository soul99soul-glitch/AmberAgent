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
  ConversationRow, MessageNodeRow,
} from '@amber/chat-domain';
import {
  CHAT_SCHEMA_SQL, conversationToRow, rowToConversation,
  messageNodeToRow, rowToMessageNode, createMemoryConversationRepository,
  conversationFileUris, removedFileUris, deleteChatFiles,
} from '@amber/chat-domain';
import { getDeleteChatFilesDeps } from './ManagedFileStore.ets';

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
  private initFailed: boolean = false;
  private memory: ConversationRepository = createMemoryConversationRepository();

  private async ensureStore(): Promise<relationalStore.RdbStore | null> {
    if (this.store !== null) return this.store;
    if (this.initFailed) return null;
    try {
      const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
      const store = await relationalStore.getRdbStore(ctx, {
        name: DB_NAME,
        securityLevel: relationalStore.SecurityLevel.S1,
      });
      // schema 幂等建表(CREATE TABLE/INDEX IF NOT EXISTS,逐句执行)
      const stmts: string[] = CHAT_SCHEMA_SQL.split(';');
      for (const raw of stmts) {
        const sql: string = raw.trim();
        if (sql.length > 0) {
          await store.executeSql(sql);
        }
      }
      this.store = store;
      return store;
    } catch (e) {
      this.initFailed = true;
      const msg: string = e instanceof Error ? e.message : String(e);
      hilog.warn(DOMAIN, TAG, 'RDB init failed, fallback to memory: %{public}s', msg);
      return null;
    }
  }

  private async queryConversationRows(
    store: relationalStore.RdbStore, predicates: relationalStore.RdbPredicates,
  ): Promise<ConversationRow[]> {
    const rs = await store.query(predicates, CONV_COLUMNS);
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
    rs.close();
    return rows;
  }

  private async queryNodeRows(
    store: relationalStore.RdbStore, conversationId: string,
  ): Promise<MessageNodeRow[]> {
    const predicates = new relationalStore.RdbPredicates('message_node');
    predicates.equalTo('conversation_id', conversationId);
    predicates.orderByAsc('node_index');
    const rs = await store.query(predicates, NODE_COLUMNS);
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
    rs.close();
    return rows;
  }

  async save(conv: Conversation): Promise<void> {
    // D-097:checkFilesDelete(ChatService.kt:1868-1877 updateConversation 默认 true +
    //   :1903-1912 diff)— 持久化前与库内旧态 diff files,移除项 deleteChatFiles;
    //   fire-and-forget(FilesManager appScope.launch 语义);旧不存在(新会话/
    //   撤销恢复插入)→ 跳过;流式中间态不落库,不受影响(Android false 旗标位)
    const old: Conversation | null = await this.getById(conv.id);
    if (old !== null) {
      const removed: string[] =
        removedFileUris(conversationFileUris(old), conversationFileUris(conv));
      if (removed.length > 0) {
        // Log.w(TAG, "checkFilesDelete: $deletedFiles")(:1911)— uri 为 uuid 文件名,
        //   不含消息内容(宪章隐私条款兼容)
        hilog.warn(DOMAIN, TAG, 'checkFilesDelete: %{public}s', JSON.stringify(removed));
        deleteChatFiles(getDeleteChatFilesDeps(), removed).catch((): void => {});
      }
    }
    const store = await this.ensureStore();
    if (store === null) return this.memory.save(conv);
    // upsert conv + 整体替换 nodes(对齐 Android ConversationDAO save 语义:
    // memory 实现同口径,已被 persistence 测试锁定)
    await store.insert('conversation', convBucket(conversationToRow(conv)),
      relationalStore.ConflictResolution.ON_CONFLICT_REPLACE);
    const delPredicates = new relationalStore.RdbPredicates('message_node');
    delPredicates.equalTo('conversation_id', conv.id);
    await store.delete(delPredicates);
    for (let i = 0; i < conv.messageNodes.length; i++) {
      await store.insert('message_node', nodeBucket(messageNodeToRow(conv.id, conv.messageNodes[i], i)));
    }
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
    // SQLite LIKE:ASCII 大小写不敏感 — 与 Android ConversationDAO.search 对齐
    predicates.like('title', `%${searchText}%`);
    predicates.orderByDesc('is_pinned');
    predicates.orderByDesc('update_at');
    const rows: ConversationRow[] = await this.queryConversationRows(store, predicates);
    return rows.map((r: ConversationRow): ConversationSummary => ChatRdbRepository.summaryOf(r));
  }

  async delete(id: string): Promise<void> {
    const store = await this.ensureStore();
    if (store === null) return this.memory.delete(id);
    // 显式级联(Harmony RDB 外键默认不开启;语义对齐 FK CASCADE)
    const nodePredicates = new relationalStore.RdbPredicates('message_node');
    nodePredicates.equalTo('conversation_id', id);
    await store.delete(nodePredicates);
    const convPredicates = new relationalStore.RdbPredicates('conversation');
    convPredicates.equalTo('id', id);
    await store.delete(convPredicates);
  }
}

export const createChatRdbRepository = (): ConversationRepository => {
  return new ChatRdbRepository();
};
