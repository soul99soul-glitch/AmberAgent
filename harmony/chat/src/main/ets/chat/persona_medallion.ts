// persona_medallion — Muse 形象圆章 4 态动效、防抖稳定器与 4x4 Sprite Sheet 切片纯逻辑

export type AmberPersonaMood = 'idle' | 'running' | 'needs_input' | 'blocked';

export type AmberPersonaCanvasPreset = 'fox' | 'crystal';

export const PERSONA_KEYFRAME_COUNT: number = 4;
export const PERSONA_FRAME_INTERVAL_MS: number = 550;
export const PERSONA_SETTLE_DEBOUNCE_MS: number = 400;
export const PERSONA_BLOCKED_DWELL_MS: number = 2000;

export interface AmberPersonaMoodSignals {
  running: boolean;
  awaitingApproval: boolean;
  hasBlockedError: boolean;
}

export const resolveRawPersonaMood = (signals: AmberPersonaMoodSignals): AmberPersonaMood => {
  if (signals.hasBlockedError) return 'blocked';
  if (signals.awaitingApproval) return 'needs_input';
  if (signals.running) return 'running';
  return 'idle';
};

export const shouldFreezeMedallionAnimation = (
  appBackgrounded: boolean,
  reduceMotion: boolean,
  sheetOpen: boolean,
): boolean => appBackgrounded || reduceMotion || sheetOpen;

/**
 * 确定性帧序选择：冻结或不足 2 帧时恒返回第 0 帧；4 帧采用 [0,1,2,3,2,1] 往返呼吸序。
 */
export const computeMedallionFrameIndex = (
  step: number,
  frameCount: number,
  frozen: boolean,
): number => {
  if (frozen || frameCount <= 1) return 0;
  const safeStep: number = Math.max(0, Math.floor(step));
  if (frameCount === 2) return safeStep % 2;
  const cycle: number[] = [];
  for (let i: number = 0; i < frameCount; i++) cycle.push(i);
  for (let i: number = frameCount - 2; i >= 1; i--) cycle.push(i);
  return cycle[safeStep % cycle.length];
};

export interface PersonaMoodUpdate {
  mood: AmberPersonaMood;
  pendingTarget: AmberPersonaMood | null;
  recheckAfterMs: number | null;
}

/**
 * 顶栏形象情绪防抖稳定器：
 * - 普通状态切换（idle / running / needs_input）与进入 blocked 需经过 0.4s 稳定窗口，避免瞬时抖动；
 * - 一旦进入 blocked，至少驻留 2.0s 再回落，确保异常可见但切换对话时可立即 reset。
 */
export class PersonaMoodStabilizer {
  private currentMood: AmberPersonaMood = 'idle';
  private blockedEnteredAtMs: number = -1;
  private candidateMood: AmberPersonaMood | null = null;
  private candidateSinceMs: number = -1;

  reset(initial: AmberPersonaMood = 'idle'): void {
    this.currentMood = initial;
    this.blockedEnteredAtMs = initial === 'blocked' ? 0 : -1;
    this.candidateMood = null;
    this.candidateSinceMs = -1;
  }

  getCurrentMood(): AmberPersonaMood {
    return this.currentMood;
  }

  update(signals: AmberPersonaMoodSignals, nowMs: number): PersonaMoodUpdate {
    const raw: AmberPersonaMood = resolveRawPersonaMood(signals);

    // blocked 最小驻留 2.0s，防止瞬时错误闪屏
    if (this.currentMood === 'blocked' && raw !== 'blocked' && this.blockedEnteredAtMs >= 0) {
      const elapsedDwell: number = Math.max(0, nowMs - this.blockedEnteredAtMs);
      if (elapsedDwell < PERSONA_BLOCKED_DWELL_MS) {
        const remaining: number = PERSONA_BLOCKED_DWELL_MS - elapsedDwell;
        return {
          mood: this.currentMood,
          pendingTarget: raw,
          recheckAfterMs: remaining,
        };
      }
    }

    if (raw === this.currentMood) {
      this.candidateMood = null;
      this.candidateSinceMs = -1;
      return { mood: this.currentMood, pendingTarget: null, recheckAfterMs: null };
    }

    if (this.candidateMood !== raw || this.candidateSinceMs < 0) {
      this.candidateMood = raw;
      this.candidateSinceMs = nowMs;
      return {
        mood: this.currentMood,
        pendingTarget: raw,
        recheckAfterMs: PERSONA_SETTLE_DEBOUNCE_MS,
      };
    }

    const elapsedSettle: number = Math.max(0, nowMs - this.candidateSinceMs);
    if (elapsedSettle < PERSONA_SETTLE_DEBOUNCE_MS) {
      return {
        mood: this.currentMood,
        pendingTarget: raw,
        recheckAfterMs: PERSONA_SETTLE_DEBOUNCE_MS - elapsedSettle,
      };
    }

    this.currentMood = raw;
    this.blockedEnteredAtMs = raw === 'blocked' ? nowMs : -1;
    this.candidateMood = null;
    this.candidateSinceMs = -1;
    return { mood: this.currentMood, pendingTarget: null, recheckAfterMs: null };
  }
}

export interface SpriteSheetLayoutSpec {
  kind: 'quad4x4' | 'static';
  cols: number;
  rows: number;
  cellWidth: number;
  cellHeight: number;
}

export interface SpriteSheetRegion {
  x: number;
  y: number;
  width: number;
  height: number;
}

/**
 * 识别 4x4 Sprite Sheet（每行对应一个情绪态：idle / running / needs_input / blocked，每行 4 帧）。
 * 非方阵或分辨率过小的图片降级为单张静态图。
 */
export const detectPersonaSpriteSheetLayout = (
  imageWidth: number,
  imageHeight: number,
): SpriteSheetLayoutSpec => {
  if (imageWidth < 256 || imageHeight < 256) {
    return { kind: 'static', cols: 1, rows: 1, cellWidth: Math.max(0, imageWidth), cellHeight: Math.max(0, imageHeight) };
  }
  const ratio: number = imageWidth / imageHeight;
  if (ratio < 0.85 || ratio > 1.18) {
    return { kind: 'static', cols: 1, rows: 1, cellWidth: imageWidth, cellHeight: imageHeight };
  }
  const cellWidth: number = Math.floor(imageWidth / 4);
  const cellHeight: number = Math.floor(imageHeight / 4);
  if (cellWidth < 64 || cellHeight < 64) {
    return { kind: 'static', cols: 1, rows: 1, cellWidth: imageWidth, cellHeight: imageHeight };
  }
  return {
    kind: 'quad4x4',
    cols: 4,
    rows: 4,
    cellWidth,
    cellHeight,
  };
};

export const moodRowIndex = (mood: AmberPersonaMood): number => {
  if (mood === 'running') return 1;
  if (mood === 'needs_input') return 2;
  if (mood === 'blocked') return 3;
  return 0;
};

export const spriteSheetRegionForMoodFrame = (
  spec: SpriteSheetLayoutSpec,
  mood: AmberPersonaMood,
  frameIndex: number,
): SpriteSheetRegion => {
  if (spec.kind !== 'quad4x4') {
    return { x: 0, y: 0, width: spec.cellWidth, height: spec.cellHeight };
  }
  const row: number = Math.min(spec.rows - 1, Math.max(0, moodRowIndex(mood)));
  const col: number = Math.max(0, Math.floor(frameIndex)) % Math.max(1, spec.cols);
  return {
    x: col * spec.cellWidth,
    y: row * spec.cellHeight,
    width: spec.cellWidth,
    height: spec.cellHeight,
  };
};
