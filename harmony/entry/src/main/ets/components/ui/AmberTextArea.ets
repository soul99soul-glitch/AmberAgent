// AmberTextArea — 多行文本输入 + 文件导入 + 全屏编辑(ui/TextArea.kt:68-254)
// Android 基准: label header + 全屏图标 + 文件导入图标 + 多行 OutlinedTextField + FullScreenTextEditor dialog
//
// ArkUI 差异:
//   - 文件导入: ArkUI 有 FilePicker(@ohos.file.picker),但本组件只暴露 onImportFile 回调,
//     实际 picker 由调用方触发(解耦平台能力)
//   - 全屏编辑: 用 bindSheet 弹出全屏 TextArea
import { INK, INK2, INK3, SURFACE, SURFACE2, LINE, ACCENT, BODY } from '../../design/tokens.ets';

@Component
export struct AmberTextArea {
  @Prop value: string = '';
  @Prop label: string = '';
  @Prop placeholder: string = '';
  @Prop minLines: number = 5;
  @Prop maxLines: number = 10;
  @Prop enabled: boolean = true;
  @Prop enableFullscreen: boolean = true;
  onChange: (value: string) => void = (_v: string): void => {};
  onImportFile: () => void = (): void => {};
  @State fullScreenOpen: boolean = false;
  @State fullScreenText: string = '';

  build(): void {
    Column() {
      if (this.label.length > 0) {
        Row() {
          Text(this.label).fontSize(BODY).fontColor(INK2).layoutWeight(1)
          if (this.enableFullscreen) {
            Text('⛶').fontSize(BODY).fontColor(INK3)
              .onClick((): void => {
                this.fullScreenText = this.value;
                this.fullScreenOpen = true;
              })
          }
          Text('📂').fontSize(BODY).fontColor(INK3).margin({ left: 12 })
            .onClick((): void => { this.onImportFile(); })
        }
        .width('100%')
        .padding({ top: 8, bottom: 4 })
      }

      TextArea({ text: this.value, placeholder: this.placeholder })
        .fontSize(BODY).fontColor(this.enabled ? INK : INK3)
        .backgroundColor(SURFACE)
        .borderRadius(12)
        .border({ width: 1, color: LINE })
        .padding({ left: 14, right: 14, top: 12, bottom: 12 })
        .width('100%')
        .constraintSize({ minHeight: this.minLines * 24 })
        .maxLines(this.maxLines)
        .enabled(this.enabled)
        .onChange((v: string): void => { this.onChange(v); })
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
    .bindSheet(this.fullScreenOpen, this.FullScreenEditor(), {
      height: SheetSize.LARGE,
      onDisappear: (): void => { this.fullScreenOpen = false; },
    })
  }

  @Builder FullScreenEditor(): void {
    Column() {
      Row() {
        Text('取消').fontSize(BODY).fontColor(INK3)
          .onClick((): void => { this.fullScreenOpen = false; })
        Blank()
        Text('保存').fontSize(BODY).fontColor(ACCENT)
          .onClick((): void => {
            this.onChange(this.fullScreenText);
            this.fullScreenOpen = false;
          })
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 12, bottom: 8 })

      TextArea({ text: this.fullScreenText, placeholder: this.label.length > 0 ? this.label : this.placeholder })
        .fontSize(BODY).fontColor(INK)
        .backgroundColor(SURFACE2)
        .borderRadius(12)
        .padding(16)
        .width('100%')
        .layoutWeight(1)
        .onChange((v: string): void => { this.fullScreenText = v; })
    }
    .width('100%').height('100%')
    .backgroundColor(SURFACE2)
  }
}
