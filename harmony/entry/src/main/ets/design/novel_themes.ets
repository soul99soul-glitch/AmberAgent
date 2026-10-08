// AmberNovel iOS NovelAppearanceView / AmberDesignSystem 的纸色与强调色配方。
import type { ThemePack, ThemePalette } from './theme_packs.ets';

export interface NovelTheme extends ThemePack { note: string; }

const NEUTRAL_LIGHT: ThemePalette = {
  bg: '#ECE8E4', surface: '#F6F5F3', surface2: '#EDEBE7',
  ink: '#161514', ink2: '#55524D', muted: '#716D67',
  muted2: '#8F8B85', border: '#D9D5CF', borderSoft: '#E4E1DC',
};
const NEUTRAL_DARK: ThemePalette = {
  bg: '#0E0D10', surface: '#1F1D23', surface2: '#2B2930',
  ink: '#F4F1ED', ink2: '#C3BEC5', muted: '#AAA5AD',
  muted2: '#6E6760', border: '#3A3741', borderSoft: '#2A2830',
};
const PAPER_LIGHT: ThemePalette = {
  bg: '#EFE7D6', surface: '#FFFDF7', surface2: '#F0EBE2',
  ink: '#1B1813', ink2: '#5B5449', muted: '#746D62',
  muted2: '#918A80', border: '#DBCEBC', borderSoft: '#ECE3D6',
};
const PAPER_DARK: ThemePalette = {
  bg: '#14110E', surface: '#221E19', surface2: '#2E2822',
  ink: '#F5F0E8', ink2: '#C8BDB0', muted: '#A89888',
  muted2: '#6E6258', border: '#3D342C', borderSoft: '#2A241E',
};
const PI_LIGHT: ThemePalette = {
  bg: '#F3F0EB', surface: '#FAF9F7', surface2: '#EBE7E0',
  ink: '#1C1B19', ink2: '#4A4640', muted: '#6A6560',
  muted2: '#9A948C', border: '#D4CFC7', borderSoft: '#E8E4DC',
};
const PI_DARK: ThemePalette = {
  bg: '#12110F', surface: '#1E1C18', surface2: '#2A2722',
  ink: '#F3F0EB', ink2: '#C4BEB4', muted: '#9A948C',
  muted2: '#6A6560', border: '#3A3630', borderSoft: '#28251F',
};
const WHITE_LIGHT: ThemePalette = {
  bg: '#F5F5F4', surface: '#FFFFFF', surface2: '#EEEEED',
  ink: '#1A1A1A', ink2: '#5C5C5C', muted: '#737373',
  muted2: '#8E8E8E', border: '#D4D4D4', borderSoft: '#E5E5E5',
};
const WHITE_DARK: ThemePalette = {
  bg: '#111111', surface: '#1C1C1C', surface2: '#282828',
  ink: '#F5F5F5', ink2: '#BDBDBD', muted: '#8E8E8E',
  muted2: '#6B6B6B', border: '#383838', borderSoft: '#2A2A2A',
};
const NOTION_LIGHT: ThemePalette = {
  bg: '#F6F5F4', surface: '#FFFFFF', surface2: '#EFEEEC',
  ink: '#1A1918', ink2: '#31302E', muted: '#615D59',
  muted2: '#A39E98', border: '#E6E5E3', borderSoft: '#F0EFED',
};
const NOTION_DARK: ThemePalette = {
  bg: '#191919', surface: '#252525', surface2: '#2F2F2F',
  ink: '#EBEBEB', ink2: '#B4B4B4', muted: '#9B9B9B',
  muted2: '#6F6F6F', border: '#373737', borderSoft: '#2C2C2C',
};
const LOTUS_LIGHT: ThemePalette = {
  bg: '#E7D5D2', surface: '#F2E5E3', surface2: '#DBC8C5',
  ink: '#2E2422', ink2: '#4F413E', muted: '#7D6D69',
  muted2: '#A4918D', border: '#D7C1BD', borderSoft: '#E3D0CD',
};
const LOTUS_DARK: ThemePalette = {
  bg: '#E7D5D2', surface: '#F2E5E3', surface2: '#DBC8C5',
  ink: '#2E2422', ink2: '#4F413E', muted: '#7D6D69',
  muted2: '#A4918D', border: '#D7C1BD', borderSoft: '#E3D0CD',
};
const GARNET_LIGHT: ThemePalette = {
  bg: '#5B1A1C', surface: '#6F282A', surface2: '#843234',
  ink: '#F7E6E4', ink2: '#E4C7C5', muted: '#C89C9B',
  muted2: '#AC7E7C', border: '#7C3436', borderSoft: '#682A2C',
};
const GARNET_DARK: ThemePalette = {
  bg: '#5B1A1C', surface: '#6F282A', surface2: '#843234',
  ink: '#F7E6E4', ink2: '#E4C7C5', muted: '#C89C9B',
  muted2: '#AC7E7C', border: '#7C3436', borderSoft: '#682A2C',
};

export const NOVEL_THEMES: NovelTheme[] = [
  { id: 'novel-sujian', displayName: '素笺', note: '暖灰纸 · 琥珀金',
    light: NEUTRAL_LIGHT, dark: NEUTRAL_DARK, accent: '#B9863A', accentInk: '#231602',
    dotGrid: false, builtin: true },
  { id: 'novel-xuanzhi', displayName: '宣纸', note: '米黄宣纸 · 陶土红',
    light: PAPER_LIGHT, dark: PAPER_DARK, accent: '#B8623A', accentInk: '#FFFFFF',
    dotGrid: false, builtin: true },
  { id: 'novel-gaozhi', displayName: '稿纸', note: '奶油稿纸 · 钢青',
    light: PI_LIGHT, dark: PI_DARK, accent: '#6B8CAD', accentInk: '#FAF9F7',
    dotGrid: false, builtin: true },
  { id: 'novel-zhuqing', displayName: '竹青', note: '素白 · 竹叶绿',
    light: WHITE_LIGHT, dark: WHITE_DARK, accent: '#5E9C6E', accentInk: '#0F150E',
    dotGrid: false, builtin: true },
  { id: 'novel-yanzhi', displayName: '胭脂', note: '暖白 · 胭脂红',
    light: NOTION_LIGHT, dark: NOTION_DARK, accent: '#C2607A', accentInk: '#FFFFFF',
    dotGrid: false, builtin: true },
  { id: 'novel-ouhe', displayName: '藕荷', note: '藕粉画布 · 紫藤',
    light: LOTUS_LIGHT, dark: LOTUS_DARK, accent: '#9277C4', accentInk: '#FFFFFF',
    dotGrid: false, builtin: true, fixedColorMode: 'light' },
  { id: 'novel-mobai', displayName: '墨白', note: '素白 · 只留墨色',
    light: WHITE_LIGHT, dark: WHITE_DARK, accent: '#222226', accentInk: '#FFFFFF',
    dotGrid: false, builtin: true },
  { id: 'novel-jiangye', displayName: '绛夜', note: '深绛画布 · 琥珀金',
    light: GARNET_LIGHT, dark: GARNET_DARK, accent: '#B9863A', accentInk: '#231602',
    dotGrid: false, builtin: true, fixedColorMode: 'dark' },
];
