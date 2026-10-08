import { DeepReadDesign as D } from '../../design/DeepReadDesign.ets';
import { Icon } from '../ui/Icon.ets';

@Component
export struct DeepReadTemplateNavigation {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @Prop title: string = '';
  @Prop largeTitle: boolean = false;
  @Prop cancelText: string = '';
  @Prop topInset: number = -1;
  onBack: () => void = (): void => {};

  build(): void {
    Column() {
      Row() {
        Row({ space: 4 }) {
          if (this.cancelText.length === 0) {
            Icon({ name: 'back', iconSize: 20, color: D.accent })
            Text('返回').fontSize(17).fontColor(D.accent)
          } else {
            Text(this.cancelText).fontSize(17).fontColor(D.accent)
          }
        }.constraintSize({ minWidth: 64, minHeight: 44 })
          .onClick((): void => { this.onBack(); })
        if (!this.largeTitle) {
          Text(this.title).fontSize(17).fontWeight(FontWeight.Medium).fontColor(D.ink)
            .layoutWeight(1).textAlign(TextAlign.Center).maxLines(1)
        } else {
          Blank().layoutWeight(1)
        }
        Blank().width(64)
      }.width('100%').padding({ left: 16, right: 16 })
      if (this.largeTitle) {
        Text(this.title).fontSize(34).fontWeight(FontWeight.Bold).fontColor(D.ink)
          .width('100%').padding({ left: D.gutter, right: D.gutter, top: 2, bottom: 12 })
      }
    }.width('100%').padding({ top: this.topInset >= 0 ? this.topInset : this.statusBarHeight })
      .backgroundColor(D.paper).opacity(this.themeEpoch >= 0 ? 1 : 1)
  }
}
