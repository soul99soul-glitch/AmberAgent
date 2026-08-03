// ToggleSurface — 可切换内容色容器(对齐 Android ToggleSurface.kt)
// checked 时内容色变 accent, 否则 ink
import { INK, INK2, INK3, ACCENT, BODY } from '../../design/tokens.ets';

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
    .onClick((): void => { this.onClick(); })
  }
}
