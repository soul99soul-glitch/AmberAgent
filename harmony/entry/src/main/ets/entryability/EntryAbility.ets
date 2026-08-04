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
    // 沉浸式: 全屏布局 + 透明状态栏/导航条 + 深色图标 + 窗口背景=BG
    //   让页面 BG 延伸到状态栏/导航条区域, 消除白色分界(顶部/底部沉浸)
    windowStage.getMainWindow().then((win: window.Window): void => {
      // 沉浸式: 全屏布局使状态栏后面=窗口背景; 窗口背景=BG; 状态栏透明 → 无缝
      //   ArkUI 默认内容避开安全区(在状态栏下方), 不会顶到状态栏后
      win.setWindowLayoutFullScreen(true);
      win.setWindowBackgroundColor('#F4F2EC'); // = BG token
      win.setWindowSystemBarEnable(['status', 'navigation']);
      // 全屏后内容顶到 y=0, 需避开状态栏: 存状态栏高度(vp)供页面顶部内边距使用
      const avoid: window.AvoidArea = win.getWindowAvoidArea(window.AvoidAreaType.TYPE_SYSTEM);
      const topPx: number = avoid.topRect.height;
      AppStorage.setOrCreate<number>('statusBarHeight', px2vp(topPx));
      win.setWindowSystemBarProperties({
        statusBarColor: '#00000000', // 透明, 露出窗口背景 BG
        statusBarContentColor: '#1B1A17', // 深色状态栏图标(BG 浅色)
        navigationBarColor: '#00000000',
        navigationBarContentColor: '#1B1A17',
      }).catch((e: BusinessError): void => {
        hilog.warn(DOMAIN, TAG, 'setWindowSystemBarProperties failed: %{public}s', String(e));
      });
    }).catch((e: BusinessError): void => {
      hilog.warn(DOMAIN, TAG, 'getMainWindow failed: %{public}s', String(e));
    });

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
