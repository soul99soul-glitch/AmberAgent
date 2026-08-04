// ChatListPage — 会话列表(G7 重构)
//
// 对齐 PageTitleBar 风格(inline header, 因需两个 trailing action):
//   - header padding 14/2/11 + border-bottom 1px LINE + bg BG + ←24/INK
//   - flat hairline 行(不用 List+divider)
//   - tokens 从 tokens.ets 导入(无本地重定义)
//
// 保留真实数据逻辑: repo.list/search/删除/撤销/置顶/删除全部

import { router } from '@kit.ArkUI';
import type { Conversation, ConversationSummary } from '@amber/chat-domain';
import { getChatRepository } from '../di/AppContainer.ets';
import { cleanupDeletedConversation } from '../platform_impl/ManagedFileStore.ets';
import { SearchField } from '../components/ui/SearchField.ets';
import { PageSpacer } from '../components/PageTemplate.ets';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, ERROR, BODY, META, LABEL, TITLE } from '../design/tokens.ets';

const SNACK_BG: string = '#32302B';
const SNACK_DURATION_MS: number = 4000;

@Entry
@Component
struct ChatListPage {
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State items: ConversationSummary[] = [];
  @State loaded: boolean = false;
  @State searchText: string = '';
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

  private onDelete(item: ConversationSummary): void {
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

  private undoDelete(): void {
    const full: Conversation | null = this.pendingDeleted;
    this.pendingDeleted = null;
    this.clearSnackTimer();
    this.snackVisible = false;
    if (full !== null) {
      getChatRepository().save(full).then((): void => this.refresh());
    }
  }

  private dismissSnack(): void {
    const full: Conversation | null = this.pendingDeleted;
    this.pendingDeleted = null;
    this.clearSnackTimer();
    this.snackVisible = false;
    if (full !== null) {
      cleanupDeletedConversation(full);
    }
  }

  private deleteAllConversations(): void {
    const repo = getChatRepository();
    repo.list().then((all: ConversationSummary[]): void => {
      let chain: Promise<void> = Promise.resolve();
      for (const item of all) {
        chain = chain.then((): Promise<void> =>
          repo.getById(item.id).then((full: Conversation | null): Promise<void> => {
            if (full === null) return Promise.resolve();
            return repo.delete(item.id).then((): Promise<void> =>
              cleanupDeletedConversation(full));
          }));
      }
      chain.then((): void => this.refresh());
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

  @Builder
  ConversationRow(item: ConversationSummary): void {
    Column() {
      Row() {
        if (item.isPinned) {
          Text('置顶')
            .fontSize(LABEL)
            .fontColor(ACCENT)
            .margin({ right: 6 })
        }
        Text(item.title.length > 0 ? item.title : '未命名对话')
          .fontSize(BODY)
          .fontColor(INK)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .layoutWeight(1)
        Text(item.updateAt.length > 10 ? item.updateAt.substring(0, 10) : item.updateAt)
          .fontSize(LABEL)
          .fontColor(INK3)
      }
      .width('100%')

      // 滑动操作按钮(内联,不用 swipeAction)
      Row() {
        Text(item.isPinned ? '取消置顶' : '置顶')
          .fontSize(META)
          .fontColor(Color.White)
          .textAlign(TextAlign.Center)
          .width(72)
          .height(36)
          .backgroundColor(ACCENT)
          .borderRadius(6)
          .margin({ right: 8 })
          .onClick((): void => { this.togglePin(item); })
        Text('删除')
          .fontSize(META)
          .fontColor(Color.White)
          .textAlign(TextAlign.Center)
          .width(64)
          .height(36)
          .backgroundColor(ERROR)
          .borderRadius(6)
          .onClick((): void => { this.onDelete(item); })
      }
      .margin({ top: 6 })
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
    .onClick((): void => { this.openConversation(item.id); })
  }

  build(): void {
    Stack({ alignContent: Alignment.Bottom }) {
      Column() {
        // 统一 title bar (对齐 SessionsView 设计: ← + 标题 + 右侧图标按钮)
        Row() {
          Text('←')
            .fontSize(24)
            .fontColor(INK)
            .onClick((): void => { router.back(); })
          Text('对话历史')
            .fontSize(TITLE)
            .fontWeight(FontWeight.Bold)
            .fontColor(INK)
            .margin({ left: 8 })
            .layoutWeight(1)
          // 右侧: 设置(单色文字) + 新对话(主操作)
          Text('设置')
            .fontSize(BODY)
            .fontColor(INK2)
            .margin({ right: 16 })
            .onClick((): void => { router.pushUrl({ url: 'pages/SettingPage' }); })
          Text('+ 新对话')
            .fontSize(BODY)
            .fontColor(ACCENT)
            .onClick((): void => { this.newConversation(); })
        }
        .width('100%')
        .padding({ left: 14, right: 14, top: this.statusBarHeight + 2, bottom: 11 })
        .border({ width: { bottom: 1 }, color: { bottom: LINE } })
        .backgroundColor(BG)

        // 搜索栏
        SearchField({
          value: this.searchText,
          placeholder: '搜索对话标题…',
          onChange: (v: string): void => {
            this.searchText = v;
            this.refresh();
          },
        })
          .margin({ left: 16, right: 16, top: 12, bottom: 8 })

        // 列表区
        Scroll() {
          Column() {
            if (!this.loaded) {
              Text('加载中…')
                .fontSize(BODY)
                .fontColor(INK3)
                .padding({ top: 40 })
            } else if (this.items.length === 0) {
              Column() {
                Text(this.searchText.trim().length > 0 ? '没有匹配的对话' : '还没有对话')
                  .fontSize(BODY)
                  .fontColor(INK2)
                if (this.searchText.trim().length === 0) {
                  Text('点右上角开始新对话')
                    .fontSize(LABEL)
                    .fontColor(INK3)
                    .margin({ top: 8 })
                }
              }
              .margin({ top: 64 })
            } else {
              ForEach(this.items, (item: ConversationSummary): void => {
                this.ConversationRow(item)
              }, (item: ConversationSummary): string => item.id)

              // 删除全部(danger,列表底部,不挤在头部)
              Row() {
                Text('删除全部对话')
                  .fontSize(BODY)
                  .fontColor(ERROR)
                Blank()
                Text('›')
                  .fontSize(BODY)
                  .fontColor(INK4)
              }
              .width('100%')
              .padding({ left: 16, right: 16, top: 14, bottom: 14 })
              .backgroundColor(SURFACE)
              .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
              .margin({ top: 24 })
              .onClick((): void => { this.showDeleteAllConfirm(); })
            }

            PageSpacer()
          }
          .width('100%')
        }
        .layoutWeight(1)
        .backgroundColor(BG)
      }
      .width('100%')
      .height('100%')

      // 撤销 snackbar
      if (this.snackVisible) {
        Row() {
          Text('对话已删除')
            .fontSize(META)
            .fontColor(Color.White)
          Blank()
          Text('撤销')
            .fontSize(META)
            .fontColor(ACCENT)
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
  }
}
