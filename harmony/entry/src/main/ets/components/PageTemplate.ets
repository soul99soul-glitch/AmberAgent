// PageTemplate — 全站共享页面骨架(G7 对齐 Amber Redesign + youhua 纸面列表优化版)
//
// 设计来源: oc-amber.css + aa-pages.jsx + amber-youhua.html(设置收成纸面列表)
// 关键规则:
//   - mono = 机器信息(模型名/时间/eyebrow), sans = 人类文字
//   - flat & hairline first, shadow 仅用于 overlay;设置域 = 纸面列表:
//     无卡片分组、行间发丝线(内缩)、40 圆形图标座、行标题 15/600 + 描述 13
//   - 触摸目标 ≥ 44px

import { router } from '@kit.ArkUI';
import {
  INK, INK2, INK3, INK4, SURFACE, SURFACE2, RAISED, LINE, LINE2, BG,
  ACCENT, ACCENT_INK, ACCENT_LIGHT, SIGNAL, ERROR, CODE_BG,
  TITLE, HEADER, BODY, BODY2, META, MONO_SMALL, LABEL, TINY, TEXT_STYLES,
  PAD_CARD, PAD_ITEM, RADIUS, RADIUS_PILL, GUTTER, lineHeightForSize,
} from '../design/tokens.ets';
import { FONT_SANS_MEDIUM, FONT_SANS_SEMIBOLD, FONT_MONO, FONT_MONO_MEDIUM } from '../design/fonts.ets';
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
          Icon({ name: 'back', iconSize: 24, color: INK })
        }
        .width(48).height(48)
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
    // amber-zhenji topbar:高 64,返回钮 48 热区、贴边 4px
    .constraintSize({ minHeight: 64 })
    .padding({ left: this.showBack ? 4 : GUTTER, right: GUTTER, top: this.statusBarHeight, bottom: 8 })
    .border({ width: { bottom: 1 }, color: { bottom: LINE } })
    .backgroundColor(BG)
  }
}

// ===== Section Label (youhua .sec-label:`//` + 标签 + 右侧发丝线) =====
@Component
export struct SectionHeader {
  @Prop label: string = '';
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  build(): void {
    Row() {
      Text('//')
        .fontSize(META)
        .fontColor(this.accentColorStore)
        .fontWeight(FontWeight.Medium)
        .lineHeight(TEXT_STYLES.meta.lineHeight)
        .fontFamily(FONT_MONO_MEDIUM)
      Text(` ${this.label.toUpperCase()}`)
        .fontSize(META)
        .fontWeight(FontWeight.Medium)
        .lineHeight(TEXT_STYLES.meta.lineHeight)
        .fontFamily(FONT_MONO_MEDIUM)
        .fontColor(INK2)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
      // 标签右侧延伸到行尾的发丝线(youhua .sec-label::after)
      Row()
        .layoutWeight(1)
        .height(1)
        .backgroundColor(LINE)
        .margin({ left: 8 })
    }
    .width('100%')
    .alignItems(VerticalAlign.Center)
    // 对齐行内文字(行 padding 左 16):包边页 18+16=34,全出血页 16,两族一致成立
    .padding({ left: 16, right: 16, top: 20, bottom: 8 })
  }
}

// ===== Paper Rows(youhua .set-row:透明底 + 内缩发丝线 + minH64) =====

// 纸面行通用:内容行 + 非末行时追加左右内缩 16 的 1px 分隔线
@Component
export struct PaperSeparator {
  build(): void {
    Row()
      .width('100%')
      .height(1)
      .backgroundColor(LINE)
      .margin({ left: 16, right: 16 })
  }
}

// youhua .set-ico:40 圆形图标座(LINE 描边 + SURFACE 底 + INK2 图标)
@Component
export struct IconSeat {
  @Prop iconName: string = '';
  build(): void {
    Row() {
      Icon({ name: this.iconName, iconSize: 18, color: INK2 })
    }
    .width(40).height(40)
    .borderRadius(20)
    .border({ width: 1, color: LINE })
    .backgroundColor(SURFACE)
    .justifyContent(FlexAlign.Center)
    .alignItems(VerticalAlign.Center)
  }
}

// youhua .chip:mono 10.5 药丸(SURFACE 底 + LINE2 描边),可带下拉箭头
@Component
export struct Chip {
  @Prop text: string = '';
  @Prop withChevron: boolean = false;
  build(): void {
    Row() {
      Text(this.text)
        .fontSize(TINY)
        .lineHeight(TEXT_STYLES.tinyTag.lineHeight)
        .fontFamily(FONT_MONO_MEDIUM)
        .fontColor(INK3)
        .maxLines(1)
      if (this.withChevron) {
        Icon({ name: 'chevron_down', iconSize: 12, color: INK3 }).margin({ left: 4 })
      }
    }
    .alignItems(VerticalAlign.Center)
    .constraintSize({ minHeight: 22 })
    .padding({ left: 8, right: this.withChevron ? 6 : 8, top: 3, bottom: 3 })
    .borderRadius(6)
    .backgroundColor(SURFACE)
    .border({ width: 1, color: LINE2 })
  }
}

// youhua .btn-fill:accent 实底主按钮(minH44 r8)
@Component
export struct BtnFill {
  @Prop label: string = '';
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  onTap: () => void = (): void => {};
  build(): void {
    Row() {
      Text(this.label)
        .fontSize(14)
        .fontWeight(600)
        .lineHeight(lineHeightForSize(14))
        .fontFamily(FONT_SANS_SEMIBOLD)
        .fontColor(ACCENT_INK)
    }
    .justifyContent(FlexAlign.Center)
    .alignItems(VerticalAlign.Center)
    .constraintSize({ minHeight: 44 })
    .padding({ left: 16, right: 16 })
    .borderRadius(8)
    .backgroundColor(this.accentColorStore)
    .onClick((): void => { this.onTap(); })
  }
}

// youhua .btn-ghost:LINE 描边次按钮(minH44 r8)
@Component
export struct BtnGhost {
  @Prop label: string = '';
  onTap: () => void = (): void => {};
  @State private pressed: boolean = false;
  build(): void {
    Row() {
      Text(this.label)
        .fontSize(14)
        .fontWeight(FontWeight.Medium)
        .lineHeight(lineHeightForSize(14))
        .fontFamily(FONT_SANS_MEDIUM)
        .fontColor(INK)
    }
    .justifyContent(FlexAlign.Center)
    .alignItems(VerticalAlign.Center)
    .constraintSize({ minHeight: 44 })
    .padding({ left: 16, right: 16 })
    .borderRadius(8)
    .border({ width: 1, color: LINE })
    .backgroundColor(this.pressed ? SURFACE2 : Color.Transparent)
    .onTouch((e: TouchEvent): void => {
      if (e.type === TouchType.Down) this.pressed = true;
      else if (e.type === TouchType.Up || e.type === TouchType.Cancel) this.pressed = false;
    })
    .onClick((): void => { this.onTap(); })
  }
}

// youhua .seg:LINE 描边一体式分段控件(r8,段 minH44,激活 SURFACE2 底 + INK)
@Component
export struct Seg {
  @Prop options: string[] = [];
  @Prop selected: number = 0;
  onSelect: (idx: number) => void = (_idx: number): void => {};
  build(): void {
    Row() {
      ForEach(this.options, (opt: string, idx: number): void => {
        Text(opt)
          .fontSize(13)
          .fontWeight(this.selected === idx ? 600 : FontWeight.Medium)
          .lineHeight(lineHeightForSize(13))
          .fontFamily(this.selected === idx ? FONT_SANS_SEMIBOLD : FONT_SANS_MEDIUM)
          .fontColor(this.selected === idx ? INK : INK2)
          .textAlign(TextAlign.Center)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .layoutWeight(1)
          .constraintSize({ minHeight: 44 })
          .padding({ top: 10, bottom: 10, left: 8, right: 8 })
          .backgroundColor(this.selected === idx ? SURFACE2 : Color.Transparent)
          .onClick((): void => { this.onSelect(idx); })
      }, (opt: string, idx: number): string => `seg-${idx}-${opt}`)
    }
    .width('100%')
    .border({ width: 1, color: LINE })
    .borderRadius(8)
    .clip(true)
  }
}

// youhua .set-row 导航行:图标座 + 标题/描述 + 值(chip/文本/链接动作)+ 箭头
@Component
export struct NavRow {
  @Prop label: string = '';
  @Prop desc: string = '';
  @Prop valueText: string = '';
  // chip 形态值(颜色模式等):含下拉箭头,显示时抑制行尾箭头
  @Prop chipText: string = '';
  // 链接动作(导出/应用等 link-act):显示时抑制行尾箭头
  @Prop linkText: string = '';
  @Prop danger: boolean = false;
  @Prop isLast: boolean = false;
  // 纯展示行(版本等)不绘箭头,避免"看似可点"的死控件感
  @Prop showChevron: boolean = true;
  @Prop iconName: string = '';
  onRowClick: () => void = (): void => {};
  @State private pressed: boolean = false;

  build(): void {
    Column() {
      Row() {
        if (this.iconName.length > 0) {
          IconSeat({ iconName: this.iconName }).margin({ right: 12 })
        }
        Column() {
          Text(this.label)
            .fontSize(TEXT_STYLES.body.size)
            .fontWeight(600)
            .lineHeight(TEXT_STYLES.body.lineHeight)
            .fontFamily(FONT_SANS_SEMIBOLD)
            .letterSpacing(-0.2)
            .fontColor(this.danger ? ERROR : INK)
          if (this.desc.length > 0) {
            Text(this.desc)
              .fontSize(TEXT_STYLES.secondary.size)
              .lineHeight(TEXT_STYLES.secondary.lineHeight)
              .fontFamily(TEXT_STYLES.secondary.family)
              .fontColor(INK3)
              .maxLines(2)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .margin({ top: 4 })
          }
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        if (this.chipText.length > 0) {
          Chip({ text: this.chipText, withChevron: true }).margin({ right: 8, left: 8 })
        } else if (this.valueText.length > 0) {
          Text(this.valueText)
            .fontSize(TEXT_STYLES.meta.size)
            .fontWeight(FontWeight.Medium)
            .lineHeight(TEXT_STYLES.meta.lineHeight)
            .fontFamily(FONT_MONO_MEDIUM)
            .fontColor(this.danger ? ERROR : INK3)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .textAlign(TextAlign.End)
            .margin({ right: 8, left: 8 })
        } else if (this.linkText.length > 0) {
          Text(this.linkText)
            .fontSize(META)
            .fontWeight(FontWeight.Medium)
            .lineHeight(TEXT_STYLES.meta.lineHeight)
            .fontFamily(FONT_MONO_MEDIUM)
            .fontColor(INK3)
            .maxLines(1)
            .margin({ left: 12 })
        }
        if (this.showChevron && this.chipText.length === 0 && this.linkText.length === 0) {
          Icon({ name: 'chevron_right', iconSize: 16, color: INK4 })
        }
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 64 })
      .padding({ left: 16, right: 16, top: 12, bottom: 12 })
      .backgroundColor(this.pressed ? SURFACE2 : Color.Transparent)
      .onTouch((e: TouchEvent): void => {
        if (e.type === TouchType.Down) this.pressed = true;
        else if (e.type === TouchType.Up || e.type === TouchType.Cancel) this.pressed = false;
      })
      .onClick((): void => { this.onRowClick(); })
      if (!this.isLast) {
        PaperSeparator()
      }
    }
    .width('100%')
  }
}

// youhua .set-row 开关行:标题/描述 + 42×24 自绘药丸开关(150ms)
@Component
export struct ToggleRow {
  @Prop label: string = '';
  @Prop desc: string = '';
  @Prop checked: boolean = false;
  @Prop isLast: boolean = false;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  onToggle: () => void = (): void => {};

  build(): void {
    Column() {
      Row() {
        Column() {
          Text(this.label)
            .fontSize(TEXT_STYLES.body.size)
            .fontWeight(600)
            .lineHeight(TEXT_STYLES.body.lineHeight)
            .fontFamily(FONT_SANS_SEMIBOLD)
            .letterSpacing(-0.2)
            .fontColor(INK)
          if (this.desc.length > 0) {
            Text(this.desc)
              .fontSize(TEXT_STYLES.secondary.size)
              .lineHeight(TEXT_STYLES.secondary.lineHeight)
              .fontFamily(TEXT_STYLES.secondary.family)
              .fontColor(INK3)
              .maxLines(2)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .margin({ top: 4 })
          }
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)

        Stack({ alignContent: Alignment.Start }) {
          Column()
            .width(42).height(24).borderRadius(99)
            .backgroundColor(this.checked ? this.accentColorStore : LINE2)
          Column()
            .width(20).height(20).borderRadius(10)
            .backgroundColor('#FFFFFF') // 开关滑块固定纯白:暗色 off 轨(LINE2)上仍需对比
            .margin({ left: this.checked ? 22 : 2 })
            .animation({ duration: 150, curve: Curve.EaseOut })
        }
        .clip(true)
        .margin({ left: 12 })
        .onClick((): void => { this.onToggle(); })
        .accessibilityText(this.checked ? '已开启' : '已关闭')
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 64 })
      .padding({ left: 16, right: 16, top: 12, bottom: 12 })
      if (!this.isLast) {
        PaperSeparator()
      }
    }
    .width('100%')
  }
}

// youhua .set-row 选择行:标题 + 等宽值
@Component
export struct SelectRow {
  @Prop label: string = '';
  @Prop desc: string = '';
  @Prop valueText: string = '';
  @Prop isLast: boolean = false;
  onRowClick: () => void = (): void => {};
  @State private pressed: boolean = false;

  build(): void {
    Column() {
      Row() {
        Column() {
          Text(this.label)
            .fontSize(TEXT_STYLES.body.size)
            .fontWeight(600)
            .lineHeight(TEXT_STYLES.body.lineHeight)
            .fontFamily(FONT_SANS_SEMIBOLD)
            .letterSpacing(-0.2)
            .fontColor(INK)
          if (this.desc.length > 0) {
            Text(this.desc)
              .fontSize(TEXT_STYLES.secondary.size)
              .lineHeight(TEXT_STYLES.secondary.lineHeight)
              .fontFamily(TEXT_STYLES.secondary.family)
              .fontColor(INK3)
              .maxLines(2)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .margin({ top: 4 })
          }
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        Text(this.valueText)
          .fontSize(TEXT_STYLES.meta.size)
          .fontWeight(FontWeight.Medium)
          .lineHeight(TEXT_STYLES.meta.lineHeight)
          .fontFamily(FONT_MONO_MEDIUM)
          .fontColor(INK3)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .textAlign(TextAlign.End)
          .margin({ left: 12 })
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 64 })
      .padding({ left: 16, right: 16, top: 12, bottom: 12 })
      .backgroundColor(this.pressed ? SURFACE2 : Color.Transparent)
      .onTouch((e: TouchEvent): void => {
        if (e.type === TouchType.Down) this.pressed = true;
        else if (e.type === TouchType.Up || e.type === TouchType.Cancel) this.pressed = false;
      })
      .onClick((): void => { this.onRowClick(); })
      if (!this.isLast) {
        PaperSeparator()
      }
    }
    .width('100%')
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
