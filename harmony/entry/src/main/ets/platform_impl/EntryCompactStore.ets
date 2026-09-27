// EntryCompactStore — CompactStore Port 的 KV 实现(设备端 adapter)
//
// Android 基准: ConversationContextRepository.kt(Room DAO;
//   conversation_compact 表 + conversation_context_event 审计表)
// 存储形态(D-052 同口径):KV 键
//   compacts/<conversationId>         — ConversationCompact[] JSON(我方 schema)
//   compacts/<conversationId>.events  — 审计 jsonl(read-concat-write)
// 偏差登记:Room 行级存储 → KV blob 整体读写(单页面使用无并发写者);
//   conversation_search/expand 工具已接(ChatPage → createConversationContextTools,
//   检索走 domain searchConversationContext/expandConversationContext)。

import type { CompactStore, ConversationCompact, KeyValueStore } from '@amber/chat-domain';

const compactsKey = (conversationId: string): string => `compacts/${conversationId}`;
const compactsEventsKey = (conversationId: string): string => `compacts/${conversationId}.events`;
const deletedMarkerKey = (conversationId: string): string => `compacts/${conversationId}.deleted`;

// R11:删除标记(代际保护)。
// 会话删除后,若仍有在途摘要落到同一 conversationId,insertCompact 会把它写回,
// 形成「已删除会话的摘要复活」。删除时落一个 deleted 标记,后续 insert 遇到标记
// 一律拒绝;记录类重放(recordEvent)在删除后也拒绝写入。没有标记就当首次,
// 不引入额外写放大。
export const deleteConversationCompactData = async (
  kv: KeyValueStore, conversationId: string,
): Promise<void> => {
  await kv.put(deletedMarkerKey(conversationId), JSON.stringify({ deletedAt: Date.now() }));
  await kv.delete(compactsKey(conversationId));
  await kv.delete(compactsEventsKey(conversationId));
};

// 显式「授权重建」时才清除删除标记(R09 备份恢复 / 产品级撤销删除的接线入口)。
// 读路径与 compaction 路径绝不自动清除,否则在途摘要会借清除之机复活。
export const clearConversationDeletedMarker = async (
  kv: KeyValueStore, conversationId: string,
): Promise<void> => {
  await kv.delete(deletedMarkerKey(conversationId));
};

class EntryCompactStore implements CompactStore {
  private readonly kv: KeyValueStore;
  // 同会话写串行:insert/recordEvent 都是 read-modify-write,并发会互相覆盖
  // (丢摘要/丢审计行)
  private readonly writeTails: Map<string, Promise<void>> = new Map();

  constructor(kv: KeyValueStore) {
    this.kv = kv;
  }

  private enqueueWrite(conversationId: string, op: () => Promise<void>): Promise<void> {
    const previous: Promise<void> = this.writeTails.get(conversationId) ?? Promise.resolve();
    const next: Promise<void> = previous.catch((): void => {}).then(op);
    this.writeTails.set(conversationId, next);
    return next.finally((): void => {
      if (this.writeTails.get(conversationId) === next) this.writeTails.delete(conversationId);
    });
  }

  private async isDeleted(conversationId: string): Promise<boolean> {
    const marker: string | null = await this.kv.get(deletedMarkerKey(conversationId));
    return marker !== null;
  }

  async getCompacts(conversationId: string): Promise<ConversationCompact[]> {
    if (await this.isDeleted(conversationId)) return [];
    const raw: string | null = await this.kv.get(compactsKey(conversationId));
    if (raw === null) return [];
    try {
      const parsed: ConversationCompact[] = JSON.parse(raw) as ConversationCompact[];
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }

  insertCompact(compact: ConversationCompact): Promise<void> {
    return this.enqueueWrite(compact.conversationId, async (): Promise<void> => {
      // 删除标记先于写检查:在途摘要迟到不会复活已删除会话
      if (await this.isDeleted(compact.conversationId)) return;
      const list: ConversationCompact[] = await this.getCompacts(compact.conversationId);
      const key: string = compactsKey(compact.conversationId);
      await this.kv.put(key, JSON.stringify([...list, compact]));
      // 写后复核:deleteConversationCompactData 不走本队列,可能在两次 await 之间
      // 落标记并删键;若如此,撤回本次写入,避免摘要成为不可见残留
      if (await this.isDeleted(compact.conversationId)) await this.kv.delete(key);
    });
  }

  deleteByConversation(conversationId: string): Promise<void> {
    // 入同一写队列:invalidate 与飞行中 precompact 的 read-put 竞态会把
    // 已失效的摘要写回复活
    return this.enqueueWrite(conversationId, async (): Promise<void> => {
      await this.kv.delete(compactsKey(conversationId));
    });
  }

  recordEvent(conversationId: string, eventType: string,
    summaryId: string | null, message: string): Promise<void> {
    return this.enqueueWrite(conversationId, async (): Promise<void> => {
      if (await this.isDeleted(conversationId)) return;
      const key: string = compactsEventsKey(conversationId);
      const existing: string | null = await this.kv.get(key);
      const line: string = JSON.stringify({
        created_at_ms: Date.now(),
        conversation_id: conversationId,
        event_type: eventType,
        summary_id: summaryId,
        message,
      });
      await this.kv.put(key, `${existing ?? ''}${line}\n`);
      if (await this.isDeleted(conversationId)) await this.kv.delete(key);
    });
  }
}

export const createEntryCompactStore = (kv: KeyValueStore): CompactStore =>
  new EntryCompactStore(kv);
