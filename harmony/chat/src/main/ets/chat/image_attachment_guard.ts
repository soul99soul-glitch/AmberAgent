// image_attachment_guard — ImageAttachmentValidator.kt 全文移植(D-111)
// Android 基准:app/amber/core/ai/vision/ImageAttachmentValidator.kt(116 行全文)
//   - 四种附件态 + zh 文案逐字;blocksSend = kind == BLOCKED
//   - inspectImage:encode 校验 → chat 模型空 → chat IMAGE 直读 →
//     视觉模型三查(配置/模态/提供商)→ FALLBACK
//   - firstBlockingIssue:>4 张拦截 + 逐张首个 BLOCKED
//   - firstBlockingIssueForSend:有 FALLBACK → VisionModelHealthChecker.probe
//     不可用 → BLOCKED(健康标签原文)
// 端口化:encodeBase64(withPrefix=false) → 注入 encodeCheck(抛错即失败,
//   entry = EntryImageEncoder.encodeImageDetailed);VisionModelHealthChecker
//   探测以闭包注入(vision_health.probeVisionModelHealth 装配见 entry)
import type { ProviderModel, ProviderSetting } from './provider_settings.ts';
import { findProviderForModel } from './provider_settings.ts';
import type { UIMessagePart } from './message.ts';
import { ImageEncodingError } from './vision_fallback.ts';
import type { VisionModelHealth } from './vision_health.ts';
import { isVisionHealthAvailable } from './vision_health.ts';

// ImageAttachmentValidator.kt:13(逐字)
export const MAX_IMAGES_PER_MESSAGE: number = 4;

export type ImageAttachmentStatusKind = 'checking' | 'ready' | 'fallback' | 'blocked';

export interface ImageAttachmentStatus {
  kind: ImageAttachmentStatusKind;
  message: string;
}

// ImageAttachmentValidator.kt:26 — blocksSend = kind == BLOCKED
export const imageAttachmentBlocksSend = (status: ImageAttachmentStatus): boolean =>
  status.kind === 'blocked';

// ImageAttachmentValidator.kt:30-31(逐字)
export const checkingImageAttachment = (): ImageAttachmentStatus => ({
  kind: 'checking',
  message: '正在检查图片',
});

// settings 三元组(Settings 子集):getCurrentChatModel()/ocrModelId/providers
//   — 调用方快照解析(ChatProviderChoice + OcrSeed,D-071b 同口径)
export interface ImageGuardSettings {
  chatModel: ProviderModel | null;
  ocrModelId: string;
  providers: ProviderSetting[];
}

// PreferencesStore.kt:383-389 findModelById(全 providers 顺序扫;纯数据扫描,域内实现)
const findModelById = (providers: ProviderSetting[], id: string): ProviderModel | null => {
  for (const p of providers) {
    for (const m of p.models) {
      if (m.id === id) return m;
    }
  }
  return null;
};

// ImageAttachmentValidator.kt:102-114 readableImageError(关键词映射逐字);
//   :103 — ImageEncodingException → cause ?: error(ImageEncodingError.cause 同构)
export const readableImageError = (error: Error): string => {
  const cause: Error = error instanceof ImageEncodingError ? error.cause : error;
  const message: string = cause.message;
  if (message.includes('File does not exist')) return '图片文件不存在或已被删除';
  if (message.includes('Unsupported URL format')) return '图片来源暂不支持';
  if (message.includes('HEIC format requires Android 9')) return 'HEIC 格式需要 Android 9 或更高版本';
  if (message.includes('AVIF format requires Android 12')) return 'AVIF 格式需要 Android 12 或更高版本';
  if (message.includes('Failed to decode image')) return '图片格式无法解码（可能是不支持的格式或文件损坏）';
  if (message.includes('Failed to guess MIME type')) return '图片格式暂不支持（支持 JPEG/PNG/WebP/GIF/HEIC/AVIF）';
  // else → '图片不可读取：${message.ifBlank { simpleName ?: "未知错误" }}'(全角冒号逐字)
  return `图片不可读取：${message.length > 0 ? message : (cause.name.length > 0 ? cause.name : '未知错误')}`;
};

// 编码校验 Port:encodeBase64(withPrefix=false) 等价 — 抛错即不可读
export type ImageEncodeCheck = (imageUrl: string) => void;

// ImageAttachmentValidator.kt:33-61 inspectImage(判定顺序逐字)
export const inspectImageAttachment = (
  imageUrl: string,
  settings: ImageGuardSettings,
  encodeCheck: ImageEncodeCheck,
): ImageAttachmentStatus => {
  try {
    encodeCheck(imageUrl);
  } catch (e) {
    return {
      kind: 'blocked',
      message: readableImageError(e instanceof Error ? e : new Error(String(e))),
    };
  }
  const chatModel: ProviderModel | null = settings.chatModel;
  if (chatModel === null) {
    return { kind: 'blocked', message: '请先选择模型' };
  }
  if (chatModel.inputModalities.includes('image')) {
    return { kind: 'ready', message: '图片可由当前模型读取' };
  }
  const visionModel: ProviderModel | null = findModelById(settings.providers, settings.ocrModelId);
  if (visionModel === null) {
    return { kind: 'blocked', message: '请先配置视觉识别模型' };
  }
  if (!visionModel.inputModalities.includes('image')) {
    return { kind: 'blocked', message: '视觉识别模型不支持图片输入' };
  }
  // visionModel.findProvider(settings.providers) — checkOverwrite 默认 true(D-084 同)
  if (findProviderForModel(settings.providers, visionModel) === null) {
    return { kind: 'blocked', message: '视觉识别模型的提供商不可用' };
  }
  return { kind: 'fallback', message: '将先由视觉识别模型读取图片' };
};

// ImageAttachmentValidator.kt:63-77 firstBlockingIssue(>4 拦截 → 逐张首个 BLOCKED)
export const firstImageBlockingIssue = (
  parts: UIMessagePart[],
  settings: ImageGuardSettings,
  encodeCheck: ImageEncodeCheck,
): ImageAttachmentStatus | null => {
  const images: UIMessagePart[] = parts.filter((p: UIMessagePart): boolean => p.type === 'image');
  if (images.length > MAX_IMAGES_PER_MESSAGE) {
    return { kind: 'blocked', message: `一次最多发送 ${MAX_IMAGES_PER_MESSAGE} 张图片` };
  }
  for (const img of images) {
    if (img.type !== 'image') continue;
    const status: ImageAttachmentStatus = inspectImageAttachment(img.url, settings, encodeCheck);
    if (imageAttachmentBlocksSend(status)) return status;
  }
  return null;
};

// ImageAttachmentValidator.kt:79-100 firstBlockingIssueForSend:
//   静态拦截先行;有 FALLBACK → 探测视觉健康,不可用 → BLOCKED(健康标签原文)
export const firstImageBlockingIssueForSend = async (
  parts: UIMessagePart[],
  settings: ImageGuardSettings,
  encodeCheck: ImageEncodeCheck,
  probeHealth: () => Promise<VisionModelHealth>,
): Promise<ImageAttachmentStatus | null> => {
  const quick: ImageAttachmentStatus | null = firstImageBlockingIssue(parts, settings, encodeCheck);
  if (quick !== null) return quick;
  const images: UIMessagePart[] = parts.filter((p: UIMessagePart): boolean => p.type === 'image');
  let needsVisionFallback: boolean = false;
  for (const img of images) {
    if (img.type !== 'image') continue;
    if (inspectImageAttachment(img.url, settings, encodeCheck).kind === 'fallback') {
      needsVisionFallback = true;
      break;
    }
  }
  if (!needsVisionFallback) return null;
  const health: VisionModelHealth = await probeHealth();
  if (isVisionHealthAvailable(health)) return null;
  return { kind: 'blocked', message: health.label };
};
