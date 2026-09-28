import type { AbortSignalLike } from '@amber/deepread-domain';
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
// File creation has no cancel API. Stop awaiting it on abort and consume its late settlement;
// the caller must never continue startup from that abandoned result.
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
export interface TerminalFailure { code: string; message: string; }
export const terminalFailure = (error: Error): TerminalFailure => {
  const coded: TerminalError = error as TerminalError;
  const code: string = coded.code ?? 'terminal_error';
  // Transport/secret-store errors may originate outside the domain. Never print arbitrary dumps.
  return { code, message: 'Remote SSH: ' + code };
};
