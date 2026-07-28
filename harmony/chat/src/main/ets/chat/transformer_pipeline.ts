// transformer 管线 — core/ai/transformers/api/.../Transformer.kt 的忠实移植
//
// 裁剪(D-012):TransformerContext 只含 assistant + clock;
// Android 的 android.Context/Settings/Model/processingStatus 是 adapter 层关注点,不进纯逻辑层。
// TemplateTransformer 的时间变量经 clock 注入(默认本机时间),保持纯逻辑可测。

import type { UIMessage, UIMessagePartTool } from './message.ts';
import { isToolExecuted } from './message.ts';
import { nowIso } from './ids.ts';
import type { Assistant } from './assistant.ts';

// 时间变量来源:模板 {{time}}/{{date}}、think 标签关闭时间戳
export interface TransformerClock {
  nowIso(): string;
  localTime(): string;   // HH:mm:ss(Java LocalTime.toString 对齐)
  localDate(): string;   // yyyy-MM-dd(Java LocalDate.toString 对齐)
}

const pad2 = (n: number): string => (n < 10 ? `0${n}` : `${n}`);

export const defaultTransformerClock = (): TransformerClock => ({
  nowIso: (): string => nowIso(),
  localTime: (): string => {
    const d = new Date();
    return `${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`;
  },
  localDate: (): string => {
    const d = new Date();
    return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
  },
});

export interface TransformerContext {
  assistant: Assistant;
  clock?: TransformerClock;
  // D-071a:Android TransformerContext 两字段补齐(vision fallback 需要)
  forceImageToText?: boolean; // Transformer.kt:18(OcrTransformer 消费)
  processingStatus?: (status: string | null) => void; // Android MutableStateFlow<String?>
}

export const clockOf = (ctx: TransformerContext): TransformerClock =>
  ctx.clock ?? defaultTransformerClock();

// ===== 接口(Android 默认方法 = 这里的可选方法,缺省即恒等) =====
// D-071a:Android transform 为 suspend(OcrTransformer 需 await 视觉识别)
//   → 允许返回 Promise;applyInputTransformers 同步改异步

export interface MessageTransformer {
  transform?(ctx: TransformerContext, messages: UIMessage[]): UIMessage[] | Promise<UIMessage[]>;
}

export interface OutputMessageTransformer extends MessageTransformer {
  visualTransform?(ctx: TransformerContext, messages: UIMessage[]): UIMessage[];
  // D-076:Android onGenerationFinish 为 suspend(Base64ImageToLocalFile 需 await
  //   图片解码/落盘)→ 允许返回 Promise;applyOnGenerationFinish 同步改异步
  onGenerationFinish?(ctx: TransformerContext, messages: UIMessage[]): UIMessage[] | Promise<UIMessage[]>;
}

export interface TailSafeOutputMessageTransformer extends OutputMessageTransformer {
  visualTransformTail(ctx: TransformerContext, message: UIMessage): UIMessage;
}

// ===== 不变量校验(Transformer.kt:174-211) =====

interface ToolSignature {
  id: string;
  name: string;
  input: string;
  executed: boolean;
}

const toolSignatures = (messages: UIMessage[]): ToolSignature[] => {
  const out: ToolSignature[] = [];
  for (const m of messages) {
    for (const p of m.parts) {
      if (p.type === 'tool') {
        const t = p as UIMessagePartTool;
        out.push({
          id: t.toolCallId, name: t.toolName, input: t.input, executed: isToolExecuted(t),
        });
      }
    }
  }
  return out;
};

export const validateTransformerInvariants = (
  before: UIMessage[], after: UIMessage[], transformerName: string,
): void => {
  if (before.some((m: UIMessage): boolean => m.role === 'system')) {
    if (!after.some((m: UIMessage): boolean => m.role === 'system')) {
      throw new Error(`${transformerName} removed the system message`);
    }
  }
  const b = JSON.stringify(toolSignatures(before));
  const a = JSON.stringify(toolSignatures(after));
  if (b !== a) {
    throw new Error(`${transformerName} modified tool call/result ordering`);
  }
};

// ===== pipeline 折叠(Transformer.kt:70-172) =====

export const applyInputTransformers = async (
  messages: UIMessage[], transformers: MessageTransformer[], ctx: TransformerContext,
): Promise<UIMessage[]> => {
  let acc: UIMessage[] = messages;
  for (const t of transformers) {
    if (t.transform === undefined) continue;
    const out: UIMessage[] = await t.transform(ctx, acc);
    if (out !== acc) validateTransformerInvariants(acc, out, 'transformer');
    acc = out;
  }
  return acc;
};

export const applyVisualTransformers = (
  messages: UIMessage[], transformers: OutputMessageTransformer[], ctx: TransformerContext,
): UIMessage[] => {
  let acc: UIMessage[] = messages;
  for (const t of transformers) {
    if (t.visualTransform === undefined) continue;
    const out: UIMessage[] = t.visualTransform(ctx, acc);
    if (out !== acc) validateTransformerInvariants(acc, out, 'visualTransformer');
    acc = out;
  }
  return acc;
};

// streaming tail:只转换最后一条 assistant(流式刷新性能路径)
export const applyVisualTransformersStreamingTail = (
  messages: UIMessage[], transformers: OutputMessageTransformer[], ctx: TransformerContext,
): UIMessage[] => {
  let tailIndex: number = -1;
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i].role === 'assistant') { tailIndex = i; break; }
  }
  if (tailIndex < 0) return messages;
  let tail: UIMessage = messages[tailIndex];
  let changed: boolean = false;
  for (const t of transformers) {
    const tailSafe = t as TailSafeOutputMessageTransformer;
    if (tailSafe.visualTransformTail === undefined) continue;
    const next: UIMessage = tailSafe.visualTransformTail(ctx, tail);
    if (next !== tail) {
      tail = next;
      changed = true;
    }
  }
  if (!changed) return messages;
  const out: UIMessage[] = [...messages];
  out[tailIndex] = tail;
  validateTransformerInvariants(messages, out, 'StreamingTailVisualTransform');
  return out;
};

export const applyOnGenerationFinish = async (
  messages: UIMessage[], transformers: OutputMessageTransformer[], ctx: TransformerContext,
): Promise<UIMessage[]> => {
  let acc: UIMessage[] = messages;
  for (const t of transformers) {
    if (t.onGenerationFinish === undefined) continue;
    const out: UIMessage[] = await t.onGenerationFinish(ctx, acc);
    validateTransformerInvariants(acc, out, 'onGenerationFinish');
    acc = out;
  }
  return acc;
};
