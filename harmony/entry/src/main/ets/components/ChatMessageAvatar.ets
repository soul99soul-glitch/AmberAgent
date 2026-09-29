// ChatMessageAvatar — 消息抬头(1:1 原型 AgentTurn/UserBubble)
//
// 原型基准:
//   - assistant:无头像,仅 "amber" mono 12/700 accent 标签行(marginBottom 11),
//     流式时尾部跟闪烁终端光标(blink 1.05s steps(1))
//   - user:无抬头(深色气泡右对齐,直接省略)
//
// F09:blink 定时器只在真实 streaming 时启动(@Watch 随 streaming 启停),
//   静止助手行不再各自空转 525ms 定时器;aboutToDisappear 对称清理。
// F16:showModelName / showModelIcon 消费显示设置(默认保持现状:显示名称、不显示图标)。
import type { UIMessage } from '@amber/chat-domain';
import { ACCENT, SURFACE2 } from '../design/tokens.ets';
import { FONT_MONO } from '../design/fonts.ets';

@Component
export struct ChatMessageAvatar {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @Prop message: UIMessage;
  // F09:仅 streaming=true 时闪烁;@Watch 对称启停(不再只看 role)
  @Prop @Watch('onStreamingChange') streaming: boolean = false;
  // F16:显示设置消费(默认值与当前渲染一致:名称开、图标关)
  @Prop showModelName: boolean = true;
  @Prop showModelIcon: boolean = false;
  @State cursorOn: boolean = true;
  private blinkTimer: number = -1;

  aboutToAppear(): void {
    if (this.shouldBlink()) this.startBlink();
  }

  aboutToDisappear(): void {
    this.stopBlink();
  }

  private onStreamingChange(): void {
    if (this.shouldBlink()) this.startBlink();
    else this.stopBlink();
  }

  private shouldBlink(): boolean {
    return this.message.role !== 'user' && this.streaming;
  }

  private startBlink(): void {
    if (this.blinkTimer !== -1) return;
    // blink 1.05s steps(1):半周期 525ms 翻转
    this.blinkTimer = setInterval((): void => { this.cursorOn = !this.cursorOn; }, 525);
  }

  private stopBlink(): void {
    if (this.blinkTimer !== -1) {
      clearInterval(this.blinkTimer);
      this.blinkTimer = -1;
    }
    // 停止后复位为常亮,避免下次开始流式时从熄灭态起跳
    this.cursorOn = true;
  }

  // 模型图标(monogram):modelId 首个字母/数字;无 id → 空(不渲染占位,不引新 asset)
  private modelIconChar(): string {
    if (!this.showModelIcon) return '';
    const id: string | null = this.message.modelId;
    if (id === null) return '';
    for (let i: number = 0; i < id.length; i++) {
      const ch: string = id.charAt(i);
      if (/[A-Za-z0-9]/.test(ch)) return ch.toUpperCase();
    }
    return '';
  }

  // F16:真实模型名优先;message.modelId 空才回退品牌标签 'amber'
  private modelLabel(): string {
    const id: string | null = this.message.modelId;
    if (id !== null && id.trim().length > 0) return id;
    return 'amber';
  }

  build(): void {
    if (this.themeEpoch >= 0 && this.message.role !== 'user') {
      Row() {
        if (this.modelIconChar().length > 0) {
          Text(this.modelIconChar())
            .fontSize(10)
            .fontWeight(FontWeight.Bold)
            .fontFamily(FONT_MONO)
            .fontColor(this.accentColorStore)
            .width(16).height(16)
            .textAlign(TextAlign.Center)
            .borderRadius(4)
            .backgroundColor(SURFACE2)
            .margin({ right: 5 })
            .accessibilityText('模型图标')
        }
        if (this.showModelName) {
          // F16:显示真实模型名(message.modelId);无则回退品牌标签,不冒充模型名
          Text(this.modelLabel())
            .fontSize(12)
            .fontWeight(FontWeight.Bold)
            .fontFamily(FONT_MONO)
            .fontColor(this.accentColorStore)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        if (this.streaming) {
          Column()
            .width(6).height(12).backgroundColor(this.accentColorStore).borderRadius(1)
            .margin({ left: 4 })
            .opacity(this.cursorOn ? 1 : 0)
        }
      }
      .margin({ bottom: 11 })
    }
  }
}
