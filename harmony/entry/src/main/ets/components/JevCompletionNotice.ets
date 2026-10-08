import { ACCENT, INK2, INK3, SURFACE2 } from '../design/tokens.ets';
import { Icon } from './ui/Icon.ets';

@Component
export struct JevCompletionNoticeView {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  onVerify: () => void = (): void => {};
  onDismiss: () => void = (): void => {};

  build(): void {
    Row({ space: 8 }) {
      Text('本轮修改后还没有成功检查记录，可以再验证一次。')
        .fontSize(12).fontColor(INK2).lineHeight(18).layoutWeight(1)
      Text('验证').fontSize(13).fontColor(ACCENT).textAlign(TextAlign.Center)
        .constraintSize({ minWidth: 44, minHeight: 44 })
        .padding({ left: 8, right: 8 }).borderRadius(12)
        .onClick((): void => { this.onVerify(); })
        .accessibilityText('请求验证本轮修改')
      Row() {
        Icon({ name: 'close', iconSize: 16, color: INK3 })
      }
      .width(44).height(44).justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
      .onClick((): void => { this.onDismiss(); })
      .accessibilityText('关闭完成校验提示')
    }
    .width('100%').padding({ left: 12, right: 4, top: 4, bottom: 4 })
    .backgroundColor(SURFACE2).borderRadius(14)
    .opacity(this.themeEpoch >= 0 ? 1 : 1)
  }
}
