// ArkTS 运行时 shim — 提供 Node/Web 全局 API 的 ArkTS 兼容实现
// HarmonyOS ArkTS 不提供 URL / URLSearchParams / AbortController / AbortSignal / crypto。
// 这些 shim 让 deepread 纯逻辑层在 ArkTS 下编译 + 运行。
//
// 注意:这些是轻量实现,够 deepread 用(解析 URL hostname、生成 hash、取消信号)。
// 真实 HTTP 的 abort 由 RCP session 自己的 cancel 处理。

// ===== URL shim(仅解析 hostname/protocol/pathname,够 url_filter / source_prefetcher 用) =====
export class URL {
  href: string;
  protocol: string;
  hostname: string;
  host: string;
  pathname: string;
  search: string;
  hash: string;
  origin: string;

  constructor(input: string, base?: string) {
    let full = input;
    // 相对 URL 解析(简化:只处理 / 开头 + base)
    if (base !== undefined && input.startsWith('/')) {
      const baseM = /^[^:]+:\/\/[^/]+/.exec(base);
      full = baseM !== null ? baseM[0] + input : input;
    }
    this.href = full;
    // 解析 protocol://host[:port]/path?search#hash
    const m = /^([^:]+):\/\/([^/?#]+)([^?#]*)(\?[^#]*)?(#.*)?$/.exec(full);
    if (m !== null) {
      this.protocol = m[1] + ':';
      this.host = m[2];
      this.hostname = m[2].split(':')[0];
      this.pathname = m[3].length > 0 ? m[3] : '/';
      this.search = m[4] ?? '';
      this.hash = m[5] ?? '';
      this.origin = `${m[1]}://${m[2]}`;
    } else {
      this.protocol = '';
      this.hostname = '';
      this.host = '';
      this.pathname = full;
      this.search = '';
      this.hash = '';
      this.origin = '';
    }
  }

  toString(): string { return this.href; }
}

// ===== URLSearchParams shim(极简:构造 + get) =====
export class URLSearchParams {
  private params: Map<string, string> = new Map();

  constructor(init?: string | Map<string, string>) {
    if (init === undefined) return;
    if (typeof init === 'string') {
      const q = init.startsWith('?') ? init.slice(1) : init;
      if (q.length === 0) return;
      for (const pair of q.split('&')) {
        const eq = pair.indexOf('=');
        if (eq >= 0) {
          this.params.set(pair.slice(0, eq), pair.slice(eq + 1));
        } else {
          this.params.set(pair, '');
        }
      }
    } else {
      init.forEach((v: string, k: string) => { this.params.set(k, v); });
    }
  }

  get(name: string): string | null {
    const v = this.params.get(name);
    return v !== undefined ? v : null;
  }

  has(name: string): boolean {
    return this.params.has(name);
  }

  forEach(callback: (value: string, key: string) => void): void {
    this.params.forEach((v: string, k: string) => callback(v, k));
  }
}

// ===== AbortController / AbortSignal shim =====
// ArkTS 无 AbortController。轻量实现:event-based abort。
// 真实 HTTP abort 由 RCP session.cancel 处理;此处仅用于逻辑层信号传递。
export class AbortSignal {
  aborted: boolean = false;
  private listeners: Array<() => void> = [];

  addEventListener(_type: string, listener: () => void): void {
    this.listeners.push(listener);
  }

  removeEventListener(_type: string, listener: () => void): void {
    this.listeners = this.listeners.filter(l => l !== listener);
  }

  _fire(): void {
    this.aborted = true;
    for (const l of this.listeners) {
      try { l(); } catch { /* 忽略 listener 错误 */ }
    }
  }
}

export class AbortController {
  signal: AbortSignal = new AbortSignal();
  abort(): void {
    this.signal._fire();
  }
}

// ===== crypto shim:FNV-1a hash 替代(够 topic_id 用,非密码学) =====
// topic_id 只需要确定性 hash,不需要密码学强度。
export const createHashShim = (algorithm: string): { update: (d: string) => { digest: (enc: string) => string } } => {
  void algorithm;  // ArkTS 不关心算法,统一用 FNV-1a
  let h = 0x811c9dc5;
  return {
    update: (data: string) => {
      for (let i = 0; i < data.length; i++) {
        h ^= data.charCodeAt(i);
        h = (h + ((h << 1) + (h << 4) + (h << 7) + (h << 8) + (h << 24))) >>> 0;
      }
      return {
        digest: (enc: string): string => {
          // 返回十六进制字符串(对照 node crypto hex)
          const hex = h.toString(16).padStart(8, '0');
          // topic_id 期望 32 位 hex(MD5 长度),重复填充
          void enc;
          return hex + hex + hex + hex;
        },
      };
    },
  };
};
