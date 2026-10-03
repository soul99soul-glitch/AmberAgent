import { getAppBundleName } from './ProductIdentity.ets';
// NovelPolishWorkScheduler — C7 WorkScheduler 平台壳。
// 官方约束：API 9+、系统择机触发、单次回调最长 2 分钟；因此这里只注册/取消
// durable job 的恢复信号，绝不把 WorkScheduler 当成业务状态机或长驻进程。

import { workScheduler } from '@kit.BackgroundTasksKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0x0001;
const TAG: string = 'Novel.PolishScheduler';

export const NOVEL_POLISH_WORK_ABILITY: string = 'NovelPolishWorkAbility';

export interface NovelPolishWorkKey {
  projectId: string;
  branchId: string;
  jobId: string;
}

export const novelPolishWorkId = (jobId: string): number => {
  let hash: number = 5381;
  for (let i: number = 0; i < jobId.length; i++) {
    hash = ((hash << 5) + hash + jobId.charCodeAt(i)) & 0x7fffffff;
  }
  return hash === 0 ? 1 : hash;
};

const workInfoFor = (key: NovelPolishWorkKey): workScheduler.WorkInfo => {
  const parameters: Record<string, number | string | boolean> = {};
  parameters['projectId'] = key.projectId;
  parameters['branchId'] = key.branchId;
  parameters['jobId'] = key.jobId;
  return {
    workId: novelPolishWorkId(key.jobId),
    bundleName: getAppBundleName(),
    abilityName: NOVEL_POLISH_WORK_ABILITY,
    networkType: workScheduler.NetworkType.NETWORK_TYPE_ANY,
    isRepeat: false,
    isPersisted: true,
    parameters,
  };
};

export const scheduleNovelPolishWork = (key: NovelPolishWorkKey): void => {
  try {
    workScheduler.startWork(workInfoFor(key));
  } catch (error) {
    hilog.error(DOMAIN, TAG, 'startWork failed: %{public}s', String(error));
  }
};

export const cancelNovelPolishWork = (key: NovelPolishWorkKey): void => {
  try {
    workScheduler.stopWork(workInfoFor(key), true);
  } catch (error) {
    hilog.warn(DOMAIN, TAG, 'stopWork failed: %{public}s', String(error));
  }
};
