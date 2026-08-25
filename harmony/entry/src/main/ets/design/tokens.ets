// design/tokens.ets — Amber 设计令牌(G7 对齐 Amber Redesign 设计系统)
//
// 来源: oc-amber.css "Terminal × Modern" 暖中性石墨语言
// 原则: 令牌唯一来源,组件中禁止硬编码 hex
//
// 主题: 色板令牌为 export let(可变),applyTheme(dark) 在启动/切换时整体赋值
//   (session-home.html :root 与 [data-theme="dark"] 逐值复制)。
//   组件在 build() 时读取 → 页面重建即生效;切换当前页经 replaceUrl 重建。

// ── 基础色(Light base, Graphite) ──
export let BG: string = '#F4F2EC';         // 页面背景(暖奶油)
export let SURFACE: string = '#FAF9F5';    // 卡片/托盘(浅奶油)
export let SURFACE2: string = '#EFECE4';   // 内嵌面(药丸/chip/开关off)
export let RAISED: string = '#FFFFFF';     // 聚焦字段/分段激活
export let INK: string = '#1B1A17';        // 主文字(近黑)
export let INK2: string = '#57544C';       // 次文字/非活跃图标
export let INK3: string = '#8F8B80';       // 三级/元数据
export let INK4: string = '#B6B1A4';       // 淡(占位/禁用/标记)
export let LINE: string = '#E4E0D6';       // 细线边框/分隔
export let LINE2: string = '#D6D1C4';      // 强边框(chip/激活轮廓)
export let USER_BG: string = '#1B1A17';    // 用户气泡填充
export let USER_INK: string = '#F6F4EE';   // 用户气泡文字
export let CODE_BG: string = '#EDEAE1';    // 行内代码/工具调用背景

// ── 强调色 ──
export let ACCENT: string = '#B8623A';     // 陶土色 — 选择/当前使用
export let ACCENT_INK: string = '#FFFFFF'; // accent 填充上的文字(亮 accent 用深字)
export let ACCENT_LIGHT: string = '#FFF5F0'; // accent 淡背景
export let SIGNAL: string = '#5E9C6E';   // 活跃/在线指示(Android 2026-06-10 起别名 accent)
export let ERROR: string = '#C2553F';    // 危险红(深浅主题分档)

// Android AmberTokens.accentInkFor:亮色 accent(如 Sage)上用深字,否则白
const accentInkFor = (accent: string): string => {
  if (accent === '#5E9C6E') return '#0F150E'; // sage-green
  if (accent === '#D9A441' || accent === '#C9A461') return '#1A1408'; // gold
  return '#FFFFFF';
};

// ── 状态色(上下文用量/压缩等活动指示;中等明度,深浅主题通用) ──
export const STATUS_BLUE: string = '#4F86D6';   // 压缩中/信息
export const STATUS_AMBER: string = '#D19A66';  // ≥50% 用量预警
export let STATUS_BLUE_LIGHT: string = '#E8F0FE'; // 蓝色 chip 淡底(随主题)

// ── 遮罩(弹层/抽屉半透明黑,统一暗度) ──
export const OVERLAY: string = '#66000000';

// ── 派生 alpha 色(BG 的渐隐/玻璃变体,随主题切换) ──
export let BG_FADE_START: string = '#00F4F2EC'; // 底部渐隐起点(全透)
export let BG_FADE_END: string = '#F4F2EC';     // 底部渐隐终点(=BG)
export let BG_GLASS: string = '#CCF4F2EC';      // 头部毛玻璃底(80% BG)

// applyTheme — 浅/深双主题(session-home.html 令牌逐值)
let darkTheme: boolean = false;

const accentLightFor = (hex: string, dark: boolean): string => {
  if (dark) {
    if (hex === '#5E9C6E') return '#1D2B22';
    if (hex === '#4F86D6') return '#1B2638';
    if (hex === '#9277C4') return '#282039';
    if (hex === '#C2607A') return '#351F28';
    return '#2A211B';
  }
  if (hex === '#5E9C6E') return '#EFF8F0';
  if (hex === '#4F86D6') return '#EEF4FD';
  if (hex === '#9277C4') return '#F3F0FC';
  if (hex === '#C2607A') return '#FDF0F4';
  return '#FFF5F0';
};

export const applyTheme = (dark: boolean): void => {
  darkTheme = dark;
  if (dark) {
    BG = '#161512'; SURFACE = '#1C1B17'; SURFACE2 = '#211F1A'; RAISED = '#23211C';
    INK = '#ECE8DF'; INK2 = '#A8A298'; INK3 = '#756F64'; INK4 = '#564F45';
    LINE = '#2E2B25'; LINE2 = '#3A362E';
    USER_BG = '#ECE8DF'; USER_INK = '#1B1A17'; CODE_BG = '#211F1A';
    BG_FADE_START = '#00161512'; BG_FADE_END = '#161512'; BG_GLASS = '#CC161512';
    STATUS_BLUE_LIGHT = '#1B2638';
    ERROR = '#FF8F86'; // Material dark error(原固定 #C2553F 在深色下过暗)
  } else {
    BG = '#F4F2EC'; SURFACE = '#FAF9F5'; SURFACE2 = '#EFECE4'; RAISED = '#FFFFFF';
    INK = '#1B1A17'; INK2 = '#57544C'; INK3 = '#8F8B80'; INK4 = '#B6B1A4';
    LINE = '#E4E0D6'; LINE2 = '#D6D1C4';
    USER_BG = '#1B1A17'; USER_INK = '#F6F4EE'; CODE_BG = '#EDEAE1';
    BG_FADE_START = '#00F4F2EC'; BG_FADE_END = '#F4F2EC'; BG_GLASS = '#CCF4F2EC';
    STATUS_BLUE_LIGHT = '#E8F0FE';
    ERROR = '#C93A2F'; // Material light error
  }
  ACCENT_LIGHT = accentLightFor(ACCENT, darkTheme);
  // signal 随 accent(Android 别名决策);accentInk 按 accent 亮度
  SIGNAL = ACCENT;
  ACCENT_INK = accentInkFor(ACCENT);
};

// setAccentColor — 强调色切换(色板五选一)
export const setAccentColor = (hex: string): void => {
  ACCENT = hex;
  ACCENT_LIGHT = accentLightFor(hex, darkTheme);
  SIGNAL = hex;
  ACCENT_INK = accentInkFor(hex);
};

// ── 字号(对齐设计稿 px 值) ──
export const TITLE: number = 19;             // 页面标题(19/700)
export const HEADER: number = 16;            // 聊天头标题(16/700)
export const BODY: number = 15;              // 正文/行标题(15/400-600)
export const BODY2: number = 13.5;           // 次正文/值(13.5/400)
export const META: number = 12;              // 元数据/模型名(12/500)
export const MONO_SMALL: number = 11.5;      // 等宽小字(11.5/400)
export const LABEL: number = 11;             // 标签/eyebrow(11/600)
export const TINY: number = 10.5;            // 极小字(10.5/400-600)

// ── 间距/圆角 ──
export const PAD_CARD: number = 16;
export const PAD_ITEM: number = 13;
export const RADIUS: number = 14;            // 卡片/字段圆角
export const RADIUS_PILL: number = 999;      // 药丸/开关
export const GUTTER: number = 18;            // 屏幕边距
