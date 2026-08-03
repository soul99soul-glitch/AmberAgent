// streaming_display — 流式文本显示的 pacing/buffer/safe-slice 纯逻辑
//
// Android 基准:
//   richtext/StreamingCharReveal.kt(95 行)— 逐字符出现时钟(纯逻辑,零 Compose 依赖)
//   richtext/StreamingDisplay.kt(数学部分 ~200 行)— pacing 速度/backlog 上限/
//     catch-up snap/safe-slice(UTF-16 surrogate/ZWJ/combining-mark 安全)
//   注意:StreamingDisplay.kt 的 Compose 驱动(withFrameNanos/LaunchedEffect/SideEffect)
//   不移植——ArkUI 侧用定时器/帧回调驱动这些纯函数。
//
// 纯逻辑:零 UI/零 IO,可单测。

// ===== 常量(StreamingDisplay.kt:223-245 逐字) =====

const STREAM_DISPLAY_BASE_CHARS_PER_SEC: number = 72;
const STREAM_DISPLAY_MAX_CHARS_PER_SEC: number = 1500;
const STREAM_DISPLAY_FINAL_MAX_CHARS_PER_SEC: number = 2400;
const STREAM_DISPLAY_SPEED_ALPHA: number = 0.08;
const STREAM_DISPLAY_TARGET_DRAIN_SECONDS: number = 0.90;
const STREAM_DISPLAY_FINAL_TARGET_DRAIN_SECONDS: number = 0.18;
const STREAM_DISPLAY_MAX_BACKLOG_CHARS: number = 1200;
const STREAM_DISPLAY_MAX_CHARS_PER_EMIT: number = 8;
const STREAM_DISPLAY_FINAL_MAX_CHARS_PER_EMIT: number = 20;

const ZERO_WIDTH_JOINER: number = 0x200D;

// combining mark 判定(简化:Unicode 范围 0x0300-0x036F + 常见附件标记)
const isAttachedMark = (codePoint: number): boolean => {
  if (codePoint >= 0x0300 && codePoint <= 0x036F) return true;
  if (codePoint >= 0x1AB0 && codePoint <= 0x1AFF) return true;
  if (codePoint >= 0x1DC0 && codePoint <= 0x1DFF) return true;
  if (codePoint >= 0x20D0 && codePoint <= 0x20FF) return true;
  if (codePoint >= 0xFE20 && codePoint <= 0xFE2F) return true;
  return false;
};

// UTF-16 codePoint at index(对齐 Java codePointAt)
const codePointAt = (str: string, index: number): number => {
  return str.codePointAt(index) ?? 0;
};

// codePoint 字符数(1 或 2,对齐 Java Character.charCount)
const charCount = (codePoint: number): number => {
  return codePoint > 0xFFFF ? 2 : 1;
};

// high/low surrogate 判定
const isHighSurrogate = (charCode: number): boolean => {
  return charCode >= 0xD800 && charCode <= 0xDBFF;
};
const isLowSurrogate = (charCode: number): boolean => {
  return charCode >= 0xDC00 && charCode <= 0xDFFF;
};

// ===== StreamingCharRevealClock(StreamingCharReveal.kt:19-95 逐字) =====

export class StreamingCharRevealClock {
  private appearNanos: Map<number, number> = new Map();
  private pruneFloor: number = Number.MIN_SAFE_INTEGER;

  stamp(
    suffixText: string,
    suffixSourceOffset: number,
    nowNanos: number,
    fadeNanos: number,
    staggerNanos: number,
    maxCascadeNanos: number,
    maxPending: number,
  ): void {
    if (suffixSourceOffset < this.pruneFloor) {
      this.appearNanos.clear();
    }
    if (suffixSourceOffset > this.pruneFloor) {
      const keys: number[] = [];
      this.appearNanos.forEach((_v: number, k: number): void => {
        if (k < suffixSourceOffset) keys.push(k);
      });
      for (const k of keys) this.appearNanos.delete(k);
    }
    this.pruneFloor = suffixSourceOffset;

    let newCount: number = 0;
    let i: number = 0;
    while (i < suffixText.length) {
      const cp: number = codePointAt(suffixText, i);
      if (!this.appearNanos.has(suffixSourceOffset + i)) newCount++;
      i += charCount(cp);
    }
    if (newCount === 0) return;

    const step: number = newCount <= 1 ? 0
      : Math.min(staggerNanos, maxCascadeNanos / (newCount - 1));
    const overflow: number = Math.max(newCount - maxPending, 0);
    let newIndex: number = 0;
    i = 0;
    while (i < suffixText.length) {
      const cp: number = codePointAt(suffixText, i);
      const key: number = suffixSourceOffset + i;
      if (!this.appearNanos.has(key)) {
        if (newIndex < overflow) {
          this.appearNanos.set(key, nowNanos - fadeNanos);
        } else {
          this.appearNanos.set(key, nowNanos + (newIndex - overflow) * step);
        }
        newIndex++;
      }
      i += charCount(cp);
    }
  }

  progressAt(absOffset: number, nowNanos: number, fadeNanos: number): number {
    const appear: number | undefined = this.appearNanos.get(absOffset);
    if (appear === undefined) return 1;
    if (fadeNanos <= 0) return 1;
    if (nowNanos <= appear) return 0;
    const progress: number = (nowNanos - appear) / fadeNanos;
    return progress >= 1 ? 1 : progress;
  }
}

// ===== StreamingDisplay 数学函数(StreamingDisplay.kt:247-360 纯逻辑部分) =====

export const streamingDisplayTargetSpeed = (
  backlog: number,
  streaming: boolean,
): number => {
  const maxCps: number = streaming ? STREAM_DISPLAY_MAX_CHARS_PER_SEC : STREAM_DISPLAY_FINAL_MAX_CHARS_PER_SEC;
  const drainSeconds: number = streaming
    ? STREAM_DISPLAY_TARGET_DRAIN_SECONDS
    : STREAM_DISPLAY_FINAL_TARGET_DRAIN_SECONDS;
  return Math.max(
    STREAM_DISPLAY_BASE_CHARS_PER_SEC,
    Math.min(maxCps, backlog / drainSeconds),
  );
};

export const streamingDisplayMaxCharsPerEmit = (streaming: boolean): number =>
  streaming ? STREAM_DISPLAY_MAX_CHARS_PER_EMIT : STREAM_DISPLAY_FINAL_MAX_CHARS_PER_EMIT;

export const streamingDisplayMaxBacklogChars = (): number => STREAM_DISPLAY_MAX_BACKLOG_CHARS;

export const streamingDisplayBacklogCatchUpEnd = (
  visibleLength: number,
  targetLength: number,
): number => {
  if (targetLength <= visibleLength) return Math.max(targetLength, 0);
  const oldestAllowed: number = targetLength - STREAM_DISPLAY_MAX_BACKLOG_CHARS;
  return Math.max(visibleLength, oldestAllowed);
};

// safeStreamingDisplayEnd:UTF-16/ZWJ/combining-mark 安全的截断位置(:293-314)
export const safeStreamingDisplayEnd = (str: string, candidate: number): number => {
  let end: number = Math.max(0, Math.min(candidate, str.length));
  if (end === 0 || end >= str.length) return end;
  if (isLowSurrogate(str.charCodeAt(end))) end++;
  while (end < str.length) {
    const prevCp: number = codePointAt(str, end - (end >= 2 && isLowSurrogate(str.charCodeAt(end - 1)) ? 2 : 1));
    if (prevCp === ZERO_WIDTH_JOINER) {
      end += charCount(codePointAt(str, end));
      continue;
    }
    const nextCp: number = codePointAt(str, end);
    if (nextCp === ZERO_WIDTH_JOINER) {
      end += charCount(nextCp);
      end += charCount(codePointAt(str, end));
      continue;
    }
    if (!isAttachedMark(nextCp)) break;
    end += charCount(nextCp);
  }
  return Math.min(end, str.length);
};

// safeStreamingTerminalEnd:从末尾往回找安全终点(:316-360)
export const safeStreamingTerminalEnd = (str: string): number => {
  let end: number = str.length;
  while (end > 0) {
    const last: number = str.charCodeAt(end - 1);
    if (isHighSurrogate(last)) { end--; continue; }
    if (isLowSurrogate(last) && (end === 1 || !isHighSurrogate(str.charCodeAt(end - 2)))) {
      end--; continue;
    }
    const lastCp: number = codePointAt(str, end - (end >= 2 && isLowSurrogate(str.charCodeAt(end - 1)) ? 2 : 1));
    if (lastCp === ZERO_WIDTH_JOINER || isAttachedMark(lastCp)) { end--; continue; }
    break;
  }
  return safeStreamingDisplayEnd(str, end);
};

// streamingImmediateDisplayText:安全截断到终端安全位置(:284-291)
export const streamingImmediateDisplayText = (content: string): string => {
  const safeEnd: number = safeStreamingTerminalEnd(content);
  return safeEnd === content.length ? content : content.substring(0, safeEnd);
};

// streamingTailActiveWhen:流式标记(:35-36)
export const streamingTailActiveWhen = (streaming: boolean): boolean => streaming;
