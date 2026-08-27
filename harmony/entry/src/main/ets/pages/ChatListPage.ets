// ChatListPage — Android 风格会话首页
// 保留真实数据逻辑: repo.list/search/删除/撤销/置顶/删除全部

import { router, promptAction } from '@kit.ArkUI';
import type { Conversation, ConversationSummary, UIMessage, DisplaySetting } from '@amber/chat-domain';
import { loadDisplaySetting } from '@amber/chat-domain';
import { getChatKvStore, getChatRepository } from '../di/AppContainer.ets';
import { cleanupDeletedConversation } from '../platform_impl/ManagedFileStore.ets';
import { Icon } from '../components/ui/Icon.ets';
import { UIAvatar } from '../components/UIAvatar.ets';
import { ACCENT, ACCENT_INK, BG, BODY, ERROR, INK, INK2, INK3, INK4, LINE, META, SURFACE2 } from '../design/tokens.ets';
import { FONT_SANS, FONT_SANS_BOLD, FONT_SANS_MEDIUM, FONT_MONO } from '../design/fonts.ets';

interface ActionMenuResult {
  index: number;
}

const SNACK_BG: string = '#32302B';
const SNACK_DURATION_MS: number = 4000;

@Entry
@Component
struct ChatListPage {
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('darkMode') darkMode: boolean = false;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @State themeTick: number = 0;
  @State items: ConversationSummary[] = [];
  @State loaded: boolean = false;
  @State searchText: string = '';
  @State snackVisible: boolean = false;
  @State fabScale: number = 1;
  @State userNickname: string = '';
  @State previews: Record<string, string> = {};
  @State msgCounts: Record<string, number> = {};
  private pendingDeleted: Conversation | null = null;
  private snackTimer: number = -1;
  private pageAlive: boolean = false;
  // refresh 请求序号:慢的旧搜索结果不得覆盖新输入的结果
  private refreshSeq: number = 0;

  onPageShow(): void {
    this.themeTick = Date.now();
    this.refresh();
    loadDisplaySetting(getChatKvStore()).then((setting: DisplaySetting): void => {
      if (this.pageAlive) this.userNickname = setting.userNickname;
    }).catch((): void => {});
    // 应用已在前台时,第二条通知不会再触发 aboutToAppear — show 时也要消费深链
    this.consumeDeepLink();
  }

  aboutToAppear(): void {
    this.pageAlive = true;
    this.refresh();
    loadDisplaySetting(getChatKvStore()).then((setting: DisplaySetting): void => {
      if (this.pageAlive) this.userNickname = setting.userNickname;
    }).catch((): void => {});

    // EntryAbility 写入的通知/分享深链在根页消费，避免改变返回栈语义。
    this.consumeDeepLink();
  }

  // 深链消费:置空后跳转;消费过的 topic/title 不残留
  private consumeDeepLink(): void {
    const wantTopicId: string = AppStorage.get<string>('wantTopicId') ?? '';
    const wantTitle: string = AppStorage.get<string>('wantTitle') ?? '';
    if (wantTopicId.length > 0) {
      AppStorage.setOrCreate<string>('wantTopicId', '');
      AppStorage.setOrCreate<string>('wantTitle', '');
      const params: Record<string, string> = {};
      params['topicId'] = wantTopicId;
      params['title'] = wantTitle;
      router.pushUrl({ url: 'pages/DeepReadArticlePage', params: params });
    }
  }

  aboutToDisappear(): void {
    this.pageAlive = false;
    if (this.pendingDeleted !== null) this.commitPendingDelete();
  }

  private refresh(): void {
    const repo = getChatRepository();
    const query: string = this.searchText.trim();
    const seq: number = ++this.refreshSeq;
    const future: Promise<ConversationSummary[]> =
      query.length > 0 ? repo.search(query) : repo.list();
    future.then((list: ConversationSummary[]): void => {
      if (seq !== this.refreshSeq || !this.pageAlive) return;
      const pendingId: string = this.pendingDeleted !== null ? this.pendingDeleted.id : '';
      const visible: ConversationSummary[] = list.filter(
        (item: ConversationSummary): boolean => item.id !== pendingId);
      this.items = visible;
      this.loaded = true;

      // 会话行预览: 使用末条选中消息的第一行，并去除常见 Markdown 行内标记。
      for (const summary of visible) {
        repo.getById(summary.id).then((conversation: Conversation | null): void => {
          if (conversation === null || seq !== this.refreshSeq || !this.pageAlive) return;
          let count: number = 0;
          let lastText: string = '';
          for (const node of conversation.messageNodes) {
            if (node.messages.length === 0) continue;
            const index: number =
              node.selectIndex >= 0 && node.selectIndex < node.messages.length ? node.selectIndex : 0;
            const message: UIMessage = node.messages[index];
            count++;
            for (const part of message.parts) {
              if (part.type === 'text') lastText = part.text;
            }
          }
          const preview: string = lastText.split('\n')[0]
            .replace(/\*\*([^*]+)\*\*/g, '$1')
            .replace(/`([^`]+)`/g, '$1');
          const nextPreviews: Record<string, string> = {};
          for (const key of Object.keys(this.previews)) nextPreviews[key] = this.previews[key];
          nextPreviews[summary.id] = preview;
          this.previews = nextPreviews;

          const nextCounts: Record<string, number> = {};
          for (const key of Object.keys(this.msgCounts)) nextCounts[key] = this.msgCounts[key];
          nextCounts[summary.id] = count;
          this.msgCounts = nextCounts;
        });
      }
    });
  }

  private openConversation(id: string): void {
    const params: Record<string, string> = {};
    params['conversationId'] = id;
    router.pushUrl({ url: 'pages/ChatPage', params: params });
  }

  private openSearch(): void {
    const params: Record<string, string> = {};
    const query: string = this.searchText.trim();
    if (query.length > 0) params['query'] = query;
    router.pushUrl({ url: 'pages/SearchPage', params: params });
  }

  private newConversation(): void {
    router.pushUrl({ url: 'pages/ChatPage' });
  }

  private togglePin(item: ConversationSummary): void {
    const repo = getChatRepository();
    repo.getById(item.id).then((conversation: Conversation | null): void => {
      if (conversation === null) return;
      conversation.isPinned = !conversation.isPinned;
      repo.save(conversation).then((): void => this.refresh());
    });
  }

  private onDelete(item: ConversationSummary): void {
    if (this.snackVisible && this.pendingDeleted !== null) {
      this.commitPendingDelete();
    }
    const repo = getChatRepository();
    repo.getById(item.id).then((fullConversation: Conversation | null): void => {
      if (fullConversation === null) return;
      this.pendingDeleted = fullConversation;
      this.snackVisible = true;
      this.refresh();
      this.snackTimer = setTimeout((): void => {
        this.commitPendingDelete();
      }, SNACK_DURATION_MS);
    }).catch((error: Error): void => {
      promptAction.showToast({ message: `删除失败：${error.message}`, duration: 1800 });
    });
  }

  private clearSnackTimer(): void {
    if (this.snackTimer >= 0) {
      clearTimeout(this.snackTimer);
      this.snackTimer = -1;
    }
  }

  private undoDelete(): void {
    this.pendingDeleted = null;
    this.clearSnackTimer();
    this.snackVisible = false;
    this.refresh();
  }

  private commitPendingDelete(): void {
    const fullConversation: Conversation | null = this.pendingDeleted;
    this.pendingDeleted = null;
    this.clearSnackTimer();
    this.snackVisible = false;
    if (fullConversation === null) return;
    const repo = getChatRepository();
    repo.delete(fullConversation.id)
      .then((): Promise<void> =>
        cleanupDeletedConversation(fullConversation, repo, getChatKvStore()))
      .then((): void => { if (this.pageAlive) this.refresh(); })
      .catch((error: Error): void => {
        if (!this.pageAlive) return;
        promptAction.showToast({ message: `删除清理失败：${error.message}`, duration: 2000 });
        this.refresh();
      });
  }

  private deleteAllConversations(): void {
    const repo = getChatRepository();
    repo.list().then((all: ConversationSummary[]): void => {
      let chain: Promise<void> = Promise.resolve();
      for (const item of all) {
        chain = chain.then((): Promise<void> =>
          repo.getById(item.id).then((fullConversation: Conversation | null): Promise<void> => {
            if (fullConversation === null) return Promise.resolve();
            return repo.delete(item.id).then((): Promise<void> =>
              cleanupDeletedConversation(fullConversation, repo, getChatKvStore()));
          }));
      }
      chain.then((): void => {
        if (this.pageAlive) this.refresh();
      }).catch((error: Error): void => {
        if (!this.pageAlive) return;
        promptAction.showToast({ message: `删除全部对话失败：${error.message}`, duration: 2000 });
        this.refresh();
      });
    }).catch((error: Error): void => {
      if (!this.pageAlive) return;
      promptAction.showToast({ message: `删除全部对话失败：${error.message}`, duration: 2000 });
    });
  }

  private showDeleteAllConfirm(): void {
    AlertDialog.show({
      title: '删除全部对话',
      message: '确定要删除所有对话吗？此操作无法撤销。',
      primaryButton: { value: '取消', action: (): void => {} },
      secondaryButton: {
        value: '删除',
        fontColor: ERROR,
        action: (): void => { this.deleteAllConversations(); },
      },
    });
  }

  private previewOf(id: string): string {
    const preview: string | undefined = this.previews[id];
    return preview === undefined || preview.length === 0 ? '暂无消息' : preview;
  }

  private messageCount(id: string): number {
    const count: number | undefined = this.msgCounts[id];
    return count === undefined ? 0 : count;
  }

  private formatConversationDate(updateAt: string): string {
    const date: Date = new Date(updateAt);
    if (isNaN(date.getTime())) return updateAt.length > 10 ? updateAt.substring(0, 10) : updateAt;
    const now: Date = new Date();
    const monthDay: string = `${date.getMonth() + 1}月${date.getDate()}日`;
    return date.getFullYear() === now.getFullYear() ? monthDay : `${date.getFullYear()}年${monthDay}`;
  }

  private todayLabel(): string {
    const date: Date = new Date();
    const weekday: string = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][date.getDay()];
    return `今天 · ${date.getMonth() + 1}月${date.getDate()}日 ${weekday}`;
  }

  private onFeature(id: string): void {
    if (id === 'reader') router.pushUrl({ url: 'pages/BoardPage' });
    else if (id === 'novel') router.pushUrl({ url: 'pages/NovelProjectsPage' });
    else if (id === 'council') router.pushUrl({ url: 'pages/CouncilPage' });
    else if (id === 'apps') router.pushUrl({ url: 'pages/MiniAppListPage' });
    else promptAction.showToast({ message: '该功能即将上线', duration: 1500 });
  }

  @Builder
  ConversationRow(item: ConversationSummary): void {
    Column() {
      Row() {
        Text(item.title.length > 0 ? item.title : '未命名对话')
          .fontSize(17)
          .fontWeight(FontWeight.Bold)
          .lineHeight(23)
          .fontFamily(FONT_SANS_BOLD)
          .fontColor(INK)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .layoutWeight(1)
        if (item.isPinned) {
          Icon({ name: 'pin', iconSize: 15, color: this.accentColorStore })
            .margin({ left: 10, right: 8 })
        }
        Text(this.formatConversationDate(item.updateAt))
          .fontSize(13)
          .lineHeight(18)
          .fontFamily(FONT_MONO)
          .fontColor(INK3)
          .maxLines(1)
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)

      Row() {
        Text(this.previewOf(item.id))
          .fontSize(14)
          .lineHeight(19)
          .fontColor(INK3)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .layoutWeight(1)
          .margin({ right: 14 })
        if (this.messageCount(item.id) > 0) {
          Text(`${this.messageCount(item.id)}`)
            .fontSize(13)
            .fontFamily(FONT_MONO)
            .fontColor(this.accentColorStore)
            .width(28)
            .height(28)
            .lineHeight(26)
            .textAlign(TextAlign.Center)
            .maxLines(1)
            .minFontSize(11)
            .border({ width: 1, color: this.accentColorStore })
            .borderRadius(14)
        }
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .margin({ top: 5 })
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
    .padding({ left: 20, right: 20, top: 11, bottom: 11 })
    .backgroundColor(BG)
    .border({ width: { bottom: 1 }, color: { bottom: LINE } })
    .onClick((): void => { this.openConversation(item.id); })
    .gesture(
      LongPressGesture()
        .onAction((): void => { this.showRowMenu(item); })
    )
  }

  @Builder
  SwipePin(item: ConversationSummary): void {
    Column() {
      Icon({ name: 'pin', iconSize: 20, color: this.accentColorStore })
      Text(item.isPinned ? '取消置顶' : '置顶')
        .fontSize(12)
        .fontColor(this.accentColorStore)
        .margin({ top: 6 })
    }
    .width(88)
    .height('100%')
    .backgroundColor(SURFACE2)
    .justifyContent(FlexAlign.Center)
    .alignItems(HorizontalAlign.Center)
    .onClick((): void => { this.togglePin(item); })
  }

  @Builder
  SwipeDelete(item: ConversationSummary): void {
    Column() {
      Icon({ name: 'trash', iconSize: 20, color: ACCENT_INK })
      Text('删除')
        .fontSize(12)
        .fontColor(ACCENT_INK)
        .margin({ top: 6 })
    }
    .width(88)
    .height('100%')
    .backgroundColor(this.accentColorStore)
    .justifyContent(FlexAlign.Center)
    .alignItems(HorizontalAlign.Center)
    .onClick((): void => { this.onDelete(item); })
  }

  private showRowMenu(item: ConversationSummary): void {
    promptAction.showActionMenu({
      buttons: [
        { text: item.isPinned ? '取消置顶' : '置顶', color: INK },
        { text: '删除', color: ERROR },
      ],
    }).then((result: ActionMenuResult): void => {
      if (result.index === 0) this.togglePin(item);
      else if (result.index === 1) this.onDelete(item);
    }).catch((): void => {});
  }

  @Builder
  FeatureItem(id: string, label: string, icon: string): void {
    Column() {
      Icon({ name: icon, iconSize: 26, color: this.accentColorStore })
      Text(label)
        .fontSize(13)
        .fontWeight(FontWeight.Medium)
        .lineHeight(18)
        .fontFamily(FONT_SANS_MEDIUM)
        .fontColor(INK2)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .margin({ top: 7 })
    }
    .width('100%')
    .height('100%')
    .justifyContent(FlexAlign.Center)
    .alignItems(HorizontalAlign.Center)
    .onClick((): void => { this.onFeature(id); })
  }

  @Builder
  FeatureDivider(): void {
    Column()
      .width(1)
      .height(44)
      .backgroundColor(LINE)
  }

  build(): void {
    Stack({ alignContent: Alignment.Bottom }) {
      Column() {
        Row() {
          Row() {
            Text('Amber')
              .fontSize(30)
              .fontWeight(FontWeight.Bold)
              .lineHeight(36)
              .letterSpacing(-0.5)
              .fontFamily(FONT_SANS_BOLD)
              .fontColor(INK)
            Column()
              .width(9)
              .height(30)
              .backgroundColor(this.accentColorStore)
              .margin({ left: 8 })
          }
          .alignItems(VerticalAlign.Center)

          Text(this.todayLabel())
            .fontSize(14)
            .lineHeight(19)
            .fontFamily(FONT_SANS)
            .fontColor(INK3)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .layoutWeight(1)
            .margin({ left: 14 })


          Row() {
            Icon({ name: 'gear', iconSize: 22, color: INK2 })
          }
          .width(40)
          .height(40)
          .justifyContent(FlexAlign.Center)
          .alignItems(VerticalAlign.Center)
          .onClick((): void => { router.pushUrl({ url: 'pages/SettingPage' }); })

          Row() {
            UIAvatar({
              name: this.userNickname,
              avatarSize: 36,
              accentColor: this.accentColorStore,
            })
          }
          .width(40)
          .height(40)
          .justifyContent(FlexAlign.Center)
          .alignItems(VerticalAlign.Center)
          .margin({ left: 6 })
          .onClick((): void => { router.pushUrl({ url: 'pages/ProfilePage' }); })
        }
        .width('100%')
        .alignItems(VerticalAlign.Center)
        .padding({ left: 20, right: 20, top: this.statusBarHeight + 10, bottom: 10 })

        Row() {
          Icon({ name: 'search', iconSize: 20, color: INK3 })
          TextInput({ text: this.searchText, placeholder: '搜索会话' })
            .fontSize(16)
            .fontColor(INK)
            .placeholderColor(INK4)
            .placeholderFont({ size: 16 })
            .fontFamily(FONT_SANS)
            .backgroundColor(Color.Transparent)
            .layoutWeight(1)
            .margin({ left: 12 })
            .onClick((): void => { this.openSearch(); })
            .onChange((value: string): void => { this.searchText = value; this.refresh(); })
          if (this.searchText.length > 0) {
            Row() {
              Icon({ name: 'close', iconSize: 18, color: INK3 })
            }
            .width(40)
            .height(40)
            .justifyContent(FlexAlign.Center)
            .alignItems(VerticalAlign.Center)
            .onClick((): void => { this.searchText = ''; this.refresh(); })
          }
        }
        .width('100%')
        .height(52)
        .padding({ left: 20, right: 20 })
        .border({ width: { bottom: 1 }, color: { bottom: LINE } })
        .alignItems(VerticalAlign.Center)

        Row() {
          Column() { this.FeatureItem('reader', '深度阅读', 'book') }.height('100%').layoutWeight(1)
          this.FeatureDivider()
          Column() { this.FeatureItem('apps', '小应用', 'box') }.height('100%').layoutWeight(1)
          this.FeatureDivider()
          Column() { this.FeatureItem('novel', '小说创作', 'edit') }.height('100%').layoutWeight(1)
          this.FeatureDivider()
          Column() { this.FeatureItem('webmount', '站点', 'globe') }.height('100%').layoutWeight(1)
          this.FeatureDivider()
          Column() { this.FeatureItem('council', '模型议会', 'brain') }.height('100%').layoutWeight(1)
        }
        .width('100%')
        .height(92)
        .padding({ left: 20, right: 20 })
        .alignItems(VerticalAlign.Center)
        .border({ width: { bottom: 1 }, color: { bottom: LINE } })

        Stack({ alignContent: Alignment.BottomEnd }) {
          List() {
            if (!this.loaded) {
              ListItem() {
                Text('加载会话中…')
                  .fontSize(BODY)
                  .fontColor(INK3)
                  .padding({ top: 48 })
              }
            } else if (this.items.length === 0) {
              ListItem() {
                Column() {
                  Text(this.searchText.trim().length > 0 ? '没有匹配的会话' : '还没有会话')
                    .fontSize(16)
                    .fontWeight(FontWeight.Medium)
                    .lineHeight(22)
                    .fontFamily(FONT_SANS_MEDIUM)
                    .fontColor(INK2)
                  Text(this.searchText.trim().length > 0 ? '换一个关键词再试试。' : '点击右下角按钮开始新的对话。')
                    .fontSize(14)
                    .lineHeight(19)
                    .fontFamily(FONT_SANS)
                    .fontColor(INK3)
                    .margin({ top: 6 })
                }
                .width('100%')
                .alignItems(HorizontalAlign.Center)
                .padding({ top: 48, bottom: 48 })
              }
            } else {
              ForEach(this.items, (item: ConversationSummary): void => {
                ListItem() {
                  this.ConversationRow(item)
                }
                .swipeAction({
                  start: (): void => { this.SwipePin(item); },
                  end: (): void => { this.SwipeDelete(item); },
                })
              }, (item: ConversationSummary): string => item.id + (this.darkMode ? '-dark' : '-light'))
            }
          }
          .width('100%')
          .height('100%')
          .padding({ bottom: this.snackVisible ? 168 : 108 })
          .backgroundColor(BG)
          .divider({ strokeWidth: 0, color: Color.Transparent })

          Column() {
            Icon({ name: 'edit', iconSize: 24, color: ACCENT_INK })
          }
          .width(56)
          .height(56)
          .borderRadius(28)
          .backgroundColor(this.accentColorStore)
          .justifyContent(FlexAlign.Center)
          .alignItems(HorizontalAlign.Center)
          .scale({ x: this.fabScale, y: this.fabScale })
          .onTouch((event: TouchEvent): void => {
            const down: boolean = event.type === TouchType.Down;
            animateTo({ duration: 180, curve: Curve.EaseOut }, (): void => {
              this.fabScale = down ? 0.96 : 1;
            });
          })
          .margin({ right: 24, bottom: this.snackVisible ? 80 : 26 })
          .onClick((): void => { this.newConversation(); })
        }
        .layoutWeight(1)
        .width('100%')
      }
      .width('100%')
      .height('100%')

      if (this.snackVisible) {
        Row() {
          Text('对话已删除').fontSize(META).fontColor(Color.White)
          Blank()
          Text('撤销')
            .fontSize(META)
            .fontColor(this.accentColorStore)
            .padding({ left: 16, top: 6, bottom: 6 })
            .onClick((): void => { this.undoDelete(); })
        }
        .width('92%')
        .padding({ left: 16, right: 16, top: 10, bottom: 10 })
        .backgroundColor(SNACK_BG)
        .borderRadius(8)
        .margin({ bottom: 24 })
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(BG)
    // 返回首页时确保重新读取可变主题令牌。
    .opacity(this.themeTick >= 0 ? 1 : 1)
    .opacity(this.accentColorStore.length >= 0 ? 1 : 1)
  }
}
