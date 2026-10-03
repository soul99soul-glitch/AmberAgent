// iOS's critically damped spring, with a native-layout burst speed ceiling.
// ArkUI can measure several newly mounted paragraphs together. Keep ordinary
// line growth unchanged, but limit catch-up to 12vp/60Hz frame (8vp at 90Hz).
// Position/velocity survive target growth; dt comes from the native frame clock.
export interface NativeTimelineSpringSample {
  position: number;
  velocity: number;
}

const MAX_FOLLOW_SPEED: number = 720; // vp per second

export const nativeTimelineSpringStep = (
  position: number, velocity: number, target: number, deltaSeconds: number,
): NativeTimelineSpringSample => {
  if (position > target) return { position: target, velocity: 0 };
  const omega: number = 2 / 0.06;
  const dt: number = Math.max(0, deltaSeconds);
  const decay: number = Math.exp(-omega * dt);
  const error: number = position - target;
  const coupling: number = (velocity + omega * error) * dt;
  const next: number = target + (error + coupling) * decay;
  if (next > target) return { position: target, velocity: 0 };
  return {
    position: Math.min(next, position + MAX_FOLLOW_SPEED * dt),
    velocity: Math.min(MAX_FOLLOW_SPEED, (velocity - omega * coupling) * decay),
  };
};
