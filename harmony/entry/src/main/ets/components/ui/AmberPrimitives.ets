// AmberPrimitives — 设计系统原语(对齐 Android AmberPrimitives.kt)
// AmberCard / Hairline / BtnInk / BtnAccent / SectionLabel / LiveDot / BlinkingCursor
import { ACCENT, ACCENT_INK, INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, BODY, META, LABEL, RADIUS } from '../../design/tokens.ets';

// AmberCard — 圆角卡片容器(borderRadius 14, surface 背景, line 边框)
@Component
export struct AmberCard {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @BuilderParam content: () => void;
  build(): void {
    Column() {
      this.content()
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .backgroundColor(SURFACE)
    .borderRadius(14)
    .border({ width: 1, color: LINE })
    .clip(true)
  }
}

// Hairline — 1px 分隔线
@Component
export struct Hairline {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  build(): void {
    Column() {
      Text('').width('100%').height(1).backgroundColor(LINE)
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
  }
}

// BtnInk — 深色主按钮(ink 背景, bg 文字)
@Component
export struct BtnInk {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop text: string = '';
  @Prop enabled: boolean = true;
  onClick: () => void = (): void => {};
  build(): void {
    Row() {
      Text(this.text)
        .fontSize(BODY)
        .fontWeight(FontWeight.Medium)
        .fontColor(this.enabled ? BG : INK4)
    }
      .enabled(this.themeEpoch >= 0)
    .width('100%')
    .justifyContent(FlexAlign.Center)
    .padding({ left: 18, right: 18, top: 12, bottom: 12 })
    .backgroundColor(this.enabled ? INK : SURFACE2)
    .borderRadius(15)
    .opacity(this.enabled ? 1 : 0.5)
    .onClick((): void => { if (this.enabled) this.onClick(); })
  }
}

// BtnAccent — 强调色按钮(accent 背景, 白字)
@Component
export struct BtnAccent {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @Prop text: string = '';
  @Prop enabled: boolean = true;
  onClick: () => void = (): void => {};
  build(): void {
    Row() {
      Text(this.text)
        .fontSize(BODY)
        .fontWeight(FontWeight.Medium)
        .fontColor(this.enabled ? ACCENT_INK : INK4)
    }
      .enabled(this.themeEpoch >= 0)
    .width('100%')
    .justifyContent(FlexAlign.Center)
    .padding({ left: 18, right: 18, top: 12, bottom: 12 })
    .backgroundColor(this.enabled ? this.accentColorStore : SURFACE2)
    .borderRadius(15)
    .opacity(this.enabled ? 1 : 0.5)
    .onClick((): void => { if (this.enabled) this.onClick(); })
  }
}

// SectionLabel — eyebrow 标签("// TITLE")
@Component
export struct SectionLabel {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @Prop text: string = '';
  build(): void {
    Row() {
      Text('//').fontSize(META).fontColor(this.accentColorStore).fontWeight(FontWeight.Bold)
      Text(' ' + this.text.toUpperCase()).fontSize(META).fontColor(INK3)
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
  }
}

// LiveDot — 呼吸点(signal 色, 脉冲动画)
@Component
export struct LiveDot {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @Prop idle: boolean = false;
  @Prop dotSize: number = 7;
  @State haloScale: number = 1;
  @State haloAlpha: number = 0.35;
  build(): void {
    Stack() {
      // 外圈 halo(呼吸动画)
      Column() {
        Text('')
          .width(this.dotSize * this.haloScale)
          .height(this.dotSize * this.haloScale)
          .borderRadius(this.dotSize * this.haloScale / 2)
          .backgroundColor(this.idle ? INK4 : this.accentColorStore)
          .opacity(this.haloAlpha)
      }
      .width(this.dotSize * 2.2)
      .height(this.dotSize * 2.2)
      .justifyContent(FlexAlign.Center)
      .alignItems(HorizontalAlign.Center)

      // 实心点
      Text('')
        .width(this.dotSize)
        .height(this.dotSize)
        .borderRadius(this.dotSize / 2)
        .backgroundColor(this.idle ? INK4 : this.accentColorStore)
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width(this.dotSize * 2.2)
    .height(this.dotSize * 2.2)
    .alignContent(Alignment.Center)
  }
}

// BlinkingCursor — 终端闪烁光标
@Component
export struct BlinkingCursor {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @Prop color: string = '';
  @Prop width: number = 8;
  @Prop height: number = 18;
  @State visible: boolean = true;
  build(): void {
    Text('')
      
      .enabled(this.themeEpoch >= 0).width(this.width)
      .height(this.height)
      .backgroundColor(this.color.length > 0 ? this.color : this.accentColorStore)
      .borderRadius(1)
      .opacity(this.visible ? 1 : 0)
  }
}
