// ChatTheme — 聊天主题系统(G6-S5,对齐 Android ChatTheme.kt / Theme.kt / Color.kt)
// 三套预设主题:Graphite(默认暖石墨)/ Sage(鼠尾草绿)/ Midnight(深夜墨)
//
// 设计原则:
//   - ChatThemeColors 仅承载 12 个核心色 token(bg/surface/surface2/ink×4/line/accent/
//     accentLight/signal/error),消费侧用 getThemeColors(theme) 取值
//   - graphiteTheme() 逐字复刻 tokens.ets 现有值(向后兼容)
//   - sageTheme() 仅替换 accent 为 #5E9C6E + accentLight 同色系浅底,其余暖石墨底不变
//   - midnightTheme() 反相:深底 #161512 + 浅字 #ECE8DF,accent 保留赤陶
//
// 持久化:loadTheme/saveTheme 走 KV store(对照 AgentRuntimePrefs/ExtensionPrefs 的
//   kv 入参惯例;design 层不直接耦合 AppContainer,由调用方注入 kv)
//
// ArkUI 规则:无 as unknown / 无对象展开 / 无 any/unknown;纯模块(非 @Component)
import {
  BG, SURFACE, SURFACE2, INK, INK2, INK3, INK4, LINE, ACCENT, ACCENT_LIGHT, SIGNAL, ERROR,
} from './tokens.ets';

// 主题枚举(Graphite 为默认)
export enum Theme {
  Graphite,
  Sage,
  Midnight,
}

// 主题颜色契约 —— 12 个核心 token,消费侧逐字段读取
export interface ChatThemeColors {
  bg: string;
  surface: string;
  surface2: string;
  ink: string;
  ink2: string;
  ink3: string;
  ink4: string;
  line: string;
  accent: string;
  accentLight: string;
  signal: string;
  error: string;
}

// 主题显示名(设置面/预览用)
export const themeLabel = (theme: Theme): string => {
  if (theme === Theme.Sage) return 'Sage';
  if (theme === Theme.Midnight) return 'Midnight';
  return 'Graphite';
};

// Graphite(默认)—— 逐字复刻 tokens.ets 现有值,保证向后兼容
export const graphiteTheme = (): ChatThemeColors => {
  return {
    bg: BG,
    surface: SURFACE,
    surface2: SURFACE2,
    ink: INK,
    ink2: INK2,
    ink3: INK3,
    ink4: INK4,
    line: LINE,
    accent: ACCENT,
    accentLight: ACCENT_LIGHT,
    signal: SIGNAL,
    error: ERROR,
  };
};

// Sage —— 鼠尾草绿 accent,暖石墨底不变(仅 accent/accentLight/signal 替换为同色系)
export const sageTheme = (): ChatThemeColors => {
  return {
    bg: BG,
    surface: SURFACE,
    surface2: SURFACE2,
    ink: INK,
    ink2: INK2,
    ink3: INK3,
    ink4: INK4,
    line: LINE,
    accent: '#5E9C6E',
    accentLight: '#EEF7F0',
    signal: '#5E9C6E',
    error: ERROR,
  };
};

// Midnight —— 深夜墨:深底 #161512 + 浅字 #ECE8DF,accent 保留赤陶
// 反相阶梯:ink 系由深→浅,surface 系由浅→深,line 加深,error 提亮(深底可读)
export const midnightTheme = (): ChatThemeColors => {
  return {
    bg: '#161512',
    surface: '#1F1D18',
    surface2: '#2A2722',
    ink: '#ECE8DF',
    ink2: '#B8B3A6',
    ink3: '#8F8B80',
    ink4: '#5E5B52',
    line: '#33302A',
    accent: '#B8623A',
    accentLight: '#3A2418',
    signal: '#5E9C6E',
    error: '#E06A5F',
  };
};

// 主题 → 颜色解析(Graphite 为默认兜底)
export const getThemeColors = (theme: Theme): ChatThemeColors => {
  if (theme === Theme.Sage) return sageTheme();
  if (theme === Theme.Midnight) return midnightTheme();
  return graphiteTheme();
};

// ===== 持久化(KV store)=====

// KV store 契约子集 —— 与 @amber/chat-domain KeyValueStore / ChatKvStore 结构兼容,
// 调用方可直接传入 getChatKvStore()(structurally satisfied)
export interface ThemeKvStore {
  get(key: string): Promise<string | null>;
  put(key: string, value: string): Promise<void>;
}

// KV 键名(逐字,与 Android PreferencesKeys 对齐)
export const CHAT_THEME_KEY: string = 'chat_theme';

// 字符串 → Theme(宽容解析:未知/缺失 → Graphite 默认)
export const parseTheme = (raw: string | null): Theme => {
  if (raw === 'sage' || raw === 'Sage') return Theme.Sage;
  if (raw === 'midnight' || raw === 'Midnight') return Theme.Midnight;
  return Theme.Graphite;
};

// 主题 → 持久化字符串(小写短名,线格式稳定)
export const themeToKey = (theme: Theme): string => {
  if (theme === Theme.Sage) return 'sage';
  if (theme === Theme.Midnight) return 'midnight';
  return 'graphite';
};

// loadTheme —— 读 KV,缺失/解析失败 → Graphite 默认
export const loadTheme = async (kv: ThemeKvStore): Promise<Theme> => {
  const raw: string | null = await kv.get(CHAT_THEME_KEY);
  return parseTheme(raw);
};

// saveTheme —— 写 KV 小写短名
export const saveTheme = async (kv: ThemeKvStore, theme: Theme): Promise<void> => {
  await kv.put(CHAT_THEME_KEY, themeToKey(theme));
};

// 按显示名解析主题(CoreScreenPreview / 设置面按名取色用)
export const themeByName = (name: string): Theme => {
  const lower: string = name.toLowerCase();
  if (lower === 'sage') return Theme.Sage;
  if (lower === 'midnight') return Theme.Midnight;
  return Theme.Graphite;
};

// 便捷:按名取色(预览组件常用)
export const colorsByName = (name: string): ChatThemeColors => {
  return getThemeColors(themeByName(name));
};
