// WorkScheduler only advances one durable polish chapter per callback. The repository/runner
// remain the sole state machine; this extension owns no UI or in-memory progress.

import { WorkSchedulerExtensionAbility, workScheduler } from '@kit.BackgroundTasksKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { getNovelCreation } from '../di/AppContainer.ets';

const DOMAIN: number = 0x0001;
const TAG: string = 'Novel.PolishWork';
const MAX_PARAMETER_LENGTH: number = 512;

interface NovelPolishWorkParameters {
  projectId: string;
  branchId: string;
  jobId: string;
}

const workString = (
  parameters: Record<string, number | string | boolean> | undefined, key: string,
): string => {
  if (parameters === undefined) return '';
  const value: number | string | boolean | undefined = parameters[key];
  if (typeof value !== 'string') return '';
  const trimmed: string = value.trim();
  return trimmed.length > 0 && trimmed.length <= MAX_PARAMETER_LENGTH ? trimmed : '';
};

const workParameters = (work: workScheduler.WorkInfo): NovelPolishWorkParameters | null => {
  const projectId: string = workString(work.parameters, 'projectId');
  const branchId: string = workString(work.parameters, 'branchId');
  const jobId: string = workString(work.parameters, 'jobId');
  if (projectId.length === 0 || branchId.length === 0 || jobId.length === 0) return null;
  return { projectId, branchId, jobId };
};

export default class NovelPolishWorkAbility extends WorkSchedulerExtensionAbility {
  onWorkStart(work: workScheduler.WorkInfo): void {
    AppStorage.setOrCreate('abilityContext', this.context);
    const parameters: NovelPolishWorkParameters | null = workParameters(work);
    if (parameters === null) {
      hilog.error(DOMAIN, TAG, 'reject invalid work parameters');
      return;
    }
    void getNovelCreation().drivePolishInBackground(
      parameters.projectId, parameters.branchId, parameters.jobId, 1,
    ).catch((error: Error): void => {
      hilog.error(DOMAIN, TAG, 'drive failed: %{public}s', String(error));
    });
  }

  onWorkStop(work: workScheduler.WorkInfo): void {
    AppStorage.setOrCreate('abilityContext', this.context);
    const parameters: NovelPolishWorkParameters | null = workParameters(work);
    if (parameters === null) return;
    void getNovelCreation().yieldPolishForSystem(parameters.projectId, parameters.jobId)
      .catch((error: Error): void => {
        hilog.warn(DOMAIN, TAG, 'yield failed: %{public}s', String(error));
      });
  }
}
