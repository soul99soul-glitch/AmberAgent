import type { AbortSignalLike } from '../platform/runtime_api.ts';
import type { CouncilApprovalRequest, CouncilSeatRunKey, CouncilToolSnapshot,
  CouncilToolVerdict } from './models.ts';

interface ApprovalWaiter {
  request: CouncilApprovalRequest;
  signal: AbortSignalLike;
  promise: Promise<CouncilToolVerdict>;
  resolve: (verdict: CouncilToolVerdict) => void;
  reject: (error: Error) => void;
  abort: () => void;
  submitted: boolean;
}

export const councilSeatKey = (key: CouncilSeatRunKey): string =>
  JSON.stringify([key.runId, key.round, key.seatId]);

const requestKey = (request: CouncilApprovalRequest): string => JSON.stringify([
  request.key.runId, request.key.round, request.key.seatId, request.conversationId,
  request.messageId, request.partIndex, request.toolCallId, request.subjectHash,
]);

const sameRequest = (left: CouncilApprovalRequest, right: CouncilApprovalRequest): boolean =>
  requestKey(left) === requestKey(right) && JSON.stringify(left.part) === JSON.stringify(right.part);

export const copyCouncilToolSnapshot = (snapshot: CouncilToolSnapshot): CouncilToolSnapshot =>
  JSON.parse(JSON.stringify(snapshot)) as CouncilToolSnapshot;

// C's saved source supplies the hash. Here we check its exact parent locator and
// pending state; only C reloads and recomputes the package-child subject before IO.
export const validCouncilPending = (snapshot: CouncilToolSnapshot): boolean => {
  const seen: Set<string> = new Set();
  for (const request of snapshot.pending) {
    if (councilSeatKey(request.key) !== councilSeatKey(snapshot.key)
      || request.conversationId !== snapshot.conversationId || request.subjectHash.length === 0
      || request.toolCallId.length === 0 || !Number.isInteger(request.partIndex) || request.partIndex < 0) return false;
    const message = snapshot.messages.find(item => item.id === request.messageId);
    const part = message?.parts[request.partIndex];
    if (part === undefined || part.type !== 'tool' || part.toolCallId !== request.toolCallId
      || part.approvalState.type !== 'pending' || part.output.length > 0
      || JSON.stringify(part) !== JSON.stringify(request.part)) return false;
    const id: string = JSON.stringify([request.messageId, request.partIndex]);
    if (seen.has(id)) return false;
    seen.add(id);
  }
  return true;
};

export class CouncilApprovalWaiters {
  private entries: Map<string, ApprovalWaiter> = new Map();

  register(snapshot: CouncilToolSnapshot, signal: AbortSignalLike): void {
    const seatId: string = councilSeatKey(snapshot.key);
    const ids: Set<string> = new Set(snapshot.pending.map(requestKey));
    this.entries.forEach((waiter: ApprovalWaiter, id: string): void => {
      if (councilSeatKey(waiter.request.key) === seatId && !ids.has(id)) {
        this.remove(id, waiter, new Error('approval subject changed'));
      }
    });
    for (const request of snapshot.pending) {
      const id: string = requestKey(request);
      const current: ApprovalWaiter | undefined = this.entries.get(id);
      if (current !== undefined) {
        if (current.signal !== signal || !sameRequest(current.request, request))
          throw new Error('approval source changed without a new subject hash');
        continue;
      }
      let resolveVerdict: (verdict: CouncilToolVerdict) => void = (): void => {};
      let rejectVerdict: (error: Error) => void = (): void => {};
      const promise: Promise<CouncilToolVerdict> = new Promise((resolve, reject): void => {
        resolveVerdict = resolve; rejectVerdict = reject;
      });
      // Other saved pending parents may not yet be awaited by C's sequential
      // continuation. Keep cancellation handled while their own await still rejects.
      promise.catch((): void => {});
      const waiter: ApprovalWaiter = {
        request, signal, promise, resolve: resolveVerdict, reject: rejectVerdict,
        submitted: false,
        abort: (): void => { this.remove(id, waiter, new Error('aborted')); },
      };
      this.entries.set(id, waiter);
      signal.addEventListener?.('abort', waiter.abort);
      if (signal.aborted) waiter.abort();
    }
  }

  wait(request: CouncilApprovalRequest, signal: AbortSignalLike): Promise<CouncilToolVerdict> {
    const waiter: ApprovalWaiter | undefined = this.entries.get(requestKey(request));
    if (signal.aborted || waiter === undefined || waiter.signal !== signal
      || !sameRequest(waiter.request, request)) return Promise.reject(new Error('approval is no longer pending'));
    return waiter.promise;
  }

  submit(request: CouncilApprovalRequest, verdict: CouncilToolVerdict): boolean {
    const waiter: ApprovalWaiter | undefined = this.entries.get(requestKey(request));
    if (waiter === undefined || waiter.signal.aborted || waiter.submitted
      || !sameRequest(waiter.request, request)) return false;
    if ((verdict.kind !== 'approved' && verdict.kind !== 'denied' && verdict.kind !== 'answered')
      || typeof verdict.reason !== 'string' || (verdict.kind === 'answered' && typeof verdict.answer !== 'string')) return false;
    waiter.submitted = true;
    waiter.signal.removeEventListener?.('abort', waiter.abort);
    waiter.resolve({ kind: verdict.kind, reason: verdict.reason,
      answer: verdict.kind === 'answered' ? verdict.answer : null });
    return true;
  }

  clearSeat(key: CouncilSeatRunKey): void {
    const seatId: string = councilSeatKey(key);
    this.entries.forEach((waiter: ApprovalWaiter, id: string): void => {
      if (councilSeatKey(waiter.request.key) === seatId) this.remove(id, waiter, new Error('seat ended'));
    });
  }

  private remove(id: string, waiter: ApprovalWaiter, error: Error): void {
    this.entries.delete(id);
    waiter.signal.removeEventListener?.('abort', waiter.abort);
    waiter.reject(error);
  }
}
