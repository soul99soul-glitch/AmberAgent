// SearchSdkInit — 搜索 SDK 运行时装配(D-070 entry 接线)
// Android 基准:
//   DataSourceModule.kt:406 — SearchService.init(okHttpClient, context)
//   KeyRoulette.kt:15-18,43,88-98 — lru(context) 持久化 cacheDir/lru_key_roulette.json
//   BingSearchService.kt:51-52 — Locale.getDefault() → "${language}-${country},${language}"
// 偏差:OkHttpClient → RcpHttpClient(同 HttpClient Port);Locale → i18n.System
import { fileIo } from '@kit.CoreFileKit';
import { i18n } from '@kit.LocalizationKit';
import type { LruKeyRouletteStore } from '@amber/chat-domain';
import { createLruKeyRoulette, initSearchSdk } from '@amber/chat-domain';
import { getAppContainer } from '../di/AppContainer.ets';

// KeyRoulette.kt:43(文件名逐字;cacheDir 同 Android)
const LRU_CACHE_FILE: string = 'lru_key_roulette.json';

let initialized: boolean = false;

const cacheFilePath = (): string => {
  const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
  return `${ctx.cacheDir}/${LRU_CACHE_FILE}`;
};

// LruKeyRouletteStore(sync;读失败 → null/空缓存,写失败吞咽 — 同 Android catch 语义)
const lruStore: LruKeyRouletteStore = {
  read: (): string | null => {
    try {
      const path: string = cacheFilePath();
      if (!fileIo.accessSync(path)) return null;
      return fileIo.readTextSync(path);
    } catch {
      return null;
    }
  },
  write: (content: string): void => {
    try {
      const path: string = cacheFilePath();
      const f = fileIo.openSync(path, fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
      fileIo.writeSync(f.fd, content);
      fileIo.closeSync(f);
    } catch {
      // Android catch {} 静默(平台缓存,非业务数据)
    }
  },
  nowMs: (): number => Date.now(),
};

// BingSearchService.kt:52 — "${language}-${country},${language}"
const acceptLanguage = (): string => {
  try {
    const lang: string = i18n.System.getSystemLanguage(); // e.g. 'zh-Hans' / 'en'
    const region: string = i18n.System.getSystemRegion(); // e.g. 'CN' / 'US'
    const primary: string = lang.split('-')[0];
    if (primary.length === 0) return 'zh-CN,zh';
    if (region.length === 0) return `${primary},${primary}`;
    return `${primary}-${region},${primary}`;
  } catch {
    return 'zh-CN,zh';
  }
};

// DataSourceModule.kt:406 等价:装配一次(幂等)
export const ensureSearchSdk = (): void => {
  if (initialized) return;
  initSearchSdk(getAppContainer().httpClient, createLruKeyRoulette(lruStore), acceptLanguage);
  initialized = true;
};
