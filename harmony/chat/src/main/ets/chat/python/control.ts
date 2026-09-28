import type { AbortSignalLike } from '@amber/deepread-domain';

export class PythonError extends Error {
  constructor(readonly code: string) { super('Python: ' + code); }
}
class PythonSignal implements AbortSignalLike {
  aborted: boolean = false;
  private listeners: Array<() => void> = [];
  addEventListener(_type: string, listener: () => void): void { this.listeners.push(listener); }
  removeEventListener(_type: string, listener: () => void): void {
    this.listeners = this.listeners.filter((current): boolean => current !== listener);
  }
  abort(): void {
    if (this.aborted) return;
    this.aborted = true;
    for (const listener of this.listeners.slice()) listener();
  }
}
export class PythonController {
  readonly signal: PythonSignal = new PythonSignal();
  abort(): void { this.signal.abort(); }
}
export const relayPythonAbort = (source: AbortSignalLike | undefined, target: PythonController): (() => void) => {
  const abort: () => void = (): void => target.abort();
  source?.addEventListener?.('abort', abort);
  if (source?.aborted) abort();
  return (): void => { source?.removeEventListener?.('abort', abort); };
};
export const pythonUTF8Size = (value: string): number => {
  let size: number = 0;
  for (let i: number = 0; i < value.length; i++) {
    const cp: number = value.charCodeAt(i);
    if (cp < 0x80) size++;
    else if (cp < 0x800) size += 2;
    else if (cp >= 0xd800 && cp <= 0xdbff && value.charCodeAt(i + 1) >= 0xdc00 && value.charCodeAt(i + 1) <= 0xdfff) {
      size += 4; i++;
    } else size += 3;
  }
  return size;
};
