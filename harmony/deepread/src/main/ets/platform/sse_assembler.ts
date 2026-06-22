// SseAssembler — 把 RCP 的 ArrayBuffer chunk 流转换成 SSE event
// 对应设计 §8.2:RCP 给 ArrayBuffer 不是 string,跨 chunk 的 event 必须累积

export interface SseEvent {
  data: string;
  done: boolean;
}

export class SseAssembler {
  private buffer: string = '';

  feed(chunk: ArrayBuffer): SseEvent[] {
    const text = this.buffer + new TextDecoder('utf-8').decode(chunk);
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
): { onDataBlock: (data: ArrayBuffer, end: boolean) => void; getAssembler: () => SseAssembler } => {
  const assembler = new SseAssembler();
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
