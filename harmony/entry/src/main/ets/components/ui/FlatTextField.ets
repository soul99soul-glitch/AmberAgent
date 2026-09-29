// FlatTextField — V3 平卡输入(ui/Input.kt:122-202)
// Android 基准: label 在上(12sp inkFaint) + 输入卡(surface 底 + hair border + 12dp 圆角 + 14.5sp ink)
//   mono 模式切等宽字体(API key / private key / baseUrl 等)
//   error: border 走 error 色;supportingText 错误提示
//
// ArkUI 差异: BasicTextField → TextInput(单行)/TextArea(多行);无 cursorBrush → 用 caretColor
import { INK, INK3, INK4, SURFACE, LINE, ACCENT, ERROR, BODY, META, LABEL } from '../../design/tokens.ets';

@Component
export struct FlatTextField {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop value: string = '';
  @Prop label: string = '';
  @Prop placeholder: string = '';
  @Prop isError: boolean = false;
  @Prop mono: boolean = false;
  @Prop singleLine: boolean = true;
  @Prop maxLines: number = 1;
  @Prop supportingText: string = '';
  @Prop fieldEnabled: boolean = true;
  @Prop obscure: boolean = false;
  onChange: (value: string) => void = (_v: string): void => {};

  build(): void {
    Column() {
      // label(对齐 Android: 12sp inkFaint letterSpacing 0.4)
      if (this.label.length > 0) {
        Text(this.label)
          .fontSize(META).fontColor(INK3)
          .margin({ left: 2, bottom: 8 })
      }

      if (this.singleLine) {
        TextInput({ text: this.value, placeholder: this.placeholder })
          .fontSize(BODY).fontColor(this.fieldEnabled ? INK : INK4)
          .fontFamily(this.mono ? 'monospace' : 'sans-serif')
          .backgroundColor(SURFACE)
          .borderRadius(12)
          .border({ width: 1, color: this.isError ? ERROR : LINE })
          .padding({ left: 14, right: 14, top: 12, bottom: 12 })
          .width('100%')
          .enabled(this.fieldEnabled)
          .type(this.obscure ? InputType.Password : InputType.Normal)
          .caretColor(ACCENT)
          .onChange((v: string): void => { this.onChange(v); })
      } else {
        TextArea({ text: this.value, placeholder: this.placeholder })
          .fontSize(BODY).fontColor(this.fieldEnabled ? INK : INK4)
          .fontFamily(this.mono ? 'monospace' : 'sans-serif')
          .backgroundColor(SURFACE)
          .borderRadius(12)
          .border({ width: 1, color: this.isError ? ERROR : LINE })
          .padding({ left: 14, right: 14, top: 12, bottom: 12 })
          .width('100%')
          .maxLines(this.maxLines)
          .enabled(this.fieldEnabled)
          .onChange((v: string): void => { this.onChange(v); })
      }

      // supportingText(对齐 Android: 11.5sp error?errorColor:inkFaint)
      if (this.supportingText.length > 0) {
        Text(this.supportingText)
          .fontSize(LABEL).fontColor(this.isError ? ERROR : INK3)
          .margin({ left: 2, top: 6 })
      }
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .alignItems(HorizontalAlign.Start)
  }
}
