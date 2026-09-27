// BackgroundGenerationKeepAlive — 后台生成保活(C6-1)
//
// DATA_TRANSFER 长时任务(backgroundTaskManager,API 12;KEEP_BACKGROUND_RUNNING
// normal 档声明即授权):生成中切后台 → startBackgroundRunning 保进程不被挂起,
// 流式生成继续;回前台/生成结束 → stopBackgroundRunning。
// 同一 UIAbility 同时仅一个长时任务(taskRunning 单槽跟踪)。
// 实况窗进度通知 = 后续梯队(v1 无进度更新,系统约 10 分钟无进展可能取消任务,登记)。

import { backgroundTaskManager } from '@kit.BackgroundTasksKit';
import { wantAgent } from '@kit.AbilityKit';
import type { Context } from '@kit.AbilityKit';
import type { WantAgent } from '@ohos.wantAgent';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.BgKeepAlive';

let taskRunning: boolean = false;

// 生成中切后台:申请数据传输长时任务(通知点击回 EntryAbility)
export const startGenerationKeepAlive = async (context: Context): Promise<boolean> => {
  if (taskRunning) return true;
  try {
    const want: WantAgent = await wantAgent.getWantAgent({
      wants: [{ bundleName: 'app.amber.deepread', abilityName: 'EntryAbility' }],
      actionType: wantAgent.OperationType.START_ABILITY,
      requestCode: 0,
    });
    await backgroundTaskManager.startBackgroundRunning(
      context, backgroundTaskManager.BackgroundMode.DATA_TRANSFER, want);
    taskRunning = true;
    hilog.info(DOMAIN, TAG, 'generation keep-alive started');
    return true;
  } catch (e) {
    hilog.warn(DOMAIN, TAG, 'startBackgroundRunning failed: %{public}s', String(e));
    return false;
  }
};

export const stopGenerationKeepAlive = async (context: Context): Promise<void> => {
  if (!taskRunning) return;
  try {
    await backgroundTaskManager.stopBackgroundRunning(context);
    hilog.info(DOMAIN, TAG, 'generation keep-alive stopped');
  } catch (e) {
    hilog.warn(DOMAIN, TAG, 'stopBackgroundRunning failed: %{public}s', String(e));
  }
  taskRunning = false;
};

export const generationKeepAliveRunning = (): boolean => taskRunning;
