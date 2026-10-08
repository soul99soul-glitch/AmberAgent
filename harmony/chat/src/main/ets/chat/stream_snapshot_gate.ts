// One publication window for UI and raw checkpoints. Read the authoritative
// accumulator only when publishing; chunks in the window need no snapshots.
export class StreamSnapshotGate {
  private lastPublishedAt: number | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private pending: boolean = false;
  private closed: boolean = false;

  constructor(
    private readonly intervalMs: number,
    private readonly nowMs: () => number,
    private readonly publish: () => void,
  ) {}

  changed(): void {
    if (this.closed) return;
    this.pending = true;
    const now: number = this.nowMs();
    if (this.lastPublishedAt === null || now - this.lastPublishedAt >= this.intervalMs) {
      this.cancelTimer();
      this.flush();
      return;
    }
    if (this.timer !== null) return;
    this.timer = setTimeout((): void => {
      this.timer = null;
      if (!this.closed) this.flush();
    }, this.intervalMs - (now - this.lastPublishedAt));
  }

  // Close before draining: queued provisional updates cannot cover a final/reset UI.
  // Return whether this flush also supplied the terminal raw checkpoint.
  finish(): boolean {
    this.closed = true;
    this.cancelTimer();
    const wasPending: boolean = this.pending;
    this.flush();
    return wasPending;
  }

  private flush(): void {
    if (!this.pending) return;
    this.pending = false;
    this.lastPublishedAt = this.nowMs();
    this.publish();
  }

  private cancelTimer(): void {
    if (this.timer === null) return;
    clearTimeout(this.timer);
    this.timer = null;
  }
}
