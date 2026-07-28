// model_config — 多模型配置(议会席位 / 小说生成各自选模型)
//
// 鸿蒙端原先只存一组 ai_base_url/ai_api_key/ai_model(单模型)。议会需要多模型,
// 故引入 ModelConfig 列表 + ModelRegistry。首次加载把遗留单模型迁移为 id='default'。
// 纯逻辑 + 依赖 Storage 端口,Node 下可用 fake Storage 单测。ArkTS 安全。

import type { Storage } from '../platform/storage.ts';

export interface ModelConfig {
  id: string;
  label: string;
  baseUrl: string;
  apiKey: string;
  model: string;
}

export const MODEL_CONFIGS_STORAGE_KEY = 'model_configs';
const LEGACY_BASE_URL_KEY = 'ai_base_url';
const LEGACY_API_KEY_KEY = 'ai_api_key';
const LEGACY_MODEL_KEY = 'ai_model';
const DEFAULT_BASE_URL = 'https://api.deepseek.com';
const DEFAULT_MODEL = 'deepseek-chat';

// 本地唯一 id(无需 crypto,时间戳 + 随机后缀即可)
export const newModelId = (): string => {
  const rand: string = Math.random().toString(36).slice(2, 10);
  return `m_${Date.now().toString(36)}_${rand}`;
};

export interface ModelConfigInit {
  id?: string;
  label?: string;
  baseUrl?: string;
  apiKey?: string;
  model?: string;
}

export const makeModelConfig = (init: ModelConfigInit): ModelConfig => {
  const cfg: ModelConfig = {
    id: init.id !== undefined && init.id.length > 0 ? init.id : newModelId(),
    label: init.label ?? '',
    baseUrl: init.baseUrl ?? '',
    apiKey: init.apiKey ?? '',
    model: init.model ?? '',
  };
  return cfg;
};

export interface ModelRegistry {
  list(): Promise<ModelConfig[]>;
  get(id: string): Promise<ModelConfig | null>;
  save(models: ModelConfig[]): Promise<void>;
}

export interface ModelRegistryOptions {
  storageKey?: string;
}

export const createStorageModelRegistry = (
  storage: Storage,
  opts: ModelRegistryOptions = {},
): ModelRegistry => {
  const key: string = opts.storageKey ?? MODEL_CONFIGS_STORAGE_KEY;

  const sanitize = (raw: ModelConfig[]): ModelConfig[] => {
    const out: ModelConfig[] = [];
    for (let i = 0; i < raw.length; i++) {
      const m: ModelConfig = raw[i];
      out.push(makeModelConfig({
        id: m.id,
        label: m.label,
        baseUrl: m.baseUrl,
        apiKey: m.apiKey,
        model: m.model,
      }));
    }
    return out;
  };

  const migrateLegacy = async (): Promise<ModelConfig[]> => {
    const apiKey: string = await storage.get<string>(LEGACY_API_KEY_KEY, '');
    if (apiKey.length === 0) return [];
    const baseUrl: string = await storage.get<string>(LEGACY_BASE_URL_KEY, DEFAULT_BASE_URL);
    const model: string = await storage.get<string>(LEGACY_MODEL_KEY, DEFAULT_MODEL);
    const migrated: ModelConfig[] = [
      makeModelConfig({ id: 'default', label: '默认模型', baseUrl: baseUrl, apiKey: apiKey, model: model }),
    ];
    await storage.set<string>(key, JSON.stringify(migrated));
    return migrated;
  };

  const loadList = async (): Promise<ModelConfig[]> => {
    const raw: string = await storage.get<string>(key, '');
    if (raw.length === 0) return migrateLegacy();
    try {
      const parsed: ModelConfig[] = JSON.parse(raw) as ModelConfig[];
      if (!Array.isArray(parsed)) return [];
      return sanitize(parsed);
    } catch {
      return [];
    }
  };

  const saveList = async (models: ModelConfig[]): Promise<void> => {
    await storage.set<string>(key, JSON.stringify(sanitize(models)));
  };

  return {
    list: loadList,
    get: async (id: string): Promise<ModelConfig | null> => {
      const all: ModelConfig[] = await loadList();
      const hit: ModelConfig | undefined = all.find(m => m.id === id);
      return hit !== undefined ? hit : null;
    },
    save: saveList,
  };
};
