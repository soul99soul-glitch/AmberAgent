import type { AbortSignalLike } from '@amber/deepread-domain';

// Normal generation and regeneration release their retry wait when the run stops.
export const waitForGenerationRetry = (
  delayMs: number, sleep: (ms: number) => Promise<void>, signal?: AbortSignalLike,
): Promise<void> => {
  if (signal === undefined || signal.addEventListener === undefined) return sleep(delayMs);
  if (signal.aborted) return Promise.resolve();
  return new Promise<void>((resolve, reject): void => {
    let settled: boolean = false;
    const finish = (): void => {
      if (settled) return;
      settled = true;
      signal.removeEventListener?.('abort', finish);
      resolve();
    };
    signal.addEventListener?.('abort', finish);
    if (signal.aborted) {
      finish();
      return;
    }
    sleep(delayMs).then(finish).catch((error: Error): void => {
      if (settled) return;
      settled = true;
      signal.removeEventListener?.('abort', finish);
      reject(error);
    });
  });
};
