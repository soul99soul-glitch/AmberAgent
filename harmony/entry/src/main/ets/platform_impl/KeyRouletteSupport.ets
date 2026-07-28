// KeyRouletteSupport — chat provider 多 key 轮换(D-078,PD-008 消除)
// Android 基准:
//   KeyRoulette.kt:43 LRU_CACHE_FILE='lru_key_roulette.json'(cacheDir 逐字)
//   OpenAIProvider.kt:61 / ClaudeProvider.kt:68 / GoogleProvider.kt:77 —
//     context != null → KeyRoulette.lru(context)(app 运行时恒 LRU)
// 文件读写失败语义:loadCache 解析失败 → 空;saveCache 吞咽(search_service 已忠实)
import { fileIo } from '@kit.CoreFileKit';
import { createLruKeyRoulette } from '@amber/chat-domain';
import type { KeyRoulette, LruKeyRouletteStore } from '@amber/chat-domain';

export const LRU_KEY_ROULETTE_FILE: string = 'lru_key_roulette.json';

const filePath = (): string => {
  const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
  return `${ctx.cacheDir}/${LRU_KEY_ROULETTE_FILE}`;
};

const store: LruKeyRouletteStore = {
  read: (): string | null => {
    try {
      const path: string = filePath();
      if (!fileIo.accessSync(path)) return null;
      return fileIo.readTextSync(path);
    } catch {
      return null;
    }
  },
  write: (content: string): void => {
    try {
      const path: string = filePath();
      const mode: number = fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC;
      const file: fileIo.File = fileIo.openSync(path, mode);
      try {
        fileIo.writeSync(file.fd, content);
      } finally {
        fileIo.closeSync(file);
      }
    } catch {
      // Android saveCache catch {} — 吞咽
    }
  },
  nowMs: (): number => Date.now(),
};

let singleton: KeyRoulette | null = null;

// app 运行时恒 LRU(Android context != null 分支)
export const getChatKeyRoulette = (): KeyRoulette => {
  if (singleton === null) {
    singleton = createLruKeyRoulette(store);
  }
  return singleton;
};
