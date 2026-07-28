// ChatListPage — 会话列表(pinned 优先 + 更新时间倒序,语义来自 chat-domain repository)
// D-022:搜索(title LIKE,repo.search) + 滑动操作(置顶切换/删除)
// D-096:删除改撤销窗(HistoryPage.kt:124-153 SwipeableConversationItem 逐字语义)—
//   滑动删除 = 立即删(deferCleanup)+ 4s snackbar('对话已删除'/'撤销');
//   撤销 → insertConversation 恢复;超时/被顶替 → purge(cleanupDeletedConversation
//   → deleteChatFiles,PD-007 清理接线同步落地)
import { router } from '@kit.ArkUI';
import type { Conversation, ConversationSummary } from '@amber/chat-domain';
import { getChatRepository } from '../di/AppContainer.ets';
import { cleanupDeletedConversation } from '../platform_impl/ManagedFileStore.ets';

const BG: string = '#F4F2EC';
const SURFACE: string = '#FAF9F5';
const INK: string = '#1B1A17';
const INK2: string = '#57544C';
const INK3: string = '#8F8B80';
const LINE: string = '#E4E0D6';
const ACCENT: string = '#B8623A';
const DANGER: string = '#B43A3A';
const SNACK_BG: string = '#32302B';

// SnackbarDuration.Short ≈ 4s(showSnackbar 默认时长)
const SNACK_DURATION_MS: number = 4000;

@Entry
@Component
struct ChatListPage {
  @State items: ConversationSummary[] = [];
  @State loaded: boolean = false;
  @State searchText: string = '';
  // D-096:撤销窗(单条待决;新删除顶替旧 = 旧 dismissed → purge)
  @State snackVisible: boolean = false;
  private pendingDeleted: Conversation | null = null;
  private snackTimer: number = -1;

  aboutToAppear(): void {
    this.refresh();
  }

  private refresh(): void {
    const repo = getChatRepository();
    const query: string = this.searchText.trim();
    const future: Promise<ConversationSummary[]> =
      query.length > 0 ? repo.search(query) : repo.list();
    future.then((list: ConversationSummary[]): void => {
      this.items = list;
      this.loaded = true;
    });
  }

  private openConversation(id: string): void {
    const params: Record<string, string> = {};
    params['conversationId'] = id;
    router.pushUrl({ url: 'pages/ChatPage', params: params });
  }

  private newConversation(): void {
    router.pushUrl({ url: 'pages/ChatPage' });
  }

  private togglePin(item: ConversationSummary): void {
    const repo = getChatRepository();
    repo.getById(item.id).then((conv: Conversation | null): void => {
      if (conv === null) return;
      conv.isPinned = !conv.isPinned;
      repo.save(conv).then((): void => this.refresh());
    });
  }

  // HistoryPage.kt:129-144 onDelete —
  //   先取完整会话(含 messageNodes)→ deleteConversation(deferCleanup=true)
  //   → snackbar;ActionPerformed → restoreConversation(insert);
  //   否则 → purgeDeletedConversation(cleanup)
  private onDelete(item: ConversationSummary): void {
    // 新 snackbar 顶替旧 → 旧 result = Dismissed → purge 旧条
    if (this.snackVisible && this.pendingDeleted !== null) {
      const stale: Conversation = this.pendingDeleted;
      this.pendingDeleted = null;
      this.clearSnackTimer();
      cleanupDeletedConversation(stale);
    }
    const repo = getChatRepository();
    repo.getById(item.id).then((full: Conversation | null): void => {
      if (full === null) return;
      repo.delete(item.id).then((): void => {
        this.pendingDeleted = full;
        this.snackVisible = true;
        this.refresh();
        this.snackTimer = setTimeout((): void => {
          this.dismissSnack();
        }, SNACK_DURATION_MS);
      });
    });
  }

  private clearSnackTimer(): void {
    if (this.snackTimer >= 0) {
      clearTimeout(this.snackTimer);
      this.snackTimer = -1;
    }
  }

  // SnackbarResult.ActionPerformed → vm.restoreConversation(insertConversation)
  private undoDelete(): void {
    const full: Conversation | null = this.pendingDeleted;
    this.pendingDeleted = null;
    this.clearSnackTimer();
    this.snackVisible = false;
    if (full !== null) {
      getChatRepository().save(full).then((): void => this.refresh());
    }
  }

  // 超时/关闭 → purge(cleanupDeletedConversation;favorites/chat_images 未移植登记)
  private dismissSnack(): void {
    const full: Conversation | null = this.pendingDeleted;
    this.pendingDeleted = null;
    this.clearSnackTimer();
    this.snackVisible = false;
    if (full !== null) {
      cleanupDeletedConversation(full);
    }
  }

  @Builder
  swipeActions(item: ConversationSummary) {
    Row() {
      Text(item.isPinned ? '取消置顶' : '置顶')
        .fontSize(13).fontColor(Color.White)
        .textAlign(TextAlign.Center)
        .width(72).height('100%')
        .backgroundColor(ACCENT)
        .onClick((): void => this.togglePin(item))
      Text('删除')
        .fontSize(13).fontColor(Color.White)
        .textAlign(TextAlign.Center)
        .width(64).height('100%')
        .backgroundColor(DANGER)
        .onClick((): void => this.onDelete(item))
    }
  }

  build() {
    Stack({ alignContent: Alignment.Bottom }) {
      Column() {
      Row() {
        Text('对话')
          .fontSize(22).fontWeight(FontWeight.Bold).fontColor(INK)
        Blank()
        Text('助手').fontSize(13).fontColor(INK3).margin({ right: 16 })
          .onClick((): void => { router.pushUrl({ url: 'pages/ChatAssistantsPage' }); })
        Text('Provider').fontSize(13).fontColor(INK3).margin({ right: 16 })
          .onClick((): void => { router.pushUrl({ url: 'pages/ChatProviderSettingsPage' }); })
        Text('记忆').fontSize(13).fontColor(INK3).margin({ right: 16 })
          .onClick((): void => { router.pushUrl({ url: 'pages/MemoryPage' }); })
        Text('任务模型').fontSize(13).fontColor(INK3).margin({ right: 16 })
          .onClick((): void => { router.pushUrl({ url: 'pages/ChatTaskModelsPage' }); })
        Text('搜索').fontSize(13).fontColor(INK3).margin({ right: 16 })
          .onClick((): void => { router.pushUrl({ url: 'pages/ChatSearchSettingsPage' }); })
        Text('+ 新对话')
          .fontSize(14).fontColor(ACCENT)
          .padding({ left: 12, right: 12, top: 6, bottom: 6 })
          .onClick((): void => this.newConversation())
      }
      .width('100%').padding({ left: 16, right: 16, top: 16, bottom: 8 })

      TextInput({ text: this.searchText, placeholder: '搜索对话标题…' })
        .fontSize(14)
        .height(40)
        .margin({ left: 16, right: 16, bottom: 8 })
        .backgroundColor(SURFACE)
        .onChange((v: string): void => {
          this.searchText = v;
          this.refresh();
        })

      if (!this.loaded) {
        Text('加载中…').fontSize(13).fontColor(INK3).margin({ top: 32 })
      } else if (this.items.length === 0) {
        Column() {
          Text(this.searchText.trim().length > 0 ? '没有匹配的对话' : '还没有对话')
            .fontSize(15).fontColor(INK2)
          if (this.searchText.trim().length === 0) {
            Text('点右上角开始新对话').fontSize(12).fontColor(INK3).margin({ top: 8 })
          }
        }.margin({ top: 64 })
      } else {
        List() {
          ForEach(this.items, (item: ConversationSummary): void => {
            ListItem() {
              Column() {
                Row() {
                  if (item.isPinned) {
                    Text('置顶').fontSize(10).fontColor(ACCENT).margin({ right: 6 })
                  }
                  Text(item.title.length > 0 ? item.title : '未命名对话')
                    .fontSize(15).fontColor(INK).maxLines(1)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                    .layoutWeight(1)
                }
                Text(item.updateAt)
                  .fontSize(11).fontColor(INK3).margin({ top: 4 })
              }
              .width('100%').alignItems(HorizontalAlign.Start)
              .padding({ left: 16, right: 16, top: 12, bottom: 12 })
              .backgroundColor(SURFACE)
              .onClick((): void => this.openConversation(item.id))
            }
            .swipeAction({ end: this.swipeActions(item) })
          }, (item: ConversationSummary): string => item.id)
        }
        .width('100%').layoutWeight(1)
        .divider({ strokeWidth: 1, color: LINE })
      }
    }
    .width('100%').height('100%')

    // D-096:撤销 snackbar(HistoryPage.kt SnackbarHost;'对话已删除'/'撤销' 逐字,
    //   strings.xml:308/:320)
    if (this.snackVisible) {
      Row() {
        Text('对话已删除')
          .fontSize(13).fontColor(Color.White)
        Blank()
        Text('撤销')
          .fontSize(13).fontColor(ACCENT)
          .padding({ left: 16, top: 6, bottom: 6 })
          .onClick((): void => this.undoDelete())
      }
      .width('92%')
      .padding({ left: 16, right: 16, top: 10, bottom: 10 })
      .backgroundColor(SNACK_BG)
      .borderRadius(8)
      .margin({ bottom: 24 })
    }
  }
  .width('100%').height('100%').backgroundColor(BG)
  }
}
