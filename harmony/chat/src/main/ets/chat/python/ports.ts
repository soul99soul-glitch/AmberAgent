import type { AbortSignalLike } from '@amber/deepread-domain';
import type { PythonExecuteOptions, PythonNativeResult } from './models.ts';

export interface PythonTransportPort {
  version(): string;
  // The adapter adds the app-owned resourceRoot and relays abort to pythonCancel.
  // It must settle only after the native execution has actually stopped.
  execute(requestId: string, options: PythonExecuteOptions, signal?: AbortSignalLike): Promise<PythonNativeResult>;
}
