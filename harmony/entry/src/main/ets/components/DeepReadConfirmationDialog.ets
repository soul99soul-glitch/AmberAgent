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
import { DeepReadDesign as D } from '../design/DeepReadDesign.ets';
import { getProductKind } from '../platform_impl/ProductIdentity.ets';

@Component
export struct DeepReadConfirmationDialog {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('reduceMotion') reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') appBackgrounded: boolean = false;
  @Prop show: boolean = false;
  @Prop dialogTitle: string = '重新生成?';
  @Prop message: string = '重新生成将覆盖当前文章';
  @Prop confirmText: string = '重新生成';
  @Prop dismissText: string = '取消';
  onConfirm: () => void = (): void => {};
  onDismiss: () => void = (): void => {};

  private dialogTransition(card: boolean): TransitionEffect {
    if (getProductKind() === 'deepread') {
      if (this.appBackgrounded) return TransitionEffect.IDENTITY;
      if (this.reduceMotion) return TransitionEffect.OPACITY.animation({ duration: 120, curve: Curve.EaseOut });
    }
    const effect: TransitionEffect = card ? TransitionEffect.scale({ x: 0.96, y: 0.96 }) : TransitionEffect.OPACITY;
    return effect.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut });
  }

  build(): void {
    if (this.themeEpoch >= 0 && this.show) {
      Stack() {
        Column() {
          // ── 标题 ──
          Text(this.dialogTitle)
            .fontSize(getProductKind() === 'deepread' ? 17 : TITLE)
            .fontWeight(FontWeight.Medium)
            .fontColor(getProductKind() === 'deepread' ? D.ink : INK)
            .textAlign(getProductKind() === 'deepread' ? TextAlign.Center : TextAlign.Start)
            .width('100%')
            .margin({ bottom: 10 })

          // ── 警示文案 ──
          Text(this.message)
            .fontSize(getProductKind() === 'deepread' ? 13 : META)
            .fontColor(getProductKind() === 'deepread' ? D.muted : INK2)
            .textAlign(getProductKind() === 'deepread' ? TextAlign.Center : TextAlign.Start)
            .width('100%')
            .lineHeight(20)
            .margin({ bottom: 18 })

          // ── 按钮行 ──
          Row() {
            Text(this.dismissText)
              .fontSize(getProductKind() === 'deepread' ? 17 : BODY)
              .fontColor(getProductKind() === 'deepread' ? D.accent : INK3)
              .layoutWeight(1)
              .textAlign(TextAlign.Center)
              .padding({ top: 12, bottom: 12 })
              .constraintSize({ minHeight: 44 })
              .onClick((): void => { this.onDismiss(); })

            // 分隔竖线
            Column() {}
              .width(0.5)
              .height(20)
              .backgroundColor(getProductKind() === 'deepread' ? D.rule : LINE)

            Text(this.confirmText)
              .fontSize(getProductKind() === 'deepread' ? 17 : BODY)
              .fontColor(getProductKind() === 'deepread' ? D.accent : ACCENT)
              .fontWeight(FontWeight.Medium)
              .layoutWeight(1)
              .textAlign(TextAlign.Center)
              .padding({ top: 12, bottom: 12 })
              .constraintSize({ minHeight: 44 })
              .onClick((): void => { this.onConfirm(); })
          }
          .width('100%')
          .border({ width: { top: 0.5 }, color: getProductKind() === 'deepread' ? D.rule : LINE })
        }
        .width('82%')
        .backgroundColor(getProductKind() === 'deepread' ? D.card : SURFACE)
        .borderRadius(14)
        .padding({ left: 22, right: 22, top: 22, bottom: 0 })
        .transition(this.dialogTransition(true))
      }
      .width('100%')
      .height('100%')
      .backgroundColor(OVERLAY)
      .alignContent(Alignment.Center)
      .transition(this.dialogTransition(false))
    }
  }
}
