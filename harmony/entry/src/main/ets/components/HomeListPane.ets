import {
  HomeConversationCollection, HOME_LIST_VERSION_KEY
} from '../platform_impl/home/HomeConversationCollection.ets';
import { HomeContinueSession } from '../platform_impl/home/HomeContinueSupport.ets';
import { HomeConversationExport } from '../platform_impl/home/HomeConversationExport.ets';
import { accentAlpha } from '../platform_impl/home/HomePresentation.ets';
import { HomeContinueCard } from './home/HomeContinueCard.ets';
import { HomeConversationRow } from './home/HomeConversationRow.ets';
import { router, promptAction, curves } from '@kit.ArkUI';
import type { Conversation, ConversationSummary, DisplaySetting, ContinueCandidate } from '@amber/chat-domain';
import { loadDisplaySetting, patchConversation } from '@amber/chat-domain';
import {
  getChatKvStore, getChatRepository, getNovelCreation, getAgentCronManager, getRepository, getCouncilManager,
  getConversationRunService, getDeepReadScheduler
} from '../di/AppContainer.ets';
import { cleanupDeletedConversation, getFilesRepository } from '../platform_impl/ManagedFileStore.ets';
import { getMiniAppRepositorySync } from '../platform_impl/MiniAppPlatform.ets';
import { Icon } from './ui/Icon.ets';
import { AmberWordmark } from './ui/AmberWordmark.ets';
import { PressGlowButton } from './ui/PressGlowButton.ets';
import { SubAgentActivityStrip } from './SubAgentActivityStrip.ets';
import {
  ACCENT, ACCENT_INK, ERROR, HOME_CARD, HOME_CARD2, HOME_GLASS_BOTTOM, HOME_GLASS_EDGE, HOME_GLASS_SHADOW,
  HOME_GLASS_TOP, HOME_INK, HOME_INK2, HOME_MUTED, HOME_MUTED2, HOME_SEP, HOME_SHADOW, MOTION_ENTER,
  MOTION_STAGGER, OVERLAY
} from '../design/tokens.ets';
import { canvasGradientColors, currentCanvasGradientAngle } from '../design/theme_packs.ets';
import { DesignCanvasLayer } from '../design/DesignCanvasLayer.ets';
import { FONT_SANS, FONT_SANS_MEDIUM, FONT_SANS_SEMIBOLD } from '../design/fonts.ets';

// HomeListPane — 首页主体(原 ChatListPage),iOS ConversationsView 1:1 复刻(点阵·陶土主题)
//
// 对齐 iOS PlaceholderViews.swift ConversationsView:
//   头部: 点阵 AMBER + 搜索胶囊(点击展开全宽玻璃条) + 齿轮(accent) + 头像
//   控制卡: 继续(单候选) + 五快捷入口, 白卡 22 圆角 + 暖棕投影
//   会话: 一体卡(顶/中/底切片), 圆形图标座(语义映射 + 稳定哈希), meta = 相对时间 · N 条
//   右下: 新对话胶囊(accent, 铅笔 + 文案)
// 数据逻辑保留: repo.list/删除/撤销/置顶/导出; 继续候选复用 Index 聚合管线取第 1 条
//   (iOS HomeContinueCardModel.resolve 同样只取最优一卡)。
//
// 宿主:单栏时 ChatListPage 整页;两栏时(design/adaptive.ets)ChatListPage 与 ChatPage
//   都把它放在左侧窄栏。组件拿不到页面生命周期,宿主经 hostVisible 转发 onPageShow/onPageHide。

interface ActionMenuResult {
  index: number;
}

const SNACK_BG: string = '#32302B';
// 最后打开会话(KV):复刻 iOS currentConversation 激活行染色
const LAST_OPEN_KEY: string = 'home_last_open_conversation';
// iOS homeCardRadius 默认 22
const HOME_RADIUS: number = 22;

// 两栏里切换会话会连同左栏一起新建 ChatPage:用上一份列表、条数与滚动位置播种,
//   新左栏不闪"加载中"、不重播入场、不跳回顶部(模块级,进程内有效)。
let cachedScrollY: number = 0;
let cachedNickname: string = '';
let cachedAvatar: string = '';
let cachedSearchQuery: string = '';
@Component
export struct HomeListPane {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @StorageProp('darkMode') darkMode: boolean = false;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @StorageProp('agentSoulDisplayName') agentSoulDisplayName: string = 'Amber';
  @StorageProp('agentSoulIsDefault') agentSoulIsDefault: boolean = true;
  // 主题包(C2):包切换 → 本页重建重读 HOME_* 令牌;画布纹理由 DesignCanvasLayer 自绘
  @StorageProp('themePackId') themePackId: string = '';
  // 宿主页可见性:宿主在 onPageShow/onPageHide 里翻转(组件收不到页面生命周期)
  @Prop @Watch('onHostVisibleChange') hostVisible: boolean = false;
  // 两栏左栏:新对话收进顶栏,不画右下胶囊(底部只留右侧输入栏,不重复)
  @Prop twoPane: boolean = false;
  // 嵌在 ChatPage 左栏:打开会话 = 原地替换右侧聊天页,而不是再压一页
  @Prop inChat: boolean = false;
  // 两栏时高亮右侧正在打开的会话(空 = 不高亮);单栏沿用"最后打开"
  @Prop @Watch('onActiveIdChange') activeId: string = '';
  @StorageProp(HOME_LIST_VERSION_KEY) @Watch('onListVersionChange') listVersion: number = 0;
  @State pressedFeature: string = '';
  @State userNickname: string = '';
  @State userAvatar: string = '';
  @State lastOpenedId: string = '';
  // 列表级重命名(iOS/Android 均在列表出重命名)
  @State renameOpen: boolean = false;
  @State renameId: string = '';
  @State renameInput: string = '';
  // 展开搜索(iOS homeSearch morph:胶囊 → 全宽玻璃条,实时过滤标题)
  @State searchExpanded: boolean = false;
  @State searchQuery: string = '';
  @State searchFocused: boolean = false;
  // 入场级联(iOS homeCascade:opacity + y8,60/100/140/180/220ms,0.48s cubic(0.2,.8,.2,1))
  @State cascadeHeader: boolean = false;
  @State cascadeCard: boolean = false;
  @State cascadeSection: boolean = false;
  @State cascadeRows: boolean = false;
  @State cascadeFab: boolean = false;
  @State collection: HomeConversationCollection = new HomeConversationCollection({
    getRepository: getChatRepository, getRunService: getConversationRunService, getKv: getChatKvStore,
    cleanupDeletedConversation,
  });
  @State continuation: HomeContinueSession = new HomeContinueSession({
    getNovelCreation, getAgentCronManager, getDeepReadScheduler, getRepository,
    getMiniAppRepositorySync, getCouncilManager, getFilesRepository,
  });
  private pageAlive: boolean = false;
  private firstShowDone: boolean = false;
  private cascadeDone: boolean = false;
  private navCooldownUntil: number = 0;
  private scroller: Scroller = new Scroller();

  private onHostVisibleChange(): void {
    if (!this.hostVisible) {
      this.collection.stopRunningSubscription();
      return;
    }
    this.collection.observeRunning();
    if (this.firstShowDone) {
      this.collection.refresh();
      this.continuation.refresh();
      this.loadLastOpened();
      // 两栏返回首页:左栏换回本实例,对齐聊天页左栏最后的滚动位置
      if (this.twoPane) this.restoreScroll();
    } else {
      this.firstShowDone = true;
    }
    this.loadUserProfile();
  }

  // 右侧打开了列表里还没有的会话(新会话首次落库):补一次刷新;已有的只换高亮
  private onActiveIdChange(): void {
    if (!this.pageAlive || this.activeId.length === 0) return;
    if (!this.collection.items.some((item: ConversationSummary): boolean => item.id === this.activeId)) this.collection.refresh();
  }

  private onListVersionChange(): void {
    if (this.pageAlive) this.collection.refresh();
  }

  aboutToAppear(): void {
    this.pageAlive = true;
    // A pane created while already visible will not receive an initial Watch callback.
    if (this.hostVisible) this.firstShowDone = true;
    const seeded: boolean = this.collection.activate(this.inChat, (id: string): void => {
      if (this.inChat && id === this.activeId) this.navigateOnce({ url: 'pages/ChatPage' }, true);
    });
    const continueReady: boolean = this.continuation.activate(seeded);
    if (seeded) {
      this.userNickname = cachedNickname;
      this.userAvatar = cachedAvatar;
      if (cachedSearchQuery.length > 0) {
        this.searchExpanded = true;
        this.searchQuery = cachedSearchQuery;
      }
    }
    this.collection.observeRunning();
    this.collection.refresh();
    if (!continueReady) this.continuation.refresh();
    this.loadLastOpened();
    this.loadUserProfile();
    this.playCascade();
  }

  private loadUserProfile(): void {
    loadDisplaySetting(getChatKvStore()).then((setting: DisplaySetting): void => {
      if (!this.pageAlive) return;
      this.userNickname = setting.userNickname;
      this.userAvatar = setting.userAvatar;
      cachedNickname = setting.userNickname;
      cachedAvatar = setting.userAvatar;
    }).catch((): void => {});
  }

  // 等 List 首次布局完成再滚,否则偏移可能被夹到 0
  private restoreScroll(): void {
    setTimeout((): void => {
      if (this.pageAlive) this.scroller.scrollTo({ xOffset: 0, yOffset: cachedScrollY });
    }, 0);
  }

  // 级联只播一次:组件实例存活期间返回本页不重播(iOS cascadeComplete 同语义);
  //   聊天页左栏是换会话时的重建,不算入场
  private playCascade(): void {
    if (this.cascadeDone || this.inChat) {
      this.cascadeDone = true;
      this.cascadeHeader = true;
      this.cascadeCard = true;
      this.cascadeSection = true;
      this.cascadeRows = true;
      this.cascadeFab = true;
      return;
    }
    this.cascadeDone = true;
    const curve: ICurve = curves.cubicBezierCurve(0.2, 0.8, 0.2, 1);
    const run = (delay: number, apply: () => void): void => {
      setTimeout((): void => {
        if (!this.pageAlive) return;
        animateTo({ duration: 480, curve: curve }, apply);
      }, delay);
    };
    run(60, (): void => { this.cascadeHeader = true; });
    run(100, (): void => { this.cascadeCard = true; });
    run(140, (): void => { this.cascadeSection = true; });
    run(180, (): void => { this.cascadeRows = true; });
    run(220, (): void => { this.cascadeFab = true; });
  }

  aboutToDisappear(): void {
    this.pageAlive = false;
    this.collection.dispose();
    this.continuation.dispose();
  }

  private openContinue(item: ContinueCandidate): void {
    if (item.route.kind === 'novel') {
      this.pushOnce({
        url: 'pages/NovelWorkspacePage',
        params: { projectId: item.route.projectId },
      });
    } else if (item.route.kind === 'cron') {
      this.pushOnce({ url: 'pages/SettingCronTasksPage' });
    } else if (item.route.kind === 'miniapp') {
      this.pushOnce({
        url: 'pages/MiniAppRunnerPage',
        params: { appId: item.route.appId },
      });
    } else if (item.route.kind === 'deepread') {
      this.pushOnce({
        url: 'pages/DeepReadArticlePage',
        params: { topicId: item.route.topicId, title: item.route.title },
      });
    } else if (item.route.kind === 'council') {
      const runId: string = item.route.runId ?? '';
      if (runId.length > 0) {
        this.pushOnce({ url: 'pages/CouncilRunPage', params: { runId: runId } });
      }
    } else if (item.route.kind === 'chat') {
      this.openConversation(item.route.conversationId);
    }
  }

  private loadLastOpened(): void {
    getChatKvStore().get(LAST_OPEN_KEY).then((value: string | null): void => {
      if (!this.pageAlive) return;
      this.lastOpenedId = value === null ? '' : value;
    }).catch((): void => {});
  }

  private openConversation(id: string): void {
    this.lastOpenedId = id;
    getChatKvStore().put(LAST_OPEN_KEY, id).catch((): void => {});
    const params: Record<string, string> = {};
    params['conversationId'] = id;
    if (this.inChat) {
      if (id !== this.activeId) this.navigateOnce({ url: 'pages/ChatPage', params: params }, true);
      return;
    }
    this.pushOnce({ url: 'pages/ChatPage', params: params });
  }

  private expandSearch(): void {
    animateTo({ duration: 320, curve: curves.cubicBezierCurve(0.2, 0.8, 0.2, 1) }, (): void => {
      this.searchExpanded = true;
    });
    // iOS 展开后 170ms 聚焦;离场/收起前取消由状态守卫兜底
    setTimeout((): void => {
      if (!this.pageAlive || !this.searchExpanded) return;
      this.getUIContext().getFocusController().requestFocus('homeSearch');
    }, 170);
  }

  private collapseSearch(): void {
    cachedSearchQuery = '';
    if (!this.searchExpanded) return;
    animateTo({ duration: 320, curve: curves.cubicBezierCurve(0.2, 0.8, 0.2, 1) }, (): void => {
      this.searchExpanded = false;
      this.searchQuery = '';
    });
  }

  private submitSearch(): void {
    const query: string = this.searchQuery.trim();
    this.collapseSearch();
    const params: Record<string, string> = {};
    if (query.length > 0) params['query'] = query;
    this.pushOnce({ url: 'pages/SearchPage', params: params });
  }

  private newConversation(): void {
    this.collapseSearch();
    // 右侧已是还没落库的空白新对话:不必重建整页
    if (this.inChat && this.collection.loaded
      && !this.collection.items.some((item: ConversationSummary): boolean => item.id === this.activeId)) return;
    this.navigateOnce({ url: 'pages/ChatPage' }, this.inChat);
  }

  private pushOnce(options: router.RouterOptions): void {
    this.navigateOnce(options, false);
  }

  // 防连点叠页:300ms 冷却
  private navigateOnce(options: router.RouterOptions, replace: boolean): void {
    const now: number = Date.now();
    if (now < this.navCooldownUntil) return;
    this.navCooldownUntil = now + 300;
    const pending: Promise<void> = replace ? router.replaceUrl(options) : router.pushUrl(options);
    pending.catch((): void => { this.navCooldownUntil = 0; });
  }

  private cachedFilterInput: ConversationSummary[] = [];
  private cachedFilterQuery: string = '';
  private cachedFilterResult: ConversationSummary[] = [];

  private previewFilter(list: ConversationSummary[]): ConversationSummary[] {
    const query: string = this.searchQuery.trim().toLowerCase();
    if (query.length === 0) return list;
    if (this.cachedFilterInput === list && this.cachedFilterQuery === query) {
      return this.cachedFilterResult;
    }
    const filtered: ConversationSummary[] = list.filter((item: ConversationSummary): boolean => {
      const title: string = item.title.length > 0 ? item.title : '新对话';
      return title.toLowerCase().includes(query);
    });
    this.cachedFilterInput = list;
    this.cachedFilterQuery = query;
    this.cachedFilterResult = filtered;
    return filtered;
  }

  private isActive(id: string): boolean {
    const activeId: string = this.twoPane ? this.activeId : this.lastOpenedId;
    return activeId.length > 0 && activeId === id;
  }

  // 行间 hairline:末行不画;当前行与下一行若为激活行也让位(iOS:避免与 accent 色带打架)
  private hideRowLine(idx: number): boolean {
    const list: ConversationSummary[] = this.previewFilter(this.collection.items);
    if (idx >= list.length - 1) return true;
    if (this.isActive(list[idx].id)) return true;
    return this.isActive(list[idx + 1].id);
  }

  private onFeature(id: string): void {
    if (id === 'reader') this.pushOnce({ url: 'pages/BoardPage' });
    else if (id === 'novel') this.pushOnce({ url: 'pages/NovelProjectsPage' });
    else if (id === 'council') this.pushOnce({ url: 'pages/CouncilPage' });
    else if (id === 'apps') this.pushOnce({ url: 'pages/MiniAppListPage' });
    else if (id === 'webmount') this.pushOnce({ url: 'pages/SettingExperimentalWebMountPage' });
    else promptAction.showToast({ message: '该功能即将上线', duration: 1500 });
  }

  private showRowMenu(item: ConversationSummary): void {
    promptAction.showActionMenu({
      buttons: [
        { text: item.isPinned ? '取消置顶' : '置顶', color: HOME_INK },
        { text: '重命名', color: HOME_INK },
        { text: '复制 Markdown', color: HOME_INK },
        { text: '导出 Markdown 文件', color: HOME_INK },
        { text: '删除', color: ERROR },
      ],
    }).then((result: ActionMenuResult): void => {
      if (result.index === 0) this.collection.togglePin(item);
      else if (result.index === 1) this.openRename(item);
      else if (result.index === 2) new HomeConversationExport(getChatRepository()).copy(item);
      else if (result.index === 3) new HomeConversationExport(getChatRepository()).saveFile(item);
      else if (result.index === 4) this.collection.onDelete(item);
    }).catch((): void => {});
  }

  private openRename(item: ConversationSummary): void {
    this.renameId = item.id;
    this.renameInput = item.title;
    this.renameOpen = true;
  }

  private submitRename(): void {
    const title: string = this.renameInput.trim();
    const id: string = this.renameId;
    this.renameOpen = false;
    if (title.length === 0 || id.length === 0) return;
    getConversationRunService().mutateStoredConversation(id, (latest: Conversation): Conversation =>
      patchConversation(latest, { title })).then((updated: Conversation | null): void => {
      if (updated !== null && this.pageAlive) this.collection.refresh();
    }).catch((): void => {});
  }

  @Builder
  SearchCapsuleContent(): void {
    Row() {
      Icon({ name: 'ph_magnifyingGlass', iconSize: 14, color: HOME_MUTED })
      // 两栏收成圆钮,只留放大镜
      if (!this.twoPane) {
        Text('搜索')
          .fontSize(13)
          .fontWeight(FontWeight.Medium)
          .letterSpacing(0.26)
          .fontFamily(FONT_SANS_SEMIBOLD)
          .fontColor(HOME_MUTED)
          .margin({ left: 6 })
      }
    }
  }

  @Builder
  GearContent(): void {
    // 与搜索胶囊放大镜同色(HOME_MUTED):顶栏三钮同族,accent 留给选中/主动作
    Icon({ name: 'ph_gear', iconSize: 20, color: HOME_MUTED })
  }

  @Builder
  AvatarContent(): void {
    if (this.userAvatar.length > 0) {
      Image(this.userAvatar.startsWith('http') || this.userAvatar.startsWith('data:')
        || this.userAvatar.startsWith('file://') || this.userAvatar.startsWith('resource:')
        ? this.userAvatar : `file://${this.userAvatar}`)
        .width(38)
        .height(38)
        .borderRadius(19)
        .objectFit(ImageFit.Cover)
    } else {
      Stack() {
        Text((this.userNickname.length > 0 ? this.userNickname : 'A').charAt(0).toUpperCase())
          .fontSize(14.5)
          .fontWeight(FontWeight.Medium)
          .fontFamily(FONT_SANS_SEMIBOLD)
          .fontColor(HOME_MUTED2)
      }
      .width(38)
      .height(38)
      .borderRadius(19)
      .backgroundColor(HOME_CARD2)
    }
  }

  @Builder
  NewChatIconContent(): void {
    Icon({ name: 'ph_pencil', iconSize: 18, color: ACCENT_INK })
  }

  @Builder
  FabContent(): void {
    Row() {
      Icon({ name: 'ph_pencil', iconSize: 14, color: ACCENT_INK })
      Text('新对话')
        .fontSize(14)
        .fontWeight(FontWeight.Medium)
        .letterSpacing(0.2)
        .fontFamily(FONT_SANS_SEMIBOLD)
        .fontColor(ACCENT_INK)
        .margin({ left: 6 })
    }
    .padding({ left: 14, right: 14 })
  }

  @Builder
  HeaderBlock(): void {
    Column() {
      Row() {
        // 顶栏左侧品牌/角色称呼(点击打开「形象人设」;自定义人设实时展示角色称呼)
        Row({ space: 6 }) {
          if (this.agentSoulIsDefault || this.agentSoulDisplayName.trim().length === 0
            || this.agentSoulDisplayName === 'Amber') {
            AmberWordmark({ color: HOME_INK, cell: this.twoPane ? 3.5 : 4 })
          } else {
            Text(this.agentSoulDisplayName)
              .fontSize(this.twoPane ? 18 : 20)
              .fontWeight(FontWeight.Bold)
              .fontFamily(FONT_SANS_SEMIBOLD)
              .fontColor(HOME_INK)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .constraintSize({ maxWidth: this.twoPane ? 132 : 176 })
            Column()
              .width(6).height(6).borderRadius(3)
              .backgroundColor(this.accentColorStore)
          }
        }
        .constraintSize({ minHeight: 44 })
        .alignItems(VerticalAlign.Center)
        .accessibilityText('形象人设')
        .onClick((): void => {
          this.collapseSearch();
          router.pushUrl({ url: 'pages/AgentSoulPage' });
        })
        Blank()
        if (!this.searchExpanded) {
          PressGlowButton({
            accentColor: this.accentColorStore,
            radius: 19,
            btnWidth: this.twoPane ? 38 : 78,
            btnHeight: 38,
            onTap: (): void => { this.expandSearch(); },
            content: (): void => { this.SearchCapsuleContent(); },
          })
            .margin({ right: this.twoPane ? 6 : 8 })
            .accessibilityText('搜索')
        }

        // 齿轮(glyph 与搜索放大镜同为 HOME_MUTED)
        PressGlowButton({
          accentColor: this.accentColorStore,
          radius: 19,
          btnWidth: 38,
          btnHeight: 38,
          onTap: (): void => {
            this.collapseSearch();
            router.pushUrl({ url: 'pages/SettingPage' });
          },
          content: (): void => { this.GearContent(); },
        })
          .accessibilityText('设置')

        // 账户头像 38(iOS: 图或昵称首字,avatarIdle 底)
        PressGlowButton({
          accentColor: this.accentColorStore,
          radius: 19,
          bgMode: 'plain',
          btnWidth: 38,
          btnHeight: 38,
          onTap: (): void => {
            this.collapseSearch();
            router.pushUrl({ url: 'pages/ProfilePage' });
          },
          content: (): void => { this.AvatarContent(); },
        })
          // 与搜索→齿轮间距一致(单栏 8 / 两栏 6)
          .margin({ left: this.twoPane ? 6 : 8 })
          .accessibilityText('我的账户')

        // 两栏:新对话从右下胶囊收进顶栏(对齐 Android SessionHome onNewSession)
        if (this.twoPane) {
          PressGlowButton({
            accentColor: this.accentColorStore,
            radius: 19,
            bgMode: 'solid',
            btnWidth: 38,
            btnHeight: 38,
            onTap: (): void => { this.newConversation(); },
            content: (): void => { this.NewChatIconContent(); },
          })
            .margin({ left: 6 })
            .accessibilityText('新建聊天')
        }
      }
      .width('100%')
      .constraintSize({ minHeight: 38 })
      .alignItems(VerticalAlign.Center)
      .padding({ left: 16, right: 16 })

      // 展开的全宽搜索条(高 41,圆角 14,聚焦环 accent)
      // 横向内缩走外层 Column padding:width('100%') + margin 在本布局下会溢出右缘
      if (this.searchExpanded) {
        Column() {
        Row() {
          Icon({ name: 'ph_magnifyingGlass', iconSize: 14, color: HOME_MUTED2 })
          TextInput({ text: this.searchQuery, placeholder: '搜索会话' })
            .id('homeSearch')
            .layoutWeight(1)
            .fontSize(17)
            .fontFamily(FONT_SANS)
            .fontColor(HOME_INK)
            .placeholderColor(HOME_MUTED2)
            .backgroundColor(Color.Transparent)
            .padding({ left: 14 })
            .onChange((value: string): void => {
              this.searchQuery = value;
              cachedSearchQuery = value;
            })
            .onFocus((): void => { this.searchFocused = true; })
            .onBlur((): void => { this.searchFocused = false; })
            .onSubmit((): void => { this.submitSearch(); })
          Text('取消')
            .fontSize(13)
            .fontWeight(FontWeight.Medium)
            .letterSpacing(0.26)
            .fontFamily(FONT_SANS_SEMIBOLD)
            .fontColor(HOME_MUTED)
            .padding({ left: 12, right: 2, top: 6, bottom: 6 })
            .onClick((): void => { this.collapseSearch(); })
        }
        .width('100%')
        .height(41)
        .alignItems(VerticalAlign.Center)
        .padding({ left: 16, right: 16 })
        .borderRadius(14)
        .linearGradient({
          direction: GradientDirection.Bottom,
          colors: [[HOME_GLASS_TOP, 0], [HOME_GLASS_BOTTOM, 1]],
        })
        .border({
          width: this.searchFocused ? 1.5 : 0.5,
          color: this.searchFocused ? accentAlpha(this.accentColorStore, 0.55) : HOME_GLASS_EDGE,
        })
        .shadow({ radius: 10, color: HOME_GLASS_SHADOW, offsetY: 4 })
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 15 })
      .transition(TransitionEffect.OPACITY
        .combine(TransitionEffect.translate({ y: -8 }))
        .animation({ duration: 320, curve: curves.cubicBezierCurve(0.2, 0.8, 0.2, 1) }))
      }
    }
    .width('100%')
    .padding({ top: 10, bottom: 4 })
    .opacity(this.cascadeHeader ? 1 : 0)
    .translate({ y: this.cascadeHeader ? 0 : 8 })
  }

  @Builder
  ShortcutItem(id: string, label: string, icon: string): void {
    Column() {
      Icon({
        name: icon,
        iconSize: 20,
        color: this.pressedFeature === id ? HOME_INK : HOME_MUTED,
      })
      Text(label)
        .fontSize(11)
        .fontWeight(FontWeight.Medium)
        .lineHeight(14)
        .letterSpacing(0.11)
        .fontFamily(FONT_SANS_SEMIBOLD)
        .fontColor(this.pressedFeature === id ? HOME_INK : HOME_MUTED)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .margin({ top: 6 })
    }
    .width('100%')
    .height('100%')
    .justifyContent(FlexAlign.Center)
    .alignItems(HorizontalAlign.Center)
    .scale({ x: this.pressedFeature === id ? 1.06 : 1, y: this.pressedFeature === id ? 1.06 : 1 })
    .animation({ duration: 300, curve: curves.springCurve(0, 1, 200, 22) })
    .onTouch((event: TouchEvent): void => {
      if (event.type === TouchType.Down) this.pressedFeature = id;
      else if (event.type === TouchType.Up || event.type === TouchType.Cancel) this.pressedFeature = '';
    })
    .onClick((): void => { this.onFeature(id); })
  }

  @Builder
  ControlCard(): void {
    // 关键:卡片自身禁 margin — ArkUI 的 margin 只缩视觉盒,子级 100% 仍按
    // margin 前宽度解析(继续 CTA 溢出右缘根因);内缩一律走外层 Column padding
    Column() {
      Column() {
        if (this.continuation.candidate !== null) {
          HomeContinueCard({ candidate: this.continuation.candidate, darkMode: this.darkMode,
            accentColorStore: this.accentColorStore,
            onOpen: (item: ContinueCandidate): void => { this.openContinue(item); } })
          Column() {
            Row()
              .width('100%')
              .height(0.5)
              .backgroundColor(HOME_SEP)
          }
          .width('100%')
          .padding({ left: 16, right: 16 })
        }
        Row() {
          Column() { this.ShortcutItem('reader', '深度阅读', 'ph_bookOpen') }.height('100%').layoutWeight(1)
          Column() { this.ShortcutItem('novel', '小说创作', 'ph_notebook') }.height('100%').layoutWeight(1)
          Column() { this.ShortcutItem('council', '模型议会', 'ph_chatCircleDots') }.height('100%').layoutWeight(1)
          Column() { this.ShortcutItem('apps', '小应用', 'ph_squaresFour') }.height('100%').layoutWeight(1)
          Column() { this.ShortcutItem('webmount', '站点', 'ph_globe') }.height('100%').layoutWeight(1)
        }
        .width('100%')
        .height(64)
        .padding({ top: 9, bottom: 11 })
        .alignItems(VerticalAlign.Center)
      }
      .width('100%')
      .backgroundColor(HOME_CARD)
      .borderRadius(HOME_RADIUS)
      .shadow({ radius: 7, color: HOME_SHADOW, offsetY: 5 })
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 20 })
    .opacity(this.cascadeCard ? 1 : 0)
    .translate({ y: this.cascadeCard ? 0 : 8 })
  }

  @Builder
  SwipePin(item: ConversationSummary): void {
    Column() {
      Icon({ name: 'ph_pushPin', iconSize: 20, color: this.accentColorStore })
      Text(item.isPinned ? '取消置顶' : '置顶')
        .fontSize(12)
        .fontColor(this.accentColorStore)
        .margin({ top: 6 })
    }
    .width(88)
    .height('100%')
    .backgroundColor(HOME_CARD2)
    .borderRadius({ topLeft: 0, bottomLeft: 0, topRight: HOME_RADIUS, bottomRight: HOME_RADIUS })
    .justifyContent(FlexAlign.Center)
    .alignItems(HorizontalAlign.Center)
    .onClick((): void => { this.collection.togglePin(item); })
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
    .borderRadius({ topLeft: HOME_RADIUS, bottomLeft: HOME_RADIUS, topRight: 0, bottomRight: 0 })
    .justifyContent(FlexAlign.Center)
    .alignItems(HorizontalAlign.Center)
    .onClick((): void => { this.collection.onDelete(item); })
  }

  build(): void {
    Stack({ alignContent: Alignment.Bottom }) {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      Stack({ alignContent: Alignment.BottomEnd }) {
        // 画布纹理层(patterns/点阵,压在渐变上、内容下;共享组件自绘)
        DesignCanvasLayer({ surface: 'home' })

        List({ scroller: this.scroller }) {
          ListItem() {
            this.HeaderBlock()
          }

          ListItem() {
            this.ControlCard()
          }

          // 子代理跨会话活动条(C5-4;运行中才渲染)
          ListItem() {
            SubAgentActivityStrip()
          }

          ListItem() {
            Text('会话')
              .fontSize(11)
              .fontWeight(FontWeight.Medium)
              .letterSpacing(0.11)
              .fontFamily(FONT_SANS_SEMIBOLD)
              .fontColor(HOME_INK2)
              .width('100%')
              .textAlign(TextAlign.Start)
              .padding({ left: 16, right: 16, top: 26, bottom: 12 })
              .opacity(this.cascadeSection ? 1 : 0)
              .translate({ y: this.cascadeSection ? 0 : 8 })
              .onClick((): void => { this.collapseSearch(); })
          }

          if (!this.collection.loaded) {
            ListItem() {
              Text('加载会话中…')
                .fontSize(13)
                .fontFamily(FONT_SANS)
                .fontColor(HOME_MUTED)
                .width('100%')
                .textAlign(TextAlign.Center)
                .padding({ top: 48, bottom: 48 })
            }
          } else if (this.previewFilter(this.collection.items).length === 0) {
            ListItem() {
              Column() {
                Text(this.searchQuery.trim().length > 0 ? '没有匹配的会话' : '还没有会话')
                  .fontSize(15)
                  .fontFamily(FONT_SANS)
                  .fontColor(HOME_MUTED)
                  .width('100%')
                  .textAlign(TextAlign.Center)
                  .padding({ left: 24, right: 24, top: 20, bottom: 20 })
                  .constraintSize({ minHeight: 120 })
                  .backgroundColor(HOME_CARD)
                  .borderRadius(HOME_RADIUS)
                  .shadow({ radius: 7, color: HOME_SHADOW, offsetY: 5 })
              }
              .width('100%')
              .padding({ left: 16, right: 16 })
              .opacity(this.cascadeRows ? 1 : 0)
              .translate({ y: this.cascadeRows ? 0 : 8 })
            }
          } else {
            ForEach(this.previewFilter(this.collection.items), (item: ConversationSummary, idx: number): void => {
              ListItem() {
                Column() {
                  HomeConversationRow({ item, count: this.collection.messageCount(item.id),
                    active: this.isActive(item.id), running: this.collection.runningIds.indexOf(item.id) >= 0,
                    isFirst: idx === 0, isLast: idx === this.previewFilter(this.collection.items).length - 1,
                    hideLine: this.hideRowLine(idx), darkMode: this.darkMode, accentColorStore: this.accentColorStore,
                    onOpen: (id: string): void => { this.openConversation(id); },
                    onMenu: (selected: ConversationSummary): void => { this.showRowMenu(selected); } })
                }
                .width('100%')
                .padding({ left: 16, right: 16 })
              }
              .swipeAction({
                start: (): void => { this.SwipePin(item); },
                end: (): void => { this.SwipeDelete(item); },
                edgeEffect: SwipeEdgeEffect.None,
              })
              // 聊天页左栏是换会话时的重建,不重播逐行入场
              .transition(this.inChat ? TransitionEffect.IDENTITY : TransitionEffect.OPACITY
                .combine(TransitionEffect.translate({ y: 16 }))
                .animation({ duration: MOTION_ENTER, curve: Curve.EaseOut,
                  delay: Math.min(idx * MOTION_STAGGER, 240) }))
            }, (item: ConversationSummary): string => item.id + (this.darkMode ? '-dark' : '-light'))
          }

          // 末行让过右下胶囊(高 42 + 余量 36;iOS homeNewChatCapsuleListClearance);两栏无胶囊
          ListItem() {
            Column().width('100%').height((this.twoPane ? 24 + this.navBarHeight : 78) + (this.collection.snackVisible ? 60 : 0))
          }
        }
        .width('100%')
        .height('100%')
        .padding({ top: this.statusBarHeight })
        .onDidScroll((): void => { cachedScrollY = this.scroller.currentOffset().yOffset; })
        .onAppear((): void => { if (this.inChat) this.restoreScroll(); })
        .scrollBar(BarState.Off)
        .divider({ strokeWidth: 0, color: Color.Transparent })
        .onClick((): void => { this.collapseSearch(); })

        // 右下拇指区:新对话胶囊(accent 底 + on-accent 墨;iOS amberProminentGlass 语义)
        // 按住光晕随 accent(同色透亮,非白按钮绿边情形),辐射半径加大
        if (!this.twoPane) {
        PressGlowButton({
          accentColor: this.accentColorStore,
          radius: 21,
          bgMode: 'solid',
          btnHeight: 42,
          haloColor: accentAlpha(this.accentColorStore, 0.9),
          haloRadius: 32,
          onTap: (): void => { this.newConversation(); },
          content: (): void => { this.FabContent(); },
        })
          .margin({ right: 28, bottom: 52 })
          .opacity(this.cascadeFab ? 1 : 0)
          .translate({ y: this.cascadeFab ? 0 : 8 })
          .accessibilityText('新建聊天')
        }
      }
      .width('100%')
      .height('100%')

      if (this.collection.snackVisible) {
        Row() {
          Text('对话已删除').fontSize(12).fontColor(Color.White).fontFamily(FONT_SANS)
          Blank()
          Text('撤销')
            .fontSize(12)
            .fontColor(this.accentColorStore)
            .fontFamily(FONT_SANS_MEDIUM)
            .padding({ left: 16, top: 6, bottom: 6 })
            .onClick((): void => { this.collection.undoDelete(); })
        }
        .width('92%')
        .padding({ left: 16, right: 16, top: 10, bottom: 10 })
        .backgroundColor(SNACK_BG)
        .borderRadius(8)
        .shadow({ radius: 12, color: '#33000000', offsetY: 4 })
        .margin({ bottom: 24 + this.navBarHeight })
        .transition(TransitionEffect.OPACITY
          .combine(TransitionEffect.translate({ y: 14 }))
          .animation({ duration: 220, curve: Curve.EaseOut }))
      }

      // 重命名对话框叠层(与小说项目库同名交互对齐)
      if (this.renameOpen) {
        Column() {
          Column() {
            Text('重命名会话')
              .fontSize(17).fontWeight(600).fontFamily(FONT_SANS_SEMIBOLD)
              .fontColor(HOME_INK).margin({ bottom: 16 })
            TextInput({ text: this.renameInput, placeholder: '会话标题' })
              .height(46).fontSize(15).fontFamily(FONT_SANS)
              .fontColor(HOME_INK).placeholderColor(HOME_MUTED)
              .backgroundColor(HOME_CARD2).borderRadius(10).padding({ left: 14, right: 14 })
              .onChange((v: string): void => { this.renameInput = v; })
            Row() {
              Text('取消').fontSize(14).fontFamily(FONT_SANS)
                .fontColor(HOME_MUTED).layoutWeight(1).textAlign(TextAlign.Center)
                .onClick((): void => { this.renameOpen = false; })
              Text('保存').fontSize(14).fontFamily(FONT_SANS_MEDIUM)
                .fontColor(this.accentColorStore).layoutWeight(1).textAlign(TextAlign.Center)
                .onClick((): void => { this.submitRename(); })
            }
            .width('100%').margin({ top: 20 })
          }
          .width('80%').padding(22).backgroundColor(HOME_CARD).borderRadius(16)
          .shadow({ radius: 24, color: HOME_SHADOW, offsetY: 8 })
        }
        .width('100%').height('100%').backgroundColor(OVERLAY)
        .justifyContent(FlexAlign.Center)
        .transition(TransitionEffect.OPACITY
          .animation({ duration: 220, curve: Curve.EaseOut }))
        .onClick((): void => { this.renameOpen = false; })
      }
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .height('100%')
    // 画布底色:包渐变(无渐变时 canvasGradientColors 退化为 HOME_BG 平涂;角度 iOS→ArkUI 已换算)
    .linearGradient({
      angle: currentCanvasGradientAngle(),
      colors: canvasGradientColors(),
    })
  }
}
