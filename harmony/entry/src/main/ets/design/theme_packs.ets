// design/theme_packs.ets — 主题包系统(C2;iOS AmberThemePack 对齐)
//
// 模型:ThemePack = 纸色组(light/dark 各 10 槽)+ accent + 首页点阵开关。
// 应用:applyThemePack = 先 applyTheme 拿状态色/玻璃/HOME alpha 默认值,再经
//   applyPaperPalette 覆写 pack 纸色;accent 走既有 setAccentColor。
// 刷新:令牌变更收口(applyTheme/applyPaperPalette/setAccentColor/applyAmoledBlack)
//   自动 bumpThemeEpoch → 全站 @Entry 页面 ForEach 纪元重挂载,整棵子树销毁重建。
//   调用方另写 AppStorage accentColor / darkMode / homeDotGrid / themePackId
//   供语义化订阅者(如点阵重绘开关)。
// 持久化:KV theme_pack_id + theme_custom_packs(JSON 数组,导入包);
//   启动时 EntryAbility hydrate 后用 applyCurrentTheme 替代裸 applyTheme。

import {
  applyTheme, setAccentColor, applyPaperPalette, applyAmoledBlack,
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

export interface ThemePack {
  id: string;
  displayName: string;
  light: ThemePalette;
  dark: ThemePalette;
  accent: string;
  dotGrid: boolean; // 首页画布点阵(点阵·陶土/点阵·Pi);false = 平涂(Notion)
  builtin: boolean;
}

const THEME_PACK_ID_KEY: string = 'theme_pack_id';
const THEME_CUSTOM_PACKS_KEY: string = 'theme_custom_packs';

// ── 内置包(iOS AmberThemePack.builtins 逐值) ──
export const BUILTIN_PACKS: ThemePack[] = [
  {
    id: 'sit-terracotta',
    displayName: '点阵 · 陶土',
    accent: '#B8623A',
    dotGrid: true,
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
    dotGrid: true,
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
    dotGrid: false,
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
];

// ── 应用 ──

// 先跑 applyTheme 拿状态色/玻璃/HOME alpha 默认,再覆写 pack 纸色(pack 只管纸色组)
export const applyThemePack = (pack: ThemePack, dark: boolean): void => {
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
    userInk: dark ? pack.light.ink : pack.dark.ink,
    codeBg: p.surface2,
    bgFadeStart: `#00${p.bg.substring(1)}`,
    bgFadeEnd: p.bg,
    bgGlass: `#CC${p.bg.substring(1)}`,
  });
  setAccentColor(pack.accent);
  // D0-11:AMOLED 纯黑覆写(仅深色;画布/渐隐/玻璃改纯黑,卡片面保持原色分层)
  if (dark && amoledBlack) {
    applyAmoledBlack();
  }
};

// ── JSON 编解码(导入/导出;schema 校验,不合法返回 null 由调用方诚实报错) ──

interface PaletteDoc {
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
  dot_grid?: boolean;
  light?: PaletteDoc;
  dark?: PaletteDoc;
}

const isHexColor = (v: string): boolean => /^#[0-9a-fA-F]{6}$/.test(v);

const readPalette = (doc: PaletteDoc | undefined): ThemePalette | null => {
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

const paletteToDoc = (p: ThemePalette): PaletteDoc => ({
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
    return {
      id,
      displayName: doc.name.trim(),
      accent: doc.accent,
      dotGrid: doc.dot_grid === true,
      builtin: false,
      light,
      dark,
    };
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

// 全局主题应用入口(模式切换/系统深浅变化时替代裸 applyTheme;pack 由 store 提供)
export const applyCurrentTheme = (dark: boolean): void => {
  applyThemePack(themePackStore.current(), dark);
};
