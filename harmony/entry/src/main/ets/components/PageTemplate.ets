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
  WARNING_FG, WARNING_BG, ERROR_BG, SUCCESS_BG, SUCCESS_FG, accentSoftBg,
  TITLE, HEADER, BODY, BODY2, META, MONO_SMALL, LABEL, TINY, TEXT_STYLES,
  PAD_CARD, PAD_ITEM, RADIUS, RADIUS_PILL, GUTTER, lineHeightForSize,
} from '../design/tokens.ets';
import { FONT_SANS_MEDIUM, FONT_SANS_SEMIBOLD, FONT_SANS_BOLD, FONT_MONO, FONT_MONO_MEDIUM } from '../design/fonts.ets';
import { Icon } from './ui/Icon.ets';

// ===== Page Header (对齐 PageHeader / SettingsHeader) =====
@Component
export struct PageTitleBar {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
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
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    // amber-zhenji topbar:高 64,返回钮 48 热区、贴边 4px
    .constraintSize({ minHeight: 64 })
    .padding({ left: this.showBack ? 4 : GUTTER, right: GUTTER, top: this.statusBarHeight, bottom: 8 })
    .border({ width: { bottom: 1 }, color: { bottom: LINE } })
    .backgroundColor(BG)
  }
}

// ===== Section Label (youhua .sec-label:`//` + 标签) =====
// 2026-09-29:右侧发丝线移除(用户裁定对齐 iOS 素组标;分隔线过多)
@Component
export struct SectionHeader {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
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
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
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
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  build(): void {
    Row()
      
      .opacity(this.themeEpoch >= 0 ? 1 : 1).width('100%')
      .height(1)
      .backgroundColor(LINE)
      .margin({ left: 16, right: 16 })
  }
}

// youhua .set-ico:40 圆形图标座(LINE 描边 + SURFACE 底 + INK2 图标)
@Component
export struct IconSeat {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop iconName: string = '';
  build(): void {
    Row() {
      Icon({ name: this.iconName, iconSize: 18, color: INK2 })
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
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
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop text: string = '';
  @Prop withChevron: boolean = false;
  @Prop danger: boolean = false;
  build(): void {
    Row() {
      Text(this.text)
        .fontSize(TINY)
        .lineHeight(TEXT_STYLES.tinyTag.lineHeight)
        .fontFamily(FONT_MONO_MEDIUM)
        .fontColor(this.danger ? ERROR : INK3)
        .maxLines(1)
      if (this.withChevron) {
        Icon({ name: 'chevron_down', iconSize: 12, color: INK3 }).margin({ left: 4 })
      }
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .alignItems(VerticalAlign.Center)
    .constraintSize({ minHeight: 22 })
    .padding({ left: 8, right: this.withChevron ? 6 : 8, top: 3, bottom: 3 })
    .borderRadius(6)
    .backgroundColor(SURFACE)
    .border({ width: 1, color: this.danger ? ERROR : LINE2 })
  }
}

// youhua .btn-fill:accent 实底主按钮(minH44 r8)
@Component
export struct BtnFill {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop label: string = '';
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  onTap: () => void = (): void => {};
  // V2 按压态:0.975 scale + 透明度(.pressable:active,同 ChatInput attach/send)
  @State private pressed: boolean = false;
  build(): void {
    Row() {
      Text(this.label)
        .fontSize(14)
        .fontWeight(600)
        .lineHeight(lineHeightForSize(14))
        .fontFamily(FONT_SANS_SEMIBOLD)
        .fontColor(ACCENT_INK)
    }
      .enabled(this.themeEpoch >= 0)
    .justifyContent(FlexAlign.Center)
    .alignItems(VerticalAlign.Center)
    .constraintSize({ minHeight: 44 })
    .padding({ left: 16, right: 16 })
    .borderRadius(8)
    .backgroundColor(this.accentColorStore)
    .scale({ x: this.pressed ? 0.975 : 1, y: this.pressed ? 0.975 : 1 })
    .opacity(this.pressed ? 0.9 : 1)
    .onTouch((e: TouchEvent): void => {
      const down: boolean = e.type === TouchType.Down;
      animateTo({ duration: 140, curve: Curve.EaseOut }, (): void => {
        this.pressed = down;
      });
    })
    .onClick((): void => { this.onTap(); })
  }
}

// youhua .btn-ghost:LINE 描边次按钮(minH44 r8)
@Component
export struct BtnGhost {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
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
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .justifyContent(FlexAlign.Center)
    .alignItems(VerticalAlign.Center)
    .constraintSize({ minHeight: 44 })
    .padding({ left: 16, right: 16 })
    .borderRadius(8)
    .border({ width: 1, color: LINE })
    .backgroundColor(this.pressed ? SURFACE2 : Color.Transparent)
    .scale({ x: this.pressed ? 0.975 : 1, y: this.pressed ? 0.975 : 1 })
    .onTouch((e: TouchEvent): void => {
      const down: boolean = e.type === TouchType.Down;
      animateTo({ duration: 140, curve: Curve.EaseOut }, (): void => {
        this.pressed = down;
      });
    })
    .onClick((): void => { this.onTap(); })
  }
}

// youhua .seg 分段控件(iOS segmented 化:灰轨 r10 无外框,选中白 pill r8 内缩 2)
// 乐观选择:点击先翻本地选中态(即时反馈),onSelect 持久化后由 selected 重同步;
//   修复「点击没即时反应,退出重进才发现变了」— 控件级即时响应不依赖页面渲染链
@Component
export struct Seg {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop options: string[] = [];
  @Prop @Watch('syncFromProp') selected: number = 0;
  @State private optimisticSelected: number = -1;
  onSelect: (idx: number) => void = (_idx: number): void => {};

  private syncFromProp(): void {
    this.optimisticSelected = -1;
  }

  private displayed(): number {
    return this.optimisticSelected >= 0 ? this.optimisticSelected : this.selected;
  }

  build(): void {
    Row() {
      ForEach(this.options, (opt: string, idx: number): void => {
        Text(opt)
          .fontSize(13)
          .fontWeight(this.displayed() === idx ? 600 : FontWeight.Medium)
          .lineHeight(lineHeightForSize(13))
          .fontFamily(this.displayed() === idx ? FONT_SANS_SEMIBOLD : FONT_SANS_MEDIUM)
          .fontColor(this.displayed() === idx ? INK : INK2)
          .textAlign(TextAlign.Center)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .layoutWeight(1)
          .constraintSize({ minHeight: 40 })
          .padding({ top: 9, bottom: 9, left: 8, right: 8 })
          .borderRadius(8)
          .backgroundColor(this.displayed() === idx ? RAISED : Color.Transparent)
          // 暗色下 RAISED 与轨道亮度差不足 2%,选中 pill 加发丝描边兜底可见性
          .border({ width: this.displayed() === idx ? 0.5 : 0, color: LINE2 })
          .margin(2)
          .onClick((): void => {
            if (this.displayed() === idx) return;
            this.optimisticSelected = idx;
            this.onSelect(idx);
          })
      }, (opt: string, idx: number): string => `seg-${idx}-${opt}`)
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .borderRadius(10)
    .backgroundColor(SURFACE2)
    .clip(true)
  }
}

// youhua .set-row 导航行:图标座 + 标题/描述 + 值(chip/文本/链接动作)+ 箭头
@Component
export struct NavRow {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
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
            .margin({ right: this.showChevron ? 8 : 0, left: 8 })
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
      .scale({ x: this.pressed ? 0.975 : 1, y: this.pressed ? 0.975 : 1 })
      .onTouch((e: TouchEvent): void => {
        const down: boolean = e.type === TouchType.Down;
        animateTo({ duration: 140, curve: Curve.EaseOut }, (): void => {
          this.pressed = down;
        });
      })
      .onClick((): void => { this.onRowClick(); })
      if (!this.isLast) {
        PaperSeparator()
      }
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
  }
}

// ===== 卡片分组(2026-09-27 设置重排:对齐 iOS SettingsHome / Android 新版六组卡片 IA)=====
// 奶油画布上的圆角卡片:SURFACE 底 clip;行间发丝线内缩至文字起点
// radius/stroke 可选:设置根页 r16 无描边(Android);二级页 r18+0.5 发丝描边(iOS AmberFormGroup)
@Component
export struct CardGroup {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop radius: number = 16;
  @Prop stroke: boolean = false;
  @BuilderParam content: () => void;
  build(): void {
    Column() {
      this.content();
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .borderRadius(this.radius)
    .backgroundColor(SURFACE)
    .border({ width: this.stroke ? 0.5 : 0, color: LINE })
    .clip(true)
  }
}

// 卡片行:着色图标座 + 标题/可选副标题 + 值(mono)+ 箭头;按压 SURFACE2
// plateStyle: 'neutral' SURFACE2 座 INK2 图标(默认,根页)| 'accent' ACCENT_LIGHT 座 accent 图标(iOS 二级页)
// plateSize: 32(根页)| 28(iOS AmberFormRow)
@Component
export struct CardRow {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop label: string = '';
  @Prop desc: string = '';
  @Prop valueText: string = '';
  @Prop iconName: string = '';
  @Prop plateStyle: string = 'neutral';
  @Prop plateSize: number = 32;
  @Prop isLast: boolean = false;
  // 纯展示行(版本等)不绘箭头,避免"看似可点"的死控件感
  @Prop showChevron: boolean = true;
  onRowClick: () => void = (): void => {};
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @State private pressed: boolean = false;

  build(): void {
    Column() {
      Row() {
        if (this.iconName.length > 0) {
          Row() {
            Icon({
              name: this.iconName,
              iconSize: this.plateSize >= 32 ? 19 : 15,
              color: this.plateStyle === 'accent' ? this.accentColorStore : INK2
            })
          }
          .width(this.plateSize).height(this.plateSize)
          .borderRadius(this.plateSize >= 32 ? 9 : 7)
          .backgroundColor(this.plateStyle === 'accent' ? accentSoftBg(this.accentColorStore) : SURFACE2)
          .justifyContent(FlexAlign.Center)
          .alignItems(VerticalAlign.Center)
          .margin({ right: 12 })
        }
        Column() {
          Text(this.label)
            .fontSize(16)
            .fontWeight(FontWeight.Medium)
            .lineHeight(lineHeightForSize(16))
            .fontFamily(FONT_SANS_MEDIUM)
            .fontColor(INK)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
          if (this.desc.length > 0) {
            Text(this.desc)
              .fontSize(12)
              .lineHeight(lineHeightForSize(12))
              .fontFamily(FONT_SANS_MEDIUM)
              .fontColor(INK3)
              .maxLines(2)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .margin({ top: 2 })
          }
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        if (this.valueText.length > 0) {
          Text(this.valueText)
            .fontSize(TEXT_STYLES.meta.size)
            .fontWeight(FontWeight.Medium)
            .lineHeight(TEXT_STYLES.meta.lineHeight)
            .fontFamily(FONT_MONO_MEDIUM)
            .fontColor(INK3)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .textAlign(TextAlign.End)
            .margin({ left: 8, right: 6 })
        }
        if (this.showChevron) {
          Icon({ name: 'chevron_right', iconSize: 16, color: INK4 })
        }
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 52 })
      .padding({ left: 14, right: 14, top: 10, bottom: 10 })
      .backgroundColor(this.pressed ? SURFACE2 : Color.Transparent)
      .onTouch((e: TouchEvent): void => {
        const down: boolean = e.type === TouchType.Down;
        animateTo({ duration: 140, curve: Curve.EaseOut }, (): void => {
          this.pressed = down;
        });
      })
      .onClick((): void => { this.onRowClick(); })
      if (!this.isLast) {
        // 分隔线起点对齐标题文字(行 padding 14 + 图标座 + 间距 12);
        // 横向内缩走外层 padding:width('100%') + margin 在本布局下不生效
        Row() {
          Row()
            .width('100%')
            .height(1)
            .backgroundColor(LINE)
        }
        .width('100%')
        // 分隔线起点对齐标题文字:有图标座时 = 14+座+12,无图标时 = 行 padding 14
        .padding({ left: this.iconName.length > 0 ? 14 + this.plateSize + 12 : 14, right: 14 })
      }
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
  }
}

// ===== iOS 二级页骨架(2026-09-27 二级页 iOS 对齐;规格:amberagent-ios PlaceholderViews.swift)=====

// iOS 顶栏:44 玻璃圆返回钮 + 整宽居中标题/副标题 + 可选右侧动作(文本 或 accent 圆图标)
//   规格: AmberGlassCircleButton(44,symbol 17 semibold,ink)+ .title2 bold 居中;无底部发丝线
@Component
export struct CenteredTitleBar {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop title: string = '';
  @Prop subtitle: string = '';
  // 右侧文本动作(如「管理」),accent 色
  @Prop actionText: string = '';
  // 右侧 accent 圆图标动作(如 'plus');与 actionText 互斥,actionText 优先
  @Prop actionIcon: string = '';
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  onAction: () => void = (): void => {};
  onBack: () => void = (): void => { router.back(); };

  build(): void {
    Stack({ alignContent: Alignment.Center }) {
      // 整宽居中标题(不被左右钮挤偏)
      Column() {
        Text(this.title)
          .fontSize(22)
          .fontWeight(FontWeight.Bold)
          .lineHeight(lineHeightForSize(22))
          .fontFamily(FONT_SANS_BOLD)
          .fontColor(INK)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
        if (this.subtitle.length > 0) {
          Text(this.subtitle)
            .fontSize(13)
            .lineHeight(lineHeightForSize(13))
            .fontFamily(FONT_SANS_MEDIUM)
            .fontColor(INK3)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .margin({ top: 2 })
        }
      }
      .alignItems(HorizontalAlign.Center)
      .padding({ left: 56, right: 56 })
      .width('100%')

      Row() {
        Row() {
          Icon({ name: 'back', iconSize: 18, color: INK })
        }
        .width(44).height(44)
        .borderRadius(22)
        .backgroundColor(SURFACE)
        .border({ width: 0.5, color: LINE })
        .justifyContent(FlexAlign.Center)
        .alignItems(VerticalAlign.Center)
        .accessibilityText('返回')
        .onClick((): void => { this.onBack(); })
        Blank()
        if (this.actionText.length > 0) {
          Row() {
            Text(this.actionText)
              .fontSize(15)
              .fontWeight(FontWeight.Medium)
              .lineHeight(lineHeightForSize(15))
              .fontFamily(FONT_SANS_MEDIUM)
              .fontColor(this.accentColorStore)
              .maxLines(1)
          }
          .constraintSize({ minWidth: 44, minHeight: 44 })
          .justifyContent(FlexAlign.Center)
          .alignItems(VerticalAlign.Center)
          .accessibilityText(this.actionText)
          .onClick((): void => { this.onAction(); })
        } else if (this.actionIcon.length > 0) {
          Row() {
            Icon({ name: this.actionIcon, iconSize: 20, color: ACCENT_INK })
          }
          .width(44).height(44)
          .borderRadius(22)
          .backgroundColor(this.accentColorStore)
          .justifyContent(FlexAlign.Center)
          .alignItems(VerticalAlign.Center)
          .onClick((): void => { this.onAction(); })
        }
      }
      .width('100%')
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .padding({ left: 16, right: 16, top: this.statusBarHeight + 10, bottom: 14 })
    .backgroundColor(BG)
  }
}

// iOS 组标(AmberSectionLabel):caption 12 semibold muted,拉丁大写,tracking 0.4;无 // 无发丝线
@Component
export struct PlainSection {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop label: string = '';
  build(): void {
    Text(this.label.toUpperCase())
      
      .opacity(this.themeEpoch >= 0 ? 1 : 1).fontSize(12)
      .fontWeight(600)
      .lineHeight(lineHeightForSize(12))
      .fontFamily(FONT_SANS_SEMIBOLD)
      .letterSpacing(0.4)
      .fontColor(INK3)
      .width('100%')
      .padding({ left: 16, right: 16, top: 20, bottom: 7 })
  }
}

// iOS 卡片开关行:标题/副标题 + accent 药丸开关(开时 accent 底);无图标
// 乐观翻转:点击先翻本地开关态(即时反馈),onToggle 持久化后由 checked 重同步
@Component
export struct CardToggleRow {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop label: string = '';
  @Prop desc: string = '';
  @Prop @Watch('syncFromProp') checked: boolean = false;
  @Prop isLast: boolean = false;
  @State private optimisticChecked: boolean = false;
  @State private hasOptimistic: boolean = false;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  onToggle: () => void = (): void => {};

  private syncFromProp(): void {
    this.hasOptimistic = false;
  }

  private displayed(): boolean {
    return this.hasOptimistic ? this.optimisticChecked : this.checked;
  }

  build(): void {
    Column() {
      Row() {
        Column() {
          Text(this.label)
            .fontSize(16)
            .fontWeight(FontWeight.Medium)
            .lineHeight(lineHeightForSize(16))
            .fontFamily(FONT_SANS_MEDIUM)
            .fontColor(INK)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
          if (this.desc.length > 0) {
            Text(this.desc)
              .fontSize(12)
              .lineHeight(lineHeightForSize(12))
              .fontFamily(FONT_SANS_MEDIUM)
              .fontColor(INK3)
              .maxLines(2)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .margin({ top: 2 })
          }
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)

        Row() {
          Stack({ alignContent: Alignment.Start }) {
            Column()
              .width(42).height(24).borderRadius(99)
              .backgroundColor(this.displayed() ? this.accentColorStore : LINE2)
            Column()
              .width(20).height(20).borderRadius(10)
              .backgroundColor(Color.White) // 滑块固定纯白:off 轨上仍需对比
              .margin({ left: this.displayed() ? 22 : 2 })
              .animation({ duration: 150, curve: Curve.EaseOut })
          }
          .clip(true)
        }
        .constraintSize({ minWidth: 44, minHeight: 44 })
        .justifyContent(FlexAlign.End)
        .alignItems(VerticalAlign.Center)
        .margin({ left: 12 })
        .onClick((): void => {
          this.optimisticChecked = !this.displayed();
          this.hasOptimistic = true;
          this.onToggle();
        })
        .accessibilityText(this.displayed() ? '已开启' : '已关闭')
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 52 })
      .padding({ left: 14, right: 14, top: 10, bottom: 10 })
      if (!this.isLast) {
        Row() {
          Row()
            .width('100%')
            .height(1)
            .backgroundColor(LINE)
        }
        .width('100%')
        .padding({ left: 14, right: 14 })
      }
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
  }
}

// 状态 chip:软底小药丸(已配置/已登录/自动批准/高风险/未填写)
// tone: success(SUCCESS_BG/FG 绿)| accent(ACCENT_LIGHT 浅底)| warn(高风险)| error | muted(SURFACE2 灰)
@Component
export struct StatusChip {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop text: string = '';
  @Prop tone: string = 'muted';
  @StorageProp('accentColor') accentColorStore: string = ACCENT;

  private fg(): string {
    if (this.tone === 'success') return SUCCESS_FG;
    if (this.tone === 'accent') return this.accentColorStore;
    if (this.tone === 'warn') return WARNING_FG;
    if (this.tone === 'error') return ERROR;
    return INK3;
  }

  private bg(): string {
    if (this.tone === 'success') return SUCCESS_BG;
    if (this.tone === 'accent') return accentSoftBg(this.accentColorStore);
    if (this.tone === 'warn') return WARNING_BG;
    if (this.tone === 'error') return ERROR_BG;
    return SURFACE2;
  }

  build(): void {
    Text(this.text)
      
      .opacity(this.themeEpoch >= 0 ? 1 : 1).fontSize(12)
      .fontWeight(FontWeight.Medium)
      .lineHeight(lineHeightForSize(12))
      .fontFamily(FONT_SANS_MEDIUM)
      .fontColor(this.fg())
      .maxLines(1)
      .padding({ left: 10, right: 10, top: 4, bottom: 4 })
      .backgroundColor(this.bg())
      .borderRadius(999)
  }
}

// iOS 组下脚注:caption 12 muted(解释行为/回退规则)
@Component
export struct FooterNote {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop text: string = '';
  build(): void {
    Text(this.text)
      
      .opacity(this.themeEpoch >= 0 ? 1 : 1).fontSize(12)
      .lineHeight(17)
      .fontFamily(FONT_SANS_MEDIUM)
      .fontColor(INK3)
      .width('100%')
      .padding({ left: 16, right: 16, top: 6 })
  }
}

// youhua .set-row 开关行:标题/描述 + 42×24 自绘药丸开关(150ms)
@Component
export struct ToggleRow {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
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

        Row() {
          Stack({ alignContent: Alignment.Start }) {
            Column()
              .width(42).height(24).borderRadius(99)
              .backgroundColor(this.checked ? this.accentColorStore : LINE2)
            Column()
              .width(20).height(20).borderRadius(10)
              .backgroundColor(Color.White) // 滑块固定纯白:off 轨上仍需对比
              .margin({ left: this.checked ? 22 : 2 })
              .animation({ duration: 150, curve: Curve.EaseOut })
          }
          .clip(true)
        }
        .constraintSize({ minWidth: 44, minHeight: 44 })
        .justifyContent(FlexAlign.End)
        .alignItems(VerticalAlign.Center)
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
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
  }
}

// youhua .set-row 选择行:标题 + 等宽值
@Component
export struct SelectRow {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
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
      .scale({ x: this.pressed ? 0.975 : 1, y: this.pressed ? 0.975 : 1 })
      .onTouch((e: TouchEvent): void => {
        const down: boolean = e.type === TouchType.Down;
        animateTo({ duration: 140, curve: Curve.EaseOut }, (): void => {
          this.pressed = down;
        });
      })
      .onClick((): void => { this.onRowClick(); })
      if (!this.isLast) {
        PaperSeparator()
      }
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
  }
}

// 会话行(对齐 SessionRow): title + snippet + modelId + time
@Component
export struct SessionRowItem {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
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
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
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
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  build(): void {
    Column() {}
      .opacity(this.themeEpoch >= 0 ? 1 : 1).height(40)
  }
}
