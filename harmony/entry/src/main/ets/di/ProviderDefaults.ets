// ProviderDefaults — Android DefaultProviders.kt 的 1:1 鸿蒙移植
// (9 家预置:OpenAI/Gemini/DeepSeek/OpenRouter/月之暗面/智谱/小米 MiMo/MiniMax/xAI,
//  含种子生图模型、余额查询配置、品牌 slug/auth 徽标 helper)
// id 与 Android 侧使用完全相同的 UUID 字符串(将来跨端迁移/导入可对上)。
import {
  ProviderSetting, ProviderModel, ProviderSettingOpenAIVariant,
  makeProviderSettingOpenAIVariant, makeProviderSettingGoogle,
  makeProviderModel, makeBalanceOption,
} from '@amber/chat-domain';

const SEED_OPENAI_IMAGE_ID: string = 'c7e8a911-3b4d-4f2a-8c1e-0d7f2a3b4c5d';
const SEED_GEMINI_IMAGE_ID: string = 'c7e8a911-3b4d-4f2a-8c1e-0d7f2a3b4c5e';
const SEED_MINIMAX_M3_ID: string = 'c7e8a911-3b4d-4f2a-8c1e-0d7f2a3b4c5f';

// gpt-image-2:ModelType.IMAGE,只进「生图模型」选择器,不污染聊天下拉
const seedOpenAIImageModel = (): ProviderModel => makeProviderModel({
  id: SEED_OPENAI_IMAGE_ID,
  modelId: 'gpt-image-2',
  displayName: 'gpt-image-2',
  type: 'image',
  inputModalities: ['text'],
  outputModalities: ['image'],
});

const seedGeminiImageModel = (): ProviderModel => makeProviderModel({
  id: SEED_GEMINI_IMAGE_ID,
  modelId: 'gemini-3.1-flash-image-preview',
  displayName: 'Gemini 3.1 Flash Image (Nano Banana 2)',
  type: 'image',
  inputModalities: ['text'],
  outputModalities: ['image'],
});

const seedMiniMaxM3Model = (): ProviderModel => makeProviderModel({
  id: SEED_MINIMAX_M3_ID,
  modelId: 'MiniMax-M3',
  displayName: 'MiniMax-M3',
});

export const DEFAULT_PROVIDERS: ProviderSetting[] = [
  makeProviderSettingOpenAIVariant({
    id: '1eeea727-9ee5-4cae-93e6-6fb01a4d051e',
    name: 'OpenAI',
    baseUrl: 'https://api.openai.com/v1',
    apiKey: '',
    brand: 'openai',
    models: [seedOpenAIImageModel()],
  }),
  makeProviderSettingGoogle({
    id: '6ab18148-c138-4394-a46f-1cd8c8ceaa6d',
    name: 'Gemini',
    apiKey: '',
    enabled: true,
    models: [seedGeminiImageModel()],
  }),
  makeProviderSettingOpenAIVariant({
    id: 'f099ad5b-ef03-446d-8e78-7e36787f780b',
    name: 'DeepSeek',
    baseUrl: 'https://api.deepseek.com/v1',
    apiKey: '',
    balanceOption: makeBalanceOption({
      enabled: true,
      apiPath: '/user/balance',
      resultPath: 'balance_infos[0].total_balance',
    }),
    brand: 'deepseek',
  }),
  makeProviderSettingOpenAIVariant({
    id: 'd5734028-d39b-4d41-9841-fd648d65440e',
    name: 'OpenRouter',
    baseUrl: 'https://openrouter.ai/api/v1',
    apiKey: '',
    balanceOption: makeBalanceOption({
      enabled: true,
      apiPath: '/credits',
      resultPath: 'data.total_credits - data.total_usage',
    }),
    brand: 'generic',
  }),
  makeProviderSettingOpenAIVariant({
    id: 'd6c4d8c6-3f62-4ca9-a6f3-7ade6b15ecc3',
    name: '月之暗面 (Kimi)',
    baseUrl: 'https://api.moonshot.cn/v1',
    apiKey: '',
    enabled: true,
    balanceOption: makeBalanceOption({
      enabled: true,
      apiPath: '/users/me/balance',
      resultPath: 'data.available_balance',
    }),
    brand: 'kimi',
  }),
  makeProviderSettingOpenAIVariant({
    id: '9f3a6b2c-7d4e-4810-9a2f-3e8b5d142c01',
    name: '智谱 GLM',
    baseUrl: 'https://open.bigmodel.cn/api/paas/v4',
    apiKey: '',
    enabled: false,
    brand: 'zhipu',
  }),
  makeProviderSettingOpenAIVariant({
    id: '9f3a6b2c-7d4e-4810-9a2f-3e8b5d142c02',
    name: '小米 MiMo',
    baseUrl: 'https://api.xiaomi.com/v1',
    apiKey: '',
    enabled: false,
    brand: 'mimo',
  }),
  makeProviderSettingOpenAIVariant({
    id: '9f3a6b2c-7d4e-4810-9a2f-3e8b5d142c03',
    name: 'MiniMax',
    baseUrl: 'https://api.minimaxi.com/v1',
    apiKey: '',
    enabled: false,
    brand: 'minimax',
    models: [seedMiniMaxM3Model()],
  }),
  makeProviderSettingOpenAIVariant({
    id: 'ff3cde7e-0f65-43d7-8fb2-6475c99f5990',
    name: 'xAI',
    baseUrl: 'https://api.x.ai/v1',
    apiKey: '',
    enabled: false,
    brand: 'generic',
    // Android 同款默认走 Responses API;鸿蒙打开详情会提示迁移到 Chat Completions
    useResponseApi: true,
  }),
];

// monogram 缩写:DeepSeek→DS、月之暗面→kimi、小米→mimo、智谱→Z.AI,
// 其余取词首字母(两词)或前 4 字符;空回退 AI(Android toProviderMonogram 同款)
export function toProviderMonogram(name: string): string {
  const normalized: string = name.trim().toLowerCase();
  if (normalized.indexOf('deepseek') >= 0) return 'DS';
  if (normalized.indexOf('kimi') >= 0 || normalized.indexOf('月之暗面') >= 0) return 'kimi';
  if (normalized.indexOf('mimo') >= 0 || normalized.indexOf('小米') >= 0) return 'mimo';
  if (normalized.indexOf('zhipu') >= 0 || normalized.indexOf('智谱') >= 0
    || normalized.indexOf('z.ai') >= 0) {
    return 'Z.AI';
  }
  const words: string[] = name.trim().split(/[\s\-_]+/).filter(
    (w: string): boolean => w.length > 0);
  if (words.length >= 2) {
    let out: string = '';
    for (let i: number = 0; i < 2; i++) {
      out += words[i].charAt(0).toUpperCase();
    }
    return out;
  }
  if (words.length === 1) {
    return words[0].substring(0, 4).toUpperCase();
  }
  return 'AI';
}

// slug:品牌转小写(下划线转连字符);generic 用名称 slug;google 变体固定
export function providerSlug(p: ProviderSetting): string {
  if (p.type === 'google') return 'google-gemini';
  if (p.type === 'openai') {
    const v: ProviderSettingOpenAIVariant = p as ProviderSettingOpenAIVariant;
    if (v.brand === 'generic') return nameSlug(p.name);
    return v.brand.replace(/_/g, '-');
  }
  return p.type;
}

const nameSlug = (name: string): string => {
  const lowered: string = name.trim().toLowerCase();
  let out: string = '';
  let prevDash: boolean = false;
  for (let i: number = 0; i < lowered.length; i++) {
    const ch: string = lowered.charAt(i);
    const ok: boolean = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
      || (ch.charCodeAt(0) > 127);
    if (ok) {
      out += ch;
      prevDash = false;
    } else if (!prevDash && out.length > 0) {
      out += '-';
      prevDash = true;
    }
  }
  while (out.endsWith('-')) out = out.substring(0, out.length - 1);
  return out;
};

// Auth labels describe configuration only; Google account state is resolved by Entry.
export function providerAuthLabel(p: ProviderSetting): string {
  if (p.type === 'google') {
    if (p.useServiceAccount) return 'service_account';
    if (p.authMode !== 'api_key') return 'google_oauth';
  }
  return p.apiKey.length > 0 ? 'key' : '—';
}
