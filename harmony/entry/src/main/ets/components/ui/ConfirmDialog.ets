// ConfirmDialog — 确认对话框(对齐 Android ConfirmDialog.kt)
import { INK, INK2, INK3, SURFACE, ACCENT, LINE, BODY, META, OVERLAY, MOTION_OVERLAY } from '../../design/tokens.ets';

@Component
export struct ConfirmDialog {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop show: boolean = false;
  @Prop title: string = '';
  @Prop message: string = '';
  @Prop confirmText: string = '确定';
  @Prop dismissText: string = '取消';
  onConfirm: () => void = (): void => {};
  onDismiss: () => void = (): void => {};

  build(): void {
    if (this.themeEpoch >= 0 && this.show) {
      // 遮罩层独立成全屏子项，卡片置后并消费点击避免误关闭
      Stack() {
        Row() {}
          .width('100%')
          .height('100%')
          .backgroundColor(OVERLAY)
          .onClick((): void => { this.onDismiss(); })
        Column() {
          Text(this.title)
            .fontSize(BODY)
            .fontWeight(FontWeight.Medium)
            .fontColor(INK)
            .width('100%')
            .margin({ bottom: 8 })

          if (this.message.length > 0) {
            Text(this.message)
              .fontSize(META)
              .fontColor(INK2)
              .width('100%')
              .lineHeight(20)
              .margin({ bottom: 16 })
          }

          // 按钮行
          Row() {
            Text(this.dismissText)
              .fontSize(BODY)
              .fontColor(INK3)
              .layoutWeight(1)
              .textAlign(TextAlign.Center)
              .padding({ top: 10, bottom: 10 })
              .onClick((): void => { this.onDismiss(); })

            Text(this.confirmText)
              .fontSize(BODY)
              .fontColor(ACCENT)
              .fontWeight(FontWeight.Medium)
              .layoutWeight(1)
              .textAlign(TextAlign.Center)
              .padding({ top: 10, bottom: 10 })
              .onClick((): void => { this.onConfirm(); })
          }
          .width('100%')
          .border({ width: { top: 0.5 }, color: LINE })
        }
        .width('80%')
        .backgroundColor(SURFACE)
        .borderRadius(14)
        .padding({ left: 20, right: 20, top: 20, bottom: 0 })
        .onClick((): void => {})
        .transition(TransitionEffect.scale({ x: 0.96, y: 0.96 })
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }
      .width('100%')
      .height('100%')
      .alignContent(Alignment.Center)
      .transition(TransitionEffect.OPACITY
        .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
    }
  }
}
