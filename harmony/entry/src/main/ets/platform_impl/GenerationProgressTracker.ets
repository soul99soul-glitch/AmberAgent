// GenerationProgressTracker — 单 run 的真实接收量测量(E13-B)
//
// 口径与 streamContentLength 一致:仅模型提供的 assistant text/reasoning/
//   工具 name+input(不含工具 output、USER/STEER、批准/回执/本地包装)。
// 启动历史按现值做基线(零接收);新消息 ID 的实际增长只计一次;
//   重试/重置只减基线不减租期累计,流重排不会造成倒退或重复计数。
// receivedChars = JS 字符串长度(UTF16 code units);receivedBytes = 同内容
//   UTF8 字节数 — 不是 HTTP 线上字节核算。

import { util } from '@kit.ArkTS';
import type { UIMessage, UIMessagePart } from '@amber/chat-domain';

export interface GenerationProgressTotals { chars: number; bytes: number; }

const contentOf = (msg: UIMessage): string => {
  let out: string = '';
  for (const p of msg.parts) {
    const part: UIMessagePart = p;
    if (part.type === 'text') out += part.text;
    else if (part.type === 'reasoning') out += part.reasoning;
    else if (part.type === 'tool') out += part.toolName + part.input;
  }
  return out;
};

const utf8Bytes = (text: string): number => {
  if (text.length === 0) return 0;
  return util.TextEncoder.create('utf-8').encodeInto(text).byteLength;
};

export class GenerationProgressTracker {
  private countedChars: Map<string, number> = new Map();
  private countedBytes: Map<string, number> = new Map();
  private measuredMessages: Map<string, UIMessage> = new Map();
  private receivedChars: number = 0;
  private receivedBytes: number = 0;

  private measure(msg: UIMessage): GenerationProgressTotals {
    const content: string = contentOf(msg);
    return { chars: content.length, bytes: utf8Bytes(content) };
  }

  // 启动历史基线:按现值计入 counted,不产生任何接收量
  seedBaseline(messages: UIMessage[]): void {
    for (const msg of messages) {
      if (msg.role !== 'assistant') continue;
      const current: GenerationProgressTotals = this.measure(msg);
      this.measuredMessages.set(msg.id, msg);
      this.countedChars.set(msg.id, current.chars);
      this.countedBytes.set(msg.id, current.bytes);
    }
  }

  // 观察一份 raw 快照;返回本次新增(未变/非 assistant/历史基线均为 0)
  observe(messages: UIMessage[]): GenerationProgressTotals {
    let addChars: number = 0;
    let addBytes: number = 0;
    for (const msg of messages) {
      if (msg.role !== 'assistant') continue;
      if (this.measuredMessages.get(msg.id) === msg) continue;
      const current: GenerationProgressTotals = this.measure(msg);
      this.measuredMessages.set(msg.id, msg);
      const prevChars: number = this.countedChars.get(msg.id) ?? 0;
      const prevBytes: number = this.countedBytes.get(msg.id) ?? 0;
      if (current.chars > prevChars) {
        addChars += current.chars - prevChars;
        this.countedChars.set(msg.id, current.chars);
      }
      if (current.bytes > prevBytes) {
        addBytes += current.bytes - prevBytes;
        this.countedBytes.set(msg.id, current.bytes);
      }
    }
    this.receivedChars += addChars;
    this.receivedBytes += addBytes;
    return { chars: addChars, bytes: addBytes };
  }

  totals(): GenerationProgressTotals {
    return { chars: this.receivedChars, bytes: this.receivedBytes };
  }
}
