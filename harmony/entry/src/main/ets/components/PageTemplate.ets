// PageTemplate — 全站共享页面骨架(G7 对齐 Amber Redesign)
//
// 设计来源: oc-amber.css + aa-pages.jsx + aa-settings.jsx
// 关键规则:
//   - mono = 机器信息(模型名/时间/eyebrow), sans = 人类文字
//   - flat & hairline first, shadow 仅用于 overlay
//   - 触摸目标 ≥ 44px

import { router } from '@kit.ArkUI';
import {
  INK, INK2, INK3, INK4, SURFACE, SURFACE2, RAISED, LINE, LINE2, BG,
  ACCENT, ACCENT_INK, ACCENT_LIGHT, SIGNAL, ERROR, CODE_BG,
  TITLE, HEADER, BODY, BODY2, META, MONO_SMALL, LABEL, TINY,
  PAD_CARD, PAD_ITEM, RADIUS, RADIUS_PILL, GUTTER,
} from '../design/tokens.ets';
import { Icon } from './ui/Icon.ets';

// ===== Page Header (对齐 PageHeader / SettingsHeader) =====
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
          .fontSize(24)
          .fontColor(INK)
          .onClick((): void => { this.onBack(); })
      }
      Text(this.title)
        .fontSize(TITLE)
        .fontWeight(FontWeight.Bold)
        .fontColor(INK)
        .margin({ left: this.showBack ? 8 : 0 })
        .layoutWeight(1)
      if (this.actionText.length > 0) {
        Text(this.actionText)
          .fontSize(BODY)
          .fontColor(ACCENT)
          .onClick((): void => { this.onAction(); })
      }
    }
    .width('100%')
    .padding({ left: 14, right: 14, top: 2, bottom: 11 })
    .border({ width: { bottom: 1 }, color: { bottom: LINE } })
    .backgroundColor(BG)
  }
}

// ===== Section Label (对齐 SectionLabel) =====
@Component
export struct SectionHeader {
  @Prop label: string = '';
  build(): void {
    Row() {
      Text('//')
        .fontSize(LABEL)
        .fontColor(ACCENT)
        .fontWeight(FontWeight.Bold)
      Text(` ${this.label.toUpperCase()}`)
        .fontSize(LABEL)
        .fontWeight(FontWeight.Bold)
        .fontColor(INK3)
    }
    .width('100%')
    .padding({ left: 6, top: 20, bottom: 4 })
  }
}

// ===== Flat Rows =====

@Component
export struct NavRow {
  @Prop label: string = '';
  @Prop valueText: string = '';
  @Prop danger: boolean = false;
  @Prop isLast: boolean = false;
  onRowClick: () => void = (): void => {};

  build(): void {
    Row() {
      Text(this.label)
        .fontSize(BODY)
        .fontWeight(FontWeight.Medium)
        .fontColor(this.danger ? ERROR : INK)
        .layoutWeight(1)
      if (this.valueText.length > 0) {
        Text(this.valueText)
          .fontSize(BODY2)
          .fontColor(this.danger ? ERROR : INK3)
          .fontFamily('monospace')
          .margin({ right: 8 })
      }
      Text('›')
        .fontSize(BODY)
        .fontColor(INK4)
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE)
    .border(this.isLast ? { width: 0 } : { width: { bottom: 0.5 }, color: { bottom: LINE } })
    .onClick((): void => { this.onRowClick(); })
  }
}

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
          .fontWeight(FontWeight.Medium)
          .fontColor(INK)
        if (this.desc.length > 0) {
          Text(this.desc)
            .fontSize(BODY2)
            .fontColor(INK3)
            .margin({ top: 2 })
        }
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)

      Toggle({ type: ToggleType.Switch, isOn: this.checked })
        .selectedColor(this.checked ? ACCENT : LINE2)
        .onChange((_v: boolean): void => { this.onToggle(); })
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
  }
}

@Component
export struct SelectRow {
  @Prop label: string = '';
  @Prop valueText: string = '';
  onRowClick: () => void = (): void => {};

  build(): void {
    Row() {
      Text(this.label)
        .fontSize(BODY)
        .fontWeight(FontWeight.Medium)
        .fontColor(INK)
        .layoutWeight(1)
      Text(this.valueText)
        .fontSize(BODY2)
        .fontColor(INK3)
        .fontFamily('monospace')
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
    .onClick((): void => { this.onRowClick(); })
  }
}

// 会话行(对齐 SessionRow): title + snippet + modelId + time
@Component
export struct SessionRowItem {
  @Prop title: string = '';
  @Prop snippet: string = '';
  @Prop modelId: string = '';
  @Prop timeText: string = '';
  @Prop pinned: boolean = false;
  onRowClick: () => void = (): void => {};

  build(): void {
    Row() {
      Column() {
        Row() {
          if (this.pinned) {
            Icon({ name: 'pin', iconSize: 13, color: ACCENT }).margin({ right: 7 })
          }
          Text(this.title.length > 0 ? this.title : '未命名对话')
            .fontSize(BODY)
            .fontWeight(FontWeight.Medium)
            .fontColor(INK)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .layoutWeight(1)
        }
        .width('100%')

        if (this.snippet.length > 0) {
          Text(this.snippet)
            .fontSize(BODY2)
            .fontColor(INK3)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .margin({ top: 3 })
            .width('100%')
        }

        if (this.modelId.length > 0) {
          Text(this.modelId)
            .fontSize(TINY)
            .fontColor(INK4)
            .fontFamily('monospace')
            .margin({ top: 6 })
        }
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)

      Text(this.timeText)
        .fontSize(LABEL)
        .fontColor(INK4)
        .fontFamily('monospace')
        .margin({ left: 11, top: 2 })
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
    .onClick((): void => { this.onRowClick(); })
  }
}

// 页面尾部 spacer
@Component
export struct PageSpacer {
  build(): void {
    Column() {}.height(40)
  }
}
