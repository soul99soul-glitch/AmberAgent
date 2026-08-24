// SseAssembler — 把 RCP 的 ArrayBuffer chunk 流转换成 SSE event
// 对应设计 §8.2:RCP 给 ArrayBuffer 不是 string,跨 chunk 的 event 必须累积
//
// ArkTS 应用层没有全局 TextDecoder(那是 Node 全局)。为保持 deepread 纯逻辑层无 @kit 依赖,
// 解码函数可注入:Node 测试默认用全局 TextDecoder;entry adapter 注入 util.TextDecoder。

export interface SseEvent {
  data: string;
  done: boolean;
  // event: 字段(Claude content_block_*/Responses response.* 依赖;Chat Completions/Gemini 不用)
  event?: string;
  // id: 字段,仅透传(Android okhttp reader 同语义,无业务使用)
  id?: string;
}

// 解码器类型:把 ArrayBuffer 转 UTF-8 字符串
export type DecodeChunk = (chunk: ArrayBuffer) => string;

// 纯 UTF-8 解码(ArkTS 应用层无全局 TextDecoder;Node 测试也可用)。
// 输入必须是「完整」的 UTF-8 序列(尾部不含截断的多字节字符,由 feed 的字节缓冲保证)。
const decodeUtf8 = (bytes: Uint8Array): string => {
  let s: string = '';
  let i: number = 0;
  while (i < bytes.length) {
    const b: number = bytes[i];
    if ((b & 0x80) === 0) { s += String.fromCharCode(b); i += 1; }
    else if ((b & 0xE0) === 0xC0) {
      s += String.fromCharCode(((b & 0x1F) << 6) | (bytes[i + 1] & 0x3F)); i += 2;
    } else if ((b & 0xF0) === 0xE0) {
      s += String.fromCharCode(((b & 0x0F) << 12) | ((bytes[i + 1] & 0x3F) << 6) | (bytes[i + 2] & 0x3F)); i += 3;
    } else {
      const cp: number = ((b & 0x07) << 18) | ((bytes[i + 1] & 0x3F) << 12) | ((bytes[i + 2] & 0x3F) << 6) | (bytes[i + 3] & 0x3F);
      s += String.fromCharCode(0xD800 + ((cp - 0x10000) >> 10), 0xDC00 + ((cp - 0x10000) & 0x3FF)); i += 4;
    }
  }
  return s;
};

const concatBytes = (a: number[], b: Uint8Array): Uint8Array => {
  const out: Uint8Array = new Uint8Array(a.length + b.length);
  for (let i: number = 0; i < a.length; i++) out[i] = a[i];
  for (let i: number = 0; i < b.length; i++) out[a.length + i] = b[i];
  return out;
};

// 返回可安全解码的字节上界(尾部不完整多字节序列不计入,留给下一次 feed)
const safeDecodeEnd = (bytes: Uint8Array): number => {
  const n: number = bytes.length;
  if (n === 0) return 0;
  // 从尾部回扫最多 3 个 continuation 字节 + 1 个 lead 字节
  for (let back: number = 1; back <= Math.min(4, n); back++) {
    const b: number = bytes[n - back];
    if ((b & 0xC0) === 0x80) continue; // continuation, 继续回扫
    // lead 字节: 计算该序列需要的总长
    let need: number = 1;
    if ((b & 0xE0) === 0xC0) need = 2;
    else if ((b & 0xF0) === 0xE0) need = 3;
    else if ((b & 0xF8) === 0xF0) need = 4;
    return back < need ? n - back : n; // 不完整 → 截掉; 完整 → 全部
  }
  return n; // 全是 continuation(异常), 全解码
};

// Node 环境:全局 TextDecoder 可用;ArkTS 应用层无该全局 → 走纯 UTF-8 解码。
const defaultDecode: DecodeChunk = (chunk: ArrayBuffer): string => {
  const g: { TextDecoder?: { new (label: string): { decode: (input: ArrayBuffer) => string } } } =
    globalThis as { TextDecoder?: { new (label: string): { decode: (input: ArrayBuffer) => string } } };
  if (g.TextDecoder !== undefined) {
    return new g.TextDecoder('utf-8').decode(chunk);
  }
  return decodeUtf8(new Uint8Array(chunk));
};

export class SseAssembler {
  private buffer: string = '';
  private pending: number[] = [];
  private decode: DecodeChunk;

  constructor(decode: DecodeChunk = defaultDecode) {
    this.decode = decode;
  }

  feed(chunk: ArrayBuffer): SseEvent[] {
    // 字节级缓冲: 合并上次尾部不完整多字节序列 + 本次 chunk, 只解码安全部分
    const incoming: Uint8Array = new Uint8Array(chunk);
    const combined: Uint8Array = this.pending.length === 0
      ? incoming
      : concatBytes(this.pending, incoming);
    const safeEnd: number = safeDecodeEnd(combined);
    this.pending = [];
    for (let i: number = safeEnd; i < combined.length; i++) this.pending.push(combined[i]);
    const safeBytes: Uint8Array = combined.slice(0, safeEnd);
    // TS 5.7+ 下 Uint8Array.buffer 是 ArrayBufferLike;slice() 的结果必背靠普通 ArrayBuffer,窄化安全
    const text = this.buffer + (safeBytes.length > 0 ? this.decode(safeBytes.buffer as ArrayBuffer) : '');
    this.buffer = '';
    const events: SseEvent[] = [];
    let searchFrom = 0;
    // SSE event 间用空行分隔;\n\n 或 \r\n\r\n 或混合
    while (true) {
      const lf = text.indexOf('\n\n', searchFrom);
      const crlf = text.indexOf('\r\n\r\n', searchFrom);
      let idx: number;
      let sepLen: number;
      if (crlf >= 0 && (lf < 0 || crlf <= lf)) {
        idx = crlf; sepLen = 4;
      } else if (lf >= 0) {
        idx = lf; sepLen = 2;
      } else {
        break;
      }
      const raw = text.slice(searchFrom, idx);
      searchFrom = idx + sepLen;
      const evt = parseSseBlock(raw);
      if (evt !== null) events.push(evt);
    }
    this.buffer = text.slice(searchFrom);
    return events;
  }

  flush(): SseEvent[] {
    if (this.buffer.trim().length === 0) {
      this.buffer = '';
      return [];
    }
    const evt = parseSseBlock(this.buffer);
    this.buffer = '';
    return evt !== null ? [evt] : [];
  }
}

const parseSseBlock = (raw: string): SseEvent | null => {
  const lines = raw.split(/\r?\n/);
  const dataParts: string[] = [];
  let eventName: string | undefined;
  let eventId: string | undefined;
  for (const line of lines) {
    if (line.startsWith(':')) {
      // 注释行(含心跳 ": heartbeat"):忽略,不分派(对齐 okhttp reader / Codex 手写解析器)
      continue;
    }
    if (line.startsWith('data:')) {
      dataParts.push(line.slice(5).trimStart());
    } else if (line.startsWith('event:')) {
      eventName = line.slice(6).trimStart();
    } else if (line.startsWith('id:')) {
      eventId = line.slice(3).trimStart();
    }
    // retry: 及其他未知字段:忽略,不自动重连(对齐 SSE.kt:114)
  }
  if (dataParts.length === 0) return null;
  const data = dataParts.join('\n');
  // 剥离嵌套 data: 前缀(有些 provider 嵌套 data: data: {...})
  let stripped = data;
  while (stripped.startsWith('data:')) {
    stripped = stripped.slice(5).trimStart();
  }
  const trimmedDone: string = stripped.trimEnd();
  if (trimmedDone === '[DONE]' || trimmedDone === '[DONE]\n') {
    return { data: '[DONE]', done: true, event: eventName, id: eventId };
  }
  return { data: stripped, done: false, event: eventName, id: eventId };
};

// 便利函数:把 RCP onReceiveDataBlock 回调适配成 SseEvent 流
export const createSseStreamHandler = (
  onEvent: (evt: SseEvent) => void,
  onDone?: () => void,
  decode: DecodeChunk = defaultDecode,
): { onDataBlock: (data: ArrayBuffer, end: boolean) => void; getAssembler: () => SseAssembler } => {
  const assembler = new SseAssembler(decode);
  return {
    onDataBlock: (data: ArrayBuffer, end: boolean): void => {
      for (const evt of assembler.feed(data)) {
        if (evt.done) { onDone?.(); return; }
        onEvent(evt);
      }
      if (end) {
        for (const evt of assembler.flush()) {
          if (evt.done) { onDone?.(); return; }
          onEvent(evt);
        }
      }
    },
    getAssembler: () => assembler,
  };
};
