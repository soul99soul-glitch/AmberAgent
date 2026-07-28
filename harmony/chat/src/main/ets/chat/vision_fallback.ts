// vision_fallback — 视觉回退分类器 + 异常 + OCR prompt(D-071a)
// Android 基准:
//   GenerationHandler.kt:822-823(hasImageParts)/:1122-1138(shouldFallbackToVisionRecognition)
//   FileEncoder.kt:37-40(ImageEncodingException)
//   OcrTransformer.kt:29(VisualRecognitionException)
//   OcrPrompt.kt(全文:DEFAULT_VISION_RECOGNITION_PROMPT/resolveVisionRecognitionPrompt)
import type { UIMessage } from './message.ts';

// FileEncoder.kt:37-40 — IllegalArgumentException('Failed to encode image: CAUSE')
// (ArkTS Error 构造器仅收 message 且无 cause 声明 — 显式字段承载 cause 链语义)
export class ImageEncodingError extends Error {
  readonly imageUrl: string;
  readonly cause: Error;
  constructor(imageUrl: string, cause: Error) {
    super(`Failed to encode image: ${cause.message}`);
    this.name = 'ImageEncodingError';
    this.imageUrl = imageUrl;
    this.cause = cause;
  }
}

// OcrTransformer.kt:29 — IllegalStateException(message, cause)
export class VisualRecognitionError extends Error {
  readonly cause?: Error;
  constructor(message: string, cause?: Error) {
    super(message);
    this.name = 'VisualRecognitionError';
    if (cause !== undefined) this.cause = cause;
  }
}

// GenerationHandler.kt:822-823 — Image part 且 url 非 blank
export const hasImageParts = (messages: UIMessage[]): boolean =>
  messages.some((m: UIMessage): boolean =>
    m.parts.some((p): boolean => p.type === 'image' && p.url.trim().length > 0));

// GenerationHandler.kt:1122-1138 — ImageEncodingException 直 true;
//   否则 cause 链 message 拼接 lowercase 命中关键词
const FALLBACK_KEYWORDS: string[] = [
  'image',
  'vision',
  'modalit',
  'unsupported url',
  'unsupported file',
  'invalid file',
  'invalid mime',
  'decode',
  'base64',
];

interface ErrorWithCause { message: string; cause?: unknown; }

export const shouldFallbackToVisionRecognition = (error: Error): boolean => {
  if (error instanceof ImageEncodingError) return true;
  // generateSequence(error) { it.cause }.mapNotNull { it.message }.joinToString(' ')
  const messages: string[] = [];
  let current: unknown = error;
  const seen: unknown[] = [];
  while (current !== null && current !== undefined && !seen.includes(current)) {
    seen.push(current);
    if (typeof current === 'object' && 'message' in (current as ErrorWithCause)) {
      const msg: unknown = (current as ErrorWithCause).message;
      if (typeof msg === 'string') messages.push(msg);
    }
    current = (current as ErrorWithCause).cause;
  }
  const joined: string = messages.join(' ').toLowerCase();
  return FALLBACK_KEYWORDS.some((k: string) => joined.includes(k));
};

// ===== OcrPrompt.kt(全文) =====

export const DEFAULT_VISION_RECOGNITION_PROMPT: string = [
  'You are a visual recognition assistant.',
  '',
  "Read the user's image or screenshot and produce a concise visual context for another AI agent.",
  '',
  'Focus on:',
  '- Visible text, titles, buttons, labels, messages, tables, errors, and numbers.',
  '- The main subject or screen state.',
  '- UI layout and spatial relationships when they matter.',
  '- Important clues, risks, or actionable details that a text-only agent would otherwise miss.',
  '',
  'For app screenshots, describe the current app/page, selected tab, visible content, and relevant controls.',
  'For documents or tables, preserve the key structure in markdown.',
  'For photos, describe the scene and notable objects.',
  '',
  'Be compact and factual. Do not invent details. If something is unclear, say it is unclear.',
].join('\n');

export const DEFAULT_OCR_PROMPT: string = DEFAULT_VISION_RECOGNITION_PROMPT;

const containsIgnoreCase = (haystack: string, needle: string): boolean =>
  haystack.toLowerCase().includes(needle.toLowerCase());

// OcrPrompt.kt:25-35 — 旧 OCR 提示词迁移;blank → 默认
export const resolveVisionRecognitionPrompt = (prompt: string): string => {
  const normalized: string = prompt.trim();
  if (
    containsIgnoreCase(normalized, 'You are an OCR assistant.') ||
    containsIgnoreCase(normalized, 'Do not interpret or translate')
  ) {
    return DEFAULT_VISION_RECOGNITION_PROMPT;
  }
  return normalized.length > 0 ? normalized : DEFAULT_VISION_RECOGNITION_PROMPT;
};

// Kotlin String.hashCode(cacheKey 组成):31 滚哈希,32 位带符号
export const javaStringHashCode = (s: string): number => {
  let h: number = 0;
  for (let i = 0; i < s.length; i++) {
    h = (Math.imul(h, 31) + s.charCodeAt(i)) | 0;
  }
  return h;
};

// ===== 生成期视觉兜底(GenerationHandler.kt:425-470/:595-665) =====

// 兜底钩子:modelSupportsImageInput = model.inputModalities.contains(IMAGE)
//   (GenerationHandler.kt:431 canUseVisionFallback 前半);rebuildInternalMessages
//   = prepareInternalMessages(forceImageToText = true)(:613/:648)—— 由调用方
//   闭包提供(重跑 input transformers,OcrTransformer 消费 forceImageToText)
export interface VisionFallbackHook {
  modelSupportsImageInput: boolean;
  rebuildInternalMessages: () => Promise<UIMessage[]>;
}

// 状态文案(GenerationHandler.kt:610/:652,processingStatus)
export const VISION_FALLBACK_STATUS: string = '正在改用视觉识别模型读取图片...';

// 内层 try/catch 忠实(:601-665):首轮 provider 调用出错 → canUseVisionFallback
//   (模型支持图像 && 消息含图,:431)且错误命中分类器 → 状态文案 → 同一
//   accumulator 重跑(图像已被 OCR 替换为文本);**兜底自身的错误向外传播**
//   (进外层重试循环分类);不可兜底 → 原错误重抛
export const runWithVisionFallback = async (
  internalMessages: UIMessage[],
  hook: VisionFallbackHook | undefined,
  callProvider: (messages: UIMessage[]) => Promise<void>,
  onRetryStatus?: (status: string | null) => void,
): Promise<void> => {
  try {
    await callProvider(internalMessages);
  } catch (callErr) {
    const err: Error = callErr instanceof Error ? callErr : new Error(String(callErr));
    // :431 canUseVisionFallback = 模型支持 IMAGE && internalMessages.hasImageParts()
    const canUse: boolean = hook !== undefined
      && hook.modelSupportsImageInput
      && hasImageParts(internalMessages);
    if (hook === undefined || !canUse || !shouldFallbackToVisionRecognition(err)) {
      throw err;
    }
    if (onRetryStatus !== undefined) onRetryStatus(VISION_FALLBACK_STATUS);
    const forced: UIMessage[] = await hook.rebuildInternalMessages();
    await callProvider(forced);
  }
};
