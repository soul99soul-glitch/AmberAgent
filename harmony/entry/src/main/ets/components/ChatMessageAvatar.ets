// ChatMessageAvatar — 消息头像组件(第二梯队 F + UIAvatar 接入)
// Android 基准: ChatMessageAvatar.kt(167 行)— 用户/助手头像
import type { UIMessage } from '@amber/chat-domain';
import { INK3 } from '../design/tokens.ets';
import { UIAvatar } from './UIAvatar.ets';

@Component
export struct ChatMessageAvatar {
  @Prop message: UIMessage;
  build(): void {
    Row() {
      UIAvatar({
        name: this.message.role === 'user' ? '我' : 'AI',
        emoji: this.message.role === 'user' ? '👤' : '🤖',
        avatarSize: 28,
        circular: true,
      })
      Text(this.message.role === 'user' ? '我' : '助手')
        .fontSize(11).fontColor(INK3)
        .margin({ left: 6 })
    }
    .margin({ bottom: 4 })
  }
}
