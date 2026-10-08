// 平板 / 折叠屏展开横屏:首页列表窄栏在左、聊天在右(对齐 Android TwoPaneScene)。
// 手机与竖屏窗口保持单栏页面栈;两种布局共用同一 router 栈,旋转/折叠只换布局。
//
// EntryAbility 在 windowSizeChange 时写入窗口尺寸(vp),页面经 @StorageProp 订阅。
export const WINDOW_WIDTH_VP_KEY: string = 'windowWidthVp';
export const WINDOW_HEIGHT_VP_KEY: string = 'windowHeightVp';

/** 高度足够放两栏,且足够宽或在宽屏横放。 */
export function isTwoPaneWindow(widthVp: number, heightVp: number): boolean {
  return heightVp >= 480 && (widthVp >= 840 || (widthVp >= 600 && widthVp > heightVp));
}

/** 约三分之一窗口宽,绝不对半:iPad 侧栏比例。 */
export function twoPaneListWidth(widthVp: number): number {
  return Math.min(400, Math.max(320, widthVp * 0.3));
}

export function publishWindowSize(widthPx: number, heightPx: number, density: number): void {
  if (widthPx <= 0 || heightPx <= 0 || density <= 0) return;
  AppStorage.setOrCreate<number>(WINDOW_WIDTH_VP_KEY, Math.round(widthPx / density));
  AppStorage.setOrCreate<number>(WINDOW_HEIGHT_VP_KEY, Math.round(heightPx / density));
}
