// ChatKvStore — chat-domain KeyValueStore Port 的 Preferences 适配
// 复用 deepread Storage(PreferencesStorage)实现字符串 KV 语义。

import type { Storage } from '@amber/deepread-domain';
import type { KeyValueStore } from '@amber/chat-domain';

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
}

export const createChatKvStore = (storage: Storage): KeyValueStore => new ChatKvStore(storage);
