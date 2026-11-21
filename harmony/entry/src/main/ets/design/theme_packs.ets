// design/theme_packs.ets — 主题包系统(C2;iOS AmberThemePack 对齐)
//
// 模型:ThemePack = 纸色组(light/dark 各 10 槽)+ accent(+ 可选 accentInk 上墨色)+ 首页点阵开关。
// 跨平台兼容:iOS/Android 的 amber.theme.pack v1 与 Android 旧 token 包由
//   design/theme_pack_import.ets 解析/映射为本模型(导入),并负责跨平台格式导出。
// 应用:applyThemePack = 先 applyTheme 拿状态色/玻璃/HOME alpha 默认值,再经
//   applyPaperPalette 覆写 pack 纸色;accent 走既有 setAccentColor。
// 刷新:令牌变更收口(applyTheme/applyPaperPalette/setAccentColor/applyAmoledBlack)
//   自动 bumpThemeEpoch → 全站 @Entry 页面 ForEach 纪元重挂载,整棵子树销毁重建。
//   调用方另写 AppStorage accentColor / darkMode / homeDotGrid / themePackId
//   供语义化订阅者(如点阵重绘开关)。
// 持久化:KV theme_pack_id + theme_custom_packs(JSON 数组,导入包);
//   启动时 EntryAbility hydrate 后用 applyCurrentTheme 替代裸 applyTheme。

import {
  applyTheme, setAccentColor, applyPaperPalette, applyAmoledBlack, BG,
} from './tokens.ets';
import type { KeyValueStore } from '@amber/chat-domain';

// D0-11:AMOLED 纯黑(深色模式画布纯黑;持久化 KV theme_amoled_black)
const THEME_AMOLED_KEY: string = 'theme_amoled_black';
let amoledBlack: boolean = false;
export const isAmoledBlack = (): boolean => amoledBlack;

// 应用 + 持久化;调用方负责 AppStorage 刷新(与模式切换同机制)
export const setAmoledBlack = async (kv: KeyValueStore, enabled: boolean, dark: boolean): Promise<void> => {
  amoledBlack = enabled;
  await kv.put(THEME_AMOLED_KEY, enabled ? 'true' : 'false');
  applyCurrentTheme(dark);
};

export interface ThemePalette {
  bg: string;
  surface: string;
  surface2: string;
  ink: string;
  ink2: string;
  muted: string;
  muted2: string;
  border: string;
  borderSoft: string;
}

// 跨平台 design 纹理层(amber.theme.pack v1;首页画布渲染,iOS AmberThemeDesignPatternRenderer 逐值)
export interface ThemePackPattern {
  kind: string;    // dots | grid | diagonal | crosses | waves | rings
  color: string;   // #RRGGBB
  opacity: number; // 0–0.3(逐层 globalAlpha)
  spacing: number; // 12–120(网格点间距)
  size: number;    // 0.5–8(标记尺寸;lineWidth = max(0.5, size*0.32))
}

export interface ThemePackGradient {
  colors: string[];     // 浅色 stops(2–4 个 #RRGGBB)
  darkColors: string[]; // 深色 stops(2–4)
  angle: number;        // iOS 语义:自水平轴顺时针角度(0=左→右,90=上→下)
}

export interface ThemePack {
  id: string;
  displayName: string;
  light: ThemePalette;
  dark: ThemePalette;
  accent: string;
  // accent 填充上的墨色(跨平台 amber.theme.pack 的 inkHex;缺省 = 按 accent 对比度自动推导)
  accentInk?: string;
  dotGrid: boolean; // 首页画布点阵(点阵·陶土/点阵·Pi);false = 平涂(Notion)
  // design 纹理(patterns 存在时优先于 dotGrid;iOS design 层为权威背景)
  patterns?: ThemePackPattern[];
  gradient?: ThemePackGradient;
  // 纹理作用域(amber.theme.pack v1 可选槽):homeOnly(默认)| shell(home+设置树)| appWide(全部集成面)
  canvasScope?: string;
  // 源文件带 design.dark(Android 规则:自定义深色色板时关闭 AMOLED 纯黑覆写)
  designDark?: boolean;
  fixedColorMode?: string; // 小说沉浸纸色固定明暗，保留外观偏好。
  builtin: boolean;
}

const THEME_PACK_ID_KEY: string = 'theme_pack_id';
const THEME_CUSTOM_PACKS_KEY: string = 'theme_custom_packs';

// ── 内置包(iOS AmberThemePack.builtins 逐值;accentInk 取 iOS AmberAccentOption.inkHex) ──
export const BUILTIN_PACKS: ThemePack[] = [
  {
    id: 'sit-terracotta',
    displayName: '点阵 · 陶土',
    accent: '#B8623A',
    accentInk: '#FFFFFF',
    dotGrid: true,
    canvasScope: 'shell',
    builtin: true,
    light: {
      bg: '#EFE7D6', surface: '#FFFDF7', surface2: '#F0EBE2',
      ink: '#1B1813', ink2: '#5B5449', muted: '#746D62', muted2: '#918A80',
      border: '#DBCEBC', borderSoft: '#ECE3D6',
    },
    dark: {
      bg: '#14110E', surface: '#221E19', surface2: '#2E2822',
      ink: '#F5F0E8', ink2: '#C8BDB0', muted: '#A89888', muted2: '#6E6258',
      border: '#3D342C', borderSoft: '#2A241E',
    },
  },
  {
    id: 'pi-steel',
    displayName: '点阵 · Pi',
    accent: '#6B8CAD',
    accentInk: '#FAF9F7',
    dotGrid: true,
    canvasScope: 'shell',
    builtin: true,
    light: {
      bg: '#F3F0EB', surface: '#FAF9F7', surface2: '#EBE7E0',
      ink: '#1C1B19', ink2: '#4A4640', muted: '#6A6560', muted2: '#9A948C',
      border: '#D4CFC7', borderSoft: '#E8E4DC',
    },
    dark: {
      bg: '#12110F', surface: '#1E1C18', surface2: '#2A2722',
      ink: '#F3F0EB', ink2: '#C4BEB4', muted: '#9A948C', muted2: '#6A6560',
      border: '#3A3630', borderSoft: '#28251F',
    },
  },
  {
    id: 'notion-blue',
    displayName: 'Notion · 暖白',
    accent: '#0075DE',
    accentInk: '#FFFFFF',
    dotGrid: false,
    canvasScope: 'homeOnly',
    builtin: true,
    light: {
      bg: '#F6F5F4', surface: '#FFFFFF', surface2: '#EFEEEC',
      ink: '#1A1918', ink2: '#31302E', muted: '#615D59', muted2: '#A39E98',
      border: '#E6E5E3', borderSoft: '#F0EFED',
    },
    dark: {
      bg: '#191919', surface: '#252525', surface2: '#2F2F2F',
      ink: '#EBEBEB', ink2: '#B4B4B4', muted: '#9B9B9B', muted2: '#6F6F6F',
      border: '#373737', borderSoft: '#2C2C2C',
    },
  },
  // ── iOS AmberArquielThemePresets 策展主题包（与 iOS AmberThemeDesign.swift 逐值对齐） ──
  {
    id: 'arquiel-bookcraft-cinnabar',
    displayName: '吕敬人 · 故宫朱砂',
    accent: '#AE3528',
    accentInk: '#FFFFFF',
    dotGrid: false,
    canvasScope: 'homeOnly',
    designDark: true,
    builtin: true,
    light: {
      bg: '#F6F1E7', surface: '#EDE6D8', surface2: '#E4DBC9',
      ink: '#1E1B18', ink2: '#413B34', muted: '#635A50', muted2: '#8E8376',
      border: '#DDD3C2', borderSoft: '#E7DFD1',
    },
    dark: {
      bg: '#151210', surface: '#221D19', surface2: '#2D2621',
      ink: '#F4EFE6', ink2: '#CDC5B9', muted: '#A69B8E', muted2: '#766C61',
      border: '#3A312A', borderSoft: '#2B241F',
    },
  },
  {
    id: 'arquiel-warm-editorial',
    displayName: '初版 · 暖纸赤陶',
    accent: '#C96442',
    accentInk: '#FFFFFF',
    dotGrid: false,
    canvasScope: 'homeOnly',
    designDark: true,
    builtin: true,
    light: {
      bg: '#FBF9F5', surface: '#F3EFEA', surface2: '#EAE4DC',
      ink: '#1E1B18', ink2: '#4B453F', muted: '#787067', muted2: '#9E958B',
      border: '#E6E0D6', borderSoft: '#EFEAE1',
    },
    dark: {
      bg: '#141210', surface: '#211D19', surface2: '#2C2722',
      ink: '#F5F0E8', ink2: '#CEC7BD', muted: '#A89E92', muted2: '#766D63',
      border: '#38312A', borderSoft: '#2A251F',
    },
  },
  {
    id: 'arquiel-things-polish',
    displayName: 'Things 3 · 原生精修',
    accent: '#2B6CE6',
    accentInk: '#FFFFFF',
    dotGrid: false,
    canvasScope: 'homeOnly',
    designDark: true,
    builtin: true,
    light: {
      bg: '#FFFFFF', surface: '#F4F6F9', surface2: '#EBF0F5',
      ink: '#1D1D1F', ink2: '#3E4148', muted: '#5E6572', muted2: '#8B929F',
      border: '#E3E7ED', borderSoft: '#EDF0F5',
    },
    dark: {
      bg: '#101216', surface: '#1B1E24', surface2: '#252A33',
      ink: '#F3F5F8', ink2: '#C4C8D0', muted: '#949BA8', muted2: '#666D7A',
      border: '#2C313B', borderSoft: '#20242D',
    },
  },
  {
    id: 'arquiel-comet-indigo',
    displayName: '天文图录 · 琉璃绀',
    accent: '#234B8C',
    accentInk: '#FFFFFF',
    dotGrid: false,
    canvasScope: 'homeOnly',
    designDark: true,
    builtin: true,
    light: {
      bg: '#F7F4EC', surface: '#EEE9DD', surface2: '#E4DDD0',
      ink: '#141B29', ink2: '#373F4E', muted: '#5A6373', muted2: '#89909E',
      border: '#DDD6C7', borderSoft: '#E8E2D6',
    },
    dark: {
      bg: '#0E131D', surface: '#182030', surface2: '#222C40',
      ink: '#F0EDE4', ink2: '#C2C6CB', muted: '#949FB2', muted2: '#657084',
      border: '#2A354B', borderSoft: '#1E2738',
    },
  },
  {
    id: 'arquiel-takram-moss',
    displayName: 'Takram · 松林苔绿',
    accent: '#1E6B4E',
    accentInk: '#FFFFFF',
    dotGrid: false,
    canvasScope: 'homeOnly',
    designDark: true,
    builtin: true,
    light: {
      bg: '#F6F6F1', surface: '#ECEEE6', surface2: '#E2E5DB',
      ink: '#17211B', ink2: '#36423B', muted: '#55635B', muted2: '#849089',
      border: '#D8DDD3', borderSoft: '#E5E9E1',
    },
    dark: {
      bg: '#0F1512', surface: '#19221D', surface2: '#232F28',
      ink: '#EEF2EE', ink2: '#C0CAC3', muted: '#92A198', muted2: '#64736A',
      border: '#2A362F', borderSoft: '#1F2923',
    },
  },
  {
    id: 'arquiel-linear-terminal',
    displayName: 'Linear · 暗色发丝',
    accent: '#5E6AD2',
    accentInk: '#FFFFFF',
    dotGrid: false,
    canvasScope: 'homeOnly',
    designDark: true,
    fixedColorMode: 'dark',
    builtin: true,
    light: {
      bg: '#0C0D10', surface: '#15171C', surface2: '#1E2129',
      ink: '#F2F3F5', ink2: '#BEC2CB', muted: '#8B91A1', muted2: '#5F6575',
      border: '#262932', borderSoft: '#1B1E26',
    },
    dark: {
      bg: '#0C0D10', surface: '#15171C', surface2: '#1E2129',
      ink: '#F2F3F5', ink2: '#BEC2CB', muted: '#8B91A1', muted2: '#5F6575',
      border: '#262932', borderSoft: '#1B1E26',
    },
  },
];

// ── 应用 ──

// 先跑 applyTheme 拿状态色/玻璃/HOME alpha 默认,再覆写 pack 纸色(pack 只管纸色组)
export const applyThemePack = (pack: ThemePack, dark: boolean): void => {
  dark = resolveThemeDark(pack, dark);
  applyTheme(dark);
  const p: ThemePalette = dark ? pack.dark : pack.light;
  applyPaperPalette({
    bg: p.bg,
    surface: p.surface,
    surface2: p.surface2,
    raised: dark ? p.surface2 : p.surface,
    ink: p.ink,
    ink2: p.ink2,
    muted: p.muted,
    muted2: p.muted2,
    borderSoft: p.borderSoft,
    border: p.border,
    userBg: p.ink,
    userInk: pack.fixedColorMode !== undefined ? p.bg : (dark ? pack.light.ink : pack.dark.ink),
    codeBg: p.surface2,
    bgFadeStart: `#00${p.bg.substring(1)}`,
    bgFadeEnd: p.bg,
    bgGlass: `#CC${p.bg.substring(1)}`,
  });
  setAccentColor(pack.accent, pack.accentInk);
  // D0-11:AMOLED 纯黑覆写(仅深色;画布/渐隐/玻璃改纯黑,卡片面保持原色分层)。
  //   源文件带自定义 design.dark 的包跳过(Android 同规则:让作者的深色色板/渐变生效)
  if (dark && amoledBlack && pack.designDark !== true && pack.fixedColorMode === undefined) {
    applyAmoledBlack();
  }
};

// ── JSON 编解码(导入/导出;schema 校验,不合法返回 null 由调用方诚实报错) ──
// PaletteDoc/readPalette/paletteToDoc 导出给 theme_pack_import.ets(跨平台文件里
// harmony 扩展段的 9 槽无损回读用)

export interface PaletteDoc {
  bg?: string;
  surface?: string;
  surface2?: string;
  ink?: string;
  ink2?: string;
  muted?: string;
  muted2?: string;
  border?: string;
  borderSoft?: string;
}

interface ThemePackDoc {
  format?: string;
  version?: number;
  id?: string;
  name?: string;
  accent?: string;
  accent_ink?: string;
  dot_grid?: boolean;
  light?: PaletteDoc;
  dark?: PaletteDoc;
  patterns?: ThemePackPattern[];
  gradient?: ThemePackGradient;
  design_dark?: boolean;
  canvas_scope?: string;
}

const isHexColor = (v: string): boolean => /^#[0-9a-fA-F]{6}$/.test(v);

const PATTERN_KINDS: string[] = ['dots', 'grid', 'diagonal', 'crosses', 'waves', 'rings'];

// 原生文档纹理校验(KV 回读/AI 生成:宽松,单层非法即丢整层,不拒包)
const readPatterns = (doc: ThemePackPattern[] | undefined): ThemePackPattern[] | null => {
  if (doc === undefined || !Array.isArray(doc)) return null;
  const out: ThemePackPattern[] = [];
  for (const p of doc) {
    if (p === null || typeof p !== 'object') continue;
    if (PATTERN_KINDS.indexOf(p.kind) < 0) continue;
    if (typeof p.color !== 'string' || !isHexColor(p.color)) continue;
    if (!Number.isFinite(p.opacity) || p.opacity < 0 || p.opacity > 0.3) continue;
    if (!Number.isFinite(p.spacing) || p.spacing < 12 || p.spacing > 120) continue;
    if (!Number.isFinite(p.size) || p.size < 0.5 || p.size > 8) continue;
    out.push({ kind: p.kind, color: p.color, opacity: p.opacity, spacing: p.spacing, size: p.size });
    if (out.length >= 3) break;
  }
  return out.length > 0 ? out : null;
};

const readGradient = (doc: ThemePackGradient | undefined): ThemePackGradient | null => {
  if (doc === undefined || doc === null || typeof doc !== 'object') return null;
  if (!Array.isArray(doc.colors) || !Array.isArray(doc.darkColors)) return null;
  if (doc.colors.length < 2 || doc.colors.length > 4) return null;
  if (doc.darkColors.length < 2 || doc.darkColors.length > 4) return null;
  for (const c of doc.colors) { if (typeof c !== 'string' || !isHexColor(c)) return null; }
  for (const c of doc.darkColors) { if (typeof c !== 'string' || !isHexColor(c)) return null; }
  if (!Number.isFinite(doc.angle)) return null;
  return { colors: doc.colors, darkColors: doc.darkColors, angle: doc.angle };
};

export const readPalette = (doc: PaletteDoc | undefined): ThemePalette | null => {
  if (doc === undefined) return null;
  const values: string[] = [
    doc.bg ?? '', doc.surface ?? '', doc.surface2 ?? '',
    doc.ink ?? '', doc.ink2 ?? '', doc.muted ?? '', doc.muted2 ?? '',
    doc.border ?? '', doc.borderSoft ?? '',
  ];
  for (const v of values) {
    if (!isHexColor(v)) return null;
  }
  return {
    bg: values[0], surface: values[1], surface2: values[2],
    ink: values[3], ink2: values[4], muted: values[5], muted2: values[6],
    border: values[7], borderSoft: values[8],
  };
};

export const paletteToDoc = (p: ThemePalette): PaletteDoc => ({
  bg: p.bg, surface: p.surface, surface2: p.surface2,
  ink: p.ink, ink2: p.ink2, muted: p.muted, muted2: p.muted2,
  border: p.border, borderSoft: p.borderSoft,
});

export const parseThemePackJson = (text: string): ThemePack | null => {
  try {
    const doc = JSON.parse(text) as ThemePackDoc;
    if (doc === null || doc.name === undefined || doc.name.trim().length === 0) return null;
    if (doc.accent === undefined || !isHexColor(doc.accent)) return null;
    const light = readPalette(doc.light);
    const dark = readPalette(doc.dark);
    if (light === null || dark === null) return null;
    const id: string = doc.id !== undefined && doc.id.trim().length > 0
      ? doc.id.trim()
      : `custom-${Date.now()}`;
    const pack: ThemePack = {
      id,
      displayName: doc.name.trim(),
      accent: doc.accent,
      dotGrid: doc.dot_grid === true,
      builtin: false,
      light,
      dark,
    };
    if (doc.accent_ink !== undefined && isHexColor(doc.accent_ink)) {
      pack.accentInk = doc.accent_ink;
    }
    const patterns: ThemePackPattern[] | null = readPatterns(doc.patterns);
    if (patterns !== null) pack.patterns = patterns;
    const gradient: ThemePackGradient | null = readGradient(doc.gradient);
    if (gradient !== null) pack.gradient = gradient;
    if (doc.design_dark === true) pack.designDark = true;
    if (doc.canvas_scope === 'homeOnly' || doc.canvas_scope === 'shell' || doc.canvas_scope === 'appWide') {
      pack.canvasScope = doc.canvas_scope;
    }
    return pack;
  } catch (_e) {
    return null;
  }
};

export const themePackToJson = (pack: ThemePack): string => {
  const doc: ThemePackDoc = {
    format: 'amber-theme-pack',
    version: 1,
    id: pack.id,
    name: pack.displayName,
    accent: pack.accent,
    dot_grid: pack.dotGrid,
    light: paletteToDoc(pack.light),
    dark: paletteToDoc(pack.dark),
  };
  if (pack.accentInk !== undefined) {
    doc.accent_ink = pack.accentInk;
  }
  if (pack.patterns !== undefined) {
    doc.patterns = pack.patterns;
  }
  if (pack.gradient !== undefined) {
    doc.gradient = pack.gradient;
  }
  if (pack.designDark === true) {
    doc.design_dark = true;
  }
  if (pack.canvasScope !== undefined) {
    doc.canvas_scope = pack.canvasScope;
  }
  return JSON.stringify(doc);
};

// ── 注册表(启动 hydrate 一次;服务非状态,UI 刷新走 AppStorage 既定机制) ──

class ThemePackStore {
  private currentPack: ThemePack = BUILTIN_PACKS[0];
  private imported: ThemePack[] = [];
  private hydrated: boolean = false;

  async hydrate(kv: KeyValueStore): Promise<void> {
    if (this.hydrated) return;
    this.hydrated = true;
    try {
      const rawCustom: string | null = await kv.get(THEME_CUSTOM_PACKS_KEY);
      if (rawCustom !== null) {
        const arr = JSON.parse(rawCustom) as ThemePackDoc[];
        for (const doc of arr) {
          const pack = parseThemePackJson(JSON.stringify(doc));
          if (pack !== null) this.imported.push(pack);
        }
      }
    } catch (_e) {
      // 损坏 → 空导入库(不阻断启动)
    }
    try {
      const rawId: string | null = await kv.get(THEME_PACK_ID_KEY);
      if (rawId !== null) {
        const hit: ThemePack | undefined = this.all().find(
          (p: ThemePack): boolean => p.id === rawId);
        if (hit !== undefined) this.currentPack = hit;
      }
    } catch (_e) {
      // 缺失 → 默认包
    }
    try {
      const rawAmoled: string | null = await kv.get(THEME_AMOLED_KEY);
      amoledBlack = rawAmoled === 'true';
    } catch (_e) {
      // 缺失 → 非纯黑
    }
  }

  current(): ThemePack {
    return this.currentPack;
  }

  all(): ThemePack[] {
    return [...BUILTIN_PACKS, ...this.imported];
  }

  importedPacks(): ThemePack[] {
    return this.imported;
  }

  async select(kv: KeyValueStore, pack: ThemePack): Promise<void> {
    this.currentPack = pack;
    await kv.put(THEME_PACK_ID_KEY, pack.id);
  }

  async addImported(kv: KeyValueStore, pack: ThemePack): Promise<void> {
    this.imported = [...this.imported.filter((p: ThemePack): boolean => p.id !== pack.id), pack];
    await this.saveImported(kv);
  }

  async removeImported(kv: KeyValueStore, id: string): Promise<void> {
    this.imported = this.imported.filter((p: ThemePack): boolean => p.id !== id);
    if (this.currentPack.id === id) {
      this.currentPack = BUILTIN_PACKS[0];
      await kv.put(THEME_PACK_ID_KEY, this.currentPack.id);
    }
    await this.saveImported(kv);
  }

  private async saveImported(kv: KeyValueStore): Promise<void> {
    const docs: ThemePackDoc[] = this.imported.map(
      (p: ThemePack): ThemePackDoc => JSON.parse(themePackToJson(p)) as ThemePackDoc);
    await kv.put(THEME_CUSTOM_PACKS_KEY, JSON.stringify(docs));
  }
}

export const themePackStore = new ThemePackStore();

export const resolveThemeDark = (pack: ThemePack, preferredDark: boolean): boolean => {
  return pack.fixedColorMode === 'dark' || (pack.fixedColorMode !== 'light' && preferredDark);
};

// 全局主题应用入口(模式切换/系统深浅变化时替代裸 applyTheme;pack 由 store 提供)
export const applyCurrentTheme = (dark: boolean): void => {
  applyThemePack(themePackStore.current(), dark);
};

// ── 首页画布纹理访问(patterns 优先于 dotGrid;iOS design 层为权威背景) ──

export const currentCanvasPatterns = (): ThemePackPattern[] => {
  return themePackStore.current().patterns ?? [];
};

// 当前模式的渐变 stops;无渐变 → null(调用方退化为纯色)
export const currentCanvasGradient = (dark: boolean): string[] | null => {
  const g: ThemePackGradient | undefined = themePackStore.current().gradient;
  if (g === undefined) return null;
  return dark ? g.darkColors : g.colors;
};

// iOS 角度 → ArkUI linearGradient angle(ArkUI 0°=从下往上、顺时针为正;iOS 0°=从左往右)
export const currentCanvasGradientAngle = (): number => {
  const g: ThemePackGradient | undefined = themePackStore.current().gradient;
  if (g === undefined) return 180;
  return (g.angle + 90) % 360;
};

// 纹理作用域(缺省 homeOnly;DesignCanvasLayer 按 surface 判定可见性)
export const currentCanvasScope = (): string => {
  return themePackStore.current().canvasScope ?? 'homeOnly';
};

// 页面根背景渐变 stops(均匀分布,对齐 SwiftUI LinearGradient);无渐变 → BG 双色点平涂。
// dark 直读 AppStorage(EntryAbility 维护),调用页无需自持 darkMode prop;
// 在 epoch 重挂载的 build 中调用即随模式/包切换重读。
export const canvasGradientColors = (): Array<[string, number]> => {
  const out: Array<[string, number]> = [];
  const dark: boolean = AppStorage.get<boolean>('darkMode') ?? false;
  const stops: string[] | null = currentCanvasGradient(dark);
  if (stops === null || stops.length < 2) {
    out.push([BG, 0]);
    out.push([BG, 1]);
    return out;
  }
  for (let i = 0; i < stops.length; i++) {
    out.push([stops[i], i / (stops.length - 1)]);
  }
  return out;
};
