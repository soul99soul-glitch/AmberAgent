// design/tokens.ets — Amber 设计令牌(G7 对齐 Amber Redesign 设计系统)
//
// 来源: oc-amber.css "Terminal × Modern" 暖中性石墨语言
// 原则: 令牌唯一来源,组件中禁止硬编码 hex

// ── 基础色(Light base, Graphite) ──
export const BG: string = '#F4F2EC';         // 页面背景(暖奶油)
export const SURFACE: string = '#FAF9F5';    // 卡片/托盘(浅奶油)
export const SURFACE2: string = '#EFECE4';   // 内嵌面(药丸/chip/开关off)
export const RAISED: string = '#FFFFFF';     // 聚焦字段/分段激活
export const INK: string = '#1B1A17';        // 主文字(近黑)
export const INK2: string = '#57544C';       // 次文字/非活跃图标
export const INK3: string = '#8F8B80';       // 三级/元数据
export const INK4: string = '#B6B1A4';       // 淡(占位/禁用/标记)
export const LINE: string = '#E4E0D6';       // 细线边框/分隔
export const LINE2: string = '#D6D1C4';      // 强边框(chip/激活轮廓)
export const USER_BG: string = '#1B1A17';    // 用户气泡填充
export const USER_INK: string = '#F6F4EE';   // 用户气泡文字
export const CODE_BG: string = '#EDEAE1';    // 行内代码/工具调用背景

// ── 强调色 ──
export const ACCENT: string = '#B8623A';     // 陶土色 — 选择/当前使用
export const ACCENT_INK: string = '#FFFFFF'; // accent 填充上的文字
export const ACCENT_LIGHT: string = '#FFF5F0'; // accent 淡背景
export const SIGNAL: string = '#5E9C6E';     // 活跃/在线绿(区别于 accent)
export const ERROR: string = '#C2553F';      // 危险红

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
