// EntryAbility — HarmonyOS Stage 模型 UIAbility 入口
// 对照设计文档 §7.2 入口路由:
//   - Share/deep link: onCreate/onNewWant 收 URL → deriveTopicId({url}) → 跳 DeepReadArticlePage
//   - 手动输入: DeepReadHomePage 输入标题 → deriveTopicId({title}) → 跳 DeepReadArticlePage
//   - 通知跳转: §6.7(Phase 7)
//
// ArkTS 严格模式:无 any、不用全局可变状态(用 AppStorage)。

import { UIAbility, AbilityConstant, Want } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { deriveTopicId } from '@amber/deepread-domain';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.EntryAbility';

interface RouteParams {
  url?: string;
  title?: string;
}

// 推导 topicId 并写入 AppStorage,供首页/文章页读取
const routeFromWant = (want: Want): void => {
  const params = want.parameters as Record<string, object> | undefined;
  let url: string | null = null;
  let title: string | null = null;
  if (params !== undefined && params !== null) {
    const u = params.url;
    const t = params.title;
    if (typeof u === 'string' && u.length > 0) url = u;
    if (typeof t === 'string' && t.length > 0) title = t;
  }

  // 对照 §7.2:URL 优先 hash
  const topicId: string | null = url !== null
    ? deriveTopicId({ url })
    : (title !== null ? deriveTopicId({ title }) : null);

  if (topicId !== null) {
    AppStorage.setOrCreate<string>('pendingTopicId', topicId);
    if (title !== null) AppStorage.setOrCreate<string>('pendingTopicTitle', title);
    if (url !== null) AppStorage.setOrCreate<string>('pendingSeedUrl', url);
  }
  // 显式标注 RouteParams 未使用(占位,Phase 7 Want 契约扩展用)
  const _routeParams: RouteParams = {};
  void _routeParams;
};

export default class EntryAbility extends UIAbility {
  onCreate(want: Want, _launchParam: AbilityConstant.LaunchParam): void {
    hilog.info(DOMAIN, TAG, 'onCreate');
    routeFromWant(want);
  }

  onNewWant(want: Want, _launchParam: AbilityConstant.LaunchParam): void {
    hilog.info(DOMAIN, TAG, 'onNewWant');
    routeFromWant(want);
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    // 主入口:DeepReadHomePage(Index)。若 pendingTopicId 存在,首页会自动跳文章页。
    windowStage.loadContent('pages/Index', (err: Error) => {
      if (err !== null && err !== undefined) {
        hilog.error(DOMAIN, TAG, 'loadContent failed: %{public}s', JSON.stringify(err));
      }
    });
  }

  // 切后台/前台:§6.5 — 不主动暂停(让当前 stage 跑完),下一 stage 边界检查 isBackgrounded。
  // 真实调度由 scheduler.setBackgrounded(topicId, ...) 触发(在 ArticlePage 订阅生命周期)。
  onForeground(): void {
    hilog.info(DOMAIN, TAG, 'onForeground');
    AppStorage.setOrCreate<boolean>('appBackgrounded', false);
  }

  onBackground(): void {
    hilog.info(DOMAIN, TAG, 'onBackground');
    AppStorage.setOrCreate<boolean>('appBackgrounded', true);
  }
}
