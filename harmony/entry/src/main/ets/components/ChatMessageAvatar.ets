// ChatMessageAvatar — 消息头像组件(第二梯队 F)
// Android 基准: ChatMessageAvatar.kt(167 行)— 用户/助手头像
// 从 ChatPage MessageRow.build() 内联抽出(line 284)
import type { UIMessage } from '@amber/chat-domain';
import { INK3 } from '../design/tokens.ets';

@Component
export struct ChatMessageAvatar {
  @Prop message: UIMessage;
  build(): void {
    Row() {
      Text(this.message.role === 'user' ? '我' : '助手')
        .fontSize(11).fontColor(INK3)
    }
    .margin({ bottom: 4 })
  }
}
