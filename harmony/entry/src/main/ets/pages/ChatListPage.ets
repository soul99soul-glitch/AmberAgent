// ChatListPage — 会话列表(G7 重构)
//
// 对齐 PageTitleBar 风格(inline header, 因需两个 trailing action):
//   - header padding 14/2/11 + border-bottom 1px LINE + bg BG + ←24/INK
//   - flat hairline 行(不用 List+divider)
//   - tokens 从 tokens.ets 导入(无本地重定义)
//
// 保留真实数据逻辑: repo.list/search/删除/撤销/置顶/删除全部

import { router, promptAction } from '@kit.ArkUI';
import type { Conversation, ConversationSummary, UIMessage } from '@amber/chat-domain';
import { getChatKvStore, getChatRepository } from '../di/AppContainer.ets';
import { cleanupDeletedConversation } from '../platform_impl/ManagedFileStore.ets';
import { SearchField } from '../components/ui/SearchField.ets';
import { PageSpacer } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';
import { DotText, DotGlyph, DotGround, DFEATURE } from '../components/ui/DotMatrix.ets';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, ACCENT_INK, ERROR, BODY, META, LABEL, TITLE , BG_FADE_START, BG_FADE_END } from '../design/tokens.ets';

interface ActionMenuResult {
  index: number;
}

const SNACK_BG: string = '#32302B';
const SNACK_DURATION_MS: number = 4000;

@Entry
@Component
struct ChatListPage {
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  // 主题切换时触发整页重建(build 重读可变令牌)
  @StorageProp('darkMode') darkMode: boolean = false;
  // Return-to-page forces rebuild, reads latest theme tokens (background pages in stack don't receive StorageProp updates)
  @State themeTick: number = 0;
  @State items: ConversationSummary[] = [];
  @State loaded: boolean = false;
  @State searchText: string = '';
  @State snackVisible: boolean = false;
  @State cursorOn: boolean = true;
  @State fabScale: number = 1;
  // 1:1 原型会话行:预览行 + 消息条数(页面层装配,不动 RDB schema)
  @State previews: Record<string, string> = {};
  @State msgCounts: Record<string, number> = {};
  private pendingDeleted: Conversation | null = null;
  private snackTimer: number = -1;
  private cursorTimer: number = -1;

  onPageShow(): void {
    this.themeTick = Date.now();
  }

  aboutToAppear(): void {
    if (this.cursorTimer !== -1) clearInterval(this.cursorTimer);
    this.cursorTimer = setInterval((): void => { this.cursorOn = !this.cursorOn; }, 525);
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

  aboutToDisappear(): void {
    if (this.cursorTimer !== -1) {
      clearInterval(this.cursorTimer);
      this.cursorTimer = -1;
    }
    if (this.pendingDeleted !== null) this.commitPendingDelete();
  }

  private refresh(): void {
    const repo = getChatRepository();
    const query: string = this.searchText.trim();
    const future: Promise<ConversationSummary[]> =
      query.length > 0 ? repo.search(query) : repo.list();
    future.then((list: ConversationSummary[]): void => {
      const pendingId: string = this.pendingDeleted !== null ? this.pendingDeleted.id : '';
      const visible: ConversationSummary[] = list.filter(
        (item: ConversationSummary): boolean => item.id !== pendingId);
      this.items = visible;
      this.loaded = true;
      // 1:1 原型会话行:预览行(末条选中消息首行,去 **/`)+ mono 元行「N 条消息」
      for (const s of visible) {
        repo.getById(s.id).then((conv: Conversation | null): void => {
          if (conv === null) return;
          let count: number = 0;
          let lastText: string = '';
          for (const node of conv.messageNodes) {
            if (node.messages.length === 0) continue;
            const idx: number =
              node.selectIndex >= 0 && node.selectIndex < node.messages.length ? node.selectIndex : 0;
            const m: UIMessage = node.messages[idx];
            count++;
            for (const p of m.parts) {
              if (p.type === 'text') lastText = p.text;
            }
          }
          const first: string = lastText.split('\n')[0]
            .replace(/\*\*([^*]+)\*\*/g, '$1')
            .replace(/`([^`]+)`/g, '$1');
          const prev: Record<string, string> = {};
          for (const k of Object.keys(this.previews)) prev[k] = this.previews[k];
          prev[s.id] = first;
          this.previews = prev;
          const counts: Record<string, number> = {};
          for (const k of Object.keys(this.msgCounts)) counts[k] = this.msgCounts[k];
          counts[s.id] = count;
          this.msgCounts = counts;
        });
      }
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
      this.commitPendingDelete();
    }
    const repo = getChatRepository();
    repo.getById(item.id).then((full: Conversation | null): void => {
      if (full === null) return;
      this.pendingDeleted = full;
      this.snackVisible = true;
      this.refresh();
      this.snackTimer = setTimeout((): void => {
        this.commitPendingDelete();
      }, SNACK_DURATION_MS);
    }).catch((e: Error): void => {
      promptAction.showToast({ message: `删除失败：${e.message}`, duration: 1800 });
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
    const full: Conversation | null = this.pendingDeleted;
    this.pendingDeleted = null;
    this.clearSnackTimer();
    this.snackVisible = false;
    if (full === null) return;
    const repo = getChatRepository();
    repo.delete(full.id)
      .then((): Promise<void> =>
        cleanupDeletedConversation(full, repo, getChatKvStore()))
      .then((): void => { this.refresh(); })
      .catch((e: Error): void => {
        promptAction.showToast({ message: `删除清理失败：${e.message}`, duration: 2000 });
        this.refresh();
      });
  }

  private dismissSnack(): void {
    this.commitPendingDelete();
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
              cleanupDeletedConversation(full, repo, getChatKvStore()));
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

  // 会话行(1:1 参考首页): 标题+置顶图标 / 预览行 / mono 元信息行; 滑动/长按 置顶/删除
  private previewOf(id: string): string {
    const p: string | undefined = this.previews[id];
    return p === undefined ? '' : p;
  }

  private countLabel(id: string): string {
    const n: number | undefined = this.msgCounts[id];
    return `${n === undefined ? 0 : n} 条消息`;
  }

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
          DotGlyph({ rows: DFEATURE['pin'], dotScale: 2.4, color: ACCENT })
        }
      }
      .width('100%')

      // 1:1 原型预览行：末条消息首行(13/ink-3, marginTop 4)
      if (this.previewOf(item.id).length > 0) {
        Text(this.previewOf(item.id))
          .fontSize(13)
          .fontColor(INK3)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .width('100%')
          .margin({ top: 4 })
      }

      // mono 元信息行: 左=N 条消息, 右=日期(marginTop 6)
      Row() {
        Text(this.countLabel(item.id))
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
    .border({ width: { bottom: 1 }, color: { bottom: LINE }, style: { bottom: BorderStyle.Dotted } })
    .onClick((): void => { this.openConversation(item.id); })
    .gesture(
      LongPressGesture()
        .onAction((): void => { this.showRowMenu(item); })
    )
  }

  @Builder
  SwipePin(item: ConversationSummary): void {
    Column() {
      DotGlyph({ rows: DFEATURE['pin'], dotScale: 2.5, color: ACCENT })
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

  // 1:1 原型眉标:「今天」陶土 600,日期部分 ink-4,等宽 letter-spacing 0.08em
  private mmdd(): string {
    const d: Date = new Date();
    const p = (n: number): string => n < 10 ? `0${n}` : `${n}`;
    return `${p(d.getMonth() + 1)}.${p(d.getDate())}`;
  }

  private wd(): string {
    return ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][new Date().getDay()];
  }

  // 功能栏入口 → 对应页面
  private onFeature(id: string): void {
    if (id === 'reader') router.pushUrl({ url: 'pages/BoardPage' });
    else if (id === 'novel') router.pushUrl({ url: 'pages/NovelProjectsPage' });
    else if (id === 'council') router.pushUrl({ url: 'pages/CouncilPage' });
    else promptAction.showToast({ message: '该功能即将上线', duration: 1500 });
  }

  @Builder
  FeatureItem(id: string, label: string, icon: string): void {
    Column() {
      DotGlyph({ rows: DFEATURE[id], dotScale: 3.4, dotGap: 1.7, color: ACCENT, glow: true })
      Text(label)
        .fontSize(12)
        .fontColor(INK2)
        .maxLines(1)
        .margin({ top: 10 })
    }
    .padding({ top: 12, bottom: 13 })
    .justifyContent(FlexAlign.Center)
    .onClick((): void => { this.onFeature(id); })
  }

  // 1:1 原型底部 mask 渐隐(52/56px,transparent→BG,不拦截触摸)
  @Builder
  BottomFade(h: number): void {
    Column()
      .width('100%')
      .height(h)
      .linearGradient({
        direction: GradientDirection.Bottom,
        colors: [[BG_FADE_START, 0.0], [BG_FADE_END, 1.0]],
      })
      .hitTestBehavior(HitTestMode.None)
  }

  // 竖向点状分隔:1px 圆点列(border dotted 光栅化呈实线,故逐点渲染)
  //   固定宽度,位置由 5 等宽槽决定 → 钉在 20/40/60/80% 均分边界
  @Builder
  RailSep(): void {
    Column() {
      ForEach([0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11], (i: number): void => {
        Column().width(1).height(1).borderRadius(0.5).backgroundColor(LINE)
      }, (i: number): string => `d${i}`)
    }
    .height('100%')
    .padding({ top: 13, bottom: 13 })
    .justifyContent(FlexAlign.SpaceBetween)
    .alignItems(HorizontalAlign.Center)
  }

  build(): void {
    Stack({ alignContent: Alignment.Bottom }) {
      DotGround()
      Column() {
        // 头部(ascii 1:1): 点阵 AMBER+闪烁光标 | 日期眉标(今天·点阵MM.DD·周X)
        //   词标行 alignItems flex-end,日期行 lineHeight 1 → 底边齐平
        Row() {
          Row() {
            DotText({ text: 'AMBER', dotScale: 2.3, color: INK, glow: true })
            Text('')
              .width(11).height(21).backgroundColor(ACCENT).borderRadius(1)
              .margin({ left: 4 })
              .opacity(this.cursorOn ? 1 : 0)
          }
          .alignItems(VerticalAlign.Center)
          Row() {
            Text('今天')
              .fontSize(11.5).lineHeight(11.5)
              .fontFamily('JetBrainsMono')
              .fontWeight(600)
              .fontColor(ACCENT)
              .letterSpacing(0.9)
            Text('·').fontSize(11.5).lineHeight(11.5).fontFamily('JetBrainsMono').fontColor(INK4)
              .margin({ left: 5, right: 5 })
            DotText({ text: this.mmdd(), dotScale: 1, dotGap: 0.7, color: INK3 })
            Text('·').fontSize(11.5).lineHeight(11.5).fontFamily('JetBrainsMono').fontColor(INK4)
              .margin({ left: 5, right: 5 })
            Text(this.wd())
              .fontSize(11.5).lineHeight(11.5)
              .fontFamily('JetBrainsMono')
              .fontColor(INK4)
              .letterSpacing(0.9)
          }
          .alignItems(VerticalAlign.Center)
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
        .alignItems(VerticalAlign.Bottom)
        .padding({ left: 20, right: 20, top: this.statusBarHeight + 8, bottom: 12 })

        // 搜索栏(flat, 底部 hairline)
        Row() {
          Icon({ name: 'search', iconSize: 17, color: INK3 })
          TextInput({ text: this.searchText, placeholder: '搜索会话' })
            .fontSize(14.5)
            .fontColor(INK)
            .placeholderColor(INK4)
            .backgroundColor(Color.Transparent)
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
        .border({ width: { bottom: 1 }, color: { bottom: LINE }, style: { bottom: BorderStyle.Dotted } })

        // 功能栏:5 等宽槽(首槽左锚/末槽右锚/中间居中)→ 竖线钉死 20/40/60/80% 均分边界
        Row() {
          Row() { this.FeatureItem('reader', '深度阅读', 'book') }
            .layoutWeight(1).justifyContent(FlexAlign.Start)
          this.RailSep()
          Row() { this.FeatureItem('apps', '小应用', 'box') }
            .layoutWeight(1).justifyContent(FlexAlign.Center)
          this.RailSep()
          Row() { this.FeatureItem('novel', '小说创作', 'edit') }
            .layoutWeight(1).justifyContent(FlexAlign.Center)
          this.RailSep()
          Row() { this.FeatureItem('webmount', 'WebMount', 'globe') }
            .layoutWeight(1).justifyContent(FlexAlign.Center)
          this.RailSep()
          Row() { this.FeatureItem('council', '模型议会', 'brain') }
            .layoutWeight(1).justifyContent(FlexAlign.End)
        }
        .width('100%')
        .height(76)
        .alignItems(VerticalAlign.Center)
        .padding({ left: 20, right: 20 })
        .border({ width: { bottom: 1 }, color: { bottom: LINE }, style: { bottom: BorderStyle.Dotted } })

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
              }, (item: ConversationSummary): string => item.id + (this.darkMode ? "-d" : "-l"))
            }
          }
          .width('100%')
          .height('100%')
          // 1:1 原型:滚动内容直抵底缘 + 52px 渐隐 + FAB 防遮挡 paddingBottom 100
          .padding({ bottom: 100 })
          .overlay(this.BottomFade(52), { align: Alignment.Bottom })
          .divider({ strokeWidth: 0, color: Color.Transparent })

          // FAB 新会话(右下悬浮, accent 圆)
          Column() {
            DotGlyph({ rows: DFEATURE['plus'], dotScale: 3.4, dotGap: 1.8, color: ACCENT_INK, glow: true })
          }
          .width(56).height(56).borderRadius(999)
          .backgroundColor(ACCENT)
          .justifyContent(FlexAlign.Center)
          .alignItems(HorizontalAlign.Center)
          // 1:1 原型 .fab:active scale(.96) .18s ease-out
          .scale({ x: this.fabScale, y: this.fabScale })
          .onTouch((e: TouchEvent): void => {
            const down: boolean = e.type === TouchType.Down;
            animateTo({ duration: 180, curve: Curve.EaseOut }, (): void => {
              this.fabScale = down ? 0.96 : 1;
            });
          })
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
    // 读 themeTick 建依赖:主题变更 onPageShow 后 build 重读令牌
    .opacity(this.themeTick >= 0 ? 1 : 1)
  }
}
