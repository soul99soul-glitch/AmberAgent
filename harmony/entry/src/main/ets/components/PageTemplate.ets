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
  TITLE, HEADER, BODY, BODY2, META, MONO_SMALL, LABEL, TINY, TEXT_STYLES,
  PAD_CARD, PAD_ITEM, RADIUS, RADIUS_PILL, GUTTER,
} from '../design/tokens.ets';
import { FONT_SANS_MEDIUM, FONT_MONO } from '../design/fonts.ets';
import { Icon } from './ui/Icon.ets';

// ===== Page Header (对齐 PageHeader / SettingsHeader) =====
@Component
export struct PageTitleBar {
  @Prop title: string = '';
  @Prop showBack: boolean = true;
  @Prop actionText: string = '';
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  onAction: () => void = (): void => {};
  onBack: () => void = (): void => { router.back(); };

  build(): void {
    Row() {
      if (this.showBack) {
        Row() {
          Icon({ name: 'back', iconSize: 22, color: INK })
        }
        .width(44).height(44)
        .justifyContent(FlexAlign.Center)
        .alignItems(VerticalAlign.Center)
        .accessibilityText('返回')
        .onClick((): void => { this.onBack(); })
      }
      Text(this.title)
        .fontSize(TEXT_STYLES.screenTitle.size)
        .fontWeight(TEXT_STYLES.screenTitle.weight)
        .lineHeight(TEXT_STYLES.screenTitle.lineHeight)
        .fontFamily(TEXT_STYLES.screenTitle.family)
        .fontColor(INK)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .margin({ left: this.showBack ? 8 : 0 })
        .layoutWeight(1)
      if (this.actionText.length > 0) {
        Row() {
          Text(this.actionText)
            .fontSize(TEXT_STYLES.secondary.size)
            .fontWeight(TEXT_STYLES.secondary.weight)
            .lineHeight(TEXT_STYLES.secondary.lineHeight)
            .fontFamily(TEXT_STYLES.secondary.family)
            .fontColor(this.accentColorStore)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .constraintSize({ minWidth: 44, minHeight: 44 })
        .justifyContent(FlexAlign.Center)
        .alignItems(VerticalAlign.Center)
        .accessibilityText(this.actionText)
        .onClick((): void => { this.onAction(); })
      }
    }
    .width('100%')
    // 无返回键时标题对齐页边距 GUTTER(=卡片左缘);有返回键时返回钮贴左,标题随钮
    .padding({ left: this.showBack ? 14 : GUTTER, right: GUTTER, top: this.statusBarHeight + 2, bottom: 11 })
    .border({ width: { bottom: 1 }, color: { bottom: LINE } })
    .backgroundColor(BG)
  }
}

// ===== Section Label (对齐 SectionLabel) =====
@Component
export struct SectionHeader {
  @Prop label: string = '';
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  build(): void {
    Row() {
      Text('//')
        .fontSize(TEXT_STYLES.eyebrow.size)
        .fontColor(this.accentColorStore)
        .fontWeight(TEXT_STYLES.eyebrow.weight)
        .lineHeight(TEXT_STYLES.eyebrow.lineHeight)
        .letterSpacing(TEXT_STYLES.eyebrow.letterSpacing)
        .fontFamily(TEXT_STYLES.eyebrow.family)
      Text(` ${this.label.toUpperCase()}`)
        .fontSize(TEXT_STYLES.eyebrow.size)
        .fontWeight(TEXT_STYLES.eyebrow.weight)
        .lineHeight(TEXT_STYLES.eyebrow.lineHeight)
        .letterSpacing(TEXT_STYLES.eyebrow.letterSpacing)
        .fontFamily(TEXT_STYLES.eyebrow.family)
        .fontColor(INK3)
    }
    .width('100%')
    // 对齐行内文字(行 padding 左 16):包边页 18+16=34,全出血页 16,两族一致成立
    .padding({ left: 16, top: 20, bottom: 4 })
  }
}

// ===== Flat Rows =====

@Component
export struct NavRow {
  @Prop label: string = '';
  @Prop valueText: string = '';
  @Prop danger: boolean = false;
  @Prop isLast: boolean = false;
  // 纯展示行(版本等)不绘箭头,避免"看似可点"的死控件感
  @Prop showChevron: boolean = true;
  onRowClick: () => void = (): void => {};
  @State private pressed: boolean = false;

  build(): void {
    Row() {
      Text(this.label)
        .fontSize(TEXT_STYLES.body.size)
        .fontWeight(TEXT_STYLES.body.weight)
        .lineHeight(TEXT_STYLES.body.lineHeight)
        .fontFamily(TEXT_STYLES.body.family)
        .fontColor(this.danger ? ERROR : INK)
        .layoutWeight(1)
      if (this.valueText.length > 0) {
        Text(this.valueText)
          .fontSize(TEXT_STYLES.secondary.size)
          .fontWeight(TEXT_STYLES.secondary.weight)
          .lineHeight(TEXT_STYLES.secondary.lineHeight)
          .fontFamily(FONT_MONO)
          .fontColor(this.danger ? ERROR : INK3)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .textAlign(TextAlign.End)
          .margin({ right: 8 })
      }
      if (this.showChevron) {
        Icon({ name: 'chevron_right', iconSize: 16, color: INK4 })
      }
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(this.pressed ? SURFACE2 : SURFACE)
    .border(this.isLast ? { width: 0 } : { width: { bottom: 0.5 }, color: { bottom: LINE } })
    .onTouch((e: TouchEvent): void => {
      if (e.type === TouchType.Down) this.pressed = true;
      else if (e.type === TouchType.Up || e.type === TouchType.Cancel) this.pressed = false;
    })
    .onClick((): void => { this.onRowClick(); })
  }
}

@Component
export struct ToggleRow {
  @Prop label: string = '';
  @Prop desc: string = '';
  @Prop checked: boolean = false;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  onToggle: () => void = (): void => {};

  build(): void {
    Row() {
      Column() {
        Text(this.label)
          .fontSize(BODY)
          .fontWeight(FontWeight.Medium)
          .lineHeight(TEXT_STYLES.body.lineHeight)
          .fontFamily(FONT_SANS_MEDIUM)
          .fontColor(INK)
        if (this.desc.length > 0) {
          Text(this.desc)
            .fontSize(BODY2)
            .lineHeight(TEXT_STYLES.secondary.lineHeight)
            .fontFamily(TEXT_STYLES.secondary.family)
            .fontColor(INK3)
            .maxLines(2)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .margin({ top: 2 })
        }
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)

      Toggle({ type: ToggleType.Switch, isOn: this.checked })
        .selectedColor(this.checked ? this.accentColorStore : LINE2)
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
        .lineHeight(TEXT_STYLES.body.lineHeight)
        .fontFamily(FONT_SANS_MEDIUM)
        .fontColor(INK)
        .layoutWeight(1)
      Text(this.valueText)
        .fontSize(BODY2)
        .lineHeight(TEXT_STYLES.secondary.lineHeight)
        .fontFamily(FONT_MONO)
        .fontColor(INK3)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .textAlign(TextAlign.End)
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
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  onRowClick: () => void = (): void => {};

  build(): void {
    Row() {
      Column() {
        Row() {
          if (this.pinned) {
            Icon({ name: 'pin', iconSize: 13, color: this.accentColorStore }).margin({ right: 7 })
          }
          Text(this.title.length > 0 ? this.title : '未命名对话')
            .fontSize(BODY)
            .fontWeight(FontWeight.Medium)
            .lineHeight(TEXT_STYLES.body.lineHeight)
            .fontFamily(FONT_SANS_MEDIUM)
            .fontColor(INK)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .layoutWeight(1)
        }
        .width('100%')

        if (this.snippet.length > 0) {
          Text(this.snippet)
            .fontSize(BODY2)
            .lineHeight(TEXT_STYLES.secondary.lineHeight)
            .fontFamily(TEXT_STYLES.secondary.family)
            .fontColor(INK3)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .margin({ top: 3 })
            .width('100%')
        }

        if (this.modelId.length > 0) {
          Text(this.modelId)
            .fontSize(TINY)
            .lineHeight(TEXT_STYLES.meta.lineHeight)
            .fontFamily(FONT_MONO)
            .fontColor(INK4)
            .margin({ top: 6 })
        }
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)

      Text(this.timeText)
        .fontSize(LABEL)
        .lineHeight(TEXT_STYLES.meta.lineHeight)
        .fontFamily(FONT_MONO)
        .fontColor(INK4)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
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
