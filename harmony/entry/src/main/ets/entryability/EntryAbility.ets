// EntryAbility — Phase 7 切后台暂停/回前台续跑
// onBackground → AppStorage 写 appBackgrounded=true(Index 检查后暂停新 stage)
// onForeground → AppStorage 写 appBackgrounded false(恢复)
// 对照设计 §6.5

import { UIAbility, AbilityConstant, Want } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { getSkillManager, rescheduleCronTasksOnStartup } from '../di/AppContainer.ets';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.EntryAbility';

export default class EntryAbility extends UIAbility {
  onCreate(want: Want, _launchParam: AbilityConstant.LaunchParam): void {
    hilog.info(DOMAIN, TAG, 'onCreate');
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
    const topicIdVal: object | undefined = params['topicId'];
    const titleVal: object | undefined = params['title'];
    if (topicIdVal !== undefined && typeof topicIdVal === 'string') {
      AppStorage.setOrCreate<string>('wantTopicId', topicIdVal as string);
    }
    if (titleVal !== undefined && typeof titleVal === 'string') {
      AppStorage.setOrCreate<string>('wantTitle', titleVal as string);
    }
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    // 沉浸式(同步, 必须在 loadContent 前完成, 否则页面首帧读到 statusBarHeight=0):
    //   全屏布局使状态栏后面=窗口背景; 窗口背景=BG; 状态栏透明 → 无缝
    try {
      const win: window.Window = windowStage.getMainWindowSync();
      win.setWindowLayoutFullScreen(true);
      win.setWindowBackgroundColor('#F4F2EC'); // = BG token
      win.setWindowSystemBarEnable(['status', 'navigation']);
      // 全屏后内容顶到 y=0, 需避开状态栏: 存状态栏高度(vp)供页面顶部内边距
      const avoid: window.AvoidArea = win.getWindowAvoidArea(window.AvoidAreaType.TYPE_SYSTEM);
      const topVp: number = px2vp(avoid.topRect.height);
      AppStorage.setOrCreate<number>('statusBarHeight', topVp);
      hilog.info(DOMAIN, TAG, 'statusBarHeight vp=%{public}d', topVp);
      win.setWindowSystemBarProperties({
        statusBarColor: '#00000000',
        statusBarContentColor: '#1B1A17',
        navigationBarColor: '#00000000',
        navigationBarContentColor: '#1B1A17',
      });
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'immersive setup failed: %{public}s', String(e));
    }

    windowStage.loadContent('pages/Index', (err: BusinessError) => {
      if (err.code !== 0) {
        hilog.error(DOMAIN, TAG, 'loadContent failed: %{public}s', JSON.stringify(err));
      } else {
        hilog.info(DOMAIN, TAG, 'loadContent success');
      }
    });
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
