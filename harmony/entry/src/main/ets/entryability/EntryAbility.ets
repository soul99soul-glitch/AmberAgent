// EntryAbility — Phase 7 切后台暂停/回前台续跑
// onBackground → AppStorage 写 appBackgrounded=true(Index 检查后暂停新 stage)
// onForeground → AppStorage 写 appBackgrounded false(恢复)
// 对照设计 §6.5

import { UIAbility, AbilityConstant, Want, Configuration, ConfigurationConstant } from '@kit.AbilityKit';
import { window, display, router } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { notificationManager } from '@kit.NotificationKit';
import { BusinessError } from '@kit.BasicServicesKit';
import BuildProfile from 'BuildProfile';
import { getSkillManager, rescheduleCronTasksOnStartup, getChatKvStore, resetWorkspaceManager } from '../di/AppContainer.ets';
import { loadDisplaySetting } from '@amber/chat-domain';
import type { DisplaySetting } from '@amber/chat-domain';
import { ACCENT, applyTheme, setAccentColor, BG } from '../design/tokens.ets';
import { themePackStore, applyCurrentTheme } from '../design/theme_packs.ets';
import { registerAmberFonts, registerAmberFontsFor } from '../design/fonts.ets';
import { syncMacGatewayPushTokenOnLaunch } from '../platform_impl/MacGatewayClient.ets';
import { startGenerationKeepAlive, stopGenerationKeepAlive } from '../platform_impl/BackgroundGenerationKeepAlive.ets';
import { hydrateWorkspaceCustomDir, activateWorkspaceCustomDir, currentWorkspaceCustomDir } from '../platform_impl/WorkspaceStore.ets';
import { runNativeTokenCounterFixture } from '../platform_impl/NativeTokenCounterFixture.ets';

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

// ===== R23:统一 Want 深链派发 =====
// 根因:onNewWant 只写 AppStorage,唯一消费者是根页 ChatListPage;栈顶为
// ChatPage/设置页时前台 Want 不被消费,未消费参数还会被后续 Want 覆盖。
// 机制:EntryAbility 把校验后的目标写入待处理槽并自增序列号;onNewWant 与窗口
// loadContent 成功(markWantRoutingReady)都触发 routePendingWant,经全局 router
// 从任意栈顶 push 目标页;仅 pushUrl 成功且序列号未被新 Want 顶替时才清参数,
// 旧跳的迟到回调不得清掉新参数;冷启动在 loadContent 前只挂起、不发跳。
const WANT_ROUTE_SEQ_KEY: string = 'wantRouteSeq';
const WANT_ROUTE_READY_KEY: string = 'wantRouteReady';
let wantRouteInFlight: boolean = false;

const clearPendingWantRoute = (): void => {
  AppStorage.setOrCreate<string>('wantTopicId', '');
  AppStorage.setOrCreate<string>('wantTitle', '');
  AppStorage.setOrCreate<string>('wantNovelProjectId', '');
  AppStorage.setOrCreate<string>('wantNovelBranchId', '');
  AppStorage.setOrCreate<string>('wantNovelJobId', '');
  AppStorage.setOrCreate<number>(WANT_ROUTE_SEQ_KEY, 0);
};

// 消费 AppStorage 里的待处理深链。ready 前 / 无 pending / 跳转在途 → 空操作。
export const routePendingWant = (): void => {
  if (!(AppStorage.get<boolean>(WANT_ROUTE_READY_KEY) ?? false)) return;
  if (wantRouteInFlight) return;
  const seq: number = AppStorage.get<number>(WANT_ROUTE_SEQ_KEY) ?? 0;
  if (seq <= 0) return;
  const novelProjectId: string = AppStorage.get<string>('wantNovelProjectId') ?? '';
  const novelBranchId: string = AppStorage.get<string>('wantNovelBranchId') ?? '';
  const novelJobId: string = AppStorage.get<string>('wantNovelJobId') ?? '';
  const topicId: string = AppStorage.get<string>('wantTopicId') ?? '';
  const title: string = AppStorage.get<string>('wantTitle') ?? '';
  let targetUrl: string = '';
  const targetParams: Record<string, string> = {};
  if (novelProjectId.length > 0 && novelBranchId.length > 0 && novelJobId.length > 0) {
    targetUrl = 'pages/NovelWorkspacePage';
    targetParams['projectId'] = novelProjectId;
    targetParams['branchId'] = novelBranchId;
    targetParams['jobId'] = novelJobId;
    targetParams['tab'] = 'chapters';
  } else if (topicId.length > 0) {
    targetUrl = 'pages/DeepReadArticlePage';
    targetParams['topicId'] = topicId;
    targetParams['title'] = title;
  } else {
    clearPendingWantRoute();
    return;
  }
  wantRouteInFlight = true;
  try {
    router.pushUrl({ url: targetUrl, params: targetParams }).then((): void => {
      wantRouteInFlight = false;
      // sequence 守卫:跳转期间到达的新 Want 参数不得被旧跳清除
      if ((AppStorage.get<number>(WANT_ROUTE_SEQ_KEY) ?? 0) === seq) {
        clearPendingWantRoute();
        return;
      }
      routePendingWant();
    }).catch((e: Error): void => {
      wantRouteInFlight = false;
      // nav success 才清:pending 保留,下次 onNewWant/onPageShow 重试
      hilog.warn(DOMAIN, TAG, 'want route failed: %{public}s', String(e));
    });
  } catch (e) {
    wantRouteInFlight = false;
    hilog.warn(DOMAIN, TAG, 'want route threw: %{public}s', String(e));
  }
};

// 路由就绪(窗口 loadContent 成功)后才允许消费;并补跳冷启动挂起的 Want。
export const markWantRoutingReady = (): void => {
  AppStorage.setOrCreate<boolean>(WANT_ROUTE_READY_KEY, true);
  routePendingWant();
};

// 校验后的目标写入待处理槽 + 自增序列号。三类语义:
//   1) 完整目标(topic 或 novel 三键齐全)→ 落参 + seq++ + 派发。
//   2) 含 topic/novel 路由字段但**不完整**(如只有 novelProjectId)→ 这是一个新的
//      畸形意图,作废旧 pending(wantRouteSeq=0),避免用户被误路由到上一条合法目标;
//      既有 seq 守卫保证旧的在途 push 迟到回调不会再清/跳。
//   3) 完全不含路由字段的普通激活(如无参数点图标、或只有无关参数)→ **保留** pending,
//      不机械清;否则冷启动已挂起的目标会被一次无关激活吞掉。
export const publishWantRoute = (
  topicId: string, title: string,
  novelProjectId: string, novelBranchId: string, novelJobId: string,
): void => {
  const hasNovel: boolean =
    novelProjectId.length > 0 && novelBranchId.length > 0 && novelJobId.length > 0;
  const hasTopic: boolean = topicId.length > 0;
  if (!hasNovel && !hasTopic) {
    const declaredRoute: boolean = topicId.length > 0 || title.length > 0
      || novelProjectId.length > 0 || novelBranchId.length > 0 || novelJobId.length > 0;
    if (declaredRoute) clearPendingWantRoute();
    return;
  }
  if (hasNovel) {
    AppStorage.setOrCreate<string>('wantNovelProjectId', novelProjectId);
    AppStorage.setOrCreate<string>('wantNovelBranchId', novelBranchId);
    AppStorage.setOrCreate<string>('wantNovelJobId', novelJobId);
    AppStorage.setOrCreate<string>('wantTopicId', '');
    AppStorage.setOrCreate<string>('wantTitle', '');
  } else {
    AppStorage.setOrCreate<string>('wantTopicId', topicId);
    // title 无条件覆写(含空):仅在有值时写入会残留上一条通知的旧标题
    AppStorage.setOrCreate<string>('wantTitle', title);
    AppStorage.setOrCreate<string>('wantNovelProjectId', '');
    AppStorage.setOrCreate<string>('wantNovelBranchId', '');
    AppStorage.setOrCreate<string>('wantNovelJobId', '');
  }
  AppStorage.setOrCreate<number>(WANT_ROUTE_SEQ_KEY,
    (AppStorage.get<number>(WANT_ROUTE_SEQ_KEY) ?? 0) + 1);
  routePendingWant();
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
    if (BuildProfile.DEBUG) runNativeTokenCounterFixture(want);
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
    if (BuildProfile.DEBUG) runNativeTokenCounterFixture(want);
  }

  private routeFromWant(want: Want): void {
    // 从 want.parameters 提取 DeepRead 或 Novel 的稳定 id；无效/超长参数忽略。
    const params: Record<string, object> | undefined = want.parameters as Record<string, object>;
    if (params === undefined || params === null) {
      return;
    }
    // R23:校验后在统一待处理槽落参并触发派发(ready 前只挂起,冷启动由
    // loadContent 成功回调补跳;前台任意栈顶由 onNewWant 立即派发)。
    publishWantRoute(
      validatedWantParameter(params['topicId']),
      validatedWantParameter(params['title']),
      validatedWantParameter(params['novelProjectId']),
      validatedWantParameter(params['novelBranchId']),
      validatedWantParameter(params['novelJobId']),
    );
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
          syncMacGatewayPushTokenOnLaunch();
          // R23:窗口内容就绪 = 全局 router 可用;此时补跳冷启动挂起的 Want
          //   (onCreate 已写待处理槽,但那时 router 尚未就绪,不能发跳)。
          markWantRoutingReady();
        }
      });
    };
    getChatKvStore().get('ui_color_mode').then((mode: string | null): Promise<DisplaySetting> => {
      const resolvedDark: boolean = mode === 'dark' || (mode !== 'light' && this.systemDarkMode);
      // D2-1:自选工作区目录 hydrate + 授权激活;完成后若目录非空,
      //   reset 可能已被竞态(cron 启动)用默认根创建的管理器单例
      hydrateWorkspaceCustomDir(getChatKvStore())
        .then((): Promise<void> => activateWorkspaceCustomDir(getChatKvStore()))
        .then((): void => {
          if (currentWorkspaceCustomDir().length > 0) resetWorkspaceManager();
        })
        .catch((): void => {});
      // 主题包:先 hydrate(KV 选中包/导入库),再按包应用;applyCurrentTheme 内含 applyTheme 全套
      themePackStore.hydrate(getChatKvStore()).then((): void => {
        applyCurrentTheme(resolvedDark);
        AppStorage.setOrCreate<string>('themePackId', themePackStore.current().id);
        AppStorage.setOrCreate<boolean>('homeDotGrid', themePackStore.current().dotGrid);
        // 包可能换 BG:窗口底色按包再落一次(同步那次仍是默认纸色)
        applyWindowTheme(resolvedDark);
      }).catch((): void => {
        applyTheme(resolvedDark);
      });
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
      applyCurrentTheme(isDark);
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
    // 后台生成保活对称释放(回到前台即不需要长时任务)
    stopGenerationKeepAlive(this.context).catch((): void => {});
  }

  onBackground(): void {
    hilog.info(DOMAIN, TAG, 'onBackground — 切后台暂停');
    AppStorage.setOrCreate<boolean>('appBackgrounded', true);
    // 生成中切后台:DATA_TRANSFER 长时任务保活,流式继续(C6-1)
    const generating: boolean = AppStorage.get<boolean>('chatGenerating') ?? false;
    if (generating) {
      startGenerationKeepAlive(this.context).then((ok: boolean): void => {
        if (!ok) hilog.warn(DOMAIN, TAG, 'generation keep-alive unavailable, stream may suspend');
      }).catch((): void => {});
    }
  }
}
