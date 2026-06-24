// EntryAbility — Phase 7 切后台暂停/回前台续跑
// onBackground → AppStorage 写 appBackgrounded=true(Index 检查后暂停新 stage)
// onForeground → AppStorage 写 appBackgrounded false(恢复)
// 对照设计 §6.5

import { UIAbility, AbilityConstant, Want } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.EntryAbility';

export default class EntryAbility extends UIAbility {
  onCreate(want: Want, _launchParam: AbilityConstant.LaunchParam): void {
    hilog.info(DOMAIN, TAG, 'onCreate');
    AppStorage.setOrCreate<boolean>('appBackgrounded', false);
    AppStorage.setOrCreate<Context>('abilityContext', this.context);
    // Want 契约(§7.2):分享/deep-link 收 URL/title → 写 AppStorage 供 Index 跳转
    this.routeFromWant(want);
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
    windowStage.loadContent('pages/Index', (err: Error) => {
      if (err !== null && err !== undefined) {
        hilog.error(DOMAIN, TAG, 'loadContent failed: %{public}s', JSON.stringify(err));
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
