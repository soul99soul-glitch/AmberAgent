import { MOTION_OVERLAY,
  ACCENT, ACCENT_LIGHT, INK, INK3, LINE, LINE2, SURFACE2, TEXT_STYLES,
} from '../design/tokens.ets';
import { FONT_SANS_SEMIBOLD } from '../design/fonts.ets';
import { PaperSeparator } from './PageTemplate.ets';
import { Icon } from './ui/Icon.ets';

@Component
export struct McpToolPaperRow {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop toolName: string = '';
  @Prop description: string = '';
  @Prop expanded: boolean = false;
  @Prop enabledState: boolean = false;
  @Prop needsApproval: boolean = false;
  @Prop params: string[] = [];
  @Prop requiredParams: string[] = [];
  @Prop isLast: boolean = false;
  onEnableChange: (value: boolean) => void = (_value: boolean): void => {};
  onApprovalChange: (value: boolean) => void = (_value: boolean): void => {};
  onExpand: () => void = (): void => {};
  @State private pressed: boolean = false;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;

  build(): void {
    Column() {
      Row() {
        Text(this.toolName)
          .fontSize(TEXT_STYLES.body.size).fontWeight(600)
          .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(FONT_SANS_SEMIBOLD)
          .fontColor(INK).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }).layoutWeight(1)
        Text('审批')
          .fontSize(TEXT_STYLES.tinyTag.size).fontWeight(600)
          .lineHeight(TEXT_STYLES.tinyTag.lineHeight).fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK3)
          .margin({ left: 6 })
        Stack({ alignContent: Alignment.Start }) {
          Column()
            .width(42).height(24).borderRadius(99)
            .backgroundColor(this.needsApproval ? this.accentColorStore : LINE2)
          Column()
            .width(20).height(20).borderRadius(10)
            .backgroundColor('#FFFFFF')
            .margin({ left: this.needsApproval ? 22 : 2 })
            .animation({ duration: 150, curve: Curve.EaseOut })
        }
        .clip(true)
        .margin({ left: 4 })
        .onClick((): void => { this.onApprovalChange(!this.needsApproval); })
        .accessibilityText(this.needsApproval ? '已开启审批' : '已关闭审批')
        Text('启用')
          .fontSize(TEXT_STYLES.tinyTag.size).fontWeight(600)
          .lineHeight(TEXT_STYLES.tinyTag.lineHeight).fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK3)
          .margin({ left: 4 })
        Stack({ alignContent: Alignment.Start }) {
          Column()
            .width(42).height(24).borderRadius(99)
            .backgroundColor(this.enabledState ? this.accentColorStore : LINE2)
          Column()
            .width(20).height(20).borderRadius(10)
            .backgroundColor('#FFFFFF')
            .margin({ left: this.enabledState ? 22 : 2 })
            .animation({ duration: 150, curve: Curve.EaseOut })
        }
        .clip(true)
        .margin({ left: 4 })
        .onClick((): void => { this.onEnableChange(!this.enabledState); })
        .accessibilityText(this.enabledState ? '已开启' : '已关闭')
        Row() {
          Icon({ name: 'chevron_right', iconSize: 16, color: INK3 })
            .rotate({ angle: this.expanded ? 90 : 0 })
            .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut })
        }
        .width(32).height(44).justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
        .onClick((): void => {
          this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
            this.onExpand();
          });
        })
      }
      .width('100%').constraintSize({ minHeight: 64 })
      .padding({ left: 16, right: 16, top: 12, bottom: 12 })
      .backgroundColor(this.pressed ? SURFACE2 : Color.Transparent)
      .onTouch((event: TouchEvent): void => {
        if (event.type === TouchType.Down) this.pressed = true;
        else if (event.type === TouchType.Up || event.type === TouchType.Cancel) this.pressed = false;
      })
      if (this.expanded) {
        Column() {
          if (this.description.length > 0) {
            Text(this.description)
              .fontSize(TEXT_STYLES.secondary.size).fontWeight(TEXT_STYLES.secondary.weight)
              .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family)
              .fontColor(INK3).width('100%')
          }
          if (this.params.length > 0) {
            Flex({ wrap: FlexWrap.Wrap }) {
              ForEach(this.params, (key: string): void => {
                Text(key)
                  .fontSize(TEXT_STYLES.tinyTag.size).fontWeight(600)
                  .lineHeight(TEXT_STYLES.tinyTag.lineHeight).fontFamily(TEXT_STYLES.tinyTag.family)
                  .fontColor(this.requiredParams.indexOf(key) >= 0 ? ACCENT : INK3)
                  .padding({ left: 8, right: 8, top: 3, bottom: 3 })
                  .backgroundColor(this.requiredParams.indexOf(key) >= 0 ? ACCENT_LIGHT : SURFACE2)
                  .border({ width: 1, color: LINE }).borderRadius(6).margin({ right: 4, top: 4 })
              }, (key: string): string => key)
            }
            .width('100%').margin({ top: this.description.length > 0 ? 6 : 0 })
          }
        }
        .width('100%').padding({ left: 16, right: 16, bottom: 12 }).alignItems(HorizontalAlign.Start)
        .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }
      if (!this.isLast) {
        PaperSeparator()
      }
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .clip(true)
  }
}
