// ChatEpisodeDivider — 时间线轻量分段条（✦ 新话题 · 前序重点已归档 + 可选折叠摘要）

import {
  ACCENT, INK2, INK3, LINE, SURFACE2, accentEdge, accentSoftBg,
} from '../../design/tokens.ets';
import { FONT_SANS } from '../../design/fonts.ets';
import { Icon } from '../ui/Icon.ets';

@Component
export struct ChatEpisodeDivider {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @Prop episodeId: string = '';
  @Prop title: string = '';
  @Prop summary: string = '';
  @Prop reason: string = 'manual_new_topic';
  @State private expanded: boolean = false;

  build(): void {
    Column({ space: 6 }) {
      Row({ space: 8 }) {
        Column().height(0.5).layoutWeight(1).backgroundColor(LINE)
        Row({ space: 5 }) {
          Icon({ name: 'spark', iconSize: 11, color: this.accentColorStore })
          Text(this.reason === 'idle_timeout'
            ? '新话题 · 间隔归档'
            : '✦ 新话题 · 前序重点已归档')
            .fontSize(11.5)
            .fontFamily(FONT_SANS)
            .fontWeight(FontWeight.Medium)
            .fontColor(this.accentColorStore)
          if (this.title.length > 0) {
            Icon({ name: 'chevron_down', iconSize: 11, color: INK3 })
              .rotate({ angle: this.expanded ? 180 : 0 })
          }
        }
        .padding({ left: 10, right: 10, top: 4, bottom: 4 })
        .borderRadius(999)
        .backgroundColor(accentSoftBg(this.accentColorStore))
        .border({ width: 0.5, color: accentEdge(this.accentColorStore) })
        .onClick((): void => {
          if (this.title.length > 0 || this.summary.length > 0) {
            animateTo({ duration: 160, curve: Curve.EaseOut }, (): void => {
              this.expanded = !this.expanded;
            });
          }
        })
        Column().height(0.5).layoutWeight(1).backgroundColor(LINE)
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)

      if (this.expanded && (this.title.length > 0 || this.summary.length > 0)) {
        Column({ space: 2 }) {
          if (this.title.length > 0) {
            Text(this.title)
              .fontSize(12)
              .fontWeight(FontWeight.Medium)
              .fontColor(INK2)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
          }
          if (this.summary.length > 0) {
            Text(this.summary)
              .fontSize(11.5)
              .fontColor(INK3)
              .maxLines(2)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
          }
        }
        .width('92%')
        .padding({ left: 12, right: 12, top: 8, bottom: 8 })
        .borderRadius(12)
        .backgroundColor(SURFACE2)
        .border({ width: 0.5, color: LINE })
        .alignItems(HorizontalAlign.Start)
      }
    }
    .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .padding({ top: 14, bottom: 10 })
    .alignItems(HorizontalAlign.Center)
  }
}
