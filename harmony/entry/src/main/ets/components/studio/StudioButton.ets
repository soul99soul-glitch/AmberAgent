import { curves } from '@kit.ArkUI';
import { ACCENT, ACCENT_INK, INK, SURFACE, LINE, MOTION_PRESS, SHADOW_KEY } from '../../design/tokens.ets';
import { FONT_SANS_SEMIBOLD } from '../../design/fonts.ets';
import { Icon } from '../ui/Icon.ets';

// Scoped to the two studio homes; no change to the host application's controls.
@Component
export struct StudioButton {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @Prop label: string = '';
  @Prop iconName: string = '';
  @Prop primary: boolean = false;
  @Prop compact: boolean = false;
  @Prop @Watch('releaseIfDisabled') disabled: boolean = false;
  onTap: () => void = (): void => {};
  @State private pressed: boolean = false;

  private releaseIfDisabled(): void {
    if (this.disabled) this.pressed = false;
  }

  aboutToDisappear(): void {
    this.pressed = false;
  }

  private setPressed(value: boolean): void {
    this.getUIContext().animateTo({ duration: MOTION_PRESS,
      curve: curves.springCurve(0, 1, 260, 24) }, (): void => {
      this.pressed = value;
    });
  }

  build(): void {
    Row({ space: 8 }) {
      if (this.iconName.length > 0) {
        Icon({ name: this.iconName, iconSize: 18, color: this.primary ? ACCENT_INK : ACCENT })
          .rotate({ angle: this.pressed ? -8 : 0 })
      }
      Text(this.label).fontSize(this.compact ? 13 : 15).fontWeight(600).fontFamily(FONT_SANS_SEMIBOLD)
        .fontColor(this.primary ? ACCENT_INK : INK).textAlign(TextAlign.Center)
        .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
    }
    .width('100%').constraintSize({ minHeight: this.compact ? 44 : 48 })
    .padding({ left: this.compact ? 6 : 18, right: this.compact ? 6 : 18,
      top: this.compact ? 6 : 12, bottom: this.compact ? 6 : 12 })
    .justifyContent(FlexAlign.Center)
    .backgroundColor(this.primary ? ACCENT : SURFACE)
    .borderRadius(this.compact ? 12 : 16).border({ width: this.primary ? 0 : 0.5, color: LINE })
    .shadow({ radius: this.compact ? 0 : this.pressed ? 2 : 8, color: SHADOW_KEY,
      offsetY: this.compact ? 0 : this.pressed ? 1 : 3 })
    .scale({ x: this.pressed ? 0.96 : 1, y: this.pressed ? 0.96 : 1 })
    .opacity(this.disabled ? 0.45 : 1)
    .enabled(!this.disabled)
    .onTouch((event: TouchEvent): void => {
      if (event.type === TouchType.Down) this.setPressed(true);
      else if (event.type === TouchType.Up || event.type === TouchType.Cancel) this.setPressed(false);
    })
    .onClick((): void => { if (!this.disabled) this.onTap(); })
    .accessibilityText(this.label)
  }
}
