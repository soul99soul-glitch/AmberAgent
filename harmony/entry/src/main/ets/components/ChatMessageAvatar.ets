// ChatMessageAvatar — 消息抬头(1:1 原型 AgentTurn/UserBubble)
//
// 原型基准:
//   - assistant:无头像,仅 "amber" mono 12/700 accent 标签行(marginBottom 11),
//     流式时尾部跟闪烁终端光标(blink 1.05s steps(1))
//   - user:无抬头(深色气泡右对齐,直接省略)
import type { UIMessage } from '@amber/chat-domain';
import { ACCENT, SIGNAL } from '../design/tokens.ets';

@Component
export struct ChatMessageAvatar {
  @Prop message: UIMessage;
  @Prop streaming: boolean = false;
  @State cursorOn: boolean = true;
  private blinkTimer: number = -1;

  aboutToAppear(): void {
    if (this.message.role !== 'user') {
      // blink 1.05s steps(1):半周期 525ms 翻转(streaming prop 后变也需timer在跑)
      this.blinkTimer = setInterval((): void => { this.cursorOn = !this.cursorOn; }, 525);
    }
  }

  aboutToDisappear(): void {
    if (this.blinkTimer !== -1) {
      clearInterval(this.blinkTimer);
      this.blinkTimer = -1;
    }
  }

  build(): void {
    if (this.message.role !== 'user') {
      Row() {
        Text('amber')
          .fontSize(12)
          .fontWeight(FontWeight.Bold)
          .fontFamily('JetBrainsMono')
          .fontColor(ACCENT)
        if (this.streaming) {
          Column()
            .width(6).height(12).backgroundColor(SIGNAL).borderRadius(1)
            .margin({ left: 4 })
            .opacity(this.cursorOn ? 1 : 0)
        }
      }
      .margin({ bottom: 11 })
    }
  }
}
