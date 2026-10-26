import type { ContinueCandidate } from '@amber/chat-domain';
import { Icon } from '../ui/Icon.ets';
import { ACCENT, HOME_INK, HOME_MUTED, HOME_PRESS } from '../../design/tokens.ets';
import { FONT_SANS, FONT_SANS_SEMIBOLD } from '../../design/fonts.ets';
import { accentAlpha } from '../../platform_impl/home/HomePresentation.ets';
import {
  continueIcon, continueTitle, continueMeta, continueCta
} from '../../platform_impl/home/HomeContinueSupport.ets';

@Component
export struct HomeContinueCard {
  @Prop candidate: ContinueCandidate | null = null;
  @Prop darkMode: boolean = false;
  @Prop accentColorStore: string = ACCENT;
  onOpen: (candidate: ContinueCandidate) => void = (_candidate: ContinueCandidate): void => {};
  @State pressedContinue: boolean = false;

  build(): void {
    if (this.candidate !== null) {
      // 内缩走外层 Column padding:Row 直接 width('100%')+padding 会溢出卡片右缘
      Column() {
        Row() {
          // 功能图标座:44 圆角方,accent 浅染底 + accent 墨
          Row() {
            Icon({ name: continueIcon(this.candidate), iconSize: 22, color: this.accentColorStore })
          }
          .width(44)
          .height(44)
          .borderRadius(15)
          .backgroundColor(accentAlpha(this.accentColorStore, this.darkMode ? 0.32 : 0.18))
          .justifyContent(FlexAlign.Center)
          .alignItems(VerticalAlign.Center)

          Column() {
            Text(continueTitle(this.candidate))
              .fontSize(15)
              .fontWeight(FontWeight.Medium)
              .lineHeight(20)
              .fontFamily(FONT_SANS_SEMIBOLD)
              .fontColor(HOME_INK)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
            Text(continueMeta(this.candidate))
              .fontSize(11)
              .lineHeight(14)
              .letterSpacing(0.11)
              .fontFamily(FONT_SANS)
              .fontColor(HOME_MUTED)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .margin({ top: 7 })
          }
          .layoutWeight(1)
          .alignItems(HorizontalAlign.Start)
          .margin({ left: 14, right: 12 })

          // 续接 CTA:浅强调底 + 主墨字(次级动作;主强调留给右下新对话)
          Text(continueCta(this.candidate))
            .fontSize(13)
            .fontWeight(FontWeight.Medium)
            .letterSpacing(0.26)
            .fontFamily(FONT_SANS_SEMIBOLD)
            .fontColor(HOME_INK)
            .padding({ left: 18, right: 18, top: 7, bottom: 7 })
            .constraintSize({ minHeight: 32 })
            .borderRadius(999)
            .backgroundColor(accentAlpha(this.accentColorStore, 0.12))
        }
        .width('100%')
        .alignItems(VerticalAlign.Center)
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 18, bottom: 18 })
      .backgroundColor(this.pressedContinue ? HOME_PRESS : Color.Transparent)
      .onTouch((event: TouchEvent): void => {
        if (event.type === TouchType.Down) this.pressedContinue = true;
        else if (event.type === TouchType.Up || event.type === TouchType.Cancel) this.pressedContinue = false;
      })
      .onClick((): void => {
        if (this.candidate !== null) this.onOpen(this.candidate);
      })
    }
  }
}
