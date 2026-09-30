// design/theme_pack_import.ets — 跨平台主题包导入/导出(iOS / Android 主题包兼容层)
//
// 支持三种文件格式(按顶层字段自动分流):
//   1. amber.theme.pack v1(跨平台协议,iOS/Android 互通基准,
//      协议文档: amberagent-Android/docs/theme-pack-cross-platform.md)
//   2. Android 旧 token 包(schemaVersion=1,colors/fonts/layout 三组 token)
//   3. 鸿蒙原生 amber-theme-pack v1(theme_packs.ets;含 AI 生成的无 format 文档)
//
// 校验口径(与两端对齐的部分严格,鸿蒙特有容差处收敛到可读默认值):
//   严格拒绝:format/version 不符、id/displayName 空、accentHex 非法、
//     design palette 缺字段/颜色非法/对比度不足(foreground≥4.5:1,mutedForeground≥3:1)、
//     patterns 层数/kind/颜色/数值范围非法、gradient 缺 palette/stop 数/对比度/角度非法。
//     —— 这些在 iOS/Android 同样被拒,拒绝即兼容,放进来才会出怪异界面。
//   自动修正(告警不拒绝):inkHex 缺失/非法/与 accent 对比度 <3:1 → 按 WCAG 自动取黑/白;
//     未知/沉浸 paper → 回退暖纸;未知 canvasStyle → 平涂;内置 id 冲突 → 改名。
//   忽略不校验:components 与其余表面槽(鸿蒙不渲染,接收超集;
//     Android 侧同样有一批"可解析不消费"的槽位)。
// 渲染范围:patterns/gradient 经严格校验后按 canvasScope 渲染(homeOnly=首页,
//   shell=首页+设置树,appWide=全部已集成面;DesignCanvasLayer 逐面判定);
//   components(圆角/描边/阴影/品牌字)本轮不消费。
// 5 槽 → 9 槽派生(iOS AmberThemeDesign.Palette.resolving 逐值):
//   surface2 = midpoint(background, surface);ink2 = ink;muted2 = muted;borderSoft = border。
// design 缺一侧时该侧继承 paper 画布色板(iOS 同款语义);paper 五画布色板逐值取自
//   iOS AmberTheme(paper/neutral/white/pi/notion,沉浸色 garnet 等 iOS 导入侧也拒绝)。
// 导出:统一发 amber.theme.pack v1(iOS Codable / Android ignoreUnknownKeys 均兼容),
//   附带 harmony 扩展段(9 槽全量 + dotGrid)供鸿蒙无损回读。

import {
  normalizeHexRgb, parseHexRgb, contrastRatio, midpointHex, accentInkFor,
} from './tokens.ets';
import {
  ThemePack, ThemePalette, BUILTIN_PACKS, PaletteDoc, readPalette, paletteToDoc,
  parseThemePackJson, ThemePackPattern, ThemePackGradient,
} from './theme_packs.ets';

const PORTABLE_FORMAT: string = 'amber.theme.pack';
const NATIVE_FORMAT: string = 'amber-theme-pack';
const LEGACY_BUILTIN_PREFIX: string = 'builtin:';
const DEFAULT_ACCENT: string = '#B8623A';

// ── 结果模型 ──

// Android 旧 token 包随包的显示偏好(fonts/layout;颜色已折进色板)
export interface ThemePackDisplayPatch {
  chatFontFamily?: string;
  fontSizeRatio?: number;
  showUserAvatar?: boolean;
  showAssistantBubble?: boolean;
  amberBaseFamily?: string;
}

export class ThemePackImportResult {
  pack: ThemePack | null = null;
  display: ThemePackDisplayPatch | null = null;
  warnings: string[] = [];
  issues: string[] = [];
}

interface PalettePair {
  light: ThemePalette;
  dark: ThemePalette;
}

// ── 跨平台文档结构(只建模鸿蒙消费的字段;其余槽位 JSON 里存在但不读) ──

interface ProbeDoc {
  format?: string;
  schemaVersion?: number;
}

interface PortablePaletteDoc {
  background?: string;
  surface?: string;
  foreground?: string;
  mutedForeground?: string;
  border?: string;
}

interface PortablePatternDoc {
  kind?: string;
  color?: string;
  opacity?: number;
  spacing?: number;
  size?: number;
}

interface PortableGradientDoc {
  colors?: string[];
  darkColors?: string[];
  angle?: number;
}

interface PortableDesignDoc {
  light?: PortablePaletteDoc;
  dark?: PortablePaletteDoc;
  // iOS Codable 要求 design 内含 patterns 数组(无纹理时导出为 [])
  patterns?: PortablePatternDoc[];
  gradient?: PortableGradientDoc;
}

// 鸿蒙扩展段:自家导出文件的 9 槽无损回读(iOS/Android 解码均忽略未知键)
interface HarmonyExtDoc {
  dotGrid?: boolean;
  light?: PaletteDoc;
  dark?: PaletteDoc;
}

interface PortableDoc {
  format?: string;
  version?: number;
  id?: string;
  displayName?: string;
  paper?: string;
  accentHex?: string;
  inkHex?: string;
  canvasStyle?: string;
  brandMark?: string;
  shortcutIconStyle?: string;
  chromeTypeface?: string;
  canvasScope?: string;
  assetMode?: string;
  immersivePolicy?: string;
  design?: PortableDesignDoc;
  harmony?: HarmonyExtDoc;
}

interface LegacyColorsDoc {
  baseFamily?: string;
  accent?: string;
}

interface LegacyFontsDoc {
  chatFontFamily?: string;
  fontSizeRatio?: string;
}

interface LegacyLayoutDoc {
  showUserAvatar?: string;
  showAssistantBubble?: string;
}

interface LegacyPackageDoc {
  schemaVersion?: number;
  id?: string;
  name?: string;
  colors?: LegacyColorsDoc;
  fonts?: LegacyFontsDoc;
  layout?: LegacyLayoutDoc;
  document?: PortableDoc;
}

// ── paper 画布色板(iOS AmberTheme 逐值;paper/pi/notion 直接复用内置包,值一致) ──

// iOS neutralLight / darkPalette(E 版暖灰)
const NEUTRAL_LIGHT: ThemePalette = {
  bg: '#ECE8E4', surface: '#F6F5F3', surface2: '#EDEBE7',
  ink: '#161514', ink2: '#55524D', muted: '#716D67', muted2: '#8F8B85',
  border: '#D9D5CF', borderSoft: '#E4E1DC',
};
const NEUTRAL_DARK: ThemePalette = {
  bg: '#0E0D10', surface: '#1F1D23', surface2: '#2B2930',
  ink: '#F4F1ED', ink2: '#C3BEC5', muted: '#AAA5AD', muted2: '#6E6760',
  border: '#3A3741', borderSoft: '#2A2830',
};
// iOS whiteLight / whiteDark(中性白)
const WHITE_LIGHT: ThemePalette = {
  bg: '#F5F5F4', surface: '#FFFFFF', surface2: '#EEEEED',
  ink: '#1A1A1A', ink2: '#5C5C5C', muted: '#737373', muted2: '#8E8E8E',
  border: '#D4D4D4', borderSoft: '#E5E5E5',
};
const WHITE_DARK: ThemePalette = {
  bg: '#111111', surface: '#1C1C1C', surface2: '#282828',
  ink: '#F5F5F5', ink2: '#BDBDBD', muted: '#8E8E8E', muted2: '#6B6B6B',
  border: '#383838', borderSoft: '#2A2A2A',
};
// Android 旧 token 包 baseFamily=SAGE(AmberTokens.kt AmberSage / AmberSageDark 逐值)
const SAGE_LIGHT: ThemePalette = {
  bg: '#F0F2EA', surface: '#F6F8F0', surface2: '#E7EADF',
  ink: '#1B201A', ink2: '#535A4D', muted: '#888F7E', muted2: '#B0B5A4',
  border: '#D0D4C4', borderSoft: '#E0E3D6',
};
const SAGE_DARK: ThemePalette = {
  bg: '#131711', surface: '#191D15', surface2: '#1E2219',
  ink: '#E6EBDF', ink2: '#A0A896', muted: '#6E7563', muted2: '#515845',
  border: '#353A2C', borderSoft: '#2A2E22',
};

const paperPalettes = (paper: string): PalettePair | null => {
  switch (paper) {
    case 'paper':
      return { light: BUILTIN_PACKS[0].light, dark: BUILTIN_PACKS[0].dark };
    case 'pi':
      return { light: BUILTIN_PACKS[1].light, dark: BUILTIN_PACKS[1].dark };
    case 'notion':
      return { light: BUILTIN_PACKS[2].light, dark: BUILTIN_PACKS[2].dark };
    case 'neutral':
      return { light: NEUTRAL_LIGHT, dark: NEUTRAL_DARK };
    case 'white':
      return { light: WHITE_LIGHT, dark: WHITE_DARK };
    default:
      return null;
  }
};

// iOS 运行时存在但导入校验拒绝的沉浸色画布(单独给出准确告警)
const IMMERSIVE_PAPERS: string[] = ['garnet', 'ochre', 'turmeric', 'magenta', 'lotus'];

const CANVAS_STYLES: string[] = ['flat', 'dotGrid', 'lineGrid', 'paperGrain'];

const PATTERN_KINDS: string[] = ['dots', 'grid', 'diagonal', 'crosses', 'waves', 'rings'];

// design.patterns 逐层校验(与两端协议一致:≤3 层,kind/颜色/数值范围非法即拒)
const readPortablePatterns = (doc: PortablePatternDoc[] | undefined,
  result: ThemePackImportResult): ThemePackPattern[] | null => {
  if (doc === undefined) return null;
  if (!Array.isArray(doc) || doc.length > 3) {
    result.issues.push('patterns 最多支持 3 层');
    return null;
  }
  const out: ThemePackPattern[] = [];
  for (let i = 0; i < doc.length; i++) {
    const p: PortablePatternDoc = doc[i];
    const prefix: string = `patterns[${i}]`;
    if (p === null || typeof p !== 'object') {
      result.issues.push(`${prefix} 必须是对象`);
      return null;
    }
    if (typeof p.kind !== 'string' || PATTERN_KINDS.indexOf(p.kind) < 0) {
      result.issues.push(`${prefix}.kind 不受支持:${p.kind}`);
      return null;
    }
    const color: string | null = normalizeHexRgb(typeof p.color === 'string' ? p.color : '');
    if (color === null) {
      result.issues.push(`${prefix}.color 必须是 RGB 十六进制颜色`);
      return null;
    }
    if (typeof p.opacity !== 'number' || !Number.isFinite(p.opacity) || p.opacity < 0 || p.opacity > 0.3) {
      result.issues.push(`${prefix}.opacity 必须在 0 到 0.3 之间`);
      return null;
    }
    if (typeof p.spacing !== 'number' || !Number.isFinite(p.spacing) || p.spacing < 12 || p.spacing > 120) {
      result.issues.push(`${prefix}.spacing 必须在 12 到 120 之间`);
      return null;
    }
    if (typeof p.size !== 'number' || !Number.isFinite(p.size) || p.size < 0.5 || p.size > 8) {
      result.issues.push(`${prefix}.size 必须在 0.5 到 8 之间`);
      return null;
    }
    out.push({ kind: p.kind, color, opacity: p.opacity, spacing: p.spacing, size: p.size });
  }
  return out;
};

// design.gradient:需双 palette;每侧 2–4 stops,stop 与对应 foreground 对比度 ≥4.5(两端同)
const readPortableGradient = (doc: PortableGradientDoc, hasPalettes: boolean,
  lightFg: number, darkFg: number, result: ThemePackImportResult): ThemePackGradient | null => {
  if (!hasPalettes) {
    result.issues.push('gradient 需要同时提供 light 和 dark palette');
    return null;
  }
  const readStops = (raw: string[] | undefined, field: string, fg: number): string[] | null => {
    if (!Array.isArray(raw) || raw.length < 2 || raw.length > 4) {
      result.issues.push(`${field} 必须包含 2 到 4 个颜色`);
      return null;
    }
    const stops: string[] = [];
    for (let i = 0; i < raw.length; i++) {
      const color: string | null = normalizeHexRgb(typeof raw[i] === 'string' ? raw[i] : '');
      if (color === null) {
        result.issues.push(`${field}[${i}] 必须是 RGB 十六进制颜色`);
        return null;
      }
      const rgb: number = parseHexRgb(color) ?? 0;
      if (contrastRatio(rgb, fg) < 4.5) {
        result.issues.push(`${field}[${i}] 与 foreground 的对比度必须至少为 4.5:1`);
        return null;
      }
      stops.push(color);
    }
    return stops;
  };
  const colors: string[] | null = readStops(doc.colors, 'gradient.colors', lightFg);
  if (colors === null) return null;
  const darkColors: string[] | null = readStops(doc.darkColors, 'gradient.darkColors', darkFg);
  if (darkColors === null) return null;
  if (typeof doc.angle !== 'number' || !Number.isFinite(doc.angle)) {
    result.issues.push('gradient.angle 必须是有限角度');
    return null;
  }
  return { colors, darkColors, angle: doc.angle };
};

// ── 共用小件 ──

// 内置 id 冲突改名(选中态/启动恢复都按 id 索引,撞名会出现"选了没生效"的错乱)
const dedupeBuiltinId = (pack: ThemePack, warnings: string[]): void => {
  const hit: ThemePack | undefined = BUILTIN_PACKS.find(
    (b: ThemePack): boolean => b.id === pack.id);
  if (hit === undefined) return;
  const nextId: string = `${pack.id}-custom-${Date.now().toString(36)}`;
  warnings.push(`id「${pack.id}」与内置主题冲突,已改为「${nextId}」`);
  pack.id = nextId;
};

// Android 旧包 accent 允许 #AARRGGBB;鸿蒙色板令牌全部不透明,导入时剥掉 alpha
const normalizeLegacyAccent = (raw: string, warnings: string[]): string | null => {
  if (typeof raw !== 'string' || !raw.startsWith('#')) return null;
  const digits: string = raw.substring(1);
  if (digits.length === 6) return normalizeHexRgb(raw);
  if (digits.length === 8 && /^[0-9a-fA-F]{8}$/.test(digits)) {
    const alpha: string = digits.substring(0, 2);
    if (alpha.toUpperCase() !== 'FF') {
      warnings.push('accent 含透明度,已按不透明色导入');
    }
    return `#${digits.substring(2).toUpperCase()}`;
  }
  return null;
};

// design palette(5 槽)→ 鸿蒙 9 槽;校验规则与两端一致(缺字段/坏 hex/对比度 → 拒绝)
const readPortablePalette = (name: string, doc: PortablePaletteDoc,
  result: ThemePackImportResult): ThemePalette | null => {
  const bg: string | null = normalizeHexRgb(doc.background ?? '');
  const surface: string | null = normalizeHexRgb(doc.surface ?? '');
  const ink: string | null = normalizeHexRgb(doc.foreground ?? '');
  const muted: string | null = normalizeHexRgb(doc.mutedForeground ?? '');
  const border: string | null = normalizeHexRgb(doc.border ?? '');
  if (bg === null) { result.issues.push(`design.${name}.background 必须是 RGB 十六进制颜色`); return null; }
  if (surface === null) { result.issues.push(`design.${name}.surface 必须是 RGB 十六进制颜色`); return null; }
  if (ink === null) { result.issues.push(`design.${name}.foreground 必须是 RGB 十六进制颜色`); return null; }
  if (muted === null) { result.issues.push(`design.${name}.mutedForeground 必须是 RGB 十六进制颜色`); return null; }
  if (border === null) { result.issues.push(`design.${name}.border 必须是 RGB 十六进制颜色`); return null; }
  const bgRgb: number = parseHexRgb(bg) ?? 0;
  const surfaceRgb: number = parseHexRgb(surface) ?? 0;
  const inkRgb: number = parseHexRgb(ink) ?? 0;
  const mutedRgb: number = parseHexRgb(muted) ?? 0;
  if (contrastRatio(inkRgb, bgRgb) < 4.5 || contrastRatio(inkRgb, surfaceRgb) < 4.5) {
    result.issues.push(`design.${name}.foreground 对 background 和 surface 的对比度都必须至少为 4.5:1`);
    return null;
  }
  if (contrastRatio(mutedRgb, bgRgb) < 3.0 || contrastRatio(mutedRgb, surfaceRgb) < 3.0) {
    result.issues.push(`design.${name}.mutedForeground 对 background 和 surface 的对比度都必须至少为 3:1`);
    return null;
  }
  return {
    bg, surface,
    surface2: midpointHex(bg, surface),
    ink, ink2: ink,
    muted, muted2: muted,
    border, borderSoft: border,
  };
};

// ── 分支 1:amber.theme.pack v1(跨平台) ──

const parsePortableDoc = (doc: PortableDoc, result: ThemePackImportResult): ThemePackImportResult => {
  if (doc.version !== 1) {
    const shown: string = doc.version === undefined ? '缺失' : `${doc.version}`;
    result.issues.push(`不支持的主题 version:${shown}(当前支持 1)`);
    return result;
  }
  if (typeof doc.id !== 'string' || doc.id.trim().length === 0) {
    result.issues.push('主题 id 不能为空');
    return result;
  }
  if (typeof doc.displayName !== 'string' || doc.displayName.trim().length === 0) {
    result.issues.push('主题名称不能为空');
    return result;
  }
  // accent:必需且无法推导,非法即拒(两端同)
  const accent: string | null = normalizeHexRgb(typeof doc.accentHex === 'string' ? doc.accentHex : '');
  if (accent === null) {
    result.issues.push('accentHex 必须是 #RRGGBB / 0xRRGGBB 颜色');
    return result;
  }
  // inkHex:可推导 —— 缺失/非法/低对比一律按 accent 取黑/白中可读色(两端此处拒绝,
  // 鸿蒙收敛为可读默认值,避免导入后按钮白字看不清)
  let accentInk: string = accentInkFor(accent);
  const accentRgb: number = parseHexRgb(accent) ?? 0;
  if (typeof doc.inkHex === 'string') {
    const ink: string | null = normalizeHexRgb(doc.inkHex);
    if (ink === null) {
      result.warnings.push('inkHex 无法解析,已按强调色自动配色');
    } else {
      const inkRgb: number = parseHexRgb(ink) ?? 0;
      if (contrastRatio(accentRgb, inkRgb) < 3.0) {
        result.warnings.push('accentHex 与 inkHex 对比度不足 3:1,已自动调整上墨色');
      } else {
        accentInk = ink;
      }
    }
  } else {
    result.warnings.push('inkHex 缺失,已按强调色自动配色');
  }
  // paper 画布(五种已知;沉浸色/未知 → 回退暖纸)
  const paperRaw: string = typeof doc.paper === 'string' ? doc.paper : '';
  let pair: PalettePair | null = paperRaw.length > 0 ? paperPalettes(paperRaw) : null;
  if (pair === null) {
    pair = { light: BUILTIN_PACKS[0].light, dark: BUILTIN_PACKS[0].dark };
    if (paperRaw.length === 0) {
      result.warnings.push('paper 缺失,已按暖纸画布处理');
    } else if (IMMERSIVE_PAPERS.indexOf(paperRaw) >= 0) {
      result.warnings.push(`沉浸色画布「${paperRaw}」不支持导入,已回退暖纸`);
    } else {
      result.warnings.push(`未知画布「${paperRaw}」,已回退暖纸`);
    }
  }
  let light: ThemePalette = pair.light;
  let dark: ThemePalette = pair.dark;
  let designDark: boolean = false;
  let patterns: ThemePackPattern[] | null = null;
  let gradient: ThemePackGradient | null = null;
  // design:有则逐侧覆写,缺的一侧继承 paper(iOS resolving 语义);纹理层严格校验
  if (doc.design !== undefined && doc.design !== null && typeof doc.design === 'object') {
    let hasDesignLight: boolean = false;
    let hasDesignDark: boolean = false;
    if (doc.design.light !== undefined && doc.design.light !== null && typeof doc.design.light === 'object') {
      const p: ThemePalette | null = readPortablePalette('light', doc.design.light, result);
      if (p === null) return result;
      light = p;
      hasDesignLight = true;
    }
    if (doc.design.dark !== undefined && doc.design.dark !== null && typeof doc.design.dark === 'object') {
      const p: ThemePalette | null = readPortablePalette('dark', doc.design.dark, result);
      if (p === null) return result;
      dark = p;
      hasDesignDark = true;
      designDark = true;
    }
    if (doc.design.patterns !== undefined) {
      patterns = readPortablePatterns(doc.design.patterns, result);
      if (patterns === null) return result;
    }
    if (doc.design.gradient !== undefined && doc.design.gradient !== null && typeof doc.design.gradient === 'object') {
      const lightFg: number = parseHexRgb(light.ink) ?? 0;
      const darkFg: number = parseHexRgb(dark.ink) ?? 0;
      gradient = readPortableGradient(doc.design.gradient, hasDesignLight && hasDesignDark,
        lightFg, darkFg, result);
      if (gradient === null) return result;
    }
  }
  // 画布纹理:点阵/方格 → 首页点阵;paperGrain/flat → 平涂(鸿蒙无纸纹层)
  let dotGrid: boolean = false;
  if (typeof doc.canvasStyle === 'string') {
    if (doc.canvasStyle === 'dotGrid' || doc.canvasStyle === 'lineGrid') {
      dotGrid = true;
    } else if (CANVAS_STYLES.indexOf(doc.canvasStyle) < 0) {
      result.warnings.push(`未知画布纹理「${doc.canvasStyle}」,已按平涂处理`);
    }
  }
  // 鸿蒙扩展段:己方导出的 9 槽全量色板,无损回读(跳过对比度拒绝,导入时已可信)
  const ext: HarmonyExtDoc | undefined = doc.harmony;
  if (ext !== undefined && ext !== null && typeof ext === 'object') {
    const extLight: ThemePalette | null = ext.light !== undefined ? readPalette(ext.light) : null;
    const extDark: ThemePalette | null = ext.dark !== undefined ? readPalette(ext.dark) : null;
    if (extLight !== null && extDark !== null) {
      light = extLight;
      dark = extDark;
      if (ext.dotGrid === true || ext.dotGrid === false) dotGrid = ext.dotGrid;
    }
  }
  const pack: ThemePack = {
    id: doc.id.trim(),
    displayName: doc.displayName.trim(),
    accent,
    accentInk,
    dotGrid,
    builtin: false,
    light,
    dark,
  };
  if (patterns !== null && patterns.length > 0) pack.patterns = patterns;
  if (gradient !== null) pack.gradient = gradient;
  if (designDark) pack.designDark = true;
  // 纹理作用域(homeOnly/shell/appWide;未知值回退默认 homeOnly)
  if (typeof doc.canvasScope === 'string') {
    if (doc.canvasScope === 'homeOnly' || doc.canvasScope === 'shell' || doc.canvasScope === 'appWide') {
      pack.canvasScope = doc.canvasScope;
    } else {
      result.warnings.push(`未知 canvasScope「${doc.canvasScope}」,已按首页作用域处理`);
    }
  }
  dedupeBuiltinId(pack, result.warnings);
  result.pack = pack;
  return result;
};

// ── 分支 2:Android 旧 token 包(schemaVersion=1) ──

const parseLegacyDoc = (doc: LegacyPackageDoc, result: ThemePackImportResult): ThemePackImportResult => {
  if (doc.schemaVersion !== 1) {
    const shown: string = doc.schemaVersion === undefined ? '缺失' : `${doc.schemaVersion}`;
    result.issues.push(`不支持的 schemaVersion:${shown}(当前支持 1)`);
    return result;
  }
  if (typeof doc.id !== 'string' || doc.id.trim().length === 0) {
    result.issues.push('主题包 id 不能为空');
    return result;
  }
  if (doc.id.indexOf(LEGACY_BUILTIN_PREFIX) === 0) {
    result.issues.push(`内置主题 id(${LEGACY_BUILTIN_PREFIX} 前缀)为保留值,导入包不能覆盖内置主题`);
    return result;
  }
  if (typeof doc.name !== 'string' || doc.name.trim().length === 0) {
    result.issues.push('主题包 name 不能为空');
    return result;
  }
  // 颜色 token:baseFamily(WARM|SAGE)+ accent(#RRGGBB/#AARRGGBB)
  let base: string = 'WARM';
  let accent: string = DEFAULT_ACCENT;
  if (doc.colors !== undefined && doc.colors !== null && typeof doc.colors === 'object') {
    if (doc.colors.baseFamily !== undefined) {
      if (doc.colors.baseFamily !== 'WARM' && doc.colors.baseFamily !== 'SAGE') {
        result.issues.push(`颜色 token「baseFamily」的值无效:${doc.colors.baseFamily}`);
        return result;
      }
      base = doc.colors.baseFamily;
    }
    if (doc.colors.accent !== undefined) {
      const normalized: string | null = normalizeLegacyAccent(doc.colors.accent, result.warnings);
      if (normalized === null) {
        result.issues.push(`颜色 token「accent」的值无效:${doc.colors.accent}`);
        return result;
      }
      accent = normalized;
    }
  }
  // 内嵌跨平台配方优先(Android ThemePackTransfer.fromPackage 语义),身份必须一致
  if (doc.document !== undefined && doc.document !== null && typeof doc.document === 'object') {
    if (doc.document.id !== doc.id || doc.document.displayName !== doc.name) {
      result.issues.push('主题身份与配方不一致');
      return result;
    }
    parsePortableDoc(doc.document, result);
    if (result.pack === null) return result;
  } else {
    const pair: PalettePair = base === 'SAGE'
      ? { light: SAGE_LIGHT, dark: SAGE_DARK }
      : { light: BUILTIN_PACKS[0].light, dark: BUILTIN_PACKS[0].dark };
    const pack: ThemePack = {
      id: doc.id.trim(),
      displayName: doc.name.trim(),
      accent,
      dotGrid: base === 'WARM',
      builtin: false,
      light: pair.light,
      dark: pair.dark,
    };
    dedupeBuiltinId(pack, result.warnings);
    result.pack = pack;
  }
  // 字体/布局 token → DisplaySetting(鸿蒙有同名字段,全量可落地)
  const patch: ThemePackDisplayPatch = {};
  let patched: boolean = false;
  if (doc.fonts !== undefined && doc.fonts !== null && typeof doc.fonts === 'object') {
    if (doc.fonts.chatFontFamily !== undefined) {
      const family: string = doc.fonts.chatFontFamily;
      if (family !== 'default' && family !== 'serif' && family !== 'monospace') {
        result.issues.push(`字体 token「chatFontFamily」的值无效:${family}`);
        result.pack = null;
        return result;
      }
      patch.chatFontFamily = family === 'serif' ? 'SERIF' : family === 'monospace' ? 'MONO' : 'DEFAULT';
      patched = true;
    }
    if (doc.fonts.fontSizeRatio !== undefined) {
      const ratio: number = Number(doc.fonts.fontSizeRatio);
      if (!Number.isFinite(ratio) || ratio < 0.5 || ratio > 2.0) {
        result.issues.push(`字体 token「fontSizeRatio」的值无效:${doc.fonts.fontSizeRatio}`);
        result.pack = null;
        return result;
      }
      patch.fontSizeRatio = ratio;
      patched = true;
    }
  }
  if (doc.layout !== undefined && doc.layout !== null && typeof doc.layout === 'object') {
    if (doc.layout.showUserAvatar !== undefined) {
      if (doc.layout.showUserAvatar !== 'true' && doc.layout.showUserAvatar !== 'false') {
        result.issues.push(`布局 token「showUserAvatar」的值无效:${doc.layout.showUserAvatar}`);
        result.pack = null;
        return result;
      }
      patch.showUserAvatar = doc.layout.showUserAvatar === 'true';
      patched = true;
    }
    if (doc.layout.showAssistantBubble !== undefined) {
      if (doc.layout.showAssistantBubble !== 'true' && doc.layout.showAssistantBubble !== 'false') {
        result.issues.push(`布局 token「showAssistantBubble」的值无效:${doc.layout.showAssistantBubble}`);
        result.pack = null;
        return result;
      }
      patch.showAssistantBubble = doc.layout.showAssistantBubble === 'true';
      patched = true;
    }
  }
  patch.amberBaseFamily = base;
  result.display = patched || doc.colors !== undefined ? patch : result.display;
  return result;
};

// ── 分支 3:鸿蒙原生(amber-theme-pack v1 / AI 生成的无 format 文档) ──

const parseNativeText = (text: string, result: ThemePackImportResult): ThemePackImportResult => {
  const pack: ThemePack | null = parseThemePackJson(text);
  if (pack === null) {
    result.issues.push('缺少 name/accent/light/dark 必要字段,或颜色不是 #RRGGBB');
    return result;
  }
  dedupeBuiltinId(pack, result.warnings);
  result.pack = pack;
  return result;
};

// ── 入口:格式分流 ──

export const parseThemePackImport = (text: string): ThemePackImportResult => {
  const result: ThemePackImportResult = new ThemePackImportResult();
  let probe: ProbeDoc;
  try {
    const parsed: ProbeDoc = JSON.parse(text) as ProbeDoc;
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      result.issues.push('文件不是主题包 JSON 对象');
      return result;
    }
    probe = parsed;
  } catch (_e) {
    result.issues.push('文件不是有效的 JSON');
    return result;
  }
  try {
    if (probe.format === PORTABLE_FORMAT) {
      const doc: PortableDoc = JSON.parse(text) as PortableDoc;
      return parsePortableDoc(doc, result);
    }
    if (probe.format === NATIVE_FORMAT) {
      return parseNativeText(text, result);
    }
    if (typeof probe.format === 'string' && probe.format.length > 0) {
      result.issues.push(`不支持的主题 format:「${probe.format}」`);
      return result;
    }
    if (probe.schemaVersion !== undefined) {
      const doc: LegacyPackageDoc = JSON.parse(text) as LegacyPackageDoc;
      return parseLegacyDoc(doc, result);
    }
    // 无 format/schemaVersion:鸿蒙原生宽松通道(AI 生成/手写 9 槽文档)
    return parseNativeText(text, result);
  } catch (e) {
    result.issues.push(`解析失败:${(e as Error).message}`);
    result.pack = null;
    return result;
  }
};

// ── 导出:统一发 amber.theme.pack v1(iOS/Android 可直接导入) ──

const portablePaletteDoc = (p: ThemePalette): PortablePaletteDoc => ({
  background: p.bg,
  surface: p.surface,
  foreground: p.ink,
  mutedForeground: p.muted,
  border: p.border,
});

// 品牌槽:内置包逐值对齐 iOS AmberThemePack.builtins;自定义包走系统默认
export const themePackToPortableJson = (pack: ThemePack): string => {
  let paper: string = 'paper';
  let canvasStyle: string = pack.dotGrid ? 'dotGrid' : 'flat';
  let brandMark: string = 'systemWordmark';
  let shortcutIconStyle: string = 'systemOutline';
  let chromeTypeface: string = 'system';
  if (pack.id === 'sit-terracotta') {
    canvasStyle = 'dotGrid'; brandMark = 'paintAMBER';
    shortcutIconStyle = 'pixelSit'; chromeTypeface = 'rounded';
  } else if (pack.id === 'pi-steel') {
    paper = 'pi'; canvasStyle = 'lineGrid'; brandMark = 'serifWordmark';
    shortcutIconStyle = 'phosphorFill'; chromeTypeface = 'monospace';
  } else if (pack.id === 'notion-blue') {
    paper = 'notion'; canvasStyle = 'flat'; brandMark = 'systemWordmark';
    shortcutIconStyle = 'phosphorFill'; chromeTypeface = 'system';
  }
  const ink: string = pack.accentInk ?? accentInkFor(pack.accent);
  const ext: HarmonyExtDoc = {
    dotGrid: pack.dotGrid,
    light: paletteToDoc(pack.light),
    dark: paletteToDoc(pack.dark),
  };
  const outPatterns: PortablePatternDoc[] = [];
  if (pack.patterns !== undefined) {
    for (const p of pack.patterns) {
      outPatterns.push({ kind: p.kind, color: p.color, opacity: p.opacity, spacing: p.spacing, size: p.size });
    }
  }
  const design: PortableDesignDoc = {
    light: portablePaletteDoc(pack.light),
    dark: portablePaletteDoc(pack.dark),
    patterns: outPatterns,
  };
  if (pack.gradient !== undefined) {
    design.gradient = {
      colors: pack.gradient.colors,
      darkColors: pack.gradient.darkColors,
      angle: pack.gradient.angle,
    };
  }
  const doc: PortableDoc = {
    format: PORTABLE_FORMAT,
    version: 1,
    id: pack.id,
    displayName: pack.displayName,
    paper,
    accentHex: pack.accent,
    inkHex: ink,
    canvasStyle,
    brandMark,
    shortcutIconStyle,
    chromeTypeface,
    canvasScope: pack.canvasScope ?? 'homeOnly',
    assetMode: 'builtinOnly',
    immersivePolicy: 'hidden',
    design,
    harmony: ext,
  };
  return JSON.stringify(doc);
};
