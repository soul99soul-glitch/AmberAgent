// FullScreenTextEditor — 全屏文本编辑器(对齐 Android TextArea.kt FullScreenTextEditor)
// 从 TextArea 长按或按钮触发,全屏编辑长文本
import { INK, INK2, INK3, SURFACE, SURFACE2, LINE, ACCENT, BODY, META } from '../../design/tokens.ets';

@Component
export struct FullScreenTextEditor {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop show: boolean = false;
  @Prop title: string = '编辑';
  @State editText: string = '';
  onSave: (text: string) => void = (_t: string): void => {};
  onDismiss: () => void = (): void => {};

  build(): void {
    if (this.themeEpoch >= 0 && this.show) {
      Column() {
        // 标题栏
        Row() {
          Text('取消')
            .fontSize(BODY)
            .fontColor(INK3)
            .onClick((): void => { this.onDismiss(); })
          Text(this.title)
            .fontSize(BODY)
            .fontWeight(FontWeight.Medium)
            .fontColor(INK)
            .layoutWeight(1)
            .textAlign(TextAlign.Center)
          Text('保存')
            .fontSize(BODY)
            .fontColor(ACCENT)
            .fontWeight(FontWeight.Medium)
            .onClick((): void => { this.onSave(this.editText); })
        }
        .width('100%')
        .padding({ left: 16, right: 16, top: 12, bottom: 12 })

        Divider().color(LINE).strokeWidth(0.5)

        // 编辑区
        TextArea({ text: this.editText })
          .fontSize(BODY)
          .fontColor(INK)
          .backgroundColor(SURFACE)
          .layoutWeight(1)
          .width('100%')
          .padding(16)
          .onChange((value: string): void => {
            this.editText = value;
          })
      }
      .width('100%')
      .height('100%')
      .backgroundColor(SURFACE)
    }
  }
}
