// SseAssembler — 把 RCP 的 ArrayBuffer chunk 流转换成 SSE event
// 对应设计 §8.2:RCP 给 ArrayBuffer 不是 string,跨 chunk 的 event 必须累积
//
// ArkTS 应用层没有全局 TextDecoder(那是 Node 全局)。为保持 deepread 纯逻辑层无 @kit 依赖,
// 解码函数可注入:Node 测试默认用全局 TextDecoder;entry adapter 注入 util.TextDecoder。

export interface SseEvent {
  data: string;
  done: boolean;
}

// 解码器类型:把 ArrayBuffer 转 UTF-8 字符串
export type DecodeChunk = (chunk: ArrayBuffer) => string;

// Node 环境:全局 TextDecoder 可用。用 globalThis 间接访问,避免 ArkTS 编译期
// 扫描到 TextDecoder 字面量(该全局在 ArkTS 应用层不存在)。ArkTS 路径必须显式注入 decode。
const defaultDecode: DecodeChunk = (chunk: ArrayBuffer): string => {
  const g: { TextDecoder?: { new (label: string): { decode: (input: ArrayBuffer) => string } } } =
    globalThis as { TextDecoder?: { new (label: string): { decode: (input: ArrayBuffer) => string } } };
  if (g.TextDecoder !== undefined) {
    return new g.TextDecoder('utf-8').decode(chunk);
  }
  // 兜底:逐字节 ASCII 解码(仅 SSE 控制字符场景;真实 UTF-8 多字节应走注入)
  const bytes: Uint8Array = new Uint8Array(chunk);
  let s: string = '';
  for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
  return s;
};

export class SseAssembler {
  private buffer: string = '';
  private decode: DecodeChunk;

  constructor(decode: DecodeChunk = defaultDecode) {
    this.decode = decode;
  }

  feed(chunk: ArrayBuffer): SseEvent[] {
    const text = this.buffer + this.decode(chunk);
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
  for (const line of lines) {
    if (line.startsWith('data:')) {
      dataParts.push(line.slice(5).trimStart());
    }
  }
  if (dataParts.length === 0) return null;
  const data = dataParts.join('\n');
  // 剥离嵌套 data: 前缀(有些 provider 嵌套 data: data: {...})
  let stripped = data;
  while (stripped.startsWith('data:')) {
    stripped = stripped.slice(5).trimStart();
  }
  if (stripped === '[DONE]' || stripped === '[DONE]\n') {
    return { data: '[DONE]', done: true };
  }
  return { data: stripped, done: false };
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
