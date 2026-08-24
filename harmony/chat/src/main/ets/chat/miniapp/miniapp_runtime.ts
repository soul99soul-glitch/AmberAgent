// miniapp_runtime — MiniAppEventBus / MiniAppLaunchLimiter / MiniAppAiBudget
//
// Android 基准: feature/miniapp/MiniAppV3Runtime.kt(112-160,101-106)
// 偏差:
//   - Android 全局 object(MiniAppEventBus/MiniAppLaunchLimiter) → 实例工厂
//     (测试隔离;entry 维护单例,语义等价)
//   - JsonElement → JsonValue(models 值域)
//   - 时间用 clock 注入(默认 Date.now;Android System.currentTimeMillis)
//   - AiBudget 的 KV 持久化可选注入(store 缺省 → 内存 Map;Android 恒用 SharedPreferences)
//   - LocalDate.now() → dayKey 注入(默认 yyyy-MM-dd 本地日期)
//   - 每 app 订阅 ≤ 20 / 30s ≤ 3 次 / 每日 50 次预算常量保留

import { MiniAppValidationException } from './miniapp_models.ts';
import type { JsonValue } from '../json.ts';
import { newId } from '../ids.ts';
import type { KeyValueStore } from '../kv_store.ts';

// ===== MiniAppEventBus(MiniAppV3Runtime.kt:112-144)=====

export interface MiniAppEventBus {
  subscribe(appId: string, namespace: string, topic: string, callback: (payload: JsonValue) => void): string;
  publish(namespace: string, topic: string, payload: JsonValue): void;
  unsubscribe(id: string): void;
}

interface MiniAppEventSubscription {
  appId: string;
  namespace: string;
  topic: string;
  callback: (payload: JsonValue) => void;
}

export const MINI_APP_EVENT_BUS_MAX_SUBSCRIPTIONS_PER_APP: number = 20;

export const createMiniAppEventBus = (idGen: () => string = newId): MiniAppEventBus => {
  const subscriptions: Map<string, MiniAppEventSubscription> = new Map();

  const subscribe = (
    appId: string, namespace: string, topic: string, callback: (payload: JsonValue) => void,
  ): string => {
    let count: number = 0;
    for (const sub of subscriptions.values()) {
      if (sub.appId === appId) count++;
    }
    if (count >= MINI_APP_EVENT_BUS_MAX_SUBSCRIPTIONS_PER_APP) {
      throw new MiniAppValidationException('Too many event subscriptions');
    }
    const id: string = idGen();
    subscriptions.set(id, { appId, namespace, topic, callback });
    return id;
  };

  const publish = (namespace: string, topic: string, payload: JsonValue): void => {
    for (const sub of subscriptions.values()) {
      if (sub.namespace === namespace && sub.topic === topic) {
        sub.callback(payload);
      }
    }
  };

  const unsubscribe = (id: string): void => {
    subscriptions.delete(id);
  };

  return { subscribe, publish, unsubscribe };
};

// ===== MiniAppLaunchLimiter(MiniAppV3Runtime.kt:146-160)=====

export const MINI_APP_LAUNCH_WINDOW_MS: number = 30_000;
export const MINI_APP_LAUNCH_MAX_IN_WINDOW: number = 3;

export interface MiniAppLaunchLimiter {
  check(): void;
}

export const createMiniAppLaunchLimiter = (clock: () => number = (): number => Date.now()): MiniAppLaunchLimiter => {
  const launches: number[] = [];
  return {
    check(): void {
      const now: number = clock();
      while (launches.length > 0 && now - launches[0] > MINI_APP_LAUNCH_WINDOW_MS) {
        launches.shift();
      }
      if (launches.length >= MINI_APP_LAUNCH_MAX_IN_WINDOW) {
        throw new MiniAppValidationException('Launch rate limit exceeded');
      }
      launches.push(now);
    },
  };
};

// ===== MiniAppAiBridge.consumeDailyBudget(MiniAppV3Runtime.kt:101-106)=====

export const MINI_APP_AI_DAILY_BUDGET: number = 50;

const pad2 = (n: number): string => (n < 10 ? `0${n}` : `${n}`);

export const defaultMiniAppAiDayKey = (clock: () => number = (): number => Date.now()): string => {
  const d: Date = new Date(clock());
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
};

export interface MiniAppAiBudgetDeps {
  appId: string;
  dayKey?: () => string;
  store?: KeyValueStore;
}

export interface MiniAppAiBudget {
  consume(): Promise<void>;
}

export const createMiniAppAiBudget = (deps: MiniAppAiBudgetDeps): MiniAppAiBudget => {
  const dayKey: () => string = deps.dayKey ?? defaultMiniAppAiDayKey;
  const memory: Map<string, number> = new Map();
  // consume 串行化(按 key):并发 read-check-write 会同时读到同一计数,
  // 限额被并发绕过(一个 WebView 可并行发多个 fetch)
  const consumeLocks: Map<string, Promise<void>> = new Map<string, Promise<void>>();

  const readCount = async (key: string): Promise<number> => {
    if (deps.store !== undefined) {
      const raw: string | null = await deps.store.get(key);
      const n: number = raw === null ? 0 : Number.parseInt(raw, 10);
      return Number.isNaN(n) ? 0 : n;
    }
    const v: number | undefined = memory.get(key);
    return v === undefined ? 0 : v;
  };

  const writeCount = async (key: string, count: number): Promise<void> => {
    if (deps.store !== undefined) {
      await deps.store.put(key, String(count));
    } else {
      memory.set(key, count);
    }
  };

  return {
    async consume(): Promise<void> {
      const key: string = `${deps.appId}_${dayKey()}`;
      const prev: Promise<void> = consumeLocks.get(key) ?? Promise.resolve();
      let release!: () => void;
      const gate: Promise<void> = new Promise<void>((r: () => void): void => {
        release = r;
      });
      const queued: Promise<void> = prev.then((): Promise<void> => gate);
      consumeLocks.set(key, queued);
      await prev;
      try {
        const count: number = await readCount(key);
        if (count >= MINI_APP_AI_DAILY_BUDGET) {
          throw new MiniAppValidationException('Daily MiniApp AI budget exceeded');
        }
        await writeCount(key, count + 1);
      } finally {
        release();
        if (consumeLocks.get(key) === queued) consumeLocks.delete(key);
      }
    },
  };
};
