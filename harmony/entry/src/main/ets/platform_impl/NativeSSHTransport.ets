import native from 'libamber_native.so';
import type { AbortSignalLike } from '@amber/deepread-domain';
import type {
  SSHTransportPort, SSHProbeOptions, SSHProbeResult, SSHConnectionOptions,
  SSHExecOptions, SSHPtyOptions, SSHHandle, SSHReadPacket, SSHCloseReason,
} from '@amber/chat-domain';

class SSHTransportError extends Error {
  public readonly code: string;

  constructor(code: string) {
    super(code === 'cancelled' ? 'SSH request cancelled' : 'SSH unavailable in background');
    this.code = code;
  }
}

const requestStopped = (signal?: AbortSignalLike): SSHTransportError | null => {
  if (AppStorage.get<boolean>('appBackgrounded') === true) {
    return new SSHTransportError('disconnected');
  }
  return signal?.aborted === true ? new SSHTransportError('cancelled') : null;
};

// Native registers requestId synchronously. Keep cancellation attached until the
// result has been checked, including the race where a cancelled start returns a handle.
const withRequest = async <T>(
  requestId: string, signal: AbortSignalLike | undefined,
  start: () => Promise<T>, discard: (result: T, reason: SSHCloseReason) => Promise<void>,
): Promise<T> => {
  const before: SSHTransportError | null = requestStopped(signal);
  if (before !== null) throw before;
  let started: boolean = false;
  let cancelled: boolean = false;
  const abort = (): void => {
    if (cancelled) return;
    cancelled = true;
    if (started) native.sshCancel(requestId).catch((): void => {});
  };
  signal?.addEventListener?.('abort', abort);
  try {
    const afterRegistration: SSHTransportError | null = requestStopped(signal);
    if (afterRegistration !== null || cancelled) {
      throw afterRegistration ?? new SSHTransportError('cancelled');
    }
    started = true;
    const result: T = await start();
    const afterStart: SSHTransportError | null = requestStopped(signal);
    if (afterStart !== null || cancelled) {
      const stopped: SSHTransportError = afterStart ?? new SSHTransportError('cancelled');
      await discard(result, stopped.code === 'disconnected' ? 'disconnected' : 'cancelled');
      throw stopped;
    }
    return result;
  } finally {
    signal?.removeEventListener?.('abort', abort);
  }
};

const closeDiscardedHandle = async (handle: SSHHandle, reason: SSHCloseReason): Promise<void> => {
  await native.sshClose(handle, reason);
};

export const createNativeSSHTransport = (): SSHTransportPort => ({
  probe: (requestId: string, options: SSHProbeOptions,
    signal?: AbortSignalLike): Promise<SSHProbeResult> =>
    withRequest<SSHProbeResult>(requestId, signal,
      (): Promise<SSHProbeResult> => native.sshProbe(requestId, options),
      async (_result: SSHProbeResult, _reason: SSHCloseReason): Promise<void> => {}),

  startExec: (requestId: string, connection: SSHConnectionOptions,
    options: SSHExecOptions, signal?: AbortSignalLike): Promise<SSHHandle> =>
    withRequest<SSHHandle>(requestId, signal,
      (): Promise<SSHHandle> => native.sshStartExec(requestId, connection, options), closeDiscardedHandle),

  startPty: (requestId: string, connection: SSHConnectionOptions,
    options: SSHPtyOptions, signal?: AbortSignalLike): Promise<SSHHandle> =>
    withRequest<SSHHandle>(requestId, signal,
      (): Promise<SSHHandle> => native.sshStartPty(requestId, connection, options), closeDiscardedHandle),

  read: async (handle: SSHHandle, maxBytes: number): Promise<SSHReadPacket> => native.sshRead(handle, maxBytes),
  write: (handle: SSHHandle, bytes: Uint8Array): Promise<void> => native.sshWrite(handle, bytes),
  resize: (handle: SSHHandle, columns: number, rows: number): Promise<void> =>
    native.sshResize(handle, columns, rows),
  close: (handle: SSHHandle, reason: SSHCloseReason): Promise<SSHReadPacket> => native.sshClose(handle, reason),
});
