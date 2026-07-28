// ocr_transformer — 图片→文本视觉识别 transformer(D-071a)
// Android 基准: app/.../core/ai/transformers/OcrTransformer.kt(全文 141 行)
// 偏差:
//   - Koin get<SettingsAggregator>/ProviderManager → deps 注入(纯逻辑层)
//   - Log.i(识别内容) 省略(隐私:图片识别内容不入日志,章程禁止)
//   - transform 为 suspend → MessageTransformer.transform 允许 Promise(管线同步改异步)
import type { UIMessage, UIMessagePart, UIMessagePartImage } from './message.ts';
import { makeUIMessage } from './message.ts';
import type { MessageTransformer, TransformerContext } from './transformer_pipeline.ts';
import type { ProviderModel, ProviderSetting } from './provider_settings.ts';
import type { VisionCache } from './vision_cache.ts';
import {
  VisualRecognitionError, javaStringHashCode, resolveVisionRecognitionPrompt,
} from './vision_fallback.ts';

export interface OcrTransformerDeps {
  // ctx.model.inputModalities.contains(Modality.IMAGE)(transform 第一判)
  modelSupportsImageInput: boolean;
  // settings.findModelById(settings.ocrModelId)
  findOcrModel: () => ProviderModel | null;
  // Modality.IMAGE !in model.inputModalities 判定
  modelSupportsImage: (model: ProviderModel) => boolean;
  // model.findProvider(settings.providers)(checkOverwrite 默认 true;
  //   providerOverwrite 未移植 = P1 登记)
  findProviderForModel: (model: ProviderModel) => ProviderSetting | null;
  // provider.generateText(...) → choices[0].message?.toText()(null 当缺失)
  generateText: (
    provider: ProviderSetting, model: ProviderModel, messages: UIMessage[],
  ) => Promise<string | null>;
  // settings.ocrPrompt
  ocrPrompt: () => string;
  // vision_cache(LruCache 64 / 3 天)
  cache: VisionCache;
}

// OcrTransformer.kt:89-140 全文忠实
export const performImageRecognition = async (
  part: UIMessagePartImage,
  deps: OcrTransformerDeps,
  promptOverride: string | null = null,
  useCache: boolean = true,
): Promise<string> => {
  const model: ProviderModel | null = deps.findOcrModel();
  if (model === null) throw new VisualRecognitionError('请先配置视觉识别模型');
  if (!deps.modelSupportsImage(model)) {
    throw new VisualRecognitionError('视觉识别模型不支持图片输入');
  }
  const providerSetting: ProviderSetting | null = deps.findProviderForModel(model);
  if (providerSetting === null) throw new VisualRecognitionError('视觉识别模型的提供商不可用');
  const overrideTrim: string | null = promptOverride !== null ? promptOverride.trim() : null;
  const prompt: string =
    overrideTrim !== null && overrideTrim.length > 0
      ? overrideTrim
      : resolveVisionRecognitionPrompt(deps.ocrPrompt());
  const cacheKey: string = `${part.url}|${model.id}|${javaStringHashCode(prompt)}`;

  if (useCache) {
    const cached: string | null = deps.cache.get(cacheKey);
    if (cached !== null) return cached;
  }

  let rawText: string | null;
  try {
    // :111-117 — system(prompt) + user(全新 Image(part.url),metadata 不带)
    rawText = await deps.generateText(providerSetting, model, [
      makeUIMessage('system', [{ type: 'text', text: prompt, metadata: null }]),
      makeUIMessage('user', [{ type: 'image', url: part.url, metadata: null }]),
    ]);
  } catch (e) {
    const cause: Error = e instanceof Error ? e : new Error(String(e));
    throw new VisualRecognitionError(`视觉识别模型调用失败：${cause.message}`, cause);
  }
  const content: string = (rawText ?? '').trim();
  if (content.length === 0) {
    throw new VisualRecognitionError('视觉识别模型没有返回可用内容');
  }
  // trimIndent 模板(插值内容原样,不缩进)
  const visionResult: string =
    `<image_context>\n${content}\n</image_context>\n` +
    "* The image_context tag contains visual recognition results for an image uploaded by the user, not the user's prompt.";

  if (useCache) {
    deps.cache.put(cacheKey, visionResult);
  }
  return visionResult;
};

// transform(OcrTransformer.kt:48-73)
export const createOcrTransformer = (deps: OcrTransformerDeps): MessageTransformer => ({
  transform: async (ctx: TransformerContext, messages: UIMessage[]): Promise<UIMessage[]> => {
    if (deps.modelSupportsImageInput && ctx.forceImageToText !== true) {
      return messages;
    }
    const hasImages: boolean = messages.some((m: UIMessage): boolean =>
      m.parts.some((p: UIMessagePart): boolean =>
        p.type === 'image' && (p as UIMessagePartImage).url.trim().length > 0));
    if (!hasImages) return messages;

    try {
      if (ctx.processingStatus !== undefined) ctx.processingStatus('正在识别图片...');
      const out: UIMessage[] = [];
      for (const message of messages) {
        const parts: UIMessagePart[] = [];
        for (const part of message.parts) {
          if (part.type === 'image' && (part as UIMessagePartImage).url.trim().length > 0) {
            const text: string =
              await performImageRecognition(part as UIMessagePartImage, deps);
            parts.push({ type: 'text', text, metadata: null });
          } else {
            parts.push(part);
          }
        }
        out.push({ ...message, parts });
      }
      return out;
    } finally {
      if (ctx.processingStatus !== undefined) ctx.processingStatus(null);
    }
  },
});
