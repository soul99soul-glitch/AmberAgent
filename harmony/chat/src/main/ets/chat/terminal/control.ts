import type { AbortSignalLike } from '@amber/deepread-domain';
import type { JsonObject } from '../json.ts';
import { TerminalError } from './profile_store.ts';

class TerminalSignal implements AbortSignalLike {
  aborted: boolean = false;
  private listeners: Array<() => void> = [];
  addEventListener(_type: string, listener: () => void): void { this.listeners.push(listener); }
  removeEventListener(_type: string, listener: () => void): void {
    this.listeners = this.listeners.filter((v): boolean => v !== listener);
  }
  abort(): void {
    if (this.aborted) return;
    this.aborted = true;
    for (const listener of this.listeners.slice()) listener();
  }
}
export class TerminalController {
  readonly signal: TerminalSignal = new TerminalSignal();
  abort(): void { this.signal.abort(); }
}
export const checkAbort = (signal?: AbortSignalLike): void => {
  if (signal?.aborted) throw new TerminalError('cancelled');
};
export const relayAbort = (source: AbortSignalLike | undefined, onAbort: () => void): (() => void) => {
  source?.addEventListener?.('abort', onAbort);
  if (source?.aborted) onAbort();
  return (): void => { source?.removeEventListener?.('abort', onAbort); };
};
export const terminalDelay = (ms: number, signal?: AbortSignalLike): Promise<void> => new Promise((resolve, reject) => {
  if (signal?.aborted) { reject(new TerminalError('cancelled')); return; }
  const abort: () => void = (): void => { clearTimeout(timer); detach(); reject(new TerminalError('cancelled')); };
  const timer = setTimeout((): void => { detach(); resolve(); }, ms);
  let detach: () => void = (): void => undefined;
  detach = relayAbort(signal, abort);
});
export const terminalAwait = <T>(operation: Promise<T>, signal: AbortSignalLike): Promise<T> => new Promise((resolve, reject) => {
  let settled: boolean = false;
  let detach: () => void = (): void => undefined;
  const abort: () => void = (): void => {
    if (settled) return;
    settled = true; detach(); reject(new TerminalError('cancelled'));
  };
  detach = relayAbort(signal, abort);
  if (settled) detach();
  operation.then((value: T): void => {
    if (settled) return;
    settled = true; detach(); resolve(value);
  }, (error: Error): void => {
    if (settled) return;
    settled = true; detach(); reject(error);
  });
});
export interface TerminalFailure {
  code: string;
  message: string;
  hopIndex?: number;
  hopCount?: number;
  profileId?: string;
  expectedFingerprintSHA256?: string;
  presentedFingerprintSHA256?: string;
  details?: JsonObject | null;
}
export const terminalFailure = (error: Error): TerminalFailure => {
  const coded = error as TerminalError & {
    hopIndex?: number;
    hopCount?: number;
    profileId?: string;
    expectedFingerprintSHA256?: string;
    presentedFingerprintSHA256?: string;
  };
  const code: string = coded.code ?? 'terminal_error';
  const details: JsonObject | null = coded.details ?? null;
  const hopIndex: number | undefined = typeof coded.hopIndex === 'number'
    ? coded.hopIndex
    : (typeof details?.['hop_index'] === 'number' ? details['hop_index'] as number : undefined);
  const hopCount: number | undefined = typeof coded.hopCount === 'number'
    ? coded.hopCount
    : (typeof details?.['hop_count'] === 'number' ? details['hop_count'] as number : undefined);
  const profileId: string | undefined = typeof coded.profileId === 'string' && coded.profileId.length > 0
    ? coded.profileId
    : (typeof details?.['profile_id'] === 'string' ? details['profile_id'] as string : undefined);
  const expectedFp: string | undefined = typeof coded.expectedFingerprintSHA256 === 'string' && coded.expectedFingerprintSHA256.length > 0
    ? coded.expectedFingerprintSHA256
    : (typeof details?.['expected_fingerprint'] === 'string' ? details['expected_fingerprint'] as string : undefined);
  const presentedFp: string | undefined = typeof coded.presentedFingerprintSHA256 === 'string' && coded.presentedFingerprintSHA256.length > 0
    ? coded.presentedFingerprintSHA256
    : (typeof details?.['presented_fingerprint'] === 'string' ? details['presented_fingerprint'] as string : undefined);

  const out: TerminalFailure = { code, message: 'Remote SSH: ' + code };
  if (hopIndex !== undefined) out.hopIndex = hopIndex;
  if (hopCount !== undefined) out.hopCount = hopCount;
  if (profileId !== undefined) out.profileId = profileId;
  if (expectedFp !== undefined) out.expectedFingerprintSHA256 = expectedFp;
  if (presentedFp !== undefined) out.presentedFingerprintSHA256 = presentedFp;
  if (details !== null) out.details = details;
  return out;
};
