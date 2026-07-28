// ExtensionPrefs — 提示词注入设置读写(D-075 读;D-100 写)
// Android 基准:
//   PreferencesKeys.kt:74-75 — 'mode_injections'/'lorebooks' 键名逐字
//   ExtensionPrefs.kt:49-56(update:next == current 短路)/:58-90(readFrom
//     decodeJsonOrNull ?: emptyList)/:92-113(writeTo JsonInstant 全量编码)
// 偏差:
//   - 序列化统一走 chat HAR injection_serialize(声明序 + encodeDefaults 全量;
//     decode 严格度 = kotlinx:类型错/枚举非法 → null;缺字段 → 默认,id 缺失生成)
//   - data class 相等 → JSON 串相等比较(update 短路,同 search_prefs D-070 登记)
import type { KeyValueStore } from '@amber/chat-domain';
import {
  decodeLorebooks, decodeModeInjections, encodeLorebooks, encodeModeInjections,
} from '@amber/chat-domain';
import type { Lorebook, PromptInjection } from '@amber/chat-domain';

export const MODE_INJECTIONS_KEY: string = 'mode_injections';
export const LOREBOOKS_KEY: string = 'lorebooks';

// ExtensionPrefs.kt:82-87 — decodeJsonOrNull ?: emptyList
export const loadModeInjections = async (kv: KeyValueStore): Promise<PromptInjection[]> => {
  const raw: string | null = await kv.get(MODE_INJECTIONS_KEY);
  if (raw === null) return [];
  return decodeModeInjections(raw) ?? [];
};

export const loadLorebooks = async (kv: KeyValueStore): Promise<Lorebook[]> => {
  const raw: string | null = await kv.get(LOREBOOKS_KEY);
  if (raw === null) return [];
  return decodeLorebooks(raw) ?? [];
};

// writeTo(:108-109)— JsonInstant.encodeToString 全量
export const saveModeInjections = async (
  kv: KeyValueStore, list: PromptInjection[],
): Promise<void> => {
  await kv.put(MODE_INJECTIONS_KEY, encodeModeInjections(list));
};

export const saveLorebooks = async (kv: KeyValueStore, list: Lorebook[]): Promise<void> => {
  await kv.put(LOREBOOKS_KEY, encodeLorebooks(list));
};

// update(:49-56):next == current 短路(JSON 串相等)
export const updateModeInjections = async (
  kv: KeyValueStore,
  transform: (current: PromptInjection[]) => PromptInjection[],
): Promise<void> => {
  const current: PromptInjection[] = await loadModeInjections(kv);
  const next: PromptInjection[] = transform(current);
  if (encodeModeInjections(next) === encodeModeInjections(current)) return;
  await saveModeInjections(kv, next);
};

export const updateLorebooks = async (
  kv: KeyValueStore,
  transform: (current: Lorebook[]) => Lorebook[],
): Promise<void> => {
  const current: Lorebook[] = await loadLorebooks(kv);
  const next: Lorebook[] = transform(current);
  if (encodeLorebooks(next) === encodeLorebooks(current)) return;
  await saveLorebooks(kv, next);
};
