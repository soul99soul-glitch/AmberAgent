import { DeepReadDesign as D } from '../../design/DeepReadDesign.ets';
import { FONT_MONO } from '../../design/fonts.ets';
import { Icon } from '../ui/Icon.ets';

@Component
export struct DeepReadTemplateCodeEditor {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @Prop value: string = '';
  @Prop inputEnabled: boolean = true;
  onChange: (value: string) => void = (_value: string): void => {};
  onImportFile: () => void = (): void => {};
  @State fullscreenOpen: boolean = false;
  @State fullscreenText: string = '';

  build(): void {
    Column() {
      TextArea({ text: this.value, placeholder: '<article>\n  <h1>{{title}}</h1>\n  {{content}}\n</article>' })
        .fontFamily(FONT_MONO).fontSize(12).fontColor(D.ink).placeholderColor(D.muted)
        .backgroundColor(Color.Transparent).borderRadius(0)
        .padding(14).width('100%').height(300)
        .enabled(this.inputEnabled)
        .onChange((value: string): void => { this.onChange(value); })
      Row() {
        Row({ space: 7 }) {
          Icon({ name: 'folder', iconSize: 16, color: D.accent })
          Text('导入 HTML').fontSize(13).fontColor(D.accent)
        }.constraintSize({ minHeight: 44 }).enabled(this.inputEnabled)
          .onClick((): void => { this.onImportFile(); })
        Blank().layoutWeight(1)
        Row({ space: 7 }) {
          Icon({ name: 'edit', iconSize: 16, color: D.accent })
          Text('全屏编辑').fontSize(13).fontColor(D.accent)
        }.constraintSize({ minHeight: 44 }).enabled(this.inputEnabled)
          .onClick((): void => { this.fullscreenText = this.value; this.fullscreenOpen = true; })
      }.width('100%').padding({ left: 14, right: 14 }).border({ width: { top: 0.5 }, color: D.rule })
    }.width('100%').backgroundColor(D.card).opacity(this.themeEpoch >= 0 ? 1 : 1)
      .bindSheet(this.fullscreenOpen, this.FullscreenEditor(), {
        height: SheetSize.LARGE, showClose: false,
        onDisappear: (): void => { this.fullscreenOpen = false; },
      })
  }

  @Builder
  FullscreenEditor(): void {
    Column() {
      Row() {
        Text('取消').fontSize(17).fontColor(D.accent).constraintSize({ minWidth: 64, minHeight: 44 })
          .textAlign(TextAlign.Center).onClick((): void => { this.fullscreenOpen = false; })
        Text('HTML 与 CSS').fontSize(17).fontWeight(FontWeight.Medium).fontColor(D.ink)
          .layoutWeight(1).textAlign(TextAlign.Center)
        Text('完成').fontSize(17).fontColor(D.accent).constraintSize({ minWidth: 64, minHeight: 44 })
          .textAlign(TextAlign.Center).onClick((): void => {
            this.onChange(this.fullscreenText);
            this.fullscreenOpen = false;
          })
      }.width('100%').padding({ left: 12, right: 12, top: 8, bottom: 8 })
      TextArea({ text: this.fullscreenText })
        .fontFamily(FONT_MONO).fontSize(13).fontColor(D.ink)
        .backgroundColor(D.card).borderRadius(0).padding(16)
        .width('100%').layoutWeight(1)
        .onChange((value: string): void => { this.fullscreenText = value; })
    }.width('100%').height('100%').backgroundColor(D.paper)
  }
}
