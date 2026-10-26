import type { UIMessage } from '@amber/chat-domain';
import { ChatMessageActionsSession } from '../../platform_impl/chat/ChatMessageActionsSession.ets';
import { ChatMessageCopySheet } from '../ChatMessageCopySheet.ets';
import { ChatMessageActionsSheet } from '../ChatMessageActionsSheet.ets';
import { AgentMessageActionsSheet } from '../AgentMessageActionsSheet.ets';

@Component
export struct ChatMessageActionsView {
  @ObjectLink session: ChatMessageActionsSession;

  @Builder
  copySheetBuilder(): void {
    if (this.session.copySheetMessage !== null) {
      ChatMessageCopySheet({ message: this.session.copySheetMessage as UIMessage,
        onDismiss: (): void => { this.session.closeCopySheet(); } })
    }
  }
  @Builder
  messageActionsSheetBuilder(): void {
    if (this.session.actionsSheetRow !== null) {
      if (this.session.actionsSheetMessage().role === 'assistant') {
        AgentMessageActionsSheet({
          onSelectText: (): void => { this.session.copyActionsSheetMessage(); },
          onCopy: (): void => { this.session.copyAssistantMessage(); },
          onRegenerate: (): void => { this.session.regenerateActionsSheetMessage(); },
          onDismiss: (): void => { this.session.closeActionsSheet(); },
        })
      } else {
        ChatMessageActionsSheet({
          message: this.session.actionsSheetMessage(),
          isUser: this.session.actionsSheetIsUser(),
          isLastAssistant: this.session.actionsSheetIsLastAssistant(),
          onCopy: (): void => { this.session.copyActionsSheetMessage(); },
          onEdit: (): void => { this.session.editActionsSheetMessage(); },
          onRegenerate: (): void => { this.session.regenerateActionsSheetMessage(); },
          onFork: (): void => { this.session.forkActionsSheetMessage(); },
          onShare: (): void => { this.session.shareActionsSheetMessage(); },
          canArchive: true,
          onArchive: (): void => { this.session.openChatWorkspace(this.session.actionsSheetMessage().id); },
          onOpenWorkspace: (): void => { this.session.openChatWorkspace(); },
          canFavorite: true, isFavorite: this.session.actionsSheetIsFavorite(),
          onFavorite: (): void => { this.session.toggleActionsSheetFavorite(); },
          onExport: (): void => { this.session.openMessageExport(this.session.actionsSheetMessage().id); },
          onDelete: (): void => { this.session.deleteActionsSheetMessage(); },
          onDismiss: (): void => { this.session.closeActionsSheet(); },
        })
      }
    }
  }
  build(): void {
    Column() {
      Column() {}
        .width(0).height(0).hitTestBehavior(HitTestMode.None)
        .bindSheet(this.session.copySheetOpen, this.copySheetBuilder(), {
          height: SheetSize.MEDIUM, showClose: false,
          onDisappear: (): void => { this.session.closeCopySheet(); },
        })
      Column() {}
        .width(0).height(0).hitTestBehavior(HitTestMode.None)
        .bindSheet(this.session.actionsSheetOpen, this.messageActionsSheetBuilder(), {
          height: SheetSize.FIT_CONTENT,
          showClose: this.session.actionsSheetIsUser(),
          onDisappear: (): void => { this.session.clearActionsSheet(); },
        })
    }
    .width(0).height(0).hitTestBehavior(HitTestMode.None)
  }
}
