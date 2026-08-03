// SearchField — Notion 风格搜索框(ui/WorkspaceSearchField.kt:63-153)
// Android 基准: 999dp 胶囊 + searchBarBg 底 + hair border + 🔍 图标 + 内联 × 清除
//   单行 BasicTextField + imeAction Search → onSubmit
//
// ArkUI 差异: BasicTextField → TextInput;无 focusRequester → 外层 onClick 聚焦用 focusOnTouch
import { INK, INK3, SURFACE, SURFACE2, LINE, ACCENT, BODY, LABEL } from '../../design/tokens.ets';

@Component
export struct SearchField {
  @Prop value: string = '';
  @Prop placeholder: string = '';
  onChange: (value: string) => void = (_v: string): void => {};
  onSubmit: (value: string) => void = (_v: string): void => {};

  build(): void {
    Row() {
      Text('🔍').fontSize(BODY).fontColor(INK3).margin({ right: 10 })

      TextInput({ text: this.value, placeholder: this.placeholder })
        .fontSize(BODY).fontColor(INK)
        .backgroundColor('transparent')
        .layoutWeight(1)
        .padding({ top: 0, bottom: 0, left: 0, right: 0 })
        .onChange((v: string): void => { this.onChange(v); })
        .onSubmit((_enterKey: EnterKeyType): void => { this.onSubmit(this.value); })

      if (this.value.length > 0) {
        Text('✕').fontSize(LABEL).fontColor(INK3)
          .width(20).height(20)
          .textAlign(TextAlign.Center)
          .borderRadius(6)
          .onClick((): void => { this.onChange(''); })
      }
    }
    .width('100%')
    .padding({ left: 14, right: 14, top: 10, bottom: 10 })
    .backgroundColor(SURFACE2)
    .borderRadius(999)
    .border({ width: 1, color: LINE })
  }
}
