// EntryCompactStore — CompactStore Port 的 KV 实现(设备端 adapter)
//
// Android 基准: ConversationContextRepository.kt(Room DAO;
//   conversation_compact 表 + conversation_context_event 审计表)
// 存储形态(D-052 同口径):KV 键
//   compacts/<conversationId>         — ConversationCompact[] JSON(我方 schema)
//   compacts/<conversationId>.events  — 审计 jsonl(read-concat-write)
// 偏差登记:Room 行级存储 → KV blob 整体读写(单页面使用无并发写者);
//   search/expand(repository:97-134)未接(工具循环落地时一并接入)

import type { CompactStore, ConversationCompact, KeyValueStore } from '@amber/chat-domain';

const compactsKey = (conversationId: string): string => `compacts/${conversationId}`;
const compactsEventsKey = (conversationId: string): string => `compacts/${conversationId}.events`;

class EntryCompactStore implements CompactStore {
  private readonly kv: KeyValueStore;

  constructor(kv: KeyValueStore) {
    this.kv = kv;
  }

  async getCompacts(conversationId: string): Promise<ConversationCompact[]> {
    const raw: string | null = await this.kv.get(compactsKey(conversationId));
    if (raw === null) return [];
    try {
      const parsed: ConversationCompact[] = JSON.parse(raw) as ConversationCompact[];
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }

  async insertCompact(compact: ConversationCompact): Promise<void> {
    const list: ConversationCompact[] = await this.getCompacts(compact.conversationId);
    await this.kv.put(compactsKey(compact.conversationId), JSON.stringify([...list, compact]));
  }

  async deleteByConversation(conversationId: string): Promise<void> {
    await this.kv.delete(compactsKey(conversationId));
  }

  async recordEvent(conversationId: string, eventType: string,
    summaryId: string | null, message: string): Promise<void> {
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
  }
}

export const createEntryCompactStore = (kv: KeyValueStore): CompactStore =>
  new EntryCompactStore(kv);
