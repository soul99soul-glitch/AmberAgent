// 设计系统组件 — 杂志风排版原语 + 聊天交互原语
// 基准: Android ds/AmberPrimitives.kt(186 行) + 杂志风既有组件
import { INK, INK2, INK3, INK4, LINE, ACCENT, SURFACE, SURFACE2, BG, SIGNAL, TITLE, BODY, META, LABEL, PAD_CARD, PAD_ITEM, RADIUS } from './tokens.ets';

// ===== 聊天交互原语(Android AmberPrimitives.kt 对齐) =====

// AmberCard:通用容器(surface 底 + 1dp hairline + 14dp 圆角)
@Component
export struct AmberCard {
  @BuilderParam content: () => void;
  build(): void {
    Column() {
      this.content();
    }
    .width('100%')
    .backgroundColor(SURFACE)
    .border({ width: 1, color: LINE })
    .borderRadius(RADIUS)
  }
}

// Hairline:1dp 发丝分割线
@Component
export struct Hairline {
  build(): void {
    Divider().color(LINE).strokeWidth(0.5).width('100%')
  }
}

// BtnInk:主操作按钮(ink 实心 + bg 字 + 15dp 圆角)
@Component
export struct BtnInk {
  text: string = '';
  enabled: boolean = true;
  onClick: () => void = () => {};
  build(): void {
    Text(this.text)
      .fontSize(BODY).fontWeight(FontWeight.Medium)
      .fontColor(this.enabled ? SURFACE : INK4)
      .padding({ left: 16, right: 16, top: 8, bottom: 8 })
      .borderRadius(15)
      .backgroundColor(this.enabled ? INK : SURFACE2)
      .onClick(() => { if (this.enabled) this.onClick(); })
  }
}

// BtnAccent:accent 实心按钮 + accentInk 字
@Component
export struct BtnAccent {
  text: string = '';
  enabled: boolean = true;
  onClick: () => void = () => {};
  build(): void {
    Text(this.text)
      .fontSize(BODY).fontWeight(FontWeight.Medium)
      .fontColor(this.enabled ? SURFACE : INK4)
      .padding({ left: 16, right: 16, top: 8, bottom: 8 })
      .borderRadius(15)
      .backgroundColor(this.enabled ? ACCENT : SURFACE2)
      .onClick(() => { if (this.enabled) this.onClick(); })
  }
}

// LiveDot:呼吸光晕活体圆点(signal 绿 / idle 灰)
@Component
export struct LiveDot {
  idle: boolean = false;
  dotSize: number = 8;
  build(): void {
    Circle({ width: this.dotSize, height: this.dotSize })
      .fill(this.idle ? INK4 : SIGNAL)
  }
}

// BlinkingCursor:闪烁终端光标(默认 accent)
@Component
export struct BlinkingCursor {
  color: string = ACCENT;
  width: number = 2;
  height: number = 16;
  build(): void {
    Column()
      .width(this.width)
      .height(this.height)
      .backgroundColor(this.color)
  }
}

// SectionLabel:带 accent // 前缀的 mono 大写眉标
@Component
export struct SectionLabel {
  text: string = '';
  build(): void {
    Row() {
      Text('//').fontSize(10).fontColor(ACCENT).fontWeight(FontWeight.Bold)
      Text(' ' + this.text.toUpperCase()).fontSize(10).fontWeight(FontWeight.Medium).fontColor(INK3)
    }
    .width('100%')
  }
}

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
