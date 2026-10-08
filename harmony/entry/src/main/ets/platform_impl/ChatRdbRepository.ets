// ChatRdbRepository — ConversationRepository 的真实 RDB 实现
// @kit.ArkData relationalStore。schema + 行映射复用 chat-domain persistence.ts(D-013,
// 已被 node 测试锁定);本层只做 relationalStore API 桥接,不含业务逻辑。
//
// 失败策略(R04:不再静默降级):getRdbStore 初始化/迁移抛错 → hilog.warn(不含会话内容,
// 宪章隐私条款) + AppStorage('chatStorageDegraded', true)。降级期读取回退内存快照,
// 但**持久化写(save/delete/restoreConversations)一律抛非持久化错误**,避免写进重启即丢的
// 内存且用户无可见警告;下次操作重试初始化,成功时把内存回灌 RDB 后清除降级标志。

import { relationalStore } from '@kit.ArkData';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type {
  Conversation, ConversationRepository, ConversationSummary,
  ConversationRow, MessageNodeRow, MessageNode, ConversationWindow, MessageSearchHit,
} from '@amber/chat-domain';
import {
  CHAT_SCHEMA_SQL, conversationToRow, rowToConversation,
  messageNodeToRow, rowToMessageNode, createMemoryConversationRepository,
  messageNodeRowPlainText, snippetAround,
} from '@amber/chat-domain';
import { enqueueConversationFileMutation } from './ManagedFileStore.ets';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.ChatRdbRepository';
// 消息搜索粗筛中间态(每会话最新命中行)
interface RawMessageHit {
  plainText: string;
  nodeIndex: number;
}

const DB_NAME: string = 'amber_chat.db';
// R04:D B 不可用时拒绝一切持久化写入(非静默降级);恢复/保存抛出此非持久化错误。
const DEGRADED_WRITE_MSG: string = '数据库暂不可用，已取消写入以免数据丢失，请稍后重试';

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

// 列 diff 迁移用(ALTER ADD COLUMN 语义):CREATE TABLE IF NOT EXISTS 不会给旧表补新增列。
// NOT NULL 列在 ALTER 时必须带默认值(仅影响旧行补值,与建表定义可不同)。
// 限制:id(PK)无法 ALTER 添加,不列入;真实历史增量只有后加的普通列(如 auto_approve_tools),
// 缺基础列的病态旧表不在本迁移能力内(会初始化失败回退内存实现,登记)。
const CONV_ALTER_COLUMN_DEFS: string[][] = [
  ['assistant_id', `TEXT NOT NULL DEFAULT '0950e2dc-9bd5-4801-afa3-aa887aa36b4e'`],
  ['title', `TEXT NOT NULL DEFAULT ''`],
  ['create_at', 'INTEGER NOT NULL DEFAULT 0'],
  ['update_at', 'INTEGER NOT NULL DEFAULT 0'],
  ['suggestions', `TEXT NOT NULL DEFAULT '[]'`],
  ['is_pinned', 'INTEGER NOT NULL DEFAULT 0'],
  ['auto_approve_tools', 'INTEGER NOT NULL DEFAULT 0'],
];
const NODE_ALTER_COLUMN_DEFS: string[][] = [
  ['conversation_id', `TEXT NOT NULL DEFAULT ''`],
  ['node_index', 'INTEGER NOT NULL DEFAULT 0'],
  ['messages', `TEXT NOT NULL DEFAULT '[]'`],
  ['select_index', 'INTEGER NOT NULL DEFAULT 0'],
];

class ChatRdbRepository implements ConversationRepository {
  private store: relationalStore.RdbStore | null = null;
  private initPromise: Promise<relationalStore.RdbStore | null> | null = null;
  private memory: ConversationRepository = createMemoryConversationRepository();
  // RDB calls are asynchronous; serialize mutations per conversation so an older
  // whole-node replacement cannot finish after a newer save/delete.
  private mutationTails: Map<string, Promise<void>> = new Map();
  private deletedIds: Set<string> = new Set();
  // R09 代际:每次删除/授权恢复自增。排队中的旧 save 在调用时捕获 gen，
  // 执行时 gen 不一致即判为「迟到旧写」拒绝；恢复清墓碑前先自增，故旧写仍被挡。
  private deletedGen: Map<string, number> = new Map();

  private enqueueMutation(id: string, mutation: () => Promise<void>): Promise<void> {
    const previous: Promise<void> = this.mutationTails.get(id) ?? Promise.resolve();
    const next: Promise<void> = previous.catch((): void => {}).then(mutation);
    this.mutationTails.set(id, next);
    return next.finally((): void => {
      if (this.mutationTails.get(id) === next) this.mutationTails.delete(id);
    });
  }

  private isDeletedAt(id: string, gen: number): boolean {
    return this.deletedIds.has(id) || (this.deletedGen.get(id) ?? 0) !== gen;
  }

  // 无事务写入体(供单会话事务与批量恢复的单事务复用；调用方负责事务边界)
  private async writeConversationBody(
    store: relationalStore.RdbStore, conv: Conversation,
  ): Promise<void> {
    await store.insert('conversation', convBucket(conversationToRow(conv)),
      relationalStore.ConflictResolution.ON_CONFLICT_REPLACE);
    const delPredicates = new relationalStore.RdbPredicates('message_node');
    delPredicates.equalTo('conversation_id', conv.id);
    await store.delete(delPredicates);
    for (let i = 0; i < conv.messageNodes.length; i++) {
      await store.insert('message_node', nodeBucket(
        messageNodeToRow(conv.id, conv.messageNodes[i], i)));
    }
  }

  private async writeConversation(
    store: relationalStore.RdbStore, conv: Conversation,
  ): Promise<void> {
    let committed: boolean = false;
    store.beginTransaction();
    try {
      await this.writeConversationBody(store, conv);
      store.commit();
      committed = true;
    } finally {
      if (!committed) {
        try { store.rollBack(); } catch { /* preserve original write failure */ }
      }
    }
  }

  // PRAGMA table_info 列 diff:缺列补 ALTER ADD COLUMN(升级路径,建表语句本身不动)
  private async ensureTableColumns(
    store: relationalStore.RdbStore, table: string, defs: string[][],
  ): Promise<void> {
    const resultSet = await store.querySql(`PRAGMA table_info(${table})`);
    const existing: Set<string> = new Set<string>();
    try {
      const nameIdx: number = resultSet.getColumnIndex('name');
      while (resultSet.goToNextRow()) {
        existing.add(resultSet.getString(nameIdx));
      }
    } finally {
      resultSet.close();
    }
    for (const def of defs) {
      if (!existing.has(def[0])) {
        await store.executeSql(`ALTER TABLE ${table} ADD COLUMN ${def[0]} ${def[1]}`);
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
      // 两阶段执行:先建表(旧表缺列时 CREATE INDEX 会先失败),迁移补列后再建索引
      const stmts: string[] = CHAT_SCHEMA_SQL.split(';')
        .map((raw: string): string => raw.trim())
        .filter((sql: string): boolean => sql.length > 0);
      for (const sql of stmts) {
        if (sql.startsWith('CREATE TABLE')) await store.executeSql(sql);
      }
      await this.ensureTableColumns(store, 'conversation', CONV_ALTER_COLUMN_DEFS);
      await this.ensureTableColumns(store, 'message_node', NODE_ALTER_COLUMN_DEFS);
      for (const sql of stmts) {
        if (!sql.startsWith('CREATE TABLE')) await store.executeSql(sql);
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
      const clearPending = (): void => {
        if (this.initPromise === attempt) this.initPromise = null;
      };
      attempt.then(clearPending, clearPending);
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
    // 捕获调用时的删除代际:若入队后发生删除/授权恢复，代际不符即判迟到旧写
    const gen: number = this.deletedGen.get(conv.id) ?? 0;
    return this.enqueueMutation(conv.id, async (): Promise<void> => {
      if (this.isDeletedAt(conv.id, gen)) {
        throw new Error(`Conversation was deleted: ${conv.id}`);
      }
      return enqueueConversationFileMutation(async (): Promise<void> => {
        if (this.isDeletedAt(conv.id, gen)) {
          throw new Error(`Conversation was deleted: ${conv.id}`);
        }
        const store = await this.ensureStore();
        if (store === null) {
          // R04:拒绝写入不可持久化的降级内存，避免退出即丢且无可见警告
          throw new Error(DEGRADED_WRITE_MSG);
        }
        await this.writeConversation(store, conv);
      });
    });
  }

  // 批量恢复(备份还原):单 RDB 事务；先授权(清墓碑+代际自增挡排队旧写)，
  // 再等涉及会话在途 mutation 落定，最后单事务写入全部会话(R03 全有或全无)。
  async restoreConversations(convs: Conversation[]): Promise<void> {
    if (convs.length === 0) return;
    const ids: string[] = convs.map((c: Conversation): string => c.id);
    // 授权恢复:清墓碑并自增代际；此刻起此前排队的旧 save 因代际不符被拒，
    // 不会被恢复后的旧快照覆盖(R09「显式恢复」而非简单清墓碑放旧写)
    for (const id of ids) {
      this.deletedGen.set(id, (this.deletedGen.get(id) ?? 0) + 1);
      this.deletedIds.delete(id);
    }
    // 等涉及会话的在途 mutation(含更早排队的旧 save/delete)全部落定
    for (const id of ids) {
      const tail: Promise<void> | undefined = this.mutationTails.get(id);
      if (tail !== undefined) await tail.catch((): void => {});
    }
    return enqueueConversationFileMutation(async (): Promise<void> => {
      const store = await this.ensureStore();
      if (store === null) throw new Error(DEGRADED_WRITE_MSG);
      // 进入事务前再次授权(覆盖等待期间可能的并发删除)，确保恢复结果不被墓碑阻断
      for (const id of ids) {
        this.deletedGen.set(id, (this.deletedGen.get(id) ?? 0) + 1);
        this.deletedIds.delete(id);
      }
      let committed: boolean = false;
      store.beginTransaction();
      try {
        for (const conv of convs) {
          await this.writeConversationBody(store, conv);
        }
        store.commit();
        committed = true;
      } finally {
        if (!committed) {
          try { store.rollBack(); } catch { /* preserve original restore failure */ }
        }
      }
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
    // 列表/搜索一律 ORDER BY is_pinned DESC, update_at DESC(D-013);
    // id 升序兜底保证同毫秒排序稳定(与内存实现 byPinnedThenUpdated 一致)
    predicates.orderByDesc('is_pinned');
    predicates.orderByDesc('update_at');
    predicates.orderByAsc('id');
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
    predicates.orderByAsc('id');
    const rows: ConversationRow[] = await this.queryConversationRows(store, predicates);
    const needle: string = searchText.toLowerCase();
    return rows
      .filter((r: ConversationRow): boolean => r.title.toLowerCase().includes(needle))
      .map((r: ConversationRow): ConversationSummary => ChatRdbRepository.summaryOf(r));
  }

  async searchMessages(searchText: string, limit: number): Promise<MessageSearchHit[]> {
    const term: string = searchText.trim();
    if (term.length === 0 || limit <= 0) return [];
    const store = await this.ensureStore();
    if (store === null) return this.memory.searchMessages(term, limit);
    // LIKE 粗筛(JSON blob 明文子串;关键词含引号/换行时可能因 JSON 转义假阴性,
    // 个人级消息量可接受)→ 纯文本精滤后每会话取 node_index 最大的真实命中
    const predicates = new relationalStore.RdbPredicates('message_node');
    predicates.like('messages', `%${term}%`);
    const needle: string = term.toLowerCase();
    // convId → 最新正文命中,JSON 字段名/metadata 命中不参与最新行选择
    const latestByConv = new Map<string, RawMessageHit>();
    const rs = await store.query(predicates, ['conversation_id', 'messages', 'node_index']);
    try {
      while (rs.goToNextRow()) {
        const convId: string = rs.getString(0);
        const nodeIndex: number = rs.getLong(2);
        const prev: RawMessageHit | undefined = latestByConv.get(convId);
        if (prev === undefined || nodeIndex > prev.nodeIndex) {
          const plain: string = messageNodeRowPlainText(rs.getString(1));
          if (plain.toLowerCase().indexOf(needle) < 0) continue;
          latestByConv.set(convId, { plainText: plain, nodeIndex: nodeIndex });
        }
      }
    } finally {
      rs.close();
    }
    if (latestByConv.size === 0) return [];
    const convIds: string[] = [];
    latestByConv.forEach((_v: RawMessageHit, k: string): void => { convIds.push(k); });
    // 会话元数据批量查(in 子句)
    const convPredicates = new relationalStore.RdbPredicates('conversation');
    convPredicates.in('id', convIds);
    convPredicates.orderByDesc('update_at');
    const convRows: ConversationRow[] = await this.queryConversationRows(store, convPredicates);
    const hits: MessageSearchHit[] = [];
    for (const conv of convRows) {
      const raw: RawMessageHit | undefined = latestByConv.get(conv.id);
      if (raw === undefined) continue;
      hits.push({
        conversationId: conv.id,
        conversationTitle: conv.title,
        snippet: snippetAround(raw.plainText, needle),
        updateAt: ChatRdbRepository.summaryOf(conv).updateAt,
      });
      if (hits.length >= Math.max(0, limit)) break;
    }
    return hits;
  }

  async delete(id: string): Promise<void> {
    // 代际自增 + 立墓碑:挡住此前已排队的旧 save(其捕获的 gen 已过期)
    const gen: number = (this.deletedGen.get(id) ?? 0) + 1;
    this.deletedGen.set(id, gen);
    this.deletedIds.add(id);
    return this.enqueueMutation(id, async (): Promise<void> =>
      enqueueConversationFileMutation(async (): Promise<void> => {
        // R04:先判持久化可用性，再判存在性。降级时 getById 回退(通常为空)内存会使
        // old===null 提前 return，从而绕过拒绝写并静默「删除成功」(重启复现)。
        const store = await this.ensureStore();
        if (store === null) {
          throw new Error(DEGRADED_WRITE_MSG);
        }
        const old: Conversation | null = await this.getById(id);
        if (old === null) return;
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
      }).catch((e: Error): void => {
        // 仅当本次删除代际仍为最新时撤墓碑(旧代际的墓碑属更新的删除/恢复，不动)
        if ((this.deletedGen.get(id) ?? 0) === gen) this.deletedIds.delete(id);
        throw e;
      }));
  }
}

export const createChatRdbRepository = (): ConversationRepository => {
  return new ChatRdbRepository();
};
