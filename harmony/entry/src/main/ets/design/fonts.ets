// design/fonts.ets — Amber 品牌字体注册(对齐 Android Type.kt 的逐权重配对)
//
// rawfile/fonts 下是 harmony/scripts/build_fonts.py 的产物:Hanken Grotesk 与
// Noto Sans SC 按权重实例化后合并为单一 TTF(拉丁→Hanken、汉字→Noto SC),
// 再按「一个 family 名一个字重」注册 —— 字重由 family 名本身承载,不依赖
// 运行时的变量轴/合成加粗语义。配合令牌使用时 fontWeight 应与 family 一致。

import { font } from '@kit.ArkUI';
import type { Font } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0x0001;
const TAG: string = 'AmberFonts';

// ── family 名契约(tokens.ets / 组件共用) ──
export const FONT_SANS: string = 'AmberSans';                 // 400
export const FONT_SANS_MEDIUM: string = 'AmberSansMedium';    // 500
export const FONT_SANS_SEMIBOLD: string = 'AmberSansSemiBold'; // 600
export const FONT_SANS_BOLD: string = 'AmberSansBold';        // 700
export const FONT_MONO: string = 'JetBrainsMono';             // 400(机器事实)
export const FONT_MONO_MEDIUM: string = 'JetBrainsMonoMedium';     // 500
export const FONT_MONO_SEMIBOLD: string = 'JetBrainsMonoSemiBold'; // 600(eyebrow)
export const FONT_MONO_BOLD: string = 'JetBrainsMonoBold';    // 700(wordmark)
export const FONT_SERIF: string = 'NotoSerifSC';              // 阅读衬面(Regular)

// 历史家族名与上面重名的别名(存量站点沿用 'JetBrainsMono' 字面量也等价)。
const LEGACY_JB: string = FONT_MONO;
const LEGACY_JB_BOLD: string = FONT_MONO_BOLD;

function registerFamily(familyName: string, familySrc: Resource, host: Font | null): void {
  try {
    if (host !== null) {
      host.registerFont({ familyName: familyName, familySrc: familySrc });
    } else {
      font.registerFont({ familyName: familyName, familySrc: familySrc });
    }
  } catch (e) {
    hilog.warn(DOMAIN, TAG, 'registerFont %{public}s failed: %{public}s',
      familyName, String(e));
  }
}

/** 启动时调用一次(EntryAbility.onCreate)。 */
export function registerAmberFonts(): void {
  registerAll(null);
}

// 实测(2026-08-30,SGT-AL10/HarmonyOS 7):onCreate 里走全局 font.registerFont 不生效,
// 字体全部静默回退系统字体;经主窗口 UIContext.getFont() 注册后才真正生效。
// EntryAbility.onWindowStageCreate 拿到主窗口后必须再调一次。
export function registerAmberFontsFor(host: Font): void {
  registerAll(host);
}

function registerAll(host: Font | null): void {
  registerFamily(FONT_SANS, $rawfile('fonts/AmberSans.ttf'), host);
  registerFamily(FONT_SANS_MEDIUM, $rawfile('fonts/AmberSans-Medium.ttf'), host);
  registerFamily(FONT_SANS_SEMIBOLD, $rawfile('fonts/AmberSans-SemiBold.ttf'), host);
  registerFamily(FONT_SANS_BOLD, $rawfile('fonts/AmberSans-Bold.ttf'), host);
  registerFamily(FONT_MONO_SEMIBOLD, $rawfile('fonts/JetBrainsMono-SemiBold.ttf'), host);
  registerFamily(FONT_MONO_MEDIUM, $rawfile('fonts/JetBrainsMono-Medium.ttf'), host);
  registerFamily(FONT_SERIF, $rawfile('fonts/NotoSerifSC.otf'), host);
  // 存量名(JetBrainsMono Regular/Bold 静态档)
  registerFamily(LEGACY_JB, $rawfile('fonts/JetBrainsMono-Regular.ttf'), host);
  registerFamily(LEGACY_JB_BOLD, $rawfile('fonts/JetBrainsMono-Bold.ttf'), host);
}
