import native from 'libamber_native.so';
import { fileIo } from '@kit.CoreFileKit';
import type { Context } from '@kit.AbilityKit';
import type { AbortSignalLike } from '@amber/deepread-domain';
import type { PythonTransportPort, PythonExecuteOptions, PythonNativeResult } from '@amber/chat-domain';

interface PythonResourceOptions extends PythonExecuteOptions { resourceRoot: string; }

const PYTHON_FILES: string[] = ['python314.zip', 'amber_python.py'];

// These are bundled interpreter data, never Agent-provided paths or source files.
const deployPythonResources = async (): Promise<string> => {
  const context: Context | undefined = AppStorage.get<Context>('abilityContext');
  if (context === undefined) throw new Error('python_context_unavailable');
  const root: string = `${context.filesDir}/amberagent/python`;
  if (!await fileIo.access(root)) await fileIo.mkdir(root, true);
  for (const name of PYTHON_FILES) {
    const bytes: Uint8Array = context.resourceManager.getRawFileContentSync(`python/${name}`);
    const path: string = `${root}/${name}`;
    const temporary: string = `${path}.tmp`;
    const file: fileIo.File = await fileIo.open(temporary,
      fileIo.OpenMode.WRITE_ONLY | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
    try {
      let offset: number = 0;
      while (offset < bytes.byteLength) {
        const remaining: ArrayBuffer = (bytes.buffer as ArrayBuffer)
          .slice(bytes.byteOffset + offset, bytes.byteOffset + bytes.byteLength);
        const written: number = await fileIo.write(file.fd, remaining);
        if (written <= 0 || written > remaining.byteLength) throw new Error('python_resource_short_write');
        offset += written;
      }
    } finally { await fileIo.close(file); }
    fileIo.renameSync(temporary, path);
  }
  return root;
};

const notStarted = (): PythonNativeResult => ({
  status: 'cancelled', exitCode: null, stdout: '', stderr: '', errorCode: 'cancelled',
});

const requestStopped = (signal?: AbortSignalLike): boolean =>
  signal?.aborted === true || AppStorage.get<boolean>('appBackgrounded') === true;

export const createNativePythonTransport = async (): Promise<PythonTransportPort> => {
  const resourceRoot: string = await deployPythonResources();
  return {
    version: (): string => native.pythonVersion(),
    execute: async (requestId: string, options: PythonExecuteOptions,
      signal?: AbortSignalLike): Promise<PythonNativeResult> => {
      if (requestStopped(signal)) return notStarted();
      let started: boolean = false;
      let cancelled: boolean = false;
      const abort = (): void => {
        if (cancelled) return;
        cancelled = true;
        if (started) native.pythonCancel(requestId);
      };
      signal?.addEventListener?.('abort', abort);
      try {
        if (cancelled || requestStopped(signal)) {
          return notStarted();
        }
        const request: PythonResourceOptions = {
          source: options.source, stdin: options.stdin, timeoutMs: options.timeoutMs, resourceRoot: resourceRoot,
        };
        started = true;
        // Cancellation remains attached until the interpreter has actually returned.
        return await native.pythonExecute(requestId, request);
      } finally { signal?.removeEventListener?.('abort', abort); }
    },
  };
};
