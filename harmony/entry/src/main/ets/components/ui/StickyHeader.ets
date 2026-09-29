// StickyHeader — 固定头样式容器(ui/StickyHeader.kt:23 行)
// Android 基准: Box + ProvideTextStyle(titleSmall.copy(color = secondary))
//   纯样式 scoping;无实际 sticky 定位(调用方处理)
// ArkUI: Column with content slot(样式由调用方在 content 内设定)
@Component
export struct StickyHeader {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @BuilderParam content: () => void;
  build(): void {
    Column() {
      this.content();
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
  }
}
