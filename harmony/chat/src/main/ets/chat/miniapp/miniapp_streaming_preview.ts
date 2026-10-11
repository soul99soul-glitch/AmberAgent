// miniapp_streaming_preview — 流式小应用卡片的代码预览窗口
//
// iOS 基准: IOSMiniAppChatCard.swift IOSMiniAppStreamingCodePreviewState
// 偏差:
//   - 只解码 JSON 的 "html" 字段;不做裸 HTML 回退(鸿蒙 transformer 只认 JSON 三字段,
//     流式卡片判定同口径,裸 HTML 不会进卡片)
//   - JS 字符串本身是 UTF-16,\uXXXX 逐码元拼接即可还原代理对,无需单独缓存高代理

export const MINI_APP_STREAM_PREVIEW_LIMIT: number = 2000;

export interface MiniAppStatusProjection {
  title: string;
  version: number | null;
}

const GENERATED_STATUS: RegExp = /^已生成小应用：(.+)$/;
const UPDATED_STATUS: RegExp = /^已更新小应用：(.+?) v([1-9]\d*)$/;

// 只把 output transformer 的整段终态文案当作状态;附加解释仍保留为正文。
export const miniAppStatusProjection = (text: string): MiniAppStatusProjection | null => {
  const normalized: string = text.trim();
  const updated: RegExpMatchArray | null = normalized.match(UPDATED_STATUS);
  if (updated !== null && updated[1] !== undefined && updated[2] !== undefined) {
    const title: string = updated[1].trim();
    if (title.length > 0) return { title, version: Number.parseInt(updated[2], 10) };
  }
  const generated: RegExpMatchArray | null = normalized.match(GENERATED_STATUS);
  if (generated !== null && generated[1] !== undefined && generated[1].trim().length > 0) {
    return { title: generated[1].trim(), version: null };
  }
  return null;
};

const HTML_KEY: string = '"html"';

const SIMPLE_ESCAPES: Record<string, string> = {
  'n': '\n', 't': '\t', 'r': '\r', 'b': '\b', 'f': '\f', '"': '"', '\\': '\\', '/': '/',
};

type ScanState = 'searchingKey' | 'waitingValue' | 'readingValue' | 'finished';

// 消息 API 每次给累计快照:追加时只消费新增后缀,非追加(重生成/替换)时重置。
export class MiniAppStreamingCodePreview {
  private raw: string = '';
  private state: ScanState = 'searchingKey';
  private escaping: boolean = false;
  private unicodeDigits: string | null = null;
  private decoded: string = '';

  update(next: string): void {
    if (!next.startsWith(this.raw)) this.reset();
    const from: number = this.raw.length;
    this.raw = next;
    this.consume(from);
  }

  // 返回已经解码的 HTML 全文；JSON 字符串尚未开始时不把标题/描述伪装成 HTML。
  htmlText(): string {
    return this.state === 'readingValue' || this.state === 'finished' ? this.decoded : '';
  }

  // 尾部窗口:超出上限时只保留最后 limit 字并注明省略数,避免长文本反复排版。
  displayText(limit: number = MINI_APP_STREAM_PREVIEW_LIMIT): string {
    const source: string = this.state === 'readingValue' || this.state === 'finished'
      ? this.htmlText() : this.raw;
    if (source.length <= limit) return source;
    return `已省略 ${source.length - limit} 字\n${source.slice(-limit)}`;
  }

  private reset(): void {
    this.raw = '';
    this.state = 'searchingKey';
    this.escaping = false;
    this.unicodeDigits = null;
    this.decoded = '';
  }

  private consume(from: number): void {
    let i: number = from;
    if (this.state === 'searchingKey') {
      // 回退 key 长度,覆盖跨快照切开的 "html"
      const at: number = this.raw.indexOf(HTML_KEY, Math.max(0, from - HTML_KEY.length + 1));
      if (at < 0) return;
      this.state = 'waitingValue';
      i = at + HTML_KEY.length;
    }
    if (this.state === 'waitingValue') {
      while (i < this.raw.length && this.raw[i] !== '"') i++;
      if (i >= this.raw.length) return;
      this.state = 'readingValue';
      i++;
    }
    if (this.state !== 'readingValue') return;
    let out: string = '';
    let runStart: number = i;
    for (; i < this.raw.length; i++) {
      const ch: string = this.raw[i];
      if (this.unicodeDigits !== null) {
        this.unicodeDigits += ch;
        if (this.unicodeDigits.length === 4) {
          const code: number = parseInt(this.unicodeDigits, 16);
          if (!isNaN(code)) out += String.fromCharCode(code);
          this.unicodeDigits = null;
        }
        runStart = i + 1;
      } else if (this.escaping) {
        this.escaping = false;
        if (ch === 'u') this.unicodeDigits = '';
        else out += SIMPLE_ESCAPES[ch] ?? ch;
        runStart = i + 1;
      } else if (ch === '\\') {
        out += this.raw.slice(runStart, i);
        this.escaping = true;
      } else if (ch === '"') {
        out += this.raw.slice(runStart, i);
        this.decoded += out;
        this.state = 'finished';
        return;
      }
    }
    if (!this.escaping && this.unicodeDigits === null) out += this.raw.slice(runStart, i);
    this.decoded += out;
  }
}
