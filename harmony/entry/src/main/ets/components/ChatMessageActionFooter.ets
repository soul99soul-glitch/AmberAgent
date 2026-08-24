// ChatMessageActionFooter — 消息统一尾部(第三梯队)
// Android 基准: ChatMessageActionFooter.kt(91 行)— 引用+操作行统一尾部
//   ActionFooterMode: Hidden/Reserved/Visible
//   注:Android user→Hidden,但鸿蒙旧实现 user 有操作行(分叉/编辑/重发),
//   保持鸿蒙行为:user→Visible(Android 差异登记)

import type { UIMessageAnnotation } from '@amber/chat-domain';
import { ChatMessageAnnotations } from './ChatMessageCommon.ets';
import { ChatMessageActions } from './ChatMessageActions.ets';
import { ChatMessageBranch } from './ChatMessageBranch.ets';

export type ActionFooterMode = 'hidden' | 'reserved' | 'visible';

// resolveActionFooterMode:鸿蒙适配(user→visible,保持原有操作行入口)
export const resolveActionFooterMode = (
  role: string, lastMessage: boolean, loading: boolean, hasContent: boolean,
): ActionFooterMode => {
  if (role === 'user') return 'visible';
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
  @Prop branchCount: number = 1;
  @Prop selectIndex: number = 0;
  onCopy: () => void = (): void => {};
  onFork: () => void = (): void => {};
  onEdit: () => void = (): void => {};
  onRegenerate: () => void = (): void => {};
  onSpeak: () => void = (): void => {};
  onFavorite: () => void = (): void => {};
  onSwitch: (index: number) => void = (_i: number): void => {};

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
          Row() {
            // 分支切换器(assistant 且 branchCount > 1 时显示)
            ChatMessageBranch({
              branchCount: this.branchCount,
              selectIndex: this.selectIndex,
              onSwitch: (idx: number): void => { this.onSwitch(idx); },
            })
            ChatMessageActions({
              isUser: this.isUser,
              isLastAssistant: this.isLastAssistant,
              onCopy: (): void => this.onCopy(),
              onFork: (): void => this.onFork(),
              onEdit: (): void => this.onEdit(),
              onRegenerate: (): void => this.onRegenerate(),
              onSpeak: (): void => this.onSpeak(),
              onFavorite: (): void => this.onFavorite(),
            })
          }
          .width('100%')
          // 用户消息操作行跟随气泡右对齐;助手消息保持左起
          .justifyContent(this.isUser ? FlexAlign.End : FlexAlign.Start)
        } else if (this.mode === 'reserved') {
          // 占位高度与 visible 操作行一致(按钮 44 + 上下 padding 4×2),避免结束时跳变
          Row().height(52).width('100%')
        }
      }
      .width('100%')
      .margin({ top: 2 })
    }
  }
}
