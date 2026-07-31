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

// ===== 通用表单/反馈原语(第二梯队 E 补充,Android ui/ 对齐) =====

// FormItem: 标签 + 内容行
@Component
export struct FormItem {
  @Prop label: string = '';
  @BuilderParam content: () => void;
  build(): void {
    Row() {
      Text(this.label).fontSize(BODY).fontColor(INK2).width(100)
      this.content()
    }
    .width('100%')
    .padding({ top: 10, bottom: 10 })
  }
}

// AmberSwitch: 自定义开关(系统 Toggle 封装)
@Component
export struct AmberSwitch {
  @Prop checked: boolean = false;
  onChange: (value: boolean) => void = (_v: boolean): void => {};
  build(): void {
    Toggle({ type: ToggleType.Switch, isOn: this.checked })
      .onChange((v: boolean): void => { this.onChange(v); })
  }
}

// AmberSelect: 下拉选择(系统 Select 封装)
@Component
export struct AmberSelect {
  @Prop options: string[] = [];
  @Prop selected: number = 0;
  onSelect: (index: number) => void = (_i: number): void => {};
  build(): void {
    Select(this.options.map((opt: string): SelectOption => {
      return { value: opt } as SelectOption;
    }))
      .selected(this.selected)
      .onSelect((index: number): void => { this.onSelect(index); })
      .width('100%')
  }
}

// ConfirmDialog: 通用确认对话框
@Component
export struct ConfirmDialog {
  @Prop show: boolean = false;
  @Prop title: string = '确认';
  @Prop message: string = '';
  @Prop confirmText: string = '确认';
  @Prop cancelText: string = '取消';
  onConfirm: () => void = (): void => {};
  onCancel: () => void = (): void => {};
  build(): void {
    if (this.show) {
      Column() {
        Text(this.title).fontSize(TITLE).fontWeight(FontWeight.Medium).fontColor(INK)
        if (this.message.length > 0) {
          Text(this.message).fontSize(BODY).fontColor(INK2).margin({ top: 8 })
        }
        Row() {
          Text(this.cancelText).fontSize(BODY).fontColor(INK3)
            .padding({ left: 16, right: 16, top: 8, bottom: 8 })
            .onClick((): void => { this.onCancel(); })
          Blank()
          Text(this.confirmText).fontSize(BODY).fontColor(ACCENT)
            .padding({ left: 16, right: 16, top: 8, bottom: 8 })
            .onClick((): void => { this.onConfirm(); })
        }
        .width('100%')
        .margin({ top: 16 })
      }
      .padding(20)
      .backgroundColor(SURFACE)
      .borderRadius(16)
    }
  }
}

// ErrorCard: 错误信息卡片
@Component
export struct ErrorCard {
  @Prop message: string = '';
  onDismiss: () => void = (): void => {};
  build(): void {
    if (this.message.length > 0) {
      Row() {
        Text('⚠').fontSize(14).fontColor(ERROR)
        Text(this.message).fontSize(META).fontColor(ERROR)
          .layoutWeight(1).margin({ left: 6 })
        Text('✕').fontSize(12).fontColor(INK3)
          .onClick((): void => { this.onDismiss(); })
      }
      .width('100%')
      .padding({ left: 12, right: 12, top: 8, bottom: 8 })
      .backgroundColor('#1FC93A2F')
      .borderRadius(8)
      .margin({ bottom: 4 })
    }
  }
}

// JsonTree: JSON 树形展示(MVP: 美化 JSON 文本)
@Component
export struct JsonTree {
  @Prop data: string = '';
  build(): void {
    Scroll() {
      Text(this.prettyJson())
        .fontSize(11).fontColor(INK2)
        .fontFamily('monospace')
        .width('100%')
        .padding(12)
    }
    .width('100%')
    .backgroundColor(SURFACE2)
    .borderRadius(8)
  }
  private prettyJson(): string {
    try {
      return JSON.stringify(JSON.parse(this.data), null, 2);
    } catch (_e) {
      return this.data;
    }
  }
}

// Tag: 标签 chip
@Component
export struct Tag {
  @Prop text: string = '';
  @Prop selected: boolean = false;
  onClick: () => void = (): void => {};
  build(): void {
    Text(this.text)
      .fontSize(11)
      .fontColor(this.selected ? '#FFFFFF' : INK2)
      .padding({ left: 10, right: 10, top: 4, bottom: 4 })
      .borderRadius(999)
      .backgroundColor(this.selected ? ACCENT : SURFACE2)
      .border({ width: this.selected ? 0 : 1, color: LINE })
      .onClick((): void => { this.onClick(); })
  }
}

// ===== 更多通用原语(第三梯队补充) =====

// ImagePreviewDialog: 全屏图片预览(点击关闭)
@Component
export struct ImagePreviewDialog {
  @Prop url: string = '';
  @Prop show: boolean = false;
  onDismiss: () => void = (): void => {};
  build(): void {
    if (this.show) {
      Stack({ alignContent: Alignment.Center }) {
        Column() {
          Image(this.url)
            .width('90%')
            .objectFit(ImageFit.Contain)
            .borderRadius(12)
          Text('点击关闭').fontSize(META).fontColor('#FFFFFF')
            .margin({ top: 12 })
        }
      }
      .width('100%').height('100%')
      .backgroundColor('rgba(0,0,0,0.9)')
      .onClick((): void => { this.onDismiss(); })
    }
  }
}

// DotLoading: 三点跳动加载动画(MVP:静态三点,动画需设备验证)
@Component
export struct DotLoading {
  @Prop color: string = INK3;
  dotSize: number = 6;
  build(): void {
    Row() {
      Text('●').fontSize(this.dotSize).fontColor(this.color)
      Text('●').fontSize(this.dotSize).fontColor(this.color).margin({ left: 4 }).opacity(0.6)
      Text('●').fontSize(this.dotSize).fontColor(this.color).margin({ left: 4 }).opacity(0.3)
    }
  }
}

// ShareSheet: 分享面板(MVP:复制链接/分享文本)
@Component
export struct ShareSheet {
  @Prop show: boolean = false;
  @Prop text: string = '';
  onDismiss: () => void = (): void => {};
  build(): void {
    if (this.show) {
      Column() {
        Text('分享').fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
          .width('100%').padding({ left: 16, top: 12, bottom: 8 })
        Text('复制文本').fontSize(BODY).fontColor(ACCENT)
          .width('100%').padding({ left: 16, top: 10, bottom: 10 })
          .onClick((): void => {
            // 复制逻辑由消费方注入,这里仅占位
            this.onDismiss();
          })
        Divider().color(LINE).strokeWidth(0.5)
        Text('取消').fontSize(BODY).fontColor(INK3)
          .width('100%').padding({ left: 16, top: 10, bottom: 10 })
          .onClick((): void => { this.onDismiss(); })
      }
      .width('100%')
      .backgroundColor(SURFACE)
      .borderRadius({ topLeft: 16, topRight: 16 })
    }
  }
}

// ToggleSurface: 可折叠表面(标题可点切换展开/折叠)
@Component
export struct ToggleSurface {
  @Prop title: string = '';
  @Prop expanded: boolean = false;
  onToggle: () => void = (): void => {};
  @BuilderParam content: () => void;
  build(): void {
    Column() {
      Row() {
        Text(this.title).fontSize(BODY).fontColor(INK2).layoutWeight(1)
        Text(this.expanded ? '▾' : '▸').fontSize(11).fontColor(INK3)
      }
      .width('100%')
      .padding({ left: 12, right: 12, top: 8, bottom: 8 })
      .onClick((): void => { this.onToggle(); })
      if (this.expanded) {
        Column() {
          this.content();
        }
        .width('100%')
        .padding({ left: 12, right: 12, bottom: 8 })
      }
    }
    .width('100%')
    .backgroundColor(SURFACE)
    .border({ width: 1, color: LINE })
    .borderRadius(RADIUS)
  }
}
