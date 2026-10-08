// OcrSupport — 视觉识别(OCR)entry 接线(D-071b)
// Android 基准:
//   PreferencesKeys.kt:34-35 — 'ocr_model'/'ocr_prompt' 键名逐字
//   ChatPrefs.kt:34-37,80-83 — ocrModelId 默认 Uuid.random()(未配置 → 找不到模型
//     → '请先配置视觉识别模型');parseUuidOrNull 损坏 → Log.e + null → random;
//     ocrPrompt 默认 DEFAULT_OCR_PROMPT
//   E8:新配置按 provider 容器+模型 UUID 精确解析,旧 scalar UUID 保留原顺序语义
//   OcrTransformer.kt:32-46 — vision_cache.json 文件名逐字(cacheDir)
//   OcrTransformer.kt:107-120 — generateText:TextGenerationParams(model) 仅模型
//     (不带 assistant 参数/customHeaders),choices[0].message?.toText()
// 偏差(登记):settingsFlow 实时读 → ChatPage aboutToAppear 快照加载
//   (从设置页返回 aboutToAppear 重触发,与 StateFlow 就绪时序等价,D-070 同模式)
import { fileIo } from '@kit.CoreFileKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type {
  KeyValueStore, MessageTransformer, OpenAIChatApi, ProviderModel, ProviderSetting,
  UIMessage, TaskModelReference, VisionModelHealth, VisionCache, VisionCacheFileStore,
} from '@amber/chat-domain';
import {
  copyProviderSettingWithModels, createOcrTransformer, createVisionCache,
  DEFAULT_OCR_PROMPT, findProviderForModel, findTaskProviderModel, loadProviders,
  makeTextGenerationParams, newId, probeVisionModelHealth, taskModelReferenceFromId, toChatModel, toText,
} from '@amber/chat-domain';
import type { ChatProviderChoice } from '../di/AppContainer.ets';
import { prepareProviderApi, resolveConfiguredTaskModel } from '../di/AppContainer.ets';
import { loadTaskModelReference } from './TaskModelSelectionSupport.ets';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.OcrSupport';

// PreferencesKeys.kt:34-35(逐字)
export const OCR_MODEL_KEY: string = 'ocr_model';
export const OCR_PROMPT_KEY: string = 'ocr_prompt';

// OcrTransformer.kt:36(文件名逐字;cacheDir 同 Android)
const VISION_CACHE_FILE: string = 'vision_cache.json';

// ChatPrefs.kt:80-83 — 缺失/损坏(parseUuidOrNull → null)均回 Uuid.random()
//   (不可能命中任何模型 → '请先配置视觉识别模型',设置面配置前忠实保持)
export const getOcrModelId = async (kv: KeyValueStore): Promise<string> => {
  const reference: TaskModelReference = await loadTaskModelReference(kv, OCR_MODEL_KEY);
  if (reference.kind === 'fixed') return reference.pair.modelId;
  if (reference.kind === 'legacy') {
    const raw: string = reference.modelId;
    // Uuid.parse 形态校验(8-4-4-4-12 hex);损坏 → Log.e 语义 + random
    if (/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(raw)) {
      return raw;
    }
    hilog.error(DOMAIN, TAG, 'Dropping corrupted settings uuid: %{public}s', raw);
  }
  return newId();
};

export const getOcrPrompt = async (kv: KeyValueStore): Promise<string> =>
  (await kv.get(OCR_PROMPT_KEY)) ?? DEFAULT_OCR_PROMPT;

// ===== D-098:写路径(SettingModelPage.kt:534-537 onSelect/:543-548 onPromptChange/onReset)=====
// ChatPrefs.kt:98/:102 writeTo 直存语义;无 onClear(OCR 行 allowClear=false)
export const saveOcrModelId = async (kv: KeyValueStore, uuid: string): Promise<void> => {
  await kv.put(OCR_MODEL_KEY, uuid);
};

export const saveOcrPrompt = async (kv: KeyValueStore, value: string): Promise<void> => {
  await kv.put(OCR_PROMPT_KEY, value);
};

// ===== vision_cache 单例(OcrTransformer.kt:32-46;store 异常吞咽) =====

const visionCachePath = (): string => {
  const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
  return `${ctx.cacheDir}/${VISION_CACHE_FILE}`;
};

const visionCacheStore: VisionCacheFileStore = {
  read: (): string | null => {
    try {
      const path: string = visionCachePath();
      if (!fileIo.accessSync(path)) return null;
      return fileIo.readTextSync(path);
    } catch {
      return null; // Android catch → 空缓存
    }
  },
  write: (content: string): void => {
    try {
      const path: string = visionCachePath();
      const f = fileIo.openSync(path, fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
      fileIo.writeSync(f.fd, content);
      fileIo.closeSync(f);
    } catch {
      // Android catch {} 静默(平台缓存,非业务数据)
    }
  },
};

let visionCache: VisionCache | null = null;
export const getVisionCache = (): VisionCache => {
  if (visionCache === null) {
    visionCache = createVisionCache(visionCacheStore);
  }
  return visionCache;
};

// 原缓存仍负责持久化、容量和到期;这里仅隔离重复模型 UUID 的不同任务目标。
class TargetVisionCache implements VisionCache {
  private cache: VisionCache;
  private prefix: string;
  constructor(cache: VisionCache, prefix: string) {
    this.cache = cache;
    this.prefix = prefix;
  }
  get(key: string): string | null { return this.cache.get(`${this.prefix}${key}`); }
  put(key: string, value: string): void { this.cache.put(`${this.prefix}${key}`, value); }
  remove(key: string): void { this.cache.remove(`${this.prefix}${key}`); }
  size(): number { return this.cache.size(); }
}

interface OcrCacheTarget {
  reference: TaskModelReference;
  providerContainerId: string | null;
}

// ===== 模型/提供商查找(PreferencesStore.kt:383-389/:505-517) =====

export const findModelByIdInProviders = (
  providers: ProviderSetting[], id: string,
): ProviderModel | null => {
  for (const p of providers) {
    for (const m of p.models) {
      if (m.id === id) return m;
    }
  }
  return null;
};

// model.findProvider(OcrTransformer.kt:50,checkOverwrite 默认 true)—
//   D-084 委托 HAR findProviderForModel(PreferencesStore.kt:500-518 逐字:
//   容器反查未命中 → null;命中且 providerOverwrite 非 null → overwrite 副本)
export const findProviderOfModel = (
  providers: ProviderSetting[], model: ProviderModel,
): ProviderSetting | null => findProviderForModel(providers, model);

// ===== generateText(OcrTransformer.kt:107-120;三变体分发,AppContainer 同模式) =====
// TextGenerationParams(model = model) — 仅模型,不带 assistant 参数/自定义头;
//   auth:openai 由 adapter 注入,claude/google 由 api 层从 setting.apiKey 注入
// D-111:提取为导出函数 — VisionModelHealthChecker 探针复用同口径调用
export const generateVisionText = async (
  provider: ProviderSetting, model: ProviderModel, messages: UIMessage[],
): Promise<string | null> => {
  const api: OpenAIChatApi = await prepareProviderApi(provider, {});
  const chunk = await api.generateText(
    messages, makeTextGenerationParams({ model: toChatModel(model) }));
  const first = chunk.choices.length > 0 ? chunk.choices[0] : null;
  if (first === null || first.message === null) return null;
  return toText(first.message);
};

// ===== OcrTransformer 装配 =====

export interface OcrSeed {
  providers: ProviderSetting[];
  ocrModelId: string;
  ocrPrompt: string;
  ocrReference: TaskModelReference;
  ocrChoice: ChatProviderChoice | null;
}

// ChatPage aboutToAppear 快照加载(providers 缺失 → 空列表 → '请先配置视觉识别模型')
export const loadOcrSeed = async (kv: KeyValueStore): Promise<OcrSeed> => {
  const reference: TaskModelReference = await loadTaskModelReference(kv, OCR_MODEL_KEY);
  const validLegacy: boolean = reference.kind !== 'legacy'
    || /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(reference.modelId);
  let choice: ChatProviderChoice | null = null;
  if (validLegacy && reference.kind === 'legacy') {
    // OCR 旧 scalar 语义先命中 UUID 身份,不因该目标无鉴权而跳到另一个同 UUID 服务商。
    const configured = findTaskProviderModel((await loadProviders(kv)) ?? [], reference);
    if (configured !== null) {
      choice = await resolveConfiguredTaskModel(
        taskModelReferenceFromId(configured.model.id, configured.provider.id), 'chat');
    }
  } else if (validLegacy) {
    choice = await resolveConfiguredTaskModel(reference, 'chat');
  }
  const seed: OcrSeed = {
    providers: choice === null ? [] : [copyProviderSettingWithModels(choice.provider, [choice.model])],
    ocrModelId: reference.kind === 'fixed' ? reference.pair.modelId
      : reference.kind === 'legacy' && validLegacy ? reference.modelId : newId(),
    ocrPrompt: await getOcrPrompt(kv),
    ocrReference: reference, ocrChoice: choice,
  };
  return seed;
};

export const probeEntryOcrHealth = (seed: OcrSeed): Promise<VisionModelHealth> =>
  probeVisionModelHealth({
    findOcrModel: (): ProviderModel | null => seed.ocrChoice?.model ?? null,
    modelSupportsImage: (model: ProviderModel): boolean => model.inputModalities.includes('image'),
    findProvider: (_model: ProviderModel): ProviderSetting | null => seed.ocrChoice?.provider ?? null,
    generateText: generateVisionText,
  });

// modelSupportsImageInput = 当前聊天模型 inputModalities.contains(IMAGE)
//   (OcrTransformer.kt:50 ctx.model 判定;每次发送按 runtime 构建)
export const createEntryOcrTransformer = (
  seed: OcrSeed, modelSupportsImageInput: boolean,
): MessageTransformer => {
  const target: OcrCacheTarget = {
    reference: seed.ocrReference,
    providerContainerId: seed.ocrChoice?.providerContainerId ?? null,
  };
  return createOcrTransformer({
    modelSupportsImageInput,
    findOcrModel: (): ProviderModel | null => seed.ocrChoice?.model ?? null,
    modelSupportsImage: (model: ProviderModel): boolean =>
      model.inputModalities.includes('image'),
    findProviderForModel: (_model: ProviderModel): ProviderSetting | null => seed.ocrChoice?.provider ?? null,
    generateText: (
      provider: ProviderSetting, model: ProviderModel, messages: UIMessage[],
    ): Promise<string | null> => generateVisionText(provider, model, messages),
    ocrPrompt: (): string => seed.ocrPrompt,
    cache: new TargetVisionCache(getVisionCache(), `${JSON.stringify(target)}|`),
  });
};
