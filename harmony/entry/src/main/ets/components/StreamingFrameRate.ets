import native from 'libamber_native.so';
import { deviceInfo } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

// Request native VSync only while a visible chat is generating. No per-frame JS
// callbacks or state updates are added to the Markdown/scroll path.
export class StreamingFrameRate {
  private active: boolean = false;

  update(active: boolean): void {
    if (this.active === active) return;
    this.active = active;
    if (!canIUse('SystemCapability.Graphic.Graphic2D.NativeVsync') || deviceInfo.sdkApiVersion < 20) return;
    const result: number = native.setStreamingFrameRateActive(active);
    if (result !== 0) {
      hilog.warn(0xD003900, 'StreamingFrameRate', 'native request failed: %{public}d', result);
    }
  }
}
