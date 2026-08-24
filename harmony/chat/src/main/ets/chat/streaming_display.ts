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
// 速度平滑系数(StreamingDisplay.kt STREAM_DISPLAY_SPEED_ALPHA=0.08f)
export const STREAM_DISPLAY_SPEED_ALPHA: number = 0.08;
const STREAM_DISPLAY_TARGET_DRAIN_SECONDS: number = 0.90;
const STREAM_DISPLAY_FINAL_TARGET_DRAIN_SECONDS: number = 0.18;
const STREAM_DISPLAY_MAX_BACKLOG_CHARS: number = 1200;
const STREAM_DISPLAY_MAX_CHARS_PER_EMIT: number = 8;
const STREAM_DISPLAY_FINAL_MAX_CHARS_PER_EMIT: number = 20;

// 状态驱动(StreamingDisplayState)的注入时间语义:
//   调用方(定时器/帧回调)把 nowMs 传入 advance(),本模块零时钟依赖。
// 帧间最小发射间隔(Android STREAM_DISPLAY_MIN_EMIT_INTERVAL_NANOS=8_000_000 → 8ms)
export const STREAM_DISPLAY_MIN_EMIT_INTERVAL_MS: number = 8;
// deltaSeconds 钳制(Android coerceIn(1ms, 100ms) → 秒)
export const STREAM_DISPLAY_MIN_FRAME_DELTA_MS: number = 1;
export const STREAM_DISPLAY_MAX_FRAME_DELTA_MS: number = 100;
// 首帧时间增量(Android lastFrameNanos==0 → 1/60s 占位)
export const STREAM_DISPLAY_INITIAL_FRAME_DELTA_SECONDS: number = 1 / 60;

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

// streamingNextCodePointEnd:从 offset 前进一个完整 code point(Android nextCodePointEnd)
export const streamingNextCodePointEnd = (str: string, offset: number): number => {
  if (offset >= str.length) return str.length;
  return Math.min(offset + charCount(codePointAt(str, offset)), str.length);
};

// streamingImmediateDisplayText:安全截断到终端安全位置(:284-291)
export const streamingImmediateDisplayText = (content: string): string => {
  const safeEnd: number = safeStreamingTerminalEnd(content);
  return safeEnd === content.length ? content : content.substring(0, safeEnd);
};

// streamingTailActiveWhen:流式标记(:35-36)
export const streamingTailActiveWhen = (streaming: boolean): boolean => streaming;

// ===== StreamingDisplayState:可注入时间的逐字 reveal 状态驱动 =====
//
// 对应 Android rememberStreamingDisplayText(StreamingDisplay.kt:39-221)的
// 帧循环纯逻辑部分,但把「时间」从 withFrameNanos 时钟解耦:
//   - 调用方(ArkUI setInterval/帧回调)每次把当前时间 nowMs 传入 advance();
//   - 模块内 deltaSeconds 由 lastFrameMs 与钳制常量计算,不依赖全局时钟。
// 语义忠实对齐 Android 帧循环:
//   - 首次调用(start/snap 之后)按 1/60s 占位 delta(Android lastFrameNanos==0);
//   - backlog 超上限 → streamingDisplayBacklogCatchUpEnd 硬 snap(safeEnd 切片);
//   - 平滑 emit:budget += speed*delta(cap=maxCharsPerEmit),取整发射,
//     并受最小发射间隔(8ms)约束,发射后 budget 减掉已用额度;
//   - target 内容被替换(前缀失配/变短)→ 立即 settle 到完整新内容(Android 同路径);
//   - stream=false:终态 drain(FINAL 速率/每 emit 20 字符,FINAL_TARGET_DRAIN=0.18s),
//   - 切片恒走 safeStreamingDisplayEnd(代理类可注入,测试注入恒等代理验证预算)。
// 零 UI/零 IO/零定时器,可单测。

/** 注入可选的切分/发射判定(测试用:验证预算记账,不改变可见长度) */
export interface StreamingDisplaySliceProxy {
  safeEnd(candidate: number): number;
  nextCodePointEnd(offset: number): number;
}

// 默认走真实 Unicode 安全切分(safeStreamingDisplayEnd / streamingNextCodePointEnd)。
// 仅测试注入代理时切换为外部实现(恒等代理用于验证预算记账)。

/** 状态驱动(纯逻辑,ArkTS API 12 兼容:无 !、无 object spread、无 JS 私有#) */
export class StreamingDisplayState {
  /** 当前可见长度 */
  visibleLength: number = 0;
  /** 当前目标内容 */
  content: string = '';
  /** 平滑后的速度(cps) */
  speed: number = STREAM_DISPLAY_BASE_CHARS_PER_SEC;
  /** 剩余发射预算 */
  budget: number = 0;
  /** 自本实例开始跟随以来是否见过 streaming=true(Android sawStreaming) */
  sawStreaming: boolean = false;
  /** 上一帧时间(ms),0 = 尚未有帧 */
  private lastFrameMs: number = 0;
  /** 上次成功发射时间(ms),0 = 未发射过 */
  private lastEmitMs: number = 0;
  /** 切分代理(测试可注入;null = 使用真实 Unicode 安全切分) */
  private sliceProxy: StreamingDisplaySliceProxy | null;

  constructor(sliceProxy: StreamingDisplaySliceProxy | null = null) {
    this.sliceProxy = sliceProxy;
  }

  /** 安全终点(代理注入时用代理,否则真实 safeStreamingDisplayEnd) */
  private safeEndOf(content: string, candidate: number): number {
    if (this.sliceProxy !== null) return this.sliceProxy.safeEnd(candidate);
    return safeStreamingDisplayEnd(content, candidate);
  }

  /** 下一个完整 code point 终点(代理注入时用代理,否则真实) */
  private nextCodePointEndOf(content: string, offset: number): number {
    if (this.sliceProxy !== null) return this.sliceProxy.nextCodePointEnd(offset);
    return streamingNextCodePointEnd(content, offset);
  }

  /** 是否正处在终态 drain(streaming=false 但 backlog 未清)——可见内容恒为前缀 */
  private drainingAfterStream(): boolean {
    return this.sawStreaming && this.visibleLength < this.content.length;
  }

  /** 硬 snap 到指定安全位置(直接发可见帧) */
  private snap(targetLength: number): void {
    const safeEnd: number = this.safeEndOf(this.content, Math.min(targetLength, this.content.length));
    this.visibleLength = Math.max(0, Math.min(safeEnd, this.content.length));
    this.speed = STREAM_DISPLAY_BASE_CHARS_PER_SEC;
    this.budget = 0;
  }

  /**
   * 推进一帧。
   * @param content 最新目标内容
   * @param streaming 是否仍在流式
   * @param nowMs 当前时间(注入,单调递增即可)
   * @returns true 表示本帧发出了新的可见内容(调用方可触发重绘)
   */
  advance(content: string, streaming: boolean, nowMs: number): boolean {
    if (content.length === 0) {
      this.content = '';
      this.visibleLength = 0;
      this.speed = STREAM_DISPLAY_BASE_CHARS_PER_SEC;
      this.budget = 0;
      this.lastFrameMs = 0;
      this.lastEmitMs = 0;
      return false;
    }
    if (this.content !== content) {
      // 内容更新:target 已替换。用「当前可见前缀文本」(上一 target 的子串)判定:
      // 对齐 Android `visible.length > target.length || !target.startsWith(visible)`。
      const oldVisibleText: string = this.content.substring(0, this.visibleLength);
      if (this.visibleLength > content.length || !content.startsWith(oldVisibleText)) {
        // 前缀失配/变短 → 立即 settle 到完整新内容(Android 同路径:visible=target)
        this.content = content;
        this.snap(content.length);
        this.lastFrameMs = nowMs;
        this.lastEmitMs = nowMs;
        return true;
      }
      this.content = content;
    }
    if (streaming && !this.sawStreaming) {
      this.sawStreaming = true;
    }
    const draining: boolean = this.drainingAfterStream();
    if (!streaming && !draining) {
      // settled:任何遗留可见长度偏差立即对齐(含从未流式的实例收到内容修正)
      if (this.visibleLength !== this.content.length) {
        this.snap(this.content.length);
        return true;
      }
      return false;
    }
    // === 逐字 pacing 帧 ===
    let deltaSeconds: number = STREAM_DISPLAY_INITIAL_FRAME_DELTA_SECONDS;
    if (this.lastFrameMs > 0) {
      const deltaMs: number = Math.max(
        STREAM_DISPLAY_MIN_FRAME_DELTA_MS,
        Math.min(STREAM_DISPLAY_MAX_FRAME_DELTA_MS, nowMs - this.lastFrameMs),
      );
      deltaSeconds = deltaMs / 1000;
    }
    this.lastFrameMs = nowMs;
    const backlog: number = this.content.length - this.visibleLength;
    if (backlog <= 0) return false;

    // catch-up:backlog 超上限 → 硬 snap 到安全位置(对齐 catchUpEnd 语义)
    const catchUpEnd: number = streamingDisplayBacklogCatchUpEnd(this.visibleLength, this.content.length);
    if (catchUpEnd > this.visibleLength) {
      const safeEnd: number = this.safeEndOf(this.content, Math.min(catchUpEnd, this.content.length));
      if (safeEnd > this.visibleLength) {
        this.visibleLength = Math.min(safeEnd, this.content.length);
        this.budget = 0;
        this.lastEmitMs = nowMs;
        return true;
      }
    }

    const targetSpeed: number = streamingDisplayTargetSpeed(backlog, streaming);
    this.speed += (targetSpeed - this.speed) * STREAM_DISPLAY_SPEED_ALPHA;
    const maxCharsPerEmit: number = streamingDisplayMaxCharsPerEmit(streaming);
    this.budget += this.speed * deltaSeconds;
    if (this.budget > maxCharsPerEmit) this.budget = maxCharsPerEmit;
    const releaseCount: number = Math.min(Math.floor(this.budget), maxCharsPerEmit);
    if (releaseCount <= 0) return false;
    if (this.lastEmitMs > 0 && nowMs - this.lastEmitMs < STREAM_DISPLAY_MIN_EMIT_INTERVAL_MS) {
      return false;
    }
    const nextEnd: number = this.safeEndOf(this.content, this.visibleLength + releaseCount);
    const safeEnd: number = nextEnd > this.visibleLength
      ? nextEnd
      : this.nextCodePointEndOf(this.content, this.visibleLength);
    this.visibleLength = Math.min(safeEnd, this.content.length);
    this.budget -= releaseCount;
    this.lastEmitMs = nowMs;
    return true;
  }

  /**
   * 重置状态(新实例语义:visible/content/speed 全部回到初值)。
   */
  reset(): void {
    this.visibleLength = 0;
    this.content = '';
    this.speed = STREAM_DISPLAY_BASE_CHARS_PER_SEC;
    this.budget = 0;
    this.sawStreaming = false;
    this.lastFrameMs = 0;
    this.lastEmitMs = 0;
  }
}
