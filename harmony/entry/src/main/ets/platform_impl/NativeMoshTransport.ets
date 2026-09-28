import native from 'libamber_native.so';
import type { AbortSignalLike } from '@amber/deepread-domain';
import type { MoshTransportPort, MoshOptions, MoshHandle, MoshPacket, MoshCloseReason } from '@amber/chat-domain';

class MoshTransportError extends Error {
  public readonly code: string;
  constructor(code: string) {
    super(code === 'cancelled' ? 'Mosh request cancelled' : 'Mosh unavailable in background');
    this.code = code;
  }
}

const requestStopped = (signal?: AbortSignalLike): MoshTransportError | null => {
  if (AppStorage.get<boolean>('appBackgrounded') === true) return new MoshTransportError('disconnected');
  return signal?.aborted === true ? new MoshTransportError('cancelled') : null;
};

export const createNativeMoshTransport = (): MoshTransportPort => ({
  start: async (requestId: string, options: MoshOptions, signal?: AbortSignalLike): Promise<MoshHandle> => {
    const before: MoshTransportError | null = requestStopped(signal);
    if (before !== null) throw before;
    let started: boolean = false;
    let cancelled: boolean = false;
    const abort = (): void => {
      if (cancelled) return;
      cancelled = true;
      if (started) native.moshCancel(requestId);
    };
    signal?.addEventListener?.('abort', abort);
    try {
      const registered: MoshTransportError | null = requestStopped(signal);
      if (registered !== null || cancelled) throw registered ?? new MoshTransportError('cancelled');
      started = true;
      const handle: MoshHandle = await native.moshStart(requestId, options);
      const stopped: MoshTransportError | null = requestStopped(signal);
      if (stopped !== null || cancelled) {
        const reason: MoshTransportError = stopped ?? new MoshTransportError('cancelled');
        await native.moshClose(handle, reason.code === 'disconnected' ? 'disconnected' : 'cancelled');
        throw reason;
      }
      return handle;
    } finally { signal?.removeEventListener?.('abort', abort); }
  },
  read: async (handle: MoshHandle, maxBytes: number): Promise<MoshPacket> => native.moshRead(handle, maxBytes),
  write: (handle: MoshHandle, bytes: Uint8Array): Promise<void> => native.moshWrite(handle, bytes),
  resize: (handle: MoshHandle, columns: number, rows: number): Promise<void> => native.moshResize(handle, columns, rows),
  close: (handle: MoshHandle, reason: MoshCloseReason): Promise<MoshPacket> => native.moshClose(handle, reason),
});
