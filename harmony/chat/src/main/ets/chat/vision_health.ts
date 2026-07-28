// vision_health — VisionModelHealthChecker.kt 全文移植(D-111)
// Android 基准:app/amber/core/ai/vision/VisionModelHealthChecker.kt(72 行全文)
//   - 六种健康态 + zh 标签逐字;isAvailable = kind == AVAILABLE
//   - probe:findModelById(ocrModelId) → IMAGE 模态 → findProvider →
//     generateText(TINY_PNG 探针);异常 → FAILED '不可用：<msg ?: 检测失败>'
// 端口化(宪章:域层仅依赖 Ports):ProviderManager/generateText 由调用方注入;
//   settings 三元组(findModelById/findProvider 数据源)以闭包注入,语义逐字
import { makeSystemMessage, makeUIMessage } from './message.ts';
import type { UIMessage } from './message.ts';
import type { ProviderModel, ProviderSetting } from './provider_settings.ts';

// VisionModelHealthChecker.kt:13-15(逐字,1x1 PNG data URL)
export const VISION_PROBE_TINY_PNG: string =
  'data:image/png;base64,' +
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=';

// VisionModelHealthChecker.kt:53-59(探针消息逐字)
export const VISION_PROBE_SYSTEM_PROMPT: string = 'Reply with OK if you can receive this test image.';
export const VISION_PROBE_USER_TEXT: string = 'Vision probe. Reply only OK.';

export type VisionModelHealthKind =
  'checking' | 'available' | 'not_configured' | 'unsupported' | 'provider_missing' | 'failed';

export interface VisionModelHealth {
  kind: VisionModelHealthKind;
  label: string;
}

// VisionModelHealth.kt:30 — isAvailable = kind == AVAILABLE
export const isVisionHealthAvailable = (health: VisionModelHealth): boolean =>
  health.kind === 'available';

// VisionModelHealthChecker.kt:34-35(逐字)
export const checkingVisionModelHealth = (): VisionModelHealth => ({
  kind: 'checking',
  label: '检测中',
});

// 探针依赖(Ports):数据源闭包 + generateText(TextGenerationParams(model) 仅模型,
//   由 entry 装配 — OcrSupport.generateVisionText 同口径,OcrTransformer.kt:107-120)
export interface VisionProbeDeps {
  findOcrModel: () => ProviderModel | null;
  modelSupportsImage: (model: ProviderModel) => boolean;
  findProvider: (model: ProviderModel) => ProviderSetting | null;
  generateText: (
    provider: ProviderSetting, model: ProviderModel, messages: UIMessage[],
  ) => Promise<unknown>;
}

// VisionModelHealthChecker.kt:37-70 probe(逐字顺序):
//   未配置 → 不支持图片 → 提供商不可用 → 探测成功 可用 / 失败 不可用:<msg>
export const probeVisionModelHealth = async (
  deps: VisionProbeDeps,
): Promise<VisionModelHealth> => {
  const model: ProviderModel | null = deps.findOcrModel();
  if (model === null) {
    return { kind: 'not_configured', label: '未配置' };
  }
  if (!deps.modelSupportsImage(model)) {
    return { kind: 'unsupported', label: '不支持图片' };
  }
  const provider: ProviderSetting | null = deps.findProvider(model);
  if (provider === null) {
    return { kind: 'provider_missing', label: '提供商不可用' };
  }
  // UIMessage.system(...) + UIMessage(USER, [Text(...), Image(TINY_PNG)])
  const messages: UIMessage[] = [
    makeSystemMessage(VISION_PROBE_SYSTEM_PROMPT),
    makeUIMessage('user', [
      { type: 'text', text: VISION_PROBE_USER_TEXT, metadata: null },
      { type: 'image', url: VISION_PROBE_TINY_PNG, metadata: null },
    ]),
  ];
  try {
    await deps.generateText(provider, model, messages);
    return { kind: 'available', label: '可用' };
  } catch (e) {
    // onFailure — '不可用：${it.message ?: "检测失败"}'(全角冒号逐字;
    //   Kotlin 仅 null 回退,空串保留 — ArkTS Error.message 恒 string,语义等价)
    const msg: string = e instanceof Error ? e.message : '检测失败';
    return { kind: 'failed', label: `不可用：${msg}` };
  }
};
