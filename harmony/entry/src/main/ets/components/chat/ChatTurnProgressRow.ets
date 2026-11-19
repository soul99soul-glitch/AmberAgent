// ChatTurnProgressRow — 助手回合头部的唯一稳定单行状态区
// 严禁在 LazyForEach 行内启高频定时器；仅使用轻量的状态指示点与一步明细入口。

import {
  ACCENT, ERROR, INK2, INK3, LINE, SIGNAL, SURFACE2, accentEdge, accentSoftBg,
} from '../../design/tokens.ets';
import { FONT_MONO, FONT_SANS } from '../../design/fonts.ets';
import { Icon } from '../ui/Icon.ets';

@Component
export struct ChatTurnProgressRow {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @Prop statusKind: string = 'completed';
  @Prop headline: string = '';
  @Prop stepCount: number = 0;
  @Prop hasError: boolean = false;
  @Prop streaming: boolean = false;
  onOpenDetails: () => void = (): void => {};

  private isActive(): boolean {
    return this.streaming && (this.statusKind === 'thinking' || this.statusKind === 'tool' || this.statusKind === 'writing');
  }

  private dotColor(): string {
    if (this.hasError || this.statusKind === 'error') return ERROR;
    if (this.isActive()) return this.accentColorStore;
    return SIGNAL;
  }

  build(): void {
    if (this.themeEpoch >= 0 && this.headline.length > 0) {
      Row({ space: 6 }) {
        if (this.isActive()) {
          LoadingProgress()
            .width(12)
            .height(12)
            .color(this.accentColorStore)
        } else {
          Column()
            .width(6)
            .height(6)
            .borderRadius(999)
            .backgroundColor(this.dotColor())
        }

        Text(this.headline)
          .fontSize(12)
          .fontFamily(FONT_SANS)
          .fontColor(this.hasError ? ERROR : INK2)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .layoutWeight(1)

        if (this.stepCount > 0) {
          Text(`${this.stepCount} 步`)
            .fontSize(10.5)
            .fontFamily(FONT_MONO)
            .fontColor(INK3)
        }

        Icon({ name: 'chevron_right', iconSize: 12, color: INK3 })
      }
      .width('100%')
      .height(30)
      .padding({ left: 10, right: 8 })
      .margin({ bottom: 8 })
      .borderRadius(999)
      .backgroundColor(this.isActive() ? accentSoftBg(this.accentColorStore) : SURFACE2)
      .border({
        width: 0.5,
        color: this.hasError ? ERROR : (this.isActive() ? accentEdge(this.accentColorStore) : LINE),
      })
      .alignItems(VerticalAlign.Center)
      .onClick((): void => {
        this.onOpenDetails();
      })
      .accessibilityText(`${this.headline}，点按查看步骤明细`)
    }
  }
}
