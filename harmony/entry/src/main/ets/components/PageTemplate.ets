// PageTemplate — 全站共享页面骨架(G7 风格统一)
//
// 提取自 SettingDisplayPage 的设计模式:
//   - Title bar: height(56) + padding(24) + BG + ←/18/INK3 + 标题/TITLE/Bold/INK
//   - Section: // ACCENT/Bold/META + ' Label' INK3/Medium/META, padding(16,20,6)
//   - Row: flat, padding(16,12,12) + SURFACE + bottom 0.5 LINE hairline
//   - Body: Scroll > Column(无水平padding) + 尾部 height(40) spacer
//
// 用法: 页面 import { PageScaffold, SectionHeader, NavRow, ToggleRow, SelectRow } from './PageTemplate.ets'

import { router } from '@kit.ArkUI';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, ERROR, BODY, META, LABEL, TITLE } from '../design/tokens.ets';

// ===== Title Bar =====

@Component
export struct PageTitleBar {
  @Prop title: string = '';
  @Prop showBack: boolean = true;
  @Prop actionText: string = '';
  onAction: () => void = (): void => {};
  onBack: () => void = (): void => { router.back(); };

  build(): void {
    Row() {
      if (this.showBack) {
        Text('←')
          .fontSize(18)
          .fontColor(INK3)
          .onClick((): void => { this.onBack(); })
      }
      Text(this.title)
        .fontSize(TITLE)
        .fontWeight(FontWeight.Bold)
        .fontColor(INK)
        .margin({ left: this.showBack ? 12 : 0 })
        .layoutWeight(1)
      if (this.actionText.length > 0) {
        Text(this.actionText)
          .fontSize(BODY)
          .fontColor(ACCENT)
          .onClick((): void => { this.onAction(); })
      }
    }
    .width('100%')
    .height(56)
    .padding({ left: 24, right: 24 })
    .backgroundColor(BG)
  }
}

// ===== Section Header =====

@Component
export struct SectionHeader {
  @Prop label: string = '';
  build(): void {
    Row() {
      Text('//')
        .fontSize(META)
        .fontColor(ACCENT)
        .fontWeight(FontWeight.Bold)
      Text(` ${this.label}`)
        .fontSize(META)
        .fontWeight(FontWeight.Medium)
        .fontColor(INK3)
    }
    .width('100%')
    .padding({ left: 16, top: 20, bottom: 6 })
  }
}

// ===== Flat Rows =====

// 导航行: label + › chevron, 点击导航
@Component
export struct NavRow {
  @Prop label: string = '';
  @Prop valueText: string = '';
  @Prop danger: boolean = false;
  onRowClick: () => void = (): void => {};

  build(): void {
    Row() {
      Text(this.label)
        .fontSize(BODY)
        .fontColor(this.danger ? ERROR : INK)
        .layoutWeight(1)
      if (this.valueText.length > 0) {
        Text(this.valueText)
          .fontSize(META)
          .fontColor(this.danger ? ERROR : INK3)
          .margin({ right: 8 })
      }
      Text('›')
        .fontSize(BODY)
        .fontColor(INK4)
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 12, bottom: 12 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
    .onClick((): void => { this.onRowClick(); })
  }
}

// 开关行: label + desc + Toggle
@Component
export struct ToggleRow {
  @Prop label: string = '';
  @Prop desc: string = '';
  @Prop checked: boolean = false;
  onToggle: () => void = (): void => {};

  build(): void {
    Row() {
      Column() {
        Text(this.label)
          .fontSize(BODY)
          .fontColor(INK)
        if (this.desc.length > 0) {
          Text(this.desc)
            .fontSize(LABEL)
            .fontColor(INK3)
            .margin({ top: 2 })
        }
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)

      Toggle({ type: ToggleType.Switch, isOn: this.checked })
        .onChange((_v: boolean): void => { this.onToggle(); })
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 12, bottom: 12 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
  }
}

// 选择行: label + 当前值(ACCENT), 点击
@Component
export struct SelectRow {
  @Prop label: string = '';
  @Prop valueText: string = '';
  onRowClick: () => void = (): void => {};

  build(): void {
    Row() {
      Text(this.label)
        .fontSize(BODY)
        .fontColor(INK)
        .layoutWeight(1)
      Text(this.valueText)
        .fontSize(META)
        .fontColor(ACCENT)
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 12, bottom: 12 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
    .onClick((): void => { this.onRowClick(); })
  }
}

// 文本行: label + 可编辑文本值(只读展示)
@Component
export struct TextRow {
  @Prop label: string = '';
  @Prop valueText: string = '';
  build(): void {
    Row() {
      Text(this.label)
        .fontSize(BODY)
        .fontColor(INK3)
        .layoutWeight(1)
      Text(this.valueText)
        .fontSize(BODY)
        .fontColor(INK)
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 12, bottom: 12 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
  }
}

// 页面尾部 spacer
@Component
export struct PageSpacer {
  build(): void {
    Column() {}.height(40)
  }
}
