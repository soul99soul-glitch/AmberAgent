import { MOTION_OVERLAY, INK, INK2, INK3, SURFACE2, ACCENT, ERROR } from '../design/tokens.ets';
import { FONT_MONO } from '../design/fonts.ets';

@Component
export struct PluginJSONEditor {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop text: string = '{}';
  @Prop label: string = '输入 JSON';
  @Prop schemaJSON: string = '';
  @Prop issue: string = '';
  @Prop editable: boolean = true;
  @Prop foreground: string = INK;
  @Prop secondary: string = INK2;
  @Prop muted: string = INK3;
  @Prop inset: string = SURFACE2;
  @Prop accent: string = ACCENT;
  @Prop errorColor: string = ERROR;
  @State showingSchema: boolean = false;
  onTextChange: (value: string) => void = (_value: string): void => {};

  build(): void {
    Column({ space: 8 }) {
      Column({ space: 8 }) {
        Row({ space: 8 }) {
          Text(this.label).fontSize(14).fontColor(this.foreground).layoutWeight(1)
            .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
          if (this.schemaJSON.length > 0) {
            Button(this.showingSchema ? '收起 Schema' : '查看 Schema').type(ButtonType.Normal)
              .height(44).fontSize(12).fontColor(this.accent).backgroundColor(this.inset)
              .onClick((): void => {
                this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
                  this.showingSchema = !this.showingSchema;
                });
              })
          }
        }.width('100%')
        if (this.showingSchema && this.schemaJSON.length > 0) {
          Text(this.schemaJSON).fontSize(12).fontFamily(FONT_MONO).fontColor(this.secondary).width('100%')
            .wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
          .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
            .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
        }
      }.width('100%').clip(true)
      TextArea({ text: this.text, placeholder: '{}' }).height(180).fontSize(13).fontFamily(FONT_MONO)
        .fontColor(this.foreground).placeholderColor(this.muted).backgroundColor(this.inset).enabled(this.editable)
        .onChange((value: string): void => { this.onTextChange(value); })
      if (this.issue.length > 0) {
        Text(this.issue).fontSize(12).fontColor(this.errorColor).width('100%').wordBreak(WordBreak.BREAK_ALL)
      }
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1).width('100%')
  }
}
