// Tooltip — 长按提示气泡(ui/Tooltip.kt:28 行)
// Android 基准: M3 TooltipBox + PlainTooltip
// ArkUI: 无 TooltipBox → 用 bindMenu 含提示文案(长按触发);content slot 正常渲染
@Component
export struct Tooltip {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop text: string = '';
  @BuilderParam content: () => void;

  build(): void {
    Column() {
      this.content();
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .bindMenu(this.TooltipMenu())
  }

  @Builder TooltipMenu(): void {
    Menu() {
      MenuItem({ content: this.text })
    }
  }
}
