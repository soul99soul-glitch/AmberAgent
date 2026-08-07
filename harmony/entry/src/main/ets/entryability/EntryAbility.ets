// EntryAbility — Phase 7 切后台暂停/回前台续跑
// onBackground → AppStorage 写 appBackgrounded=true(Index 检查后暂停新 stage)
// onForeground → AppStorage 写 appBackgrounded false(恢复)
// 对照设计 §6.5

import { UIAbility, AbilityConstant, Want } from '@kit.AbilityKit';
import { window, display, font } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { getSkillManager, rescheduleCronTasksOnStartup, getChatKvStore } from '../di/AppContainer.ets';
import { loadDisplaySetting } from '@amber/chat-domain';
import type { DisplaySetting } from '@amber/chat-domain';
import { applyTheme, setAccentColor, BG } from '../design/tokens.ets';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.EntryAbility';
const MAX_WANT_PARAMETER_LENGTH: number = 512;

const validatedWantParameter = (value: object | undefined): string => {
  if (value === undefined || typeof value !== 'string') return '';
  const trimmed: string = (value as string).trim();
  return trimmed.length > 0 && trimmed.length <= MAX_WANT_PARAMETER_LENGTH ? trimmed : '';
};

export default class EntryAbility extends UIAbility {
  onCreate(want: Want, _launchParam: AbilityConstant.LaunchParam): void {
    hilog.info(DOMAIN, TAG, 'onCreate');
    // 注册 JetBrains Mono(设计系统等宽字体), 供 fontFamily('JetBrainsMono') 使用
    try {
      font.registerFont({ familyName: 'JetBrainsMono', familySrc: $rawfile('fonts/JetBrainsMono-Regular.ttf') });
      font.registerFont({ familyName: 'JetBrainsMonoBold', familySrc: $rawfile('fonts/JetBrainsMono-Bold.ttf') });
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'registerFont failed: %{public}s', String(e));
    }
    AppStorage.setOrCreate<boolean>('appBackgrounded', false);
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
    }
    if (title.length > 0) {
      AppStorage.setOrCreate<string>('wantTitle', title);
    }
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    // 沉浸式(同步, 必须在 loadContent 前完成, 否则页面首帧读到 statusBarHeight=0):
    //   全屏布局使状态栏后面=窗口背景; 窗口背景=BG; 状态栏透明 → 无缝
    try {
      const win: window.Window = windowStage.getMainWindowSync();
      // 先读 avoid area(全屏前), 再全屏; 全屏后 TYPE_SYSTEM 可能返回 0
      const avoidBefore: window.AvoidArea = win.getWindowAvoidArea(window.AvoidAreaType.TYPE_SYSTEM);
      win.setWindowLayoutFullScreen(true);
      const avoidAfter: window.AvoidArea = win.getWindowAvoidArea(window.AvoidAreaType.TYPE_SYSTEM);
      win.setWindowBackgroundColor('#F4F2EC'); // = BG token
      win.setWindowSystemBarEnable(['status', 'navigation']);
      // 取非零值; px2vp 在 Ability 上下文不可用 → 用窗口 density 手动换算; 仍为0兜底44vp
      let density: number = 3;
      try { density = display.getDefaultDisplaySync().densityPixels; } catch (_e) { density = 3; }
      let topPx: number = avoidBefore.topRect.height > 0 ? avoidBefore.topRect.height : avoidAfter.topRect.height;
      let topVp: number = topPx > 0 ? Math.round(topPx / density) : 44;
      AppStorage.setOrCreate<number>('statusBarHeight', topVp);
      hilog.info(DOMAIN, TAG, 'statusBar px=%{public}d density=%{public}f vp=%{public}d', topPx, density, topVp);
      win.setWindowSystemBarProperties({
        statusBarColor: '#00000000',
        statusBarContentColor: '#1B1A17',
        navigationBarColor: '#00000000',
        navigationBarContentColor: '#1B1A17',
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
        }
      });
    };
    getChatKvStore().get('ui_color_mode').then((mode: string | null): Promise<DisplaySetting> => {
      applyTheme(mode === 'dark');
      AppStorage.setOrCreate<boolean>('darkMode', mode === 'dark');
      try {
        const win: window.Window = windowStage.getMainWindowSync();
        win.setWindowBackgroundColor(BG);
        win.setWindowSystemBarProperties({
          statusBarColor: '#00000000',
          statusBarContentColor: mode === 'dark' ? '#ECE8DF' : '#1B1A17',
          navigationBarColor: '#00000000',
          navigationBarContentColor: mode === 'dark' ? '#ECE8DF' : '#1B1A17',
        });
      } catch (e) {
        hilog.warn(DOMAIN, TAG, 'theme window apply failed: %{public}s', String(e));
      }
      return loadDisplaySetting(getChatKvStore());
    }).then((s: DisplaySetting): void => {
      if (s.accentColor.length > 0) setAccentColor(s.accentColor);
      start();
    }).catch((): void => { start(); });
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
