// council/semaphore — 简单信号量,用于按 provider 限流并发模型调用
// 对应 Android ModelCouncilManager 里 per-provider Semaphore(4) + withPermit。

export class Semaphore {
  private permits: number;
  private waiters: Array<() => void> = [];

  constructor(permits: number) {
    this.permits = permits;
  }

  acquire(): Promise<void> {
    if (this.permits > 0) {
      this.permits -= 1;
      return Promise.resolve();
    }
    return new Promise<void>(resolve => {
      this.waiters.push(resolve);
    });
  }

  release(): void {
    const next: (() => void) | undefined = this.waiters.shift();
    if (next !== undefined) {
      next();
    } else {
      this.permits += 1;
    }
  }

  // 当前可用许可数(测试用)
  available(): number {
    return this.permits;
  }
}

export const withPermit = async <T>(sem: Semaphore, fn: () => Promise<T>): Promise<T> => {
  await sem.acquire();
  try {
    return await fn();
  } finally {
    sem.release();
  }
};
