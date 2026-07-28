// ExtensionPrefs — 提示词注入设置读取(D-075)
// Android 基准:
//   PreferencesKeys.kt:74-75 — 'mode_injections'/'lorebooks' 键名逐字
//   ExtensionPrefs.kt:82-87 — decodeJsonOrNull → emptyList(缺失/解析失败 → [])
// 偏差:
//   - JSON 反序列化容错:逐字段 ?? 默认(Android kotlinx 缺字段也走默认值)
//   - 'mode'/'regex' 判别:Android sealed @SerialName("mode"/"regex"),本文件
//     mode_injections 键只承载 ModeInjection,lorebooks.entries 只承载
//     RegexInjection(与 Android 存储形状一致,不需要 type 判别字段)
import type { KeyValueStore } from '@amber/chat-domain';
import {
  makeLorebook, makeModeInjection, makeRegexInjection,
} from '@amber/chat-domain';
import type { Lorebook, PromptInjection, RegexInjection } from '@amber/chat-domain';

export const MODE_INJECTIONS_KEY: string = 'mode_injections';
export const LOREBOOKS_KEY: string = 'lorebooks';

// ExtensionPrefs.kt:82-87 — decodeJsonOrNull → emptyList
export const loadModeInjections = async (kv: KeyValueStore): Promise<PromptInjection[]> => {
  const raw: string | null = await kv.get(MODE_INJECTIONS_KEY);
  if (raw === null) return [];
  try {
    const parsed: Array<Partial<PromptInjection>> = JSON.parse(raw) as Array<Partial<PromptInjection>>;
    return parsed.map((o: Partial<PromptInjection>): PromptInjection => makeModeInjection(o));
  } catch {
    return [];
  }
};

export const loadLorebooks = async (kv: KeyValueStore): Promise<Lorebook[]> => {
  const raw: string | null = await kv.get(LOREBOOKS_KEY);
  if (raw === null) return [];
  try {
    const parsed: Array<Partial<Lorebook>> = JSON.parse(raw) as Array<Partial<Lorebook>>;
    return parsed.map((o: Partial<Lorebook>): Lorebook => makeLorebook({
      id: o.id, name: o.name, description: o.description, enabled: o.enabled,
      entries: (o.entries ?? []).map(
        (e: Partial<RegexInjection>): RegexInjection => makeRegexInjection(e)),
    }));
  } catch {
    return [];
  }
};
