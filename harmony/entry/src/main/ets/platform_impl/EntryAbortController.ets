// EntryAbortController — AbortControllerLike 的 entry 实现
// ArkTS 运行时不暴露全局 AbortController;deepread runtime_api 为此定义了
// AbortControllerLike 接口(双运行时兼容设计,见 runtime_api.ts 头注)。
// RcpHttpClient 消费 signal.addEventListener('abort', …),本实现满足该形态。

import type { AbortControllerLike, AbortSignalLike } from '@amber/deepread-domain';

class EntryAbortSignal implements AbortSignalLike {
  public aborted: boolean = false;
  private listeners: Array<() => void> = [];

  public addEventListener(type: string, listener: () => void): void {
    if (type !== 'abort') return;
    if (this.aborted) {
      listener();
      return;
    }
    this.listeners.push(listener);
  }

  public removeEventListener(type: string, listener: () => void): void {
    if (type !== 'abort') return;
    const index: number = this.listeners.indexOf(listener);
    if (index >= 0) this.listeners.splice(index, 1);
  }

  public fire(): void {
    this.aborted = true;
    const pending: Array<() => void> = this.listeners;
    this.listeners = [];
    for (const listener of pending) {
      listener();
    }
  }
}

export class EntryAbortController implements AbortControllerLike {
  public readonly signal: AbortSignalLike;

  private readonly inner: EntryAbortSignal;

  constructor() {
    this.inner = new EntryAbortSignal();
    this.signal = this.inner;
  }

  public abort(): void {
    this.inner.fire();
  }
}

export const createEntryAbortController = (): AbortControllerLike => new EntryAbortController();
