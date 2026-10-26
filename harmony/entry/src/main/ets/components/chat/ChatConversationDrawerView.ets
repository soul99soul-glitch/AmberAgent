import type { ConversationSummary, MessageSearchHit } from '@amber/chat-domain';
import { ChatConversationDrawerSession } from '../../platform_impl/chat/ChatConversationDrawerSession.ets';
import { ChatDrawer } from '../ChatDrawer.ets';
import { ChatListGrouped } from '../ChatListGrouped.ets';
import { LABEL, INK3, BODY2, INK, META, RADIUS, SURFACE } from '../../design/tokens.ets';

@Component
export struct ChatConversationDrawerView {
  @ObjectLink session: ChatConversationDrawerSession;
  @Prop userNickname: string = '用户';
  @Prop userAvatar: string = '';
  @Prop currentConversationId: string = '';
  @Prop runningIds: string[] = [];

  @Builder
  drawerConversationList(): void {
    ChatListGrouped({
      conversations: this.session.drawerConversations,
      selectedId: this.currentConversationId,
      runningIds: this.runningIds,
      onOpenConversation: (id: string): void => { this.session.openDrawerConversation(id); },
      onDelete: (item: ConversationSummary): void => { this.session.deleteDrawerConversation(item); },
      onPin: (item: ConversationSummary): void => { this.session.pinDrawerConversation(item); },
      onRegenerateTitle: (item: ConversationSummary): void => { this.session.regenerateDrawerTitle(item); },
    })
    if (this.session.drawerMessageHits.length > 0) {
      Column() {
        Text(`消息命中 · ${this.session.drawerMessageHits.length}`)
          .fontSize(LABEL).fontColor(INK3).width('100%').margin({ top: 14, bottom: 6 })
        ForEach(this.session.drawerMessageHits, (hit: MessageSearchHit): void => {
          Column() {
            Text(hit.conversationTitle.length > 0 ? hit.conversationTitle : '未命名会话')
              .fontSize(BODY2).fontWeight(FontWeight.Medium).fontColor(INK)
              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            Text(hit.snippet).fontSize(META).fontColor(INK3).margin({ top: 3 })
              .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
          }
          .alignItems(HorizontalAlign.Start).width('100%')
          .padding({ left: 12, right: 12, top: 10, bottom: 10 })
          .borderRadius(RADIUS).backgroundColor(SURFACE).margin({ bottom: 6 })
          .onClick((): void => { this.session.openDrawerConversation(hit.conversationId); })
        }, (hit: MessageSearchHit): string => hit.conversationId)
      }.width('100%')
    }
  }
  build(): void {
    if (this.session.drawerOpen) {
      ChatDrawer({
        show: true, userNickname: this.userNickname, userAvatar: this.userAvatar,
        onNewChat: (): void => { this.session.newChat(); },
        onOpenBoard: (): void => { this.session.openDrawerBoard(); },
        onOpenApps: (): void => { this.session.openDrawerApps(); },
        onOpenSettings: (): void => { this.session.openDrawerSettings(); },
        onSearchText: (text: string): void => { this.session.refreshDrawerConversations(text); },
        onDismiss: (): void => { this.session.closeDrawer(); },
        onEditNickname: (name: string): void => { this.session.editDrawerNickname(name); },
        conversationList: (): void => { this.drawerConversationList(); },
      })
    }
  }
}
