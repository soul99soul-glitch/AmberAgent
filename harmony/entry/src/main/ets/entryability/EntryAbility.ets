// EntryAbility — Phase 7 切后台暂停/回前台续跑
// onBackground → AppStorage 写 appBackgrounded=true(Index 检查后暂停新 stage)
// onForeground → AppStorage 写 appBackgrounded false(恢复)
// 对照设计 §6.5

import { UIAbility, AbilityConstant, Want, Configuration, ConfigurationConstant } from '@kit.AbilityKit';
import { window, display } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { notificationManager } from '@kit.NotificationKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { getSkillManager, rescheduleCronTasksOnStartup, getChatKvStore } from '../di/AppContainer.ets';
import { loadDisplaySetting } from '@amber/chat-domain';
import type { DisplaySetting } from '@amber/chat-domain';
import { ACCENT, applyTheme, setAccentColor, BG } from '../design/tokens.ets';
import { registerAmberFonts, registerAmberFontsFor } from '../design/fonts.ets';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.EntryAbility';
const MAX_WANT_PARAMETER_LENGTH: number = 512;
const WINDOW_SYSTEM_BAR_TRANSPARENT: string = '#00000000';
const WINDOW_LIGHT_CONTENT: string = '#1B1A17';
const WINDOW_DARK_CONTENT: string = '#ECE8DF';
let themeWindow: window.Window | null = null;

export const applyWindowTheme = (dark: boolean): void => {
  if (themeWindow === null) return;
  try {
    themeWindow.setWindowBackgroundColor(BG);
    themeWindow.setWindowSystemBarProperties({
      statusBarColor: WINDOW_SYSTEM_BAR_TRANSPARENT,
      statusBarContentColor: dark ? WINDOW_DARK_CONTENT : WINDOW_LIGHT_CONTENT,
      navigationBarColor: WINDOW_SYSTEM_BAR_TRANSPARENT,
      navigationBarContentColor: dark ? WINDOW_DARK_CONTENT : WINDOW_LIGHT_CONTENT,
    });
  } catch (e) {
    hilog.warn(DOMAIN, TAG, 'theme window apply failed: %{public}s', String(e));
  }
};

const validatedWantParameter = (value: object | undefined): string => {
  if (value === undefined || typeof value !== 'string') return '';
  const trimmed: string = (value as string).trim();
  return trimmed.length > 0 && trimmed.length <= MAX_WANT_PARAMETER_LENGTH ? trimmed : '';
};

export default class EntryAbility extends UIAbility {
  private avoidAreaWindow: window.Window | null = null;
  private displayDensity: number = 3;
  private systemDarkMode: boolean = false;

  private updateSystemAvoidArea(area: window.AvoidArea, preserveTopFallback: boolean = false): void {
    const topPx: number = area.topRect.height;
    const bottomPx: number = area.bottomRect.height;
    if (topPx > 0 || !preserveTopFallback) {
      const topVp: number = topPx > 0 ? Math.round(topPx / this.displayDensity) : 44;
      AppStorage.setOrCreate<number>('statusBarHeight', topVp);
    }
    AppStorage.setOrCreate<number>('navBarHeight',
      bottomPx > 0 ? Math.round(bottomPx / this.displayDensity) : 0);
  }

  onCreate(want: Want, _launchParam: AbilityConstant.LaunchParam): void {
    hilog.info(DOMAIN, TAG, 'onCreate');
    // 注册 Amber 品牌字体族(AmberSans 四权重/JetBrainsMono/NotoSerifSC),见 design/fonts.ets
    registerAmberFonts();
    AppStorage.setOrCreate<boolean>('appBackgrounded', false);
    this.systemDarkMode = this.context.config.colorMode === ConfigurationConstant.ColorMode.COLOR_MODE_DARK;
    AppStorage.setOrCreate<boolean>('systemDarkMode', this.systemDarkMode);
    AppStorage.setOrCreate<string>('accentColor', ACCENT);
    AppStorage.setOrCreate<Context>('abilityContext', this.context);
    // Want 契约(§7.2):分享/deep-link 收 URL/title → 写 AppStorage 供 Index 跳转
    this.routeFromWant(want);
    // D-126:AmberAgentApp.kt:205-213 — 启动 fire-and-forget 装内置技能
    //   (Dispatchers.IO 异步 → promise 丢弃;失败仅 Log.e,不阻塞启动)
    getSkillManager().installBuiltinSkillsIfMissing()
      .catch((e: Error): void => {
        hilog.error(DOMAIN, TAG, 'installBuiltinSkills failed: %{public}s', String(e));
      });
    // D-130:AmberAgentApp.kt:216-224 — 启动 fire-and-forget 重排 cron 任务
    //   (内部已 catch + hilog;应用存活期定时器重新武装)
    rescheduleCronTasksOnStartup();
  }

  onNewWant(want: Want, _launchParam: AbilityConstant.LaunchParam): void {
    hilog.info(DOMAIN, TAG, 'onNewWant');
    this.routeFromWant(want);
  }

  private routeFromWant(want: Want): void {
    // 从 want.parameters 提取 topicId + title(通知点击/分享入口)
    const params: Record<string, object> | undefined = want.parameters as Record<string, object>;
    if (params === undefined || params === null) {
      return;
    }
    const topicId: string = validatedWantParameter(params['topicId']);
    const title: string = validatedWantParameter(params['title']);
    if (topicId.length > 0) {
      AppStorage.setOrCreate<string>('wantTopicId', topicId);
      // title 无条件覆写(含空):仅在有值时写入会残留上一条通知的旧标题
      AppStorage.setOrCreate<string>('wantTitle', title);
    }
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    // 沉浸式(同步, 必须在 loadContent 前完成, 否则页面首帧读到 statusBarHeight=0):
    //   全屏布局使状态栏后面=窗口背景; 窗口背景=BG; 状态栏透明 → 无缝
    try {
      const win: window.Window = windowStage.getMainWindowSync();
      themeWindow = win;
      // 先读 avoid area(全屏前), 再全屏; 全屏后 TYPE_SYSTEM 可能返回 0
      const avoidBefore: window.AvoidArea = win.getWindowAvoidArea(window.AvoidAreaType.TYPE_SYSTEM);
      win.setWindowLayoutFullScreen(true);
      const avoidAfter: window.AvoidArea = win.getWindowAvoidArea(window.AvoidAreaType.TYPE_SYSTEM);
      // 背景和系统栏颜色在 KV 主题解析后设置，避免深色启动闪帧。
      win.setWindowSystemBarEnable(['status', 'navigation']);
      // 取非零值; px2vp 在 Ability 上下文不可用 → 用窗口 density 手动换算; 仍为0兜底44vp
      let density: number = 3;
      try { density = display.getDefaultDisplaySync().densityPixels; } catch (_e) { density = 3; }
      this.displayDensity = density;
      let topPx: number = avoidBefore.topRect.height > 0 ? avoidBefore.topRect.height : avoidAfter.topRect.height;
      let topVp: number = topPx > 0 ? Math.round(topPx / density) : 44;
      AppStorage.setOrCreate<number>('statusBarHeight', topVp);
      // P4:底部安全区(导航条)高度 — 全屏布局下导航条避让区即 bottomRect;
      //   取非零值,0 = 手势导航全隐藏或设备无导航条(ChatPage 列表/输入栏以此为底 padding)
      let bottomPx: number = avoidBefore.bottomRect.height > 0 ? avoidBefore.bottomRect.height : avoidAfter.bottomRect.height;
      let bottomVp: number = bottomPx > 0 ? Math.round(bottomPx / density) : 0;
      AppStorage.setOrCreate<number>('navBarHeight', bottomVp);
      hilog.info(DOMAIN, TAG, 'statusBar px=%{public}d density=%{public}f vp=%{public}d', topPx, density, topVp);
      hilog.info(DOMAIN, TAG, 'navBar px=%{public}d vp=%{public}d', bottomPx, bottomVp);
      this.avoidAreaWindow = win;
      // 字体走 UIContext 注册(全局 font.registerFont 本机实测不生效,见 design/fonts.ets);
      // 独立 try:getUIContext 竞态抛异常时不得连累沉浸式设置
      try {
        registerAmberFontsFor(win.getUIContext().getFont());
      } catch (e) {
        hilog.warn(DOMAIN, TAG, 'uicontext font register failed: %{public}s', String(e));
      }
      win.on('avoidAreaChange', (info: window.AvoidAreaOptions): void => {
        if (info.type === window.AvoidAreaType.TYPE_SYSTEM) {
          this.updateSystemAvoidArea(info.area, true);
        }
      });
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'immersive setup failed: %{public}s', String(e));
    }

    // 主题/强调色:必须在 loadContent 前应用完(tokens 可变,页面 build 时读取;
    //   否则首帧静态组件读浅色、异步组件读深色 → 混色)
    const start: () => void = (): void => {
      // 首页=对话列表(用户心智: 列表是首页, 点 session 进聊天界面)
      windowStage.loadContent('pages/ChatListPage', (err: BusinessError) => {
        if (err.code !== 0) {
          hilog.error(DOMAIN, TAG, 'loadContent failed: %{public}s', JSON.stringify(err));
        } else {
          hilog.info(DOMAIN, TAG, 'loadContent success');
          // 竞态兜底:内容加载后 UIContext 必然就绪,再注册一次字体(幂等)
          try {
            registerAmberFontsFor(windowStage.getMainWindowSync().getUIContext().getFont());
          } catch (e) {
            hilog.warn(DOMAIN, TAG, 'post-load font register failed: %{public}s', String(e));
          }
          // 通知授权申请(API 10+;窗口就绪后调用才弹窗;首次一次,之后静默)
          notificationManager.requestEnableNotification(this.context).catch((): void => {
            hilog.warn(DOMAIN, TAG, 'requestEnableNotification rejected');
          });
        }
      });
    };
    getChatKvStore().get('ui_color_mode').then((mode: string | null): Promise<DisplaySetting> => {
      const resolvedDark: boolean = mode === 'dark' || (mode !== 'light' && this.systemDarkMode);
      applyTheme(resolvedDark);
      AppStorage.setOrCreate<string>('uiColorMode', mode === 'dark' || mode === 'light' || mode === 'system' ? mode : 'system');
      AppStorage.setOrCreate<boolean>('darkMode', resolvedDark);
      applyWindowTheme(resolvedDark);
      return loadDisplaySetting(getChatKvStore());
    }).then((s: DisplaySetting): void => {
      const accent: string = s.accentColor.length > 0 ? s.accentColor : ACCENT;
      setAccentColor(accent);
      AppStorage.setOrCreate<string>('accentColor', accent);
      start();
    }).catch((): void => { start(); });
  }

  onConfigurationUpdate(newConfig: Configuration): void {
    const isDark: boolean = newConfig.colorMode === ConfigurationConstant.ColorMode.COLOR_MODE_DARK;
    this.systemDarkMode = isDark;
    AppStorage.setOrCreate<boolean>('systemDarkMode', isDark);
    const mode: string = AppStorage.get<string>('uiColorMode') ?? 'system';
    if (mode === 'system') {
      applyTheme(isDark);
      AppStorage.setOrCreate<boolean>('darkMode', isDark);
      applyWindowTheme(isDark);
    }
  }

  onWindowStageDestroy(): void {
    if (this.avoidAreaWindow !== null) {
      try { this.avoidAreaWindow.off('avoidAreaChange'); } catch (_e) {}
      this.avoidAreaWindow = null;
    }
    themeWindow = null;
  }

  onForeground(): void {
    hilog.info(DOMAIN, TAG, 'onForeground — 回前台续跑');
    AppStorage.setOrCreate<boolean>('appBackgrounded', false);
  }

  onBackground(): void {
    hilog.info(DOMAIN, TAG, 'onBackground — 切后台暂停');
    AppStorage.setOrCreate<boolean>('appBackgrounded', true);
  }
}
