// DeepReadConfirmationDialog — 重新生成确认对话框
// 对照 Android DeepReadScreen.kt DeepReadConfirmation(712-742)
//   用途:用户点击「重新生成」时弹出,确认覆盖当前文章。
//   样式:遮罩 + 居中卡片,标题 + 警示文案 + 取消/确认按钮。
//
// ArkUI 约束:
//   - 不用 CustomDialogController(需 @CustomDialog 装饰器 + controller 传递),
//     改用 @Prop show 条件渲染,对齐本仓库 ConfirmDialog.ets 的成熟写法。
//   - @Prop 不用保留名(size/enabled/Select)。
import { ACCENT, BODY, INK, INK2, INK3, LINE, META, MOTION_OVERLAY, OVERLAY, SURFACE, TITLE } from '../design/tokens.ets';

@Component
export struct DeepReadConfirmationDialog {
  @Prop show: boolean = false;
  @Prop dialogTitle: string = '重新生成?';
  @Prop message: string = '重新生成将覆盖当前文章';
  @Prop confirmText: string = '重新生成';
  @Prop dismissText: string = '取消';
  onConfirm: () => void = (): void => {};
  onDismiss: () => void = (): void => {};

  build(): void {
    if (this.show) {
      Stack() {
        Column() {
          // ── 标题 ──
          Text(this.dialogTitle)
            .fontSize(TITLE)
            .fontWeight(FontWeight.Medium)
            .fontColor(INK)
            .width('100%')
            .margin({ bottom: 10 })

          // ── 警示文案 ──
          Text(this.message)
            .fontSize(META)
            .fontColor(INK2)
            .width('100%')
            .lineHeight(20)
            .margin({ bottom: 18 })

          // ── 按钮行 ──
          Row() {
            Text(this.dismissText)
              .fontSize(BODY)
              .fontColor(INK3)
              .layoutWeight(1)
              .textAlign(TextAlign.Center)
              .padding({ top: 12, bottom: 12 })
              .onClick((): void => { this.onDismiss(); })

            // 分隔竖线
            Column() {}
              .width(0.5)
              .height(20)
              .backgroundColor(LINE)

            Text(this.confirmText)
              .fontSize(BODY)
              .fontColor(ACCENT)
              .fontWeight(FontWeight.Medium)
              .layoutWeight(1)
              .textAlign(TextAlign.Center)
              .padding({ top: 12, bottom: 12 })
              .onClick((): void => { this.onConfirm(); })
          }
          .width('100%')
          .border({ width: { top: 0.5 }, color: LINE })
        }
        .width('82%')
        .backgroundColor(SURFACE)
        .borderRadius(14)
        .padding({ left: 22, right: 22, top: 22, bottom: 0 })
        .transition(TransitionEffect.scale({ x: 0.96, y: 0.96 })
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }
      .width('100%')
      .height('100%')
      .backgroundColor(OVERLAY)
      .alignContent(Alignment.Center)
      .transition(TransitionEffect.OPACITY
        .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
    }
  }
}
