import type { ConversationSummary } from '@amber/chat-domain';
import { Icon } from '../ui/Icon.ets';
import { RunningIndicator } from '../ChatListIndicators.ets';
import {
  ACCENT, HOME_CARD, HOME_CARD2, HOME_INK, HOME_MUTED, HOME_MUTED2, HOME_PRESS, HOME_ROWLINE, HOME_SHADOW
} from '../../design/tokens.ets';
import { FONT_SANS, FONT_SANS_SEMIBOLD } from '../../design/fonts.ets';
import { accentAlpha, conversationIcon, relativeTimeIso } from '../../platform_impl/home/HomePresentation.ets';

const HOME_RADIUS: number = 22;

@Component
export struct HomeConversationRow {
  @Prop item: ConversationSummary;
  @Prop count: number = 0;
  @Prop active: boolean = false;
  @Prop running: boolean = false;
  @Prop isFirst: boolean = false;
  @Prop isLast: boolean = false;
  @Prop hideLine: boolean = false;
  @Prop darkMode: boolean = false;
  @Prop accentColorStore: string = ACCENT;
  onOpen: (id: string) => void = (_id: string): void => {};
  onMenu: (item: ConversationSummary) => void = (_item: ConversationSummary): void => {};
  @State pressed: boolean = false;

  build(): void {
    Column() {
      // 内容内缩走外层 Column padding(width('100%')+padding 会溢出卡片右缘)
      Column() {
        Row() {
          // 图标座:40 圆,置顶 = 图钉(iOS: pin 替换主题图标,不另挂角标)
          Stack() {
            Icon({
              name: this.item.isPinned ? 'ph_pushPin' : conversationIcon(this.item.title.length > 0 ? this.item.title : '新对话'),
              iconSize: 20,
              color: this.active ? this.accentColorStore : HOME_MUTED2,
            })
          }
          .width(40)
          .height(40)
          .borderRadius(20)
          .backgroundColor(this.active
            ? accentAlpha(this.accentColorStore, this.darkMode ? 0.32 : 0.18)
            : HOME_CARD2)

          Column() {
            Text(this.item.title.length > 0 ? this.item.title : '新对话')
              .fontSize(16)
              .fontWeight(FontWeight.Medium)
              .lineHeight(21)
              .letterSpacing(-0.08)
              .fontFamily(FONT_SANS_SEMIBOLD)
              .fontColor(HOME_INK)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
            Row() {
              Text(relativeTimeIso(this.item.updateAt))
                .fontSize(11)
                .letterSpacing(0.11)
                .fontFamily(FONT_SANS)
                .fontColor(HOME_MUTED)
                .maxLines(1)
              Text('·')
                .fontSize(11)
                .fontFamily(FONT_SANS)
                .fontColor(HOME_MUTED2)
                .margin({ left: 6, right: 6 })
              Text(`${this.count} 条`)
                .fontSize(11)
                .letterSpacing(0.11)
                .fontFamily(FONT_SANS)
                .fontColor(HOME_MUTED)
                .maxLines(1)
            }
            .margin({ top: 7 })
          }
          .layoutWeight(1)
          .alignItems(HorizontalAlign.Start)
          .margin({ left: 13 })
          if (this.running) {
            RunningIndicator().margin({ left: 8 }).accessibilityText('运行中')
          }
        }
        .width('100%')
        .alignItems(VerticalAlign.Center)
      }
      .width('100%')
      .padding({ left: 17, right: 16, top: 12, bottom: 12 })
      .constraintSize({ minHeight: 72 })

      // 卡内 hairline:左缩进 70 对齐标题(iOS 同款);内缩走外层 padding,
      //   width('100%') + margin 会越出卡片右缘
      if (!this.hideLine) {
        Column() {
          Row()
            .width('100%')
            .height(0.5)
            .backgroundColor(HOME_ROWLINE)
        }
        .width('100%')
        .padding({ left: 70, right: 16 })
      }
    }
    .width('100%')
    .backgroundColor(this.pressed
      ? HOME_PRESS
      : (this.active ? accentAlpha(this.accentColorStore, this.darkMode ? 0.16 : 0.09) : HOME_CARD))
    .borderRadius({
      topLeft: this.isFirst ? HOME_RADIUS : 0,
      topRight: this.isFirst ? HOME_RADIUS : 0,
      bottomLeft: this.isLast ? HOME_RADIUS : 0,
      bottomRight: this.isLast ? HOME_RADIUS : 0,
    })
    .shadow((this.isFirst || this.isLast) ? { radius: 7, color: HOME_SHADOW, offsetY: 5 } : { radius: 0, color: Color.Transparent })
    .onTouch((event: TouchEvent): void => {
      if (event.type === TouchType.Down) this.pressed = true;
      else if (event.type === TouchType.Up || event.type === TouchType.Cancel) this.pressed = false;
    })
    .onClick((): void => { this.onOpen(this.item.id); })
    .gesture(
      LongPressGesture()
        .onAction((): void => { this.onMenu(this.item); })
    )
  }
}
