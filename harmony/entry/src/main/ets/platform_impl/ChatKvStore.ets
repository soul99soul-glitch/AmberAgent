// ChatKvStore — chat-domain KeyValueStore Port 的 Preferences 适配
// 复用 deepread Storage(PreferencesStorage)实现字符串 KV 语义。

import type { Storage } from '@amber/deepread-domain';
import type { KeyValueStore } from '@amber/chat-domain';

// ArkTS 不允许对象字面量类型;allEntries 返回元素的具名形态
interface StringKVEntry {
  key: string;
  value: string;
}

class ChatKvStore implements KeyValueStore {
  private storage: Storage;

  constructor(storage: Storage) {
    this.storage = storage;
  }

  async get(key: string): Promise<string | null> {
    return this.storage.get<string | null>(key, null);
  }

  async put(key: string, value: string): Promise<void> {
    await this.storage.set<string>(key, value);
  }

  async delete(key: string): Promise<void> {
    await this.storage.delete(key);
  }

  // KeyValueStore.allEntries(可选契约;MiniAppStorage 配额核算前缀扫描)
  async allEntries(): Promise<StringKVEntry[]> {
    return this.storage.getAllStringEntries();
  }
}

export const createChatKvStore = (storage: Storage): KeyValueStore => new ChatKvStore(storage);
