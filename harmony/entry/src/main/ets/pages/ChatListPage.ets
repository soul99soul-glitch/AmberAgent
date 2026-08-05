// ChatListPage — 会话列表(G7 重构)
//
// 对齐 PageTitleBar 风格(inline header, 因需两个 trailing action):
//   - header padding 14/2/11 + border-bottom 1px LINE + bg BG + ←24/INK
//   - flat hairline 行(不用 List+divider)
//   - tokens 从 tokens.ets 导入(无本地重定义)
//
// 保留真实数据逻辑: repo.list/search/删除/撤销/置顶/删除全部

import { router, promptAction } from '@kit.ArkUI';
import type { Conversation, ConversationSummary } from '@amber/chat-domain';
import { getChatRepository } from '../di/AppContainer.ets';
import { cleanupDeletedConversation } from '../platform_impl/ManagedFileStore.ets';
import { SearchField } from '../components/ui/SearchField.ets';
import { PageSpacer } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, ACCENT_INK, ERROR, BODY, META, LABEL, TITLE } from '../design/tokens.ets';

interface ActionMenuResult {
  index: number;
}

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
  @State cursorOn: boolean = true;
  private pendingDeleted: Conversation | null = null;
  private snackTimer: number = -1;

  aboutToAppear(): void {
    setInterval((): void => { this.cursorOn = !this.cursorOn; }, 525);
    this.refresh();
    // Want deep-link(通知/分享入口): EntryAbility 写 AppStorage, 首页消费后跳 DeepReadArticlePage
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

  // 会话行(1:1 参考首页): 标题+置顶图标 / mono 元信息行(右=日期); 滑动/长按 置顶/删除
  @Builder
  ConversationRow(item: ConversationSummary): void {
    Column() {
      Row() {
        Text(item.title.length > 0 ? item.title : '未命名对话')
          .fontSize(15)
          .fontWeight(FontWeight.Medium)
          .fontColor(INK)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .layoutWeight(1)
        if (item.isPinned) {
          Icon({ name: 'pin', iconSize: 16, color: ACCENT })
        }
      }
      .width('100%')

      // mono 元信息行: 左=会话, 右=日期
      Row() {
        Text('会话')
          .fontSize(11)
          .fontFamily('JetBrainsMono')
          .fontColor(INK4)
        Blank()
        Text(item.updateAt.length > 10 ? item.updateAt.substring(0, 10) : item.updateAt)
          .fontSize(11)
          .fontFamily('JetBrainsMono')
          .fontColor(INK4)
      }
      .width('100%')
      .margin({ top: 6 })
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
    .padding({ left: 20, right: 20, top: 14, bottom: 14 })
    .backgroundColor(BG)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
    .onClick((): void => { this.openConversation(item.id); })
    .gesture(
      LongPressGesture()
        .onAction((): void => { this.showRowMenu(item); })
    )
  }

  @Builder
  SwipePin(item: ConversationSummary): void {
    Row() {
      Icon({ name: 'pin', iconSize: 17, color: ACCENT })
      Text(item.isPinned ? '取消置顶' : '置顶')
        .fontSize(10).fontFamily('JetBrainsMono').fontColor(ACCENT).margin({ top: 4 })
    }
    .width(88).height('100%')
    .backgroundColor(SURFACE2)
    .justifyContent(FlexAlign.Center)
    .onClick((): void => { this.togglePin(item); })
  }

  @Builder
  SwipeDelete(item: ConversationSummary): void {
    Row() {
      Icon({ name: 'trash', iconSize: 17, color: ACCENT_INK })
      Text('删除')
        .fontSize(10).fontFamily('JetBrainsMono').fontColor(ACCENT_INK).margin({ top: 4 })
    }
    .width(88).height('100%')
    .backgroundColor(ACCENT)
    .justifyContent(FlexAlign.Center)
    .onClick((): void => { this.onDelete(item); })
  }

  // 长按操作菜单(置顶/删除), 取代常显按钮(设计更干净)
  private showRowMenu(item: ConversationSummary): void {
    promptAction.showActionMenu({
      buttons: [
        { text: item.isPinned ? '取消置顶' : '置顶', color: '#1B1A17' },
        { text: '删除', color: '#C2553F' },
      ],
    }).then((result: ActionMenuResult): void => {
      if (result.index === 0) this.togglePin(item);
      else if (result.index === 1) this.onDelete(item);
    }).catch((): void => {});
  }

  // 今天 · M月D日 周X(参考首页头部日期)
  private todayLabel(): string {
    const d: Date = new Date();
    const wd: string = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][d.getDay()];
    return `今天 · ${d.getMonth() + 1}月${d.getDate()}日 ${wd}`;
  }

  // 功能栏入口 → 对应页面
  private onFeature(id: string): void {
    if (id === 'reader') router.pushUrl({ url: 'pages/BoardPage' });
    else if (id === 'novel') router.pushUrl({ url: 'pages/NovelProjectsPage' });
    else if (id === 'council') router.pushUrl({ url: 'pages/CouncilPage' });
    else promptAction.showToast({ message: '该功能即将上线', duration: 1500 });
  }

  @Builder
  FeatureItem(id: string, label: string, icon: string, idx: number): void {
    if (idx > 0) {
      Column().width(1).height(26).backgroundColor(LINE)
    }
    Column() {
      Icon({ name: icon, iconSize: 24, color: ACCENT })
      Text(label)
        .fontSize(12.5)
        .fontColor(INK2)
        .margin({ top: 10 })
    }
    .padding({ left: 10, right: 10, top: 12, bottom: 13 })
    .onClick((): void => { this.onFeature(id); })
  }

  build(): void {
    Stack({ alignContent: Alignment.Bottom }) {
      Column() {
        // 头部(1:1 参考): Amber 字标+光标 + 今天日期 | 右: 设置齿轮 + 头像
        Row() {
          Row() {
            Text('Amber')
              .fontSize(23)
              .fontWeight(FontWeight.Bold)
              .fontFamily('JetBrainsMono')
              .fontColor(INK)
            Text('')
              .width(11).height(21).backgroundColor(ACCENT).borderRadius(1)
              .margin({ left: 2 })
              .opacity(this.cursorOn ? 1 : 0)
          }
          Text(this.todayLabel())
            .fontSize(11.5)
            .fontFamily('JetBrainsMono')
            .fontColor(INK3)
            .margin({ left: 9 })
          Blank()
          Icon({ name: 'gear', iconSize: 20, color: INK2 })
            .onClick((): void => { router.pushUrl({ url: 'pages/SettingPage' }); })
          Column()
            .width(34).height(34).borderRadius(999)
            .backgroundColor(ACCENT)
            .margin({ left: 10 })
        }
        .width('100%')
        .alignItems(VerticalAlign.Center)
        .padding({ left: 20, right: 20, top: this.statusBarHeight + 8, bottom: 12 })

        // 搜索栏(flat, 底部 hairline)
        Row() {
          Icon({ name: 'search', iconSize: 17, color: INK3 })
          TextInput({ text: this.searchText, placeholder: '搜索会话' })
            .fontSize(14.5)
            .fontColor(INK)
            .placeholderColor(INK4)
            .backgroundColor('#00000000')
            .layoutWeight(1)
            .margin({ left: 9 })
            .onChange((v: string): void => { this.searchText = v; this.refresh(); })
          if (this.searchText.length > 0) {
            Icon({ name: 'close', iconSize: 15, color: INK3 })
              .onClick((): void => { this.searchText = ''; this.refresh(); })
          }
        }
        .width('100%')
        .padding({ left: 20, right: 20, top: 9, bottom: 10 })
        .border({ width: { bottom: 1 }, color: { bottom: LINE } })

        // 功能栏(5 入口, 竖直 hairline 分隔, 底部 hairline)
        Row() {
          this.FeatureItem('reader', '深度阅读', 'book', 0)
          Blank()
          this.FeatureItem('apps', '小应用', 'box', 1)
          Blank()
          this.FeatureItem('novel', '小说创作', 'edit', 2)
          Blank()
          this.FeatureItem('webmount', 'WebMount', 'globe', 3)
          Blank()
          this.FeatureItem('council', '模型议会', 'brain', 4)
        }
        .width('100%')
        .alignItems(VerticalAlign.Center)
        .padding({ left: 12, right: 12 })
        .border({ width: { bottom: 1 }, color: { bottom: LINE } })

        // 会话列表 + FAB(右下悬浮)
        Stack({ alignContent: Alignment.BottomEnd }) {
          List() {
            if (!this.loaded) {
              ListItem() {
                Text('加载中…').fontSize(BODY).fontColor(INK3).padding({ top: 40 })
              }
            } else if (this.items.length === 0) {
              ListItem() {
                Column() {
                  Row() {
                    Text('//').fontSize(12).fontFamily('JetBrainsMono').fontColor(ACCENT)
                    Text(' 0 results').fontSize(12).fontFamily('JetBrainsMono').fontColor(INK4)
                  }
                  .margin({ bottom: 8 })
                  Text(this.searchText.trim().length > 0 ? `没有匹配「${this.searchText}」的会话` : '暂无会话，点击右下角按钮开始')
                    .fontSize(13.5).fontColor(INK3)
                }
                .width('100%')
                .alignItems(HorizontalAlign.Center)
                .padding({ top: 56, bottom: 56 })
              }
            } else {
              ForEach(this.items, (item: ConversationSummary): void => {
                ListItem() {
                  this.ConversationRow(item)
                }
                .swipeAction({ start: (): void => { this.SwipePin(item); }, end: (): void => { this.SwipeDelete(item); } })
              }, (item: ConversationSummary): string => item.id)
            }
          }
          .width('100%')
          .height('100%')
          .divider({ strokeWidth: 0, color: '#00000000' })

          // FAB 新会话(右下悬浮, accent 圆)
          Column() {
            Icon({ name: 'edit', iconSize: 24, color: ACCENT_INK })
          }
          .width(56).height(56).borderRadius(999)
          .backgroundColor(ACCENT)
          .justifyContent(FlexAlign.Center)
          .alignItems(HorizontalAlign.Center)
          .margin({ right: 20, bottom: 24 })
          .onClick((): void => { this.newConversation(); })
        }
        .layoutWeight(1)
        .width('100%')
      }
      .width('100%')
      .height('100%')

      // 撤销 snackbar(底部居中)
      if (this.snackVisible) {
        Row() {
          Text('对话已删除').fontSize(META).fontColor(Color.White)
          Blank()
          Text('撤销').fontSize(META).fontColor(ACCENT)
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
