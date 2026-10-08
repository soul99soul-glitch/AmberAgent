// display_setting — 显示偏好设置(core/settings/PreferencesStore.kt:315-355)
//
// Android 基准: DisplaySetting data class(~30 字段),序列化到 Preferences DataStore
// 鸿蒙: 同字段接口 + KV JSON 持久化(loadDisplaySetting/saveDisplaySetting)
//
// 纯数据 + 序列化:零 UI/零 IO(KV store 是注入的)

export interface DisplaySetting {
  showAssistantBubble: boolean;
  showModelIcon: boolean;
  showModelName: boolean;
  showUserAvatar: boolean;
  showDateBelowName: boolean;
  showThinkingContent: boolean;
  autoCloseThinking: boolean;
  showUpdates: boolean;
  showMessageJumper: boolean;
  messageJumperOnLeft: boolean;
  fontSizeRatio: number;
  enableMessageGenerationHapticEffect: boolean;
  skipCropImage: boolean;
  enableNotificationOnMessageGeneration: boolean;
  enableLiveUpdateNotification: boolean;
  codeBlockAutoWrap: boolean;
  codeBlockAutoCollapse: boolean;
  showLineNumbers: boolean;
  pasteLongTextAsFile: boolean;
  pasteLongTextThreshold: number;
  sendOnEnter: boolean;
  enableAutoScroll: boolean;
  showBottomFollowAnimation: boolean;
  enableLatexRendering: boolean;
  enableGenerativeWidgets: boolean;
  enableBlurEffect: boolean;
  chatFontFamily: string;   // 'DEFAULT' | 'SERIF' | 'MONO'
  enableVolumeKeyScroll: boolean;
  volumeKeyScrollRatio: number;
  chatThemeChoice: string;  // 'WHISPER' etc
  amberBaseFamily: string;  // 'WARM' | 'SAGE'
  accentColor: string;      // hex
  userNickname: string;     // 用户昵称(Android DisplaySetting 对齐)
  userAvatar: string;       // 头像(emoji/图片URL,空则首字母)
}

export const DISPLAY_SETTING_KEY: string = 'display_setting';

export const makeDisplaySetting = (opts: Partial<DisplaySetting> = {}): DisplaySetting => ({
  showAssistantBubble: opts.showAssistantBubble ?? false,
  showModelIcon: opts.showModelIcon ?? true,
  showModelName: opts.showModelName ?? true,
  // 默认 false:1:1 原型用户消息无抬头,开关为 opt-in(Android showUserAvatar 对齐)
  showUserAvatar: opts.showUserAvatar ?? false,
  showDateBelowName: opts.showDateBelowName ?? false,
  showThinkingContent: opts.showThinkingContent ?? true,
  autoCloseThinking: opts.autoCloseThinking ?? true,
  showUpdates: opts.showUpdates ?? true,
  showMessageJumper: opts.showMessageJumper ?? true,
  messageJumperOnLeft: opts.messageJumperOnLeft ?? false,
  fontSizeRatio: opts.fontSizeRatio ?? 1.0,
  enableMessageGenerationHapticEffect: opts.enableMessageGenerationHapticEffect ?? false,
  skipCropImage: opts.skipCropImage ?? false,
  enableNotificationOnMessageGeneration: opts.enableNotificationOnMessageGeneration ?? false,
  enableLiveUpdateNotification: opts.enableLiveUpdateNotification ?? false,
  codeBlockAutoWrap: opts.codeBlockAutoWrap ?? false,
  codeBlockAutoCollapse: opts.codeBlockAutoCollapse ?? false,
  showLineNumbers: opts.showLineNumbers ?? false,
  pasteLongTextAsFile: opts.pasteLongTextAsFile ?? false,
  pasteLongTextThreshold: opts.pasteLongTextThreshold ?? 1000,
  sendOnEnter: opts.sendOnEnter ?? false,
  enableAutoScroll: opts.enableAutoScroll ?? true,
  showBottomFollowAnimation: opts.showBottomFollowAnimation ?? true,
  enableLatexRendering: opts.enableLatexRendering ?? true,
  enableGenerativeWidgets: opts.enableGenerativeWidgets ?? true,
  enableBlurEffect: opts.enableBlurEffect ?? false,
  chatFontFamily: opts.chatFontFamily ?? 'DEFAULT',
  enableVolumeKeyScroll: opts.enableVolumeKeyScroll ?? false,
  volumeKeyScrollRatio: opts.volumeKeyScrollRatio ?? 1.0,
  chatThemeChoice: opts.chatThemeChoice ?? 'WHISPER',
  amberBaseFamily: opts.amberBaseFamily ?? 'WARM',
  accentColor: opts.accentColor ?? '#B8623A',
  userNickname: opts.userNickname ?? '',
  userAvatar: opts.userAvatar ?? '',
});

import type { KeyValueStore } from './kv_store.ts';

export const loadDisplaySetting = async (store: KeyValueStore): Promise<DisplaySetting> => {
  const raw: string | null = await store.get(DISPLAY_SETTING_KEY);
  if (raw === null) return makeDisplaySetting();
  try {
    const parsed: Partial<DisplaySetting> = JSON.parse(raw);
    return makeDisplaySetting(parsed);
  } catch (_e) {
    return makeDisplaySetting();
  }
};

export const saveDisplaySetting = async (store: KeyValueStore, setting: DisplaySetting): Promise<void> => {
  await store.put(DISPLAY_SETTING_KEY, JSON.stringify(setting));
};
