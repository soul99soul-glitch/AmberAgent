// 设计系统组件 — 杂志风排版原语 + 聊天交互原语
// 基准: Android ds/AmberPrimitives.kt(186 行) + 杂志风既有组件
import { INK, INK2, INK3, INK4, LINE, ACCENT, ACCENT_INK, SURFACE, SURFACE2, BG, SIGNAL, ERROR, TITLE, BODY, META, LABEL, PAD_CARD, PAD_ITEM, RADIUS, OVERLAY, SHADOW_OVERLAY } from './tokens.ets';

// ===== 聊天交互原语(Android AmberPrimitives.kt 对齐) =====

// AmberCard:通用容器(surface 底 + 1dp hairline + 14dp 圆角)
@Component
export struct AmberCard {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @BuilderParam content: () => void;
  build(): void {
    Column() {
      this.content();
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .backgroundColor(SURFACE)
    .border({ width: 1, color: LINE })
    .borderRadius(RADIUS)
  }
}

// Hairline:1dp 发丝分割线
@Component
export struct Hairline {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  build(): void {
    Divider()
      .opacity(this.themeEpoch >= 0 ? 1 : 1).color(LINE).strokeWidth(0.5).width('100%')
  }
}

// BtnInk:主操作按钮(ink 实心 + bg 字 + 15dp 圆角)
// 注:enabled/onClick 与 CustomComponent 基类方法同名会触发 arkts 编译冲突,
//   故重命名为 fieldEnabled / onClicked。
@Component
export struct BtnInk {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  text: string = '';
  fieldEnabled: boolean = true;
  onClicked: () => void = (): void => {};
  @State private pressed: boolean = false; // Android pressable() 0.975 语义
  build(): void {
    Text(this.text)
      
      .enabled(this.themeEpoch >= 0).fontSize(BODY).fontWeight(FontWeight.Medium)
      .fontColor(this.fieldEnabled ? SURFACE : INK4)
      .padding({ left: 16, right: 16, top: 8, bottom: 8 })
      .borderRadius(15)
      .backgroundColor(this.fieldEnabled ? INK : SURFACE2)
      .scale({ x: this.pressed ? 0.975 : 1, y: this.pressed ? 0.975 : 1 })
      .opacity(this.pressed ? 0.85 : 1)
      .animation({ duration: 140, curve: Curve.EaseOut })
      .onTouch((e: TouchEvent): void => {
        if (!this.fieldEnabled) return;
        if (e.type === TouchType.Down) this.pressed = true;
        else if (e.type === TouchType.Up || e.type === TouchType.Cancel) this.pressed = false;
      })
      .onClick((): void => { if (this.fieldEnabled) this.onClicked(); })
  }
}

// BtnAccent:accent 实心按钮 + accentInk 字
// 注:enabled/onClick 与 CustomComponent 基类方法同名会触发 arkts 编译冲突,
//   故重命名为 fieldEnabled / onClicked。
@Component
export struct BtnAccent {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  text: string = '';
  fieldEnabled: boolean = true;
  onClicked: () => void = (): void => {};
  @State private pressed: boolean = false; // Android pressable() 0.975 语义
  build(): void {
    Text(this.text)
      
      .enabled(this.themeEpoch >= 0).fontSize(BODY).fontWeight(FontWeight.Medium)
      .fontColor(this.fieldEnabled ? ACCENT_INK : INK4)
      .padding({ left: 16, right: 16, top: 8, bottom: 8 })
      .borderRadius(15)
      .backgroundColor(this.fieldEnabled ? ACCENT : SURFACE2)
      .scale({ x: this.pressed ? 0.975 : 1, y: this.pressed ? 0.975 : 1 })
      .opacity(this.pressed ? 0.85 : 1)
      .animation({ duration: 140, curve: Curve.EaseOut })
      .onTouch((e: TouchEvent): void => {
        if (!this.fieldEnabled) return;
        if (e.type === TouchType.Down) this.pressed = true;
        else if (e.type === TouchType.Up || e.type === TouchType.Cancel) this.pressed = false;
      })
      .onClick((): void => { if (this.fieldEnabled) this.onClicked(); })
  }
}

// LiveDot:呼吸光晕活体圆点(signal 绿 / idle 灰)
@Component
export struct LiveDot {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  idle: boolean = false;
  dotSize: number = 8;
  build(): void {
    Circle({ width: this.dotSize, height: this.dotSize })
      
      .opacity(this.themeEpoch >= 0 ? 1 : 1).fill(this.idle ? INK4 : SIGNAL)
  }
}

// BlinkingCursor:闪烁终端光标(默认 accent)
// 注:width/height 与 CustomComponent 基类方法同名会触发 arkts 编译冲突,
//   故重命名为 cursorWidth / cursorHeight。
@Component
export struct BlinkingCursor {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop color: string = ACCENT;
  @Prop cursorWidth: number = 2;
  @Prop cursorHeight: number = 16;
  @State private visible: boolean = true;
  private timer: number = -1;
  private alive: boolean = false;

  aboutToAppear(): void {
    this.alive = true;
    this.start();
  }

  aboutToDisappear(): void {
    this.alive = false;
    this.stop();
  }

  private start(): void {
    this.stop();
    this.visible = true;
    this.timer = setInterval((): void => {
      if (this.alive) this.visible = !this.visible;
    }, 530);
  }

  private stop(): void {
    if (this.timer !== -1) {
      clearInterval(this.timer);
      this.timer = -1;
    }
  }

  build(): void {
    Column()
      
      .enabled(this.themeEpoch >= 0).width(this.cursorWidth)
      .height(this.cursorHeight)
      .backgroundColor(this.color)
      .opacity(this.visible ? 1 : 0)
  }
}

// SectionLabel:带 accent // 前缀的 mono 大写眉标
@Component
export struct SectionLabel {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  text: string = '';
  build(): void {
    Row() {
      Text('//').fontSize(10).fontColor(ACCENT).fontWeight(FontWeight.Bold)
      Text(' ' + this.text.toUpperCase()).fontSize(10).fontWeight(FontWeight.Medium).fontColor(INK3)
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
  }
}

@Component
export struct RubricHead {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
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
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').padding({ top: 22, bottom: 8 })
  }
}

@Component
export struct LeadCard {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
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
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').padding(PAD_CARD)
    .backgroundColor(SURFACE).borderRadius(RADIUS)
    .border({ width: 1, color: LINE })
    .onClick(() => { this.onTap(); })
  }
}

@Component
export struct IndexRow {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
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
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').padding({ top: PAD_ITEM, bottom: PAD_ITEM })
    .border({ width: { bottom: this.last ? 0 : 0.5 }, color: { bottom: LINE } })
    .onClick(() => { this.onTap(); })
  }
}

@Component
export struct EmptyState {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  icon: string = '';
  title: string = '';
  subtitle: string = '';
  actionLabel: string = '';
  onAction: () => void = () => {};
  build(): void {
    Column() {
      if (this.icon.length > 0) {
        // 图标座:淡底圆座承载 emoji/glyph,避免裸字符漂浮感
        Row() {
          Text(this.icon).fontSize(30)
        }
        .width(72).height(72).borderRadius(36)
        .backgroundColor(SURFACE2)
        .border({ width: 1, color: LINE })
        .justifyContent(FlexAlign.Center)
        .alignItems(VerticalAlign.Center)
      }
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
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .alignItems(HorizontalAlign.Center)
  }
}

@Component
export struct SkeletonRow {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  build(): void {
    Row() {
      Text('  ').fontSize(12).backgroundColor(SURFACE2).width(24)
      Text('  ').fontSize(14).backgroundColor(SURFACE2).layoutWeight(1).margin({ left: 8 })
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').padding({ top: 14, bottom: 14 })
  }
}

// ===== 通用表单/反馈原语(第二梯队 E 补充,Android ui/ 对齐) =====

// FormItem: 标签 + 内容行
@Component
export struct FormItem {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop label: string = '';
  @BuilderParam content: () => void;
  build(): void {
    Row() {
      Text(this.label).fontSize(BODY).fontColor(INK2).width(100)
      this.content()
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .padding({ top: 10, bottom: 10 })
  }
}

// AmberSwitch: 自定义开关(系统 Toggle 封装)
@Component
export struct AmberSwitch {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop checked: boolean = false;
  onChange: (value: boolean) => void = (_v: boolean): void => {};
  build(): void {
    Toggle({ type: ToggleType.Switch, isOn: this.checked })
      
      .opacity(this.themeEpoch >= 0 ? 1 : 1).onChange((v: boolean): void => { this.onChange(v); })
  }
}

// AmberSelect: 下拉选择(系统 Select 封装)
@Component
export struct AmberSelect {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop options: string[] = [];
  @Prop selected: number = 0;
  onSelect: (index: number) => void = (_i: number): void => {};
  build(): void {
    Select(this.options.map((opt: string): SelectOption => {
      return { value: opt } as SelectOption;
    }))
      
      .opacity(this.themeEpoch >= 0 ? 1 : 1).selected(this.selected)
      .onSelect((index: number): void => { this.onSelect(index); })
      .width('100%')
  }
}

// ConfirmDialog: 通用确认对话框
@Component
export struct ConfirmDialog {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop show: boolean = false;
  @Prop title: string = '确认';
  @Prop message: string = '';
  @Prop confirmText: string = '确认';
  @Prop cancelText: string = '取消';
  onConfirm: () => void = (): void => {};
  onCancel: () => void = (): void => {};
  @State private entered: boolean = false;

  aboutToAppear(): void {
    this.entered = false;
    animateTo({ duration: 180, curve: Curve.EaseOut }, (): void => { this.entered = true; });
  }

  build(): void {
    if (this.themeEpoch >= 0 && this.show) {
      Stack({ alignContent: Alignment.Center }) {
        Row() {}
          .width('100%')
          .height('100%')
          .backgroundColor(OVERLAY)
          .onClick((): void => { this.onCancel(); })
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
        .width('84%')
        .padding(20)
        .backgroundColor(SURFACE)
        .borderRadius(16)
        .shadow({ radius: 24, color: SHADOW_OVERLAY, offsetY: 8 })
        .scale({ x: this.entered ? 1 : 0.96, y: this.entered ? 1 : 0.96 })
        .opacity(this.entered ? 1 : 0)
      }
      .width('100%')
      .height('100%')
    }
  }
}

// ErrorCard: 错误信息卡片
@Component
export struct ErrorCard {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop message: string = '';
  onDismiss: () => void = (): void => {};
  build(): void {
    if (this.themeEpoch >= 0 && this.message.length > 0) {
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
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop data: string = '';
  build(): void {
    Scroll() {
      Text(this.prettyJson())
        .fontSize(11).fontColor(INK2)
        .fontFamily('JetBrainsMono')
        .width('100%')
        .padding(12)
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
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
// 注:onClick 与 CustomComponent 基类方法同名会触发 arkts 编译冲突,
//   故重命名为 onClicked。
@Component
export struct Tag {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop text: string = '';
  @Prop selected: boolean = false;
  onClicked: () => void = (): void => {};
  build(): void {
    Text(this.text)
      
      .opacity(this.themeEpoch >= 0 ? 1 : 1).fontSize(11)
      .fontColor(this.selected ? '#FFFFFF' : INK2)
      .padding({ left: 10, right: 10, top: 4, bottom: 4 })
      .borderRadius(999)
      .backgroundColor(this.selected ? ACCENT : SURFACE2)
      .border({ width: this.selected ? 0 : 1, color: LINE })
      .onClick((): void => { this.onClicked(); })
  }
}

// ===== 更多通用原语(第三梯队补充) =====

// ImagePreviewDialog: 全屏图片预览(点击关闭)
@Component
export struct ImagePreviewDialog {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop url: string = '';
  @Prop show: boolean = false;
  onDismiss: () => void = (): void => {};
  build(): void {
    if (this.themeEpoch >= 0 && this.show) {
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

// ShareSheet: 分享面板(MVP:复制链接/分享文本)
@Component
export struct ShareSheet {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop show: boolean = false;
  @Prop text: string = '';
  onCopy: () => void = (): void => {};
  onDismiss: () => void = (): void => {};
  @State private entered: boolean = false;

  aboutToAppear(): void {
    this.entered = false;
    animateTo({ duration: 220, curve: Curve.EaseOut }, (): void => { this.entered = true; });
  }

  build(): void {
    if (this.themeEpoch >= 0 && this.show) {
      Stack() {
        Row() {}
          .width('100%')
          .height('100%')
          .backgroundColor(OVERLAY)
          .onClick((): void => { this.onDismiss(); })
        Column() {
          Text('分享').fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
            .width('100%').padding({ left: 16, top: 12, bottom: 8 })
          Text('复制文本').fontSize(BODY).fontColor(ACCENT)
            .width('100%').padding({ left: 16, top: 10, bottom: 10 })
            .onClick((): void => {
              this.onCopy();
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
        .shadow({ radius: 20, color: SHADOW_OVERLAY, offsetY: -4 })
        .translate({ y: this.entered ? 0 : 80 })
      }
      .width('100%')
      .height('100%')
      .alignContent(Alignment.Bottom)
    }
  }
}

// ToggleSurface: 可折叠表面(标题可点切换展开/折叠)
@Component
export struct ToggleSurface {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
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
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .backgroundColor(SURFACE)
    .border({ width: 1, color: LINE })
    .borderRadius(RADIUS)
  }
}
