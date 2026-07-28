// openai_stream — OpenAI 兼容 SSE 流 → 文本增量 的共享解析层
//
// 议会(ModelCouncil)与小说(Novel)都需要「system+user 提示词 → 逐字流式文本」。
// 二者共用这条链路:HttpClient.fetchStream(ArrayBuffer chunk) → SseAssembler(SSE event)
//   → parseOpenAiStreamEvent(文本 delta) → 累积 + onChunk 回调。
//
// 纯逻辑、无平台依赖,Node 下可单测。ArkTS 安全:interface + factory,无 any。

import { SseAssembler } from './sse_assembler.ts';
import type { SseEvent, DecodeChunk } from './sse_assembler.ts';

// 一个 SSE event 解析出的增量
export interface OpenAiStreamDelta {
  deltaText: string;       // choices[0].delta.content 增量
  reasoningText: string;   // choices[0].delta.reasoning_content 增量(部分 provider 有)
  done: boolean;           // [DONE] 或 finish 信号
}

// OpenAI 流式 chunk 的最小结构(只取用到的字段,避免 any)
interface OpenAiStreamChoiceDelta {
  content?: string;
  reasoning_content?: string;
}

interface OpenAiStreamChoice {
  delta?: OpenAiStreamChoiceDelta;
  finish_reason?: string | null;
}

interface OpenAiStreamChunk {
  choices?: OpenAiStreamChoice[];
}

// 解析单个 SSE event → 文本增量。健壮:非法 JSON / 缺字段一律返回空增量,不抛。
export const parseOpenAiStreamEvent = (evt: SseEvent): OpenAiStreamDelta => {
  const empty: OpenAiStreamDelta = { deltaText: '', reasoningText: '', done: false };
  if (evt.done) {
    return { deltaText: '', reasoningText: '', done: true };
  }
  const raw: string = evt.data;
  if (raw.length === 0) return empty;
  if (raw === '[DONE]') {
    return { deltaText: '', reasoningText: '', done: true };
  }
  let parsed: OpenAiStreamChunk;
  try {
    parsed = JSON.parse(raw) as OpenAiStreamChunk;
  } catch {
    return empty; // 心跳/非 JSON 帧,忽略
  }
  const choices: OpenAiStreamChoice[] | undefined = parsed.choices;
  if (choices === undefined || choices.length === 0) {
    return empty;
  }
  const choice: OpenAiStreamChoice = choices[0];
  const delta: OpenAiStreamChoiceDelta | undefined = choice.delta;
  const deltaText: string = delta?.content ?? '';
  const reasoningText: string = delta?.reasoning_content ?? '';
  // finish_reason 非空(stop/length 等)表示本条是最后一帧;content 可能仍有增量
  const finished: boolean = choice.finish_reason !== undefined
    && choice.finish_reason !== null
    && choice.finish_reason.length > 0;
  return { deltaText: deltaText, reasoningText: reasoningText, done: finished };
};

// 便利封装:吃 ArrayBuffer chunk,吐文本增量。供议会 runner / 小说 adapter 复用。
// onDelta 只在确有文本时触发;onDone 在 [DONE]/finish 时触发(可能早于 feed 结束)。
export interface OpenAiTextStreamSink {
  feed(chunk: ArrayBuffer): void;
  flush(): void;
}

export const createOpenAiTextStream = (
  onDelta: (deltaText: string, reasoningText: string) => void,
  onDone?: () => void,
  decode: DecodeChunk | null = null,
): OpenAiTextStreamSink => {
  const assembler = decode !== null ? new SseAssembler(decode) : new SseAssembler();
  let finished = false;
  const handle = (evt: SseEvent): void => {
    if (finished) return;
    const d: OpenAiStreamDelta = parseOpenAiStreamEvent(evt);
    if (d.deltaText.length > 0 || d.reasoningText.length > 0) {
      onDelta(d.deltaText, d.reasoningText);
    }
    if (d.done) {
      finished = true;
      onDone?.();
    }
  };
  return {
    feed(chunk: ArrayBuffer): void {
      for (const evt of assembler.feed(chunk)) {
        handle(evt);
        if (finished) return;
      }
    },
    flush(): void {
      if (finished) return;
      for (const evt of assembler.flush()) {
        handle(evt);
        if (finished) return;
      }
    },
  };
};
