import type { AbortSignalLike } from './runtime_api.ts';
// Observable<T> — MVP 用 1s 轮询实现(对应 Android Room Flow)
// 升级路径:后续可换事件总线

export interface Observable<T> {
  subscribe(cb: (value: T) => void): () => void;
  getCurrent(): T | undefined;
}

export interface PollingOptions {
  intervalMs?: number;
  signal?: AbortSignalLike;
}

export const pollingObservable = <T>(
  fetcher: () => Promise<T>,
  opts: PollingOptions = {},
): Observable<T> => {
  const intervalMs = opts.intervalMs ?? 1000;
  const signal = opts.signal;
  const subscribers = new Set<(value: T) => void>();
  let current: T | undefined = undefined;
  let timer: ReturnType<typeof setInterval> | null = null;
  let lastNotifiedSerialized: string | undefined = undefined;

  const notify = (value: T): void => {
    const serialized = JSON.stringify(value);
    if (serialized === lastNotifiedSerialized) return;
    lastNotifiedSerialized = serialized;
    current = value;
    for (const cb of subscribers) {
      try { cb(value); } catch { /* 单个回调失败不影响其他 */ }
    }
  };

  const poll = async (): Promise<void> => {
    if (signal?.aborted) {
      if (timer) clearInterval(timer);
      timer = null;
      return;
    }
    try {
      const value = await fetcher();
      notify(value);
    } catch {
      // fetcher 失败静默,下次轮询重试
    }
  };

  const start = (): void => {
    if (timer !== null) return;
    poll();
    timer = setInterval(() => { void poll(); }, intervalMs);
  };

  const stop = (): void => {
    if (timer !== null) {
      clearInterval(timer);
      timer = null;
    }
  };

  if (signal?.addEventListener !== undefined) {
    signal.addEventListener('abort', stop);
  }

  return {
    subscribe(cb: (value: T) => void): () => void {
      subscribers.add(cb);
      if (subscribers.size === 1) start();
      if (current !== undefined) {
        try { cb(current); } catch { /* ignore */ }
      }
      return () => {
        subscribers.delete(cb);
        if (subscribers.size === 0) stop();
      };
    },
    getCurrent(): T | undefined {
      return current;
    },
  };
};
