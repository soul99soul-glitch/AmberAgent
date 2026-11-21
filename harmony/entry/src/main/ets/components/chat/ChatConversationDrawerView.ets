import type { ConversationSummary, MessageSearchHit } from '@amber/chat-domain';
import { ChatConversationDrawerSession } from '../../platform_impl/chat/ChatConversationDrawerSession.ets';
import { ChatDrawer } from '../ChatDrawer.ets';
import { RunningIndicator } from '../ChatListIndicators.ets';
import { Icon } from '../ui/Icon.ets';
import { ACCENT, BG, BODY2, INK, INK2, INK3, LABEL, LINE, META, SURFACE, accentSoftBg } from '../../design/tokens.ets';

@Component
export struct ChatConversationDrawerView {
  @ObjectLink session: ChatConversationDrawerSession;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @Prop userNickname: string = '用户';
  @Prop userAvatar: string = '';
  @Prop currentConversationId: string = '';
  @Prop runningIds: string[] = [];
  onNewTopic: () => void = (): void => {};
  onOpenAppearance: () => void = (): void => {};

  private visibleDrawerConversations(): ConversationSummary[] {
    if (this.session.drawerSearchText.trim().length > 0) return this.session.drawerConversations;
    return this.session.drawerConversations.slice(0, 5);
  }

  private isRunning(id: string): boolean {
    return this.runningIds.indexOf(id) >= 0;
  }

  @Builder
  private ConversationMenu(conv: ConversationSummary): void {
    Menu() {
      MenuItem({ content: conv.isPinned ? '取消置顶' : '置顶' })
        .onClick((): void => { this.session.pinDrawerConversation(conv); })
      MenuItem({ content: '重新生成标题' })
        .onClick((): void => { this.session.regenerateDrawerTitle(conv); })
      MenuItem({ content: '删除' })
        .onClick((): void => { this.session.deleteDrawerConversation(conv); })
    }
  }

  @Builder
  drawerConversationList(): void {
    Column({ space: 10 }) {
      if (this.visibleDrawerConversations().length === 0) {
        Text(this.session.drawerSearchText.trim().length > 0 ? '未找到匹配会话' : '暂无历史话题记录')
          .fontSize(12.5)
          .fontColor(INK3)
          .width('100%')
          .padding(14)
          .borderRadius(16)
          .backgroundColor(SURFACE)
          .border({ width: 0.75, color: LINE })
      } else {
        Column() {
          ForEach(this.visibleDrawerConversations(), (conv: ConversationSummary, index: number): void => {
            Column() {
              Row({ space: 10 }) {
                Icon({
                  name: conv.isPinned ? 'pin_fill' : (conv.id === this.currentConversationId ? 'ph_chatCircleDots' : 'clock'),
                  iconSize: 13.5,
                  color: conv.id === this.currentConversationId ? this.accentColorStore : INK3,
                })

                Text(conv.title.trim().length > 0 ? conv.title.trim() : '新对话')
                  .fontSize(14)
                  .fontWeight(conv.id === this.currentConversationId ? FontWeight.Medium : FontWeight.Normal)
                  .fontColor(INK)
                  .maxLines(1)
                  .textOverflow({ overflow: TextOverflow.Ellipsis })
                  .layoutWeight(1)

                if (this.isRunning(conv.id)) {
                  RunningIndicator()
                }

                if (conv.id === this.currentConversationId) {
                  Text('当前')
                    .fontSize(10.5)
                    .fontWeight(FontWeight.Medium)
                    .fontColor(this.accentColorStore)
                    .padding({ left: 7, right: 7, top: 2, bottom: 2 })
                    .borderRadius(999)
                    .backgroundColor(accentSoftBg(this.accentColorStore))
                }
              }
              .width('100%')
              .height(46)
              .padding({ left: 14, right: 14 })
              .alignItems(VerticalAlign.Center)
              .onClick((): void => { this.session.openDrawerConversation(conv.id); })
              .bindContextMenu(this.ConversationMenu(conv), ResponseType.LongPress)

              if (index < this.visibleDrawerConversations().length - 1) {
                Divider()
                  .color(LINE)
                  .strokeWidth(0.5)
                  .margin({ left: 38 })
              }
            }
            .width('100%')
          }, (conv: ConversationSummary): string => `${conv.id}-${conv.isPinned ? 1 : 0}-${conv.title}`)
        }
        .width('100%')
        .borderRadius(16)
        .backgroundColor(SURFACE)
        .border({ width: 0.75, color: LINE })
        .clip(true)
      }

      if (this.session.drawerMessageHits.length > 0) {
        Column({ space: 6 }) {
          Text(`消息命中 · ${this.session.drawerMessageHits.length}`)
            .fontSize(LABEL)
            .fontColor(INK3)
            .padding({ left: 4 })
            .width('100%')

          Column() {
            ForEach(this.session.drawerMessageHits, (hit: MessageSearchHit, index: number): void => {
              Column() {
                Column({ space: 3 }) {
                  Text(hit.conversationTitle.length > 0 ? hit.conversationTitle : '未命名会话')
                    .fontSize(BODY2)
                    .fontWeight(FontWeight.Medium)
                    .fontColor(INK)
                    .maxLines(1)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                  Text(hit.snippet)
                    .fontSize(META)
                    .fontColor(INK2)
                    .maxLines(2)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                }
                .alignItems(HorizontalAlign.Start)
                .width('100%')
                .padding({ left: 14, right: 14, top: 10, bottom: 10 })
                .onClick((): void => { this.session.openDrawerConversation(hit.conversationId); })

                if (index < this.session.drawerMessageHits.length - 1) {
                  Divider().color(LINE).strokeWidth(0.5).margin({ left: 14 })
                }
              }
              .width('100%')
            }, (hit: MessageSearchHit): string => hit.conversationId)
          }
          .width('100%')
          .borderRadius(16)
          .backgroundColor(SURFACE)
          .border({ width: 0.75, color: LINE })
          .clip(true)
        }
        .width('100%')
      }
    }
    .width('100%')
  }

  @Builder
  private DrawerSheetContent(): void {
    ChatDrawer({
      show: true, userNickname: this.userNickname, userAvatar: this.userAvatar,
      onNewChat: (): void => { this.session.newChat(); },
      onNewTopic: (): void => { this.onNewTopic(); },
      onOpenPersona: (): void => { this.session.openDrawerPersona(); },
      onOpenTasks: (): void => { this.session.openDrawerTasks(); },
      onOpenHistory: (): void => { this.session.openDrawerHistory(); },
      onOpenCouncil: (): void => { this.session.openDrawerCouncil(); },
      onOpenApps: (): void => { this.session.openDrawerApps(); },
      onOpenWebMount: (): void => { this.session.openDrawerWebMount(); },
      onOpenAppearance: (): void => {
        this.session.closeDrawer();
        this.onOpenAppearance();
      },
      onOpenSettings: (): void => { this.session.openDrawerSettings(); },
      onSearchText: (text: string): void => { this.session.refreshDrawerConversations(text); },
      onDismiss: (): void => { this.session.closeDrawer(); },
      onEditNickname: (name: string): void => { this.session.editDrawerNickname(name); },
      conversationList: (): void => { this.drawerConversationList(); },
    })
  }

  build(): void {
    // 对齐 iOS AmberDuetWorkspaceDrawerSheet：底部弹出大浮层面板（非左侧滑入抽屉）
    Column() {}
      .width(0)
      .height(0)
      .hitTestBehavior(HitTestMode.None)
      .bindSheet(this.session.drawerOpen, this.DrawerSheetContent(), {
        height: SheetSize.LARGE,
        showClose: false,
        dragBar: true,
        backgroundColor: BG,
        onDisappear: (): void => { this.session.closeDrawer(); },
      })
  }
}
