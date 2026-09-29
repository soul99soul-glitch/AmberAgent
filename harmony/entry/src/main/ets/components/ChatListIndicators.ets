// ChatListIndicators — 会话列表状态指示器(G5-S2)
// Android 基准: ConversationList.kt:356-410 ConversationRunningIndicator
//   (脉动 ring scale 0.72↔1.2 + alpha 0.75↔0.12, 900ms 循环);
//   ConversationList.kt:276-283 PinnedIndicator(HugeIcons.Pin 15dp);
//   未读计数徽标(ACCENT 背景)
// ArkUI: 脉冲用 animateTo iterations:-1(scale + opacity);图标用 Unicode/Text 代替矢量
import { ACCENT, INK3, SIGNAL } from '../design/tokens.ets';
import { Icon } from './ui/Icon.ets';

// ConversationRunningIndicator — 脉冲圆点(运行中会话)
//   Android: Box(22dp) { ring(16dp scale+alpha) + dot(6dp) }
//   语义: "Running" contentDescription;ring = accent 脉冲,dot = modelStatusDot 绿
@Component
export struct RunningIndicator {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @State pulseScale: number = 0.72;
  @State pulseAlpha: number = 0.75;

  private startPulse(): void {
    // 900ms 循环:scale 0.72→1.2,alpha 0.75→0.12(RepeatMode.Restart)
    animateTo({
      duration: 900,
      iterations: -1,
      curve: Curve.Linear,
      playMode: PlayMode.Normal,
    }, (): void => {
      this.pulseScale = 1.2;
      this.pulseAlpha = 0.12;
    });
  }

  build(): void {
    Stack({ alignContent: Alignment.Center }) {
      // 脉冲 ring(16dp,border 1.5dp,accent 色随 alpha 衰减)
      Stack()
        .width(16)
        .height(16)
        .border({ width: 1.5, color: ACCENT })
        .borderRadius(8)
        .opacity(this.pulseAlpha)
        .scale({ x: this.pulseScale, y: this.pulseScale })
      // 中心实心点(6dp,modelStatusDot 绿 = SIGNAL)
      Stack()
        .width(6)
        .height(6)
        .borderRadius(3)
        .backgroundColor(SIGNAL)
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width(22)
    .height(22)
    .onAppear((): void => { this.startPulse(); })
  }
}

// PinnedIndicator — 置顶图标(15dp)
//   Android: HugeIcons.Pin 15dp,tint = workspace.faint(INK3)
//   ArkUI: SymbolGlyph 单色符号(Icon.pin),对齐 HugeIcons.Pin 语义
@Component
export struct PinnedIndicator {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop tint: string = INK3;

  build(): void {
    Column() {
      Icon({ name: 'pin', iconSize: 13, color: this.tint })
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width(15)
    .height(15)
    .justifyContent(FlexAlign.Center)
    .alignItems(HorizontalAlign.Center)
  }
}

// UnreadBadge — 未读计数徽标
//   Android: M3 BadgeBox(ACCENT 背景,白色数字)
//   ArkUI: Text + 圆角背景,count > 99 显示 "99+"
@Component
export struct UnreadBadge {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop count: number = 0;

  build(): void {
    if (this.themeEpoch >= 0 && this.count > 0) {
      Text(this.count > 99 ? '99+' : `${this.count}`)
        .fontSize(10)
        .fontWeight(FontWeight.Medium)
        .fontColor(Color.White)
        .backgroundColor(ACCENT)
        .borderRadius(9)
        .padding({ left: 5, right: 5, top: 1, bottom: 1 })
        .minFontSize(9)
        .maxLines(1)
        .constraintSize({ minWidth: 18 })
        .textAlign(TextAlign.Center)
    }
  }
}
