import { accessibility } from '@kit.AccessibilityKit';
import { deviceInfo } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

// The public reduce-animation setting starts at API 23. API 12 keeps normal motion.
// This observer belongs to the ability, rather than to a theme-remounted page.
export class DeepReadMotionPreferences {
  private active: boolean = false;
  private subscribed: boolean = false;
  private readonly onChanged = (reduced: boolean): void => {
    if (this.active) AppStorage.setOrCreate<boolean>('reduceMotion', reduced);
  };

  private supported(): boolean {
    return deviceInfo.sdkApiVersion >= 23
      && canIUse('SystemCapability.BarrierFree.Accessibility.Core');
  }

  start(): void {
    if (this.active) return;
    this.active = true;
    AppStorage.setOrCreate<boolean>('reduceMotion', false);
    if (!this.supported()) return;
    this.refresh();
    try {
      accessibility.onAnimationReduceStateChange(this.onChanged);
      this.subscribed = true;
    } catch (error) {
      hilog.warn(0x0000, 'DeepRead.Motion', 'reduce-animation observer unavailable: %{public}s', String(error));
    }
  }

  refresh(): void {
    if (!this.active || !this.supported()) return;
    try {
      AppStorage.setOrCreate<boolean>('reduceMotion', accessibility.isAnimationReduceEnabledSync());
    } catch (error) {
      hilog.warn(0x0000, 'DeepRead.Motion', 'reduce-animation read failed: %{public}s', String(error));
    }
  }

  stop(): void {
    this.active = false;
    if (!this.subscribed) return;
    this.subscribed = false;
    try { accessibility.offAnimationReduceStateChange(this.onChanged); }
    catch (error) {
      hilog.warn(0x0000, 'DeepRead.Motion', 'reduce-animation observer removal failed: %{public}s', String(error));
    }
  }
}
