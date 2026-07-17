// 设计系统组件 — 杂志风排版原语
import { INK, INK2, INK3, INK4, LINE, ACCENT, SURFACE, SURFACE2, TITLE, BODY, META, LABEL, PAD_CARD, PAD_ITEM, RADIUS } from './tokens.ets';

@Component
export struct RubricHead {
  label: string = '';
  status: string = '';
  build(): void {
    Row() {
      Text('//').fontSize(12).fontColor(ACCENT).fontWeight(FontWeight.Bold)
      Text(' ' + this.label).fontSize(12).fontWeight(FontWeight.Medium).fontColor(INK2)
      Blank()
      if (this.status.length > 0) {
        Text(this.status).fontSize(10).fontColor(INK4)
      }
    }
    .width('100%').padding({ top: 22, bottom: 8 })
  }
}

@Component
export struct LeadCard {
  title: string = '';
  heat: string = '';
  onTap: () => void = () => {};
  build(): void {
    Column() {
      Text(this.title)
        .fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
        .lineHeight(26).maxLines(2)
      if (this.heat.length > 0) {
        Text(this.heat).fontSize(META).fontColor(INK3).margin({ top: 6 })
      }
    }
    .width('100%').padding(PAD_CARD)
    .backgroundColor(SURFACE).borderRadius(RADIUS)
    .border({ width: 1, color: LINE })
    .onClick(() => { this.onTap(); })
  }
}

@Component
export struct IndexRow {
  rank: number = 0;
  title: string = '';
  heat: string = '';
  last: boolean = false;
  onTap: () => void = () => {};
  build(): void {
    Row() {
      Text(this.rank < 10 ? '0' + this.rank.toString() : this.rank.toString())
        .fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK4).width(28)
      Text(this.title)
        .fontSize(BODY).fontColor(INK).layoutWeight(1).maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
      if (this.heat.length > 0) {
        Text(this.heat).fontSize(LABEL).fontColor(INK4).margin({ left: 8 })
      }
    }
    .width('100%').padding({ top: PAD_ITEM, bottom: PAD_ITEM })
    .border({ width: { bottom: this.last ? 0 : 0.5 }, color: { bottom: LINE } })
    .onClick(() => { this.onTap(); })
  }
}

@Component
export struct EmptyState {
  icon: string = '';
  title: string = '';
  subtitle: string = '';
  actionLabel: string = '';
  onAction: () => void = () => {};
  build(): void {
    Column() {
      if (this.icon.length > 0) { Text(this.icon).fontSize(34) }
      Text(this.title).fontSize(TITLE).fontWeight(FontWeight.Medium).fontColor(INK2)
        .margin({ top: 12 })
      if (this.subtitle.length > 0) {
        Text(this.subtitle).fontSize(META).fontColor(INK3).margin({ top: 6 })
      }
      if (this.actionLabel.length > 0) {
        Text(this.actionLabel).fontSize(BODY).fontColor(ACCENT).margin({ top: 16 })
          .onClick(() => { this.onAction(); })
      }
    }
    .alignItems(HorizontalAlign.Center)
  }
}

@Component
export struct SkeletonRow {
  build(): void {
    Row() {
      Text('  ').fontSize(12).backgroundColor(SURFACE2).width(24)
      Text('  ').fontSize(14).backgroundColor(SURFACE2).layoutWeight(1).margin({ left: 8 })
    }
    .width('100%').padding({ top: 14, bottom: 14 })
  }
}
