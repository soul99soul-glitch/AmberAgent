// ChatMessageActionFooter — 消息统一尾部(第三梯队)
// Android 基准: ChatMessageActionFooter.kt(91 行)— 引用+操作行统一尾部
//   ActionFooterMode: Hidden/Reserved/Visible
//   resolveActionFooterMode: user→Hidden; lastMessage+loading→Reserved;
//     lastMessage+!loading→Visible; hasContent→Visible; else→Hidden

import type { UIMessage, UIMessageAnnotation } from '@amber/chat-domain';
import { ChatMessageAnnotations } from './ChatMessageCommon.ets';
import { ChatMessageActions } from './ChatMessageActions.ets';

export type ActionFooterMode = 'hidden' | 'reserved' | 'visible';

// Android resolveActionFooterMode 逐字
export const resolveActionFooterMode = (
  role: string, lastMessage: boolean, loading: boolean, hasContent: boolean,
): ActionFooterMode => {
  if (role === 'user') return 'hidden';
  if (lastMessage && loading) return 'reserved';
  if (lastMessage && !loading) return 'visible';
  if (hasContent) return 'visible';
  return 'hidden';
};

@Component
export struct ChatMessageActionFooter {
  @Prop annotations: UIMessageAnnotation[];
  @Prop mode: ActionFooterMode = 'hidden';
  @Prop isUser: boolean = false;
  @Prop isLastAssistant: boolean = false;
  onCopy: () => void = (): void => {};
  onFork: () => void = (): void => {};
  onEdit: () => void = (): void => {};
  onRegenerate: () => void = (): void => {};

  build(): void {
    if (this.mode === 'hidden') {
      Blank().height(0)
    } else {
      Column() {
        // 引用注释(流式中也显示,Android 无条件渲染)
        ChatMessageAnnotations({
          annotations: this.annotations,
          visible: true,
        })

        // 操作行(reserved 态不可见但占位)
        if (this.mode === 'visible') {
          ChatMessageActions({
            isUser: this.isUser,
            isLastAssistant: this.isLastAssistant,
            onCopy: (): void => this.onCopy(),
            onFork: (): void => this.onFork(),
            onEdit: (): void => this.onEdit(),
            onRegenerate: (): void => this.onRegenerate(),
          })
        } else if (this.mode === 'reserved') {
          // 保留布局空间(流式中占位,不可交互)
          Row().height(20).width('100%')
        }
      }
      .width('100%')
      .margin({ top: 2 })
    }
  }
}
