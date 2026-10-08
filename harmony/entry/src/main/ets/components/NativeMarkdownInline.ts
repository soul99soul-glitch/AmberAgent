// Pure patch/fade driver. It changes a stable native surface, never reparses Markdown.
export interface NativeInlineRun { kind: string; text: string; url: string; }
export interface NativeInlineSurface {
  append(runs: NativeInlineRun[], opacity: number): void;
  replace(start: number, count: number, runs: NativeInlineRun[], opacity: number): void;
  setOpacity(start: number, runs: NativeInlineRun[], opacity: number): void;
  publish(contentChanged: boolean): void;
}
export interface NativeInlineFadeRange { start: number; length: number; opacity: number; }
interface NativeInlineFadeUnit {
  start: number;
  length: number;
  startedAt: number;
  duration: number;
  opacity: number;
}

const runLength = (runs: NativeInlineRun[]): number => {
  let length: number = 0;
  for (const run of runs) length += run.text.length;
  return length;
};

const matchingPrefix = (previous: NativeInlineRun[], next: NativeInlineRun[], matchStyle: boolean): number => {
  let oldIndex: number = 0;
  let newIndex: number = 0;
  let oldOffset: number = 0;
  let newOffset: number = 0;
  let prefix: number = 0;
  let lastCode: number = 0;
  while (oldIndex < previous.length && newIndex < next.length) {
    const oldRun: NativeInlineRun = previous[oldIndex];
    const newRun: NativeInlineRun = next[newIndex];
    if (oldOffset === oldRun.text.length) { oldIndex++; oldOffset = 0; continue; }
    if (newOffset === newRun.text.length) { newIndex++; newOffset = 0; continue; }
    if (matchStyle && (oldRun.kind !== newRun.kind || oldRun.url !== newRun.url)) break;
    if (oldOffset === 0 && newRun.text.startsWith(oldRun.text, newOffset)) {
      const count: number = oldRun.text.length;
      lastCode = oldRun.text.charCodeAt(count - 1);
      prefix += count;
      oldIndex++; oldOffset = 0; newOffset += count;
      continue;
    }
    if (oldRun.text[oldOffset] !== newRun.text[newOffset]) break;
    lastCode = oldRun.text.charCodeAt(oldOffset);
    oldOffset++; newOffset++; prefix++;
  }
  // Do not split a UTF-16 surrogate pair when replacing an edited tail.
  if (lastCode >= 0xD800 && lastCode <= 0xDBFF) prefix--;
  return prefix;
};

export const nativeInlineSlice = (runs: NativeInlineRun[], start: number, length: number): NativeInlineRun[] => {
  const result: NativeInlineRun[] = [];
  let offset: number = 0;
  for (const run of runs) {
    const from: number = Math.max(0, start - offset);
    const to: number = Math.min(run.text.length, start + length - offset);
    if (to > from) result.push({ kind: run.kind, text: run.text.substring(from, to), url: run.url });
    offset += run.text.length;
    if (offset >= start + length) break;
  }
  return result;
};

export class NativeInlineRenderer {
  private surface: NativeInlineSurface;
  private runs: NativeInlineRun[] = [];
  private length: number = 0;
  private units: NativeInlineFadeUnit[] = [];
  private lastFrame: number = -1;
  constructor(surface: NativeInlineSurface) { this.surface = surface; }

  update(runs: NativeInlineRun[], streaming: boolean): boolean {
    const length: number = runLength(runs);
    const prefix: number = matchingPrefix(this.runs, runs, true);
    const textPrefix: number = prefix === this.length ? prefix : matchingPrefix(this.runs, runs, false);
    if (prefix === this.length && prefix === length) return this.units.length > 0;
    if (prefix === this.length) {
      const count: number = length - prefix;
      this.surface.append(nativeInlineSlice(runs, prefix, count), streaming ? 0 : 1);
    } else {
      const kept: NativeInlineFadeUnit[] = [];
      for (const unit of this.units) {
        if (unit.start >= textPrefix) continue;
        unit.length = Math.min(unit.length, textPrefix - unit.start);
        kept.push(unit);
      }
      this.units = kept;
      this.surface.replace(prefix, this.length - prefix,
        nativeInlineSlice(runs, prefix, length - prefix), 1);
      // A style-only correction retains the current alpha of unchanged text.
      for (const unit of this.units) {
        this.surface.setOpacity(unit.start, nativeInlineSlice(runs, unit.start, unit.length), unit.opacity);
      }
      if (streaming && length > textPrefix) {
        this.surface.setOpacity(textPrefix, nativeInlineSlice(runs, textPrefix, length - textPrefix), 0);
      }
    }
    // Markdown delimiters can retype an in-flight tail. Like iOS, fade that
    // changed suffix too, while keeping the compatible prefix's existing clock.
    const addedLength: number = length - textPrefix;
    if (streaming && addedLength > 0) {
      this.units.push({ start: textPrefix, length: addedLength,
        startedAt: this.units.length > 0 ? this.lastFrame : -1,
        // iOS mounts a new paragraph with a 500ms entry fade. Only subsequent
        // appends scale by batch size; a large initial snapshot must not flash in.
        duration: this.length === 0 ? 500 : Math.min(500, Math.max(1000 / 30, 6000 / addedLength)), opacity: 0 });
    }
    this.runs = runs;
    this.length = length;
    this.surface.publish(true);
    return this.units.length > 0;
  }

  frame(timeMs: number): boolean {
    this.lastFrame = timeMs;
    let changed: boolean = false;
    const active: NativeInlineFadeUnit[] = [];
    for (const unit of this.units) {
      if (unit.startedAt < 0) unit.startedAt = timeMs;
      const t: number = Math.min(1, Math.max(0, (timeMs - unit.startedAt) / unit.duration));
      const inverse: number = 1 - t;
      const opacity: number = 3 * inverse * inverse * t * 0.1 + 3 * inverse * t * t + t * t * t;
      if (opacity !== unit.opacity) {
        this.surface.setOpacity(unit.start, nativeInlineSlice(this.runs, unit.start, unit.length), opacity);
        unit.opacity = opacity;
        changed = true;
      }
      if (t < 1) active.push(unit);
    }
    this.units = active;
    if (changed) this.surface.publish(false);
    return active.length > 0;
  }

  hasAnimation(): boolean { return this.units.length > 0; }

  activeFadeRanges(): NativeInlineFadeRange[] {
    return this.units.map((unit: NativeInlineFadeUnit): NativeInlineFadeRange => {
      return { start: unit.start, length: unit.length, opacity: unit.opacity };
    });
  }

  restyle(): void {
    if (this.length === 0) return;
    this.surface.setOpacity(0, this.runs, 1);
    for (const unit of this.units) {
      this.surface.setOpacity(unit.start, nativeInlineSlice(this.runs, unit.start, unit.length), unit.opacity);
    }
    this.surface.publish(true);
  }

  clear(): void { this.units = []; this.lastFrame = -1; }
}
