// CardGroup — 融合圆角卡片列表(ui/CardGroup.kt:192 行)
// Android 基准: M3 ListItem 列表 + 首尾 18dp 圆角 + 中间 2dp = 视觉融合卡
// ArkUI 差异: 无 ListItem → 自建 Row 结构;圆角差异通过 borderRadius 条件实现
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, ACCENT, BODY, META, LABEL } from '../../design/tokens.ets';

@Component
export struct CardGroupItem {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop title: string = '';
  @Prop subtitle: string = '';
  @Prop isFirst: boolean = false;
  @Prop isLast: boolean = false;
  onClick: () => void = (): void => {};

  build(): void {
    Row() {
      Column() {
        Text(this.title)
          .fontSize(BODY).fontColor(INK)
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        if (this.subtitle.length > 0) {
          Text(this.subtitle)
            .fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        }
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1)
      Text('›').fontSize(BODY).fontColor(INK4)
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE)
    .borderRadius({
      topLeft: this.isFirst ? 18 : 2,
      topRight: this.isFirst ? 18 : 2,
      bottomLeft: this.isLast ? 18 : 2,
      bottomRight: this.isLast ? 18 : 2,
    })
    .border({
      width: { bottom: this.isLast ? 0 : 0.5 },
      color: { bottom: LINE },
    })
    .onClick((): void => { this.onClick(); })
  }
}

@Component
export struct CardGroup {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop title: string = '';
  @BuilderParam content: () => void;

  build(): void {
    Column() {
      if (this.title.length > 0) {
        Text(this.title)
          .fontSize(META).fontColor(INK3)
          .width('100%')
          .padding({ left: 16, bottom: 8 })
      }
      Column() {
        this.content();
      }
      .width('100%')
      .backgroundColor(SURFACE)
      .border({ width: 1, color: LINE })
      .borderRadius(18)
      // 内层圆角覆写:子项的 borderRadius 需要透过 border 显示
      // ArkUI 无 clipToOutline → 用 overflow hidden 模拟(不完美,登记设备验证)
      .clip(true)
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
  }
}
