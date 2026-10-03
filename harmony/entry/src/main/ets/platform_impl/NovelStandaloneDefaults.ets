import type { Storage, NovelModelDefaults, NovelModelTarget, PolishContextOptions } from '@amber/deepread-domain';
import { defaultNovelModelDefaults } from '@amber/deepread-domain';
import { createPreferencesStorage } from './PreferencesStorage.ets';

const MODEL_KEY: string = 'novel.standalone.model-defaults';
const POLISH_KEY: string = 'novel.standalone.polish-context';
const POLISH_PREFERENCE_KEY: string = 'novel.standalone.polish-preference';
const storage: Storage = createPreferencesStorage();

const validTarget = (target: NovelModelTarget): boolean => target !== null &&
  (target.kind === 'global' || (target.kind === 'fixed' &&
    target.providerId.length > 0 && target.modelId.length > 0));

export const loadNovelModelDefaults = async (): Promise<NovelModelDefaults> => {
  const raw: string = await storage.get<string>(MODEL_KEY, '');
  if (raw.length === 0) return defaultNovelModelDefaults();
  const parsed: NovelModelDefaults = JSON.parse(raw) as NovelModelDefaults;
  if (!validTarget(parsed.writing) || !validTarget(parsed.review) || !validTarget(parsed.stateSync)) {
    throw new Error('小说默认模型设置格式无效，请在设置中重新保存。');
  }
  return parsed;
};
export const saveNovelModelDefaults = async (models: NovelModelDefaults): Promise<void> => {
  await storage.set<string>(MODEL_KEY, JSON.stringify(models));
};
export const loadNovelPolishContext = async (): Promise<PolishContextOptions> => {
  const raw: string = await storage.get<string>(POLISH_KEY, '');
  return raw.length === 0 ? {
    includePlot: true, includeForeshadows: true, includeCharacters: true, includeDecisions: true,
  } : JSON.parse(raw) as PolishContextOptions;
};
export const saveNovelPolishContext = async (context: PolishContextOptions): Promise<void> => {
  await storage.set<string>(POLISH_KEY, JSON.stringify(context));
};
export const loadNovelPolishPreference = async (): Promise<string> =>
  storage.get<string>(POLISH_PREFERENCE_KEY, '');
export const saveNovelPolishPreference = async (preference: string): Promise<void> => {
  await storage.set<string>(POLISH_PREFERENCE_KEY, preference.trim());
};
