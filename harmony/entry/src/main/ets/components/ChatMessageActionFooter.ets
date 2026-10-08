// ChatMessageActionFooter — 消息统一尾部(第三梯队)
// Android 基准: ChatMessageActionFooter.kt(91 行)— 引用+操作行统一尾部
//   ActionFooterMode: Hidden/Reserved/Visible
//   user 消息已有单击编辑 + 长按 ActionsSheet,因此 footer 对齐 Android 为 Hidden。

import type { UIMessageAnnotation } from '@amber/chat-domain';
import { ChatMessageAnnotations } from './ChatMessageCommon.ets';
import { ChatMessageActions } from './ChatMessageActions.ets';
import { ChatMessageBranch } from './ChatMessageBranch.ets';

export type ActionFooterMode = 'hidden' | 'reserved' | 'visible';

// resolveActionFooterMode:user 的编辑/复制/删除入口由长按 ActionsSheet 提供。
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
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop annotations: UIMessageAnnotation[];
  @Prop mode: ActionFooterMode = 'hidden';
  @Prop isUser: boolean = false;
  @Prop isLastAssistant: boolean = false;
  @Prop branchCount: number = 1;
  @Prop selectIndex: number = 0;
  @Prop canCopy: boolean = true;
  @Prop canFork: boolean = true;
  @Prop canBranchSwitch: boolean = true;
  @Prop canEdit: boolean = true;
  @Prop canRegenerate: boolean = true;
  @Prop canFavorite: boolean = true;
  @Prop isFavorite: boolean = false;
  onCopy: () => void = (): void => {};
  onFork: () => void = (): void => {};
  onEdit: () => void = (): void => {};
  onRegenerate: () => void = (): void => {};
  onFavorite: () => void = (): void => {};
  onSwitch: (index: number) => void = (_i: number): void => {};

  private hasVisibleActions(): boolean {
    return this.canCopy || this.canFork || (this.isUser && this.canEdit) ||
      ((this.isLastAssistant || this.isUser) && this.canRegenerate) ||
      (!this.isUser && this.canFavorite) || (this.canBranchSwitch && this.branchCount > 1);
  }

  build(): void {
    if (this.themeEpoch >= 0 && this.mode === 'hidden') {
      Blank().height(0)
    } else {
      Column() {
        // 引用注释(流式中也显示,Android 无条件渲染)
        ChatMessageAnnotations({
          annotations: this.annotations,
          visible: true,
        })

        // 操作行(reserved 态不可见但占位)
        if (this.mode === 'visible' && this.hasVisibleActions()) {
          Row() {
            // 分支切换器(assistant 且 branchCount > 1 时显示)
            if (this.canBranchSwitch) {
              ChatMessageBranch({
                branchCount: this.branchCount,
                selectIndex: this.selectIndex,
                onSwitch: (idx: number): void => { this.onSwitch(idx); },
              })
            }
            ChatMessageActions({
              isUser: this.isUser,
              isLastAssistant: this.isLastAssistant,
              canCopy: this.canCopy,
              canFork: this.canFork,
              canEdit: this.canEdit,
              canRegenerate: this.canRegenerate,
              canFavorite: this.canFavorite,
              isFavorite: this.isFavorite,
              onCopy: (): void => this.onCopy(),
              onFork: (): void => this.onFork(),
              onEdit: (): void => this.onEdit(),
              onRegenerate: (): void => this.onRegenerate(),
              onFavorite: (): void => this.onFavorite(),
            })
          }
          .width('100%')
          // 用户消息操作行跟随气泡右对齐;助手消息保持左起
          .justifyContent(this.isUser ? FlexAlign.End : FlexAlign.Start)
        } else if (this.mode === 'reserved' && this.hasVisibleActions()) {
          // 占位高度与 visible 操作行一致(按钮 44 + 上下 padding 4×2),避免结束时跳变
          Row().height(52).width('100%')
        }
      }
      .width('100%')
      .margin({ top: 2 })
    }
  }
}
