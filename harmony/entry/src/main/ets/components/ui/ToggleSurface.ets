// ToggleSurface — 可切换内容色容器(对齐 Android ToggleSurface.kt)
// checked 时底色变 accentLight, 否则透明;内容色由调用方 content 控制
import { ACCENT_LIGHT, BODY, INK2, INK3 } from '../../design/tokens.ets';

@Component
export struct ToggleSurface {
  @Prop checked: boolean = false;
  @BuilderParam content: () => void;
  onClick: () => void = (): void => {};
  build(): void {
    Row() {
      this.content()
    }
    .padding({ left: 12, right: 12, top: 6, bottom: 6 })
    .borderRadius(50)
    .backgroundColor(this.checked ? ACCENT_LIGHT : Color.Transparent)
    .onClick((): void => { this.onClick(); })
  }
}
