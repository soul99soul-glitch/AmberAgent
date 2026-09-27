// design/tokens.ets — Amber 设计令牌(G7 对齐 Amber Redesign 设计系统)
//
// 来源: oc-amber.css "Terminal × Modern" 暖中性石墨语言
// 原则: 令牌唯一来源,组件中禁止硬编码 hex
//
// 主题: 色板令牌为 export let(可变),applyTheme(dark) 在启动/切换时整体赋值
//   (session-home.html :root 与 [data-theme="dark"] 逐值复制)。
//   组件在 build() 时读取 → 页面重建即生效;切换当前页经 replaceUrl 重建。

import { FONT_SANS, FONT_SANS_SEMIBOLD, FONT_SANS_BOLD, FONT_MONO, FONT_MONO_SEMIBOLD } from './fonts.ets';

// ── 基础色(Light base, Graphite) ──
export let BG: string = '#EFE7D6';         // 页面背景(暖奶油,iOS paper 0xEFE7D6 = HOME_BG,全站唯一画布)
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
// 状态色淡底/前景(Tag / StatusPill / ErrorCard;随 applyTheme 切换)
export let ERROR_BG: string = '#FFF0EE';
export let SUCCESS_BG: string = '#E8F5E9';
export let SUCCESS_FG: string = '#2E7D32';
export let WARNING_BG: string = '#FFF8E1';
export let WARNING_FG: string = '#A64500';
export let INFO_BG: string = '#E8F0FE';
export let INFO_FG: string = '#114BA3';
export const ACCENT_OPTIONS: string[] = ['#B8623A', '#5E9C6E', '#4F86D6', '#9277C4', '#C2607A'];

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
export let BG_FADE_START: string = '#00EFE7D6'; // 底部渐隐起点(全透)
export let BG_FADE_END: string = '#EFE7D6';     // 底部渐隐终点(=BG)
export let BG_GLASS: string = '#CCEFE7D6';      // 头部毛玻璃底(80% BG)

// applyTheme — 浅/深双主题(session-home.html 令牌逐值)
let darkTheme: boolean = false;

// 卡片面级 accent 浅底:ACCENT_LIGHT(#FFF5F0)在 SURFACE 白卡上不可见;
// 图标座/状态 chip 在卡内改用 accent 12% alpha(深浅主题在卡上均成立,随 accent 五色动态)
export const accentSoftBg = (accent: string): string => `#1F${accent.substring(1)}`;

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
    BG = '#14110E'; SURFACE = '#1C1B17'; SURFACE2 = '#211F1A'; RAISED = '#23211C';
    INK = '#ECE8DF'; INK2 = '#A8A298'; INK3 = '#756F64'; INK4 = '#564F45';
    LINE = '#2E2B25'; LINE2 = '#3A362E';
    USER_BG = '#ECE8DF'; USER_INK = '#1B1A17'; CODE_BG = '#211F1A';
    BG_FADE_START = '#0014110E'; BG_FADE_END = '#14110E'; BG_GLASS = '#CC14110E';
    STATUS_BLUE_LIGHT = '#1B2638';
    SHADOW_KEY = '#99000000'; SHADOW_OVERLAY = '#B3000000';
    ERROR = '#FF8F86'; // Material dark error(原固定 #C2553F 在深色下过暗)
    ERROR_BG = '#2A1F1D'; SUCCESS_BG = '#1D2B22'; SUCCESS_FG = '#8FCC9A';
    WARNING_BG = '#2A2418'; WARNING_FG = '#D19A66';
    INFO_BG = '#1B2638'; INFO_FG = '#8BB4E8';
    // iOS paperDark + homeDark 逐值
    HOME_BG = '#14110E'; HOME_CARD = '#221E19'; HOME_CARD2 = '#2E2822';
    HOME_INK = '#F5F0E8'; HOME_INK2 = '#C8BDB0'; HOME_MUTED = '#A89888'; HOME_MUTED2 = '#6E6258';
    HOME_SEP = 'rgba(255, 255, 255, 0.055)'; HOME_ROWLINE = 'rgba(255, 255, 255, 0.08)';
    HOME_PRESS = 'rgba(255, 255, 255, 0.055)'; HOME_SHADOW = 'rgba(0, 0, 0, 0.40)';
    HOME_GLASS_TOP = 'rgba(255, 255, 255, 0.14)'; HOME_GLASS_BOTTOM = 'rgba(255, 255, 255, 0.08)';
    HOME_GLASS_EDGE = 'rgba(255, 255, 255, 0.16)'; HOME_GLASS_SHADOW = 'rgba(0, 0, 0, 0.30)';
    HOME_DOT = 'rgba(244, 241, 237, 0.08)';
  } else {
    BG = '#EFE7D6'; SURFACE = '#FAF9F5'; SURFACE2 = '#EFECE4'; RAISED = '#FFFFFF';
    INK = '#1B1A17'; INK2 = '#57544C'; INK3 = '#8F8B80'; INK4 = '#B6B1A4';
    LINE = '#E4E0D6'; LINE2 = '#D6D1C4';
    USER_BG = '#1B1A17'; USER_INK = '#F6F4EE'; CODE_BG = '#EDEAE1';
    BG_FADE_START = '#00EFE7D6'; BG_FADE_END = '#EFE7D6'; BG_GLASS = '#CCEFE7D6';
    STATUS_BLUE_LIGHT = '#E8F0FE';
    SHADOW_KEY = '#261B1A17'; SHADOW_OVERLAY = '#381B1A17';
    ERROR = '#C93A2F'; // Material light error
    ERROR_BG = '#FFF0EE'; SUCCESS_BG = '#E8F5E9'; SUCCESS_FG = '#2E7D32';
    WARNING_BG = '#FFF8E1'; WARNING_FG = '#A64500';
    INFO_BG = '#E8F0FE'; INFO_FG = '#114BA3';
    // iOS paperLight + homePaper 逐值
    HOME_BG = '#EFE7D6'; HOME_CARD = '#FFFDF7'; HOME_CARD2 = '#F0EBE2';
    HOME_INK = '#1B1813'; HOME_INK2 = '#5B5449'; HOME_MUTED = '#746D62'; HOME_MUTED2 = '#918A80';
    HOME_SEP = 'rgba(38, 30, 20, 0.05)'; HOME_ROWLINE = 'rgba(27, 24, 19, 0.08)';
    HOME_PRESS = 'rgba(89, 66, 35, 0.055)'; HOME_SHADOW = 'rgba(76, 59, 34, 0.10)';
    HOME_GLASS_TOP = 'rgba(255, 255, 255, 0.78)'; HOME_GLASS_BOTTOM = 'rgba(255, 255, 255, 0.58)';
    HOME_GLASS_EDGE = 'rgba(255, 255, 255, 0.50)'; HOME_GLASS_SHADOW = 'rgba(40, 36, 28, 0.10)';
    HOME_DOT = 'rgba(40, 31, 20, 0.08)';
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

// ── TypeScale contract (Android AmberType.kt seven levels) ──
// family 与 weight 绑定下发(AmberSans 系列由合并字体承载字重),消费点应同时
// 设置 fontSize/fontWeight/lineHeight/fontFamily 四件套,禁止混搭旧常数。

export class TextStyleToken {
  size: number;
  weight: FontWeight;
  lineHeight: number;
  letterSpacing: number;
  family: string;

  constructor(size: number, weight: FontWeight, lineHeight: number,
    letterSpacing: number = 0, family: string = FONT_SANS) {
    this.size = size;
    this.weight = weight;
    this.lineHeight = lineHeight;
    this.letterSpacing = letterSpacing;
    this.family = family;
  }
}

export class TextStyles {
  screenTitle: TextStyleToken = new TextStyleToken(19, FontWeight.Bold, 25, 0, FONT_SANS_BOLD);
  sessionTitle: TextStyleToken = new TextStyleToken(16, FontWeight.Bold, 21, 0, FONT_SANS_BOLD);
  body: TextStyleToken = new TextStyleToken(15, FontWeight.Normal, 22);
  secondary: TextStyleToken = new TextStyleToken(13, FontWeight.Normal, 18);
  meta: TextStyleToken = new TextStyleToken(12, FontWeight.Normal, 16, 0, FONT_MONO);
  eyebrow: TextStyleToken = new TextStyleToken(11, 600, 14, 1.3, FONT_MONO_SEMIBOLD);
  tinyTag: TextStyleToken = new TextStyleToken(10.5, 600, 13, 0, FONT_SANS_SEMIBOLD);
}

export const TEXT_STYLES: TextStyles = new TextStyles();

// ── 行高契约(AmberType 七级逐对 size→lineHeight;契约外字号取「最接近档」,
//    等距时归较小档,避免小标题被套大档行高) ──
const LH_BREAKPOINTS: number[] = [19, 16, 15, 13, 12, 11, 10.5];
const LH_VALUES: number[] = [25, 21, 22, 18, 16, 14, 13];

export const lineHeightForSize = (size: number): number => {
  let best = 0;
  let bestDist = Math.abs(size - LH_BREAKPOINTS[0]);
  for (let i = 1; i < LH_BREAKPOINTS.length; i++) {
    const dist = Math.abs(size - LH_BREAKPOINTS[i]);
    // 等距取较小档(后遍历到的为较小字号档,仅在严格更小时替换)
    if (dist < bestDist || (Math.abs(dist - bestDist) < 0.005 && LH_BREAKPOINTS[i] < LH_BREAKPOINTS[best])) {
      best = i;
      bestDist = dist;
    }
  }
  return LH_VALUES[best];
};

// ── 间距/圆角 ──
export const PAD_CARD: number = 16;
export const PAD_ITEM: number = 13;
export const RADIUS: number = 14;            // 卡片/字段圆角
export const RADIUS_PILL: number = 999;      // 药丸/开关
export const GUTTER: number = 16;            // 页面内容边距(对齐 NavRow/SectionHeader)
export const CHAT_GUTTER: number = 20;       // 会话页(列表/聊天/输入)专用

// ── 动效(统一时长,精装打磨;曲线统一 EaseOut) ──
export const MOTION_PRESS: number = 140;     // 按压反馈
export const MOTION_ENTER: number = 300;     // 列表项/区块入场
export const MOTION_STAGGER: number = 30;    // 列表项交错延迟步长
export const MOTION_OVERLAY: number = 240;   // 对话框/弹层

// ── 阴影(浮层专用,随主题;flat & hairline 仍是基底) ──
export let SHADOW_KEY: string = '#261B1A17';     // FAB/发送键等浮键
export let SHADOW_OVERLAY: string = '#381B1A17'; // 对话框/底部弹层

// ── 首页纸面令牌(iOS AmberTheme paper + homePaper/homeDark 逐值移植) ──
// 作用域: ChatListPage 首页。值随 applyTheme 切换;accent 相关仍在消费点
// 经 accentAlpha(accentColorStore, a) 派生(iOS 同样读 runtime accent)。
export let HOME_BG: string = '#EFE7D6';        // 暖纸画布
export let HOME_CARD: string = '#FFFDF7';      // 一体卡面
export let HOME_CARD2: string = '#F0EBE2';     // 卡内嵌面/图标座
export let HOME_INK: string = '#1B1813';       // 主墨
export let HOME_INK2: string = '#5B5449';      // 次墨/节标题(section)
export let HOME_MUTED: string = '#746D62';     // 元数据墨
export let HOME_MUTED2: string = '#918A80';    // 图标座墨/占位
export let HOME_SEP: string = 'rgba(38, 30, 20, 0.05)';    // 卡内 hairline(continue↔快捷行)
export let HOME_ROWLINE: string = 'rgba(27, 24, 19, 0.08)'; // 会话行间 hairline
export let HOME_PRESS: string = 'rgba(89, 66, 35, 0.055)'; // 按压垫底
export let HOME_SHADOW: string = 'rgba(76, 59, 34, 0.10)'; // 卡投影(暖棕)
// 控制层玻璃(搜索胶囊/展开条/齿轮): 白渐变 + 白描边
export let HOME_GLASS_TOP: string = 'rgba(255, 255, 255, 0.78)';
export let HOME_GLASS_BOTTOM: string = 'rgba(255, 255, 255, 0.58)';
export let HOME_GLASS_EDGE: string = 'rgba(255, 255, 255, 0.50)';
export let HOME_GLASS_SHADOW: string = 'rgba(40, 36, 28, 0.10)';
// 画布点阵(点阵·陶土主题皮)
export let HOME_DOT: string = 'rgba(40, 31, 20, 0.08)';
