import { vibrator } from '@kit.SensorServiceKit';
import { getProductKind } from '../platform_impl/ProductIdentity.ets';

// Touch feedback is an enhancement; unsupported motors must not block an action.
export class DeepReadHaptics {
  static selection(): void { DeepReadHaptics.pulse(10); }
  static success(): void { DeepReadHaptics.pulse(35); }
  static impact(): void { DeepReadHaptics.pulse(55); }

  private static pulse(duration: number): void {
    if (getProductKind() !== 'deepread' || (AppStorage.get<boolean>('appBackgrounded') ?? false)
      || !canIUse('SystemCapability.Sensors.MiscDevice')) return;
    try {
      vibrator.startVibration({ type: 'time', duration: duration }, { usage: 'touch' })
        .catch((): void => {});
    } catch (_error) {}
  }
}
