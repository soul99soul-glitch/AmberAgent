// ErrorCard — 错误卡片(对齐 Android ErrorCard.kt)
// 单条错误: 标题 + 消息 + 复制/关闭按钮
import { INK3, ERROR, ERROR_BG, LINE, LABEL, META } from '../../design/tokens.ets';

@Component
export struct ErrorCard {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop title: string = '';
  @Prop message: string = '';
  onDismiss: () => void = (): void => {};
  onCopy: () => void = (): void => {};

  build(): void {
    Row() {
      // 错误内容
      Column() {
        if (this.title.length > 0) {
          Text(this.title)
            .fontSize(META)
            .fontWeight(FontWeight.Medium)
            .fontColor(ERROR)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .width('100%')
        }
        Text(this.message.length > 0 ? this.message : 'Unknown error')
          .fontSize(LABEL)
          .fontColor(ERROR)
          .opacity(0.8)
          .maxLines(2)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .width('100%')
      }
      .layoutWeight(1)
      .alignItems(HorizontalAlign.Start)

      // 操作按钮
      Text('复制')
        .fontSize(LABEL)
        .fontColor(INK3)
        .padding({ left: 8, right: 8, top: 6, bottom: 6 })
        .onClick((): void => { this.onCopy(); })

      Text('✕')
        .fontSize(LABEL)
        .fontColor(INK3)
        .padding({ left: 8, right: 8, top: 6, bottom: 6 })
        .onClick((): void => { this.onDismiss(); })
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .backgroundColor(ERROR_BG)
    .borderRadius(12)
    .border({ width: 0.5, color: ERROR })
    .padding({ left: 12, right: 12, top: 10, bottom: 10 })
    .alignItems(VerticalAlign.Center)
  }
}
