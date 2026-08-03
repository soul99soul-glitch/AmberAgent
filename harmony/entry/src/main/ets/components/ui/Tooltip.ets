// Tooltip — 长按提示气泡(ui/Tooltip.kt:28 行)
// Android 基准: M3 TooltipBox + PlainTooltip
// ArkUI: 无 TooltipBox → 用 bindMenu 含提示文案(长按触发);content slot 正常渲染
@Component
export struct Tooltip {
  @Prop text: string = '';
  @BuilderParam content: () => void;

  build(): void {
    Column() {
      this.content();
    }
    .bindMenu(this.TooltipMenu())
  }

  @Builder TooltipMenu(): void {
    Menu() {
      MenuItem({ content: this.text })
    }
  }
}
