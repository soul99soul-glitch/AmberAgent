// AgentRuntimePrefs — agent_runtime 设置读取(D-072 起,渐进移植)
// Android 基准:
//   PreferencesKeys.kt:77 — 'agent_runtime' 键名逐字
//   AgentPrefs.kt:47-51 — decodeJsonOrNull → 默认 AgentRuntimeSetting
//   PreferencesStore.kt:156 — enableTimeReminder 默认 false
// 裁剪:其余 agentRuntime 字段(autoApproveAllToolCalls/推测执行/compact 等
//   设置面)= P1 已登记,本文件仅承载 D-072 所需快照
import { i18n } from '@kit.LocalizationKit';
import type { KeyValueStore } from '@amber/chat-domain';

export const AGENT_RUNTIME_KEY: string = 'agent_runtime';

export interface AgentRuntimeSnapshot {
  enableTimeReminder: boolean;
  // D-077b:PreferencesStore.kt:155 默认 true(GenerationHandler.kt:736 消费)
  enableRecentChatsReference: boolean;
}

interface AgentRuntimeJson {
  enableTimeReminder?: boolean;
  enableRecentChatsReference?: boolean;
}

export const defaultAgentRuntimeSnapshot = (): AgentRuntimeSnapshot => ({
  enableTimeReminder: false,
  enableRecentChatsReference: true,
});

// AgentPrefs.kt:47-51 — 缺失/解析失败 → 默认(enableTimeReminder=false)
export const loadAgentRuntimeSnapshot = async (kv: KeyValueStore): Promise<AgentRuntimeSnapshot> => {
  const raw: string | null = await kv.get(AGENT_RUNTIME_KEY);
  if (raw === null) return defaultAgentRuntimeSnapshot();
  try {
    const parsed: AgentRuntimeJson = JSON.parse(raw) as AgentRuntimeJson;
    return {
      enableTimeReminder: parsed.enableTimeReminder === true,
      // 默认 true:仅显式 false 才关(PreferencesStore.kt:155)
      enableRecentChatsReference: parsed.enableRecentChatsReference !== false,
    };
  } catch {
    return defaultAgentRuntimeSnapshot();
  }
};

// i18n.System.getSystemLanguage(Locale.getDefault 等价,SearchSdkInit 同模式);
//   失败回 'en'(TextStyle.FULL 英文档)
export const systemLanguage = (): string => {
  try {
    const lang: string = i18n.System.getSystemLanguage();
    return lang.length > 0 ? lang : 'en';
  } catch {
    return 'en';
  }
};
