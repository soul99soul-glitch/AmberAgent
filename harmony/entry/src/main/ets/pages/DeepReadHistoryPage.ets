// DeepReadHistoryPage — 深度阅读历史
// 列出已生成的 DeepRead 文章(标题 + 日期 + 状态),点击重新打开;空状态
// Android 基准: feature/ui/pages/deepread/DeepReadHistoryPage.kt
import { router, KeyboardAvoidMode, curves } from '@kit.ArkUI';
import { Icon } from '../components/ui/Icon.ets';
import type {
  DeepReadCacheEntry, DeepReadLibraryFilter, DeepReadScheduler, DeepReadActiveRun,
  DeepReadLibrarySnapshot, DeepReadLibraryRow,
  DeepReadInputSource,
} from '@amber/deepread-domain';
import { isCacheEntryExpired, observeDeepReadLibrary, queryDeepReadLibraryRows, firstFailureMessage } from '@amber/deepread-domain';
import { getRepository, getDeepReadScheduler } from '../di/AppContainer.ets';
import { INK, INK2, INK3, INK4, SURFACE, LINE, BG, ERROR, ACCENT, ACCENT_LIGHT, BODY, META, LABEL, TITLE } from '../design/tokens.ets';
import { CenteredTitleBar } from '../components/PageTemplate.ets';
import { DeepReadDesign as D } from '../design/DeepReadDesign.ets';
import { getProductKind } from '../platform_impl/ProductIdentity.ets';
import { markWantRoutingReady } from '../entryability/EntryAbility.ets';
import { DeepReadTabBar } from '../components/deepread/DeepReadTabBar.ets';
import { DeepReadDockScroll } from '../components/deepread/DeepReadDockScroll.ets';
import { DeepReadLibraryFilters } from '../components/deepread/DeepReadLibraryFilters.ets';
import { DeepReadLibraryCard } from '../components/deepread/DeepReadLibraryCard.ets';
import { DeepReadSettingsHeader } from '../components/deepread/DeepReadSettingsVisual.ets';
import { DeepReadPaperBackground } from '../components/deepread/DeepReadPaperBackground.ets';
import { DeepReadComposerSheet } from '../components/deepread/DeepReadComposerSheet.ets';


interface HistoryEntry {
  id: string;
  title: string;
  date: string;
  createdDate: string;
  status: string;
  expired: boolean;
  updatedAt: number;
  cached: boolean;
  statusKind: DeepReadLibraryFilter;
  summary: string;
  error: string;
}

interface DeepReadLibraryRouteParams {
  standaloneTabRoot?: boolean;
}

function historyStatusLabel(status: DeepReadLibraryFilter): string {
  switch (status) {
    case 'all': return '全部';
    case 'running': return '生成中';
    case 'complete': return '完成';
    case 'failed': return '失败';
    case 'incomplete': return '未完成';
  }
}

function historyDate(updatedAt: number): string {
  const d: Date = new Date(updatedAt);
  const pad = (n: number): string => n < 10 ? `0${n}` : `${n}`;
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function historyCreatedDate(createdAt: number): string {
  const date: Date = new Date(createdAt);
  return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日`;
}

@Component
export struct DeepReadHistoryPageContent {
  onBackHandler: (handler: () => boolean) => void = (_handler: () => boolean): void => {};
  @Prop embeddedRoot: boolean = false;
  @Prop @Watch('rootVisibilityChanged') rootVisible: boolean = false;
  @Prop @Watch('rootCreateChanged') rootCreate: number = 0;

  private rootVisibilityChanged(): void {
    if (this.rootVisible) this.onPageShow(); else this.onPageHide();
  }

  private rootCreateChanged(): void {
    if (!this.embeddedRoot || !this.rootVisible) return;
    this.createArticle();
  }

  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @StorageProp('reduceMotion') @Watch('motionPolicyChanged') reduceMotion: boolean = false;
  @StorageProp('deepreadActiveTab') @Watch('motionPolicyChanged') activeTab: number = 1;
  @StorageProp('appBackgrounded') @Watch('motionPolicyChanged') appBackgrounded: boolean = false;
  @State standaloneRoot: boolean = true;
  @State arrivalOffset: number = 0;
  @State emptyIconScale: number = 1;
  private emptyBounceTimer: number = -1;
  private emptyBounceId: number = 0;
  @State composeOpen: boolean = false;
  @State entries: DeepReadCacheEntry[] = [];
  @State activeRuns: DeepReadActiveRun[] = [];
  @State query: string = '';
  @State filter: DeepReadLibraryFilter = 'all';
  private readonly filters: DeepReadLibraryFilter[] = ['all', 'running', 'complete', 'failed', 'incomplete'];
  @State loaded: boolean = false;
  @State loadError: string = '';
  @State runtimeError: string = '';

  private pageAlive: boolean = false;
  @State pageVisible: boolean = false;
  private loadToken: number = 0;
  private observationToken: number = 0;
  private unsubscribeLibrary: (() => void) | null = null;
  private previousKeyboardAvoidMode: KeyboardAvoidMode = KeyboardAvoidMode.OFFSET;
  private keyboardAdjusted: boolean = false;
  private readonly libraryScroller: Scroller = new Scroller();
  private dockScroll: DeepReadDockScroll = new DeepReadDockScroll();
  private firstArrival: boolean = true;
  private arrivalTimer: number = -1;
  private restoreScrollPending: boolean = true;

  aboutToAppear(): void {
    this.onBackHandler((): boolean => { if (!this.composeOpen) return false; this.composeOpen = false; return true; });
    this.pageAlive = true;
    if (getProductKind() === 'deepread') {
      this.standaloneRoot = this.readStandaloneRoot();
      this.query = AppStorage.get<string>('deepreadLibraryQuery') ?? '';
      const filter: DeepReadLibraryFilter = AppStorage.get<DeepReadLibraryFilter>('deepreadLibraryFilter') ?? 'all';
      this.filter = this.filters.includes(filter) ? filter : 'all';
    }
    if (this.rootVisible) this.onPageShow();
  }

  onPageShow(): void {
    this.pageVisible = true;
    if (getProductKind() === 'deepread') {
      this.standaloneRoot = this.readStandaloneRoot();
      if (this.standaloneRoot) this.dockScroll.reset();
      if (this.standaloneRoot && !this.embeddedRoot) AppStorage.setOrCreate<number>('deepreadActiveTab', 1);
      if (!this.embeddedRoot) markWantRoutingReady(this.getUIContext().getRouter());
      if (!this.embeddedRoot) this.arrive();
    }
    if (!this.keyboardAdjusted) {
      this.previousKeyboardAvoidMode = this.getUIContext().getKeyboardAvoidMode();
      this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
      this.keyboardAdjusted = true;
    }
    this.startObservation();
    this.reloadHistory();
  }

  private readStandaloneRoot(): boolean {
    const params: DeepReadLibraryRouteParams | null = router.getParams() as DeepReadLibraryRouteParams | null;
    return this.embeddedRoot || parseInt(router.getLength(), 10) <= 1 || params?.standaloneTabRoot === true;
  }

  onPageHide(): void { this.leaveVisible(); }

  aboutToDisappear(): void {
    this.pageAlive = false;
    this.leaveVisible();
  }

  private leaveVisible(): void {
    if (this.standaloneRoot) this.dockScroll.reset();
    if (getProductKind() === 'deepread' && this.standaloneRoot) {
      AppStorage.setOrCreate<string>('deepreadLibraryQuery', this.query);
      AppStorage.setOrCreate<DeepReadLibraryFilter>('deepreadLibraryFilter', this.filter);
      AppStorage.setOrCreate<number>('deepreadLibraryOffset', this.libraryScroller.currentOffset().yOffset);
    }
    this.pageVisible = false;
    this.settleArrival();
    this.loadToken++;
    this.stopObservation();
    if (this.keyboardAdjusted) {
      this.getUIContext().setKeyboardAvoidMode(this.previousKeyboardAvoidMode);
      this.keyboardAdjusted = false;
    }
  }

  private arrive(): void {
    if (!this.firstArrival || !this.standaloneRoot) return;
    this.firstArrival = false;
    const direction: number = AppStorage.get<number>('deepreadTabDirection') ?? 0;
    if (direction === 0 || this.reduceMotion || this.appBackgrounded || this.activeTab !== 1) return;
    this.arrivalOffset = direction * 30;
    // Commit the starting position before the first animation transaction.
    this.arrivalTimer = setTimeout((): void => {
      if (this.arrivalTimer < 0) return;
      this.arrivalTimer = -1;
      if (!this.pageAlive || !this.pageVisible || this.reduceMotion || this.appBackgrounded || this.activeTab !== 1) {
        this.arrivalOffset = 0;
        return;
      }
      this.getUIContext().animateTo({ duration: 260, curve: curves.springCurve(0, 1, 240, 30) }, (): void => {
        this.arrivalOffset = 0;
      });
    }, 16);
  }

  private motionPolicyChanged(): void {
    if (this.reduceMotion || this.appBackgrounded || this.activeTab !== 1) this.settleArrival();
  }

  private settleArrival(): void {
    if (this.arrivalTimer >= 0) clearTimeout(this.arrivalTimer);
    this.arrivalTimer = -1;
    this.arrivalOffset = 0;
    this.settleEmptyFeedback();
  }

  private settleEmptyFeedback(): void {
    if (this.emptyBounceTimer >= 0) clearTimeout(this.emptyBounceTimer);
    this.emptyBounceTimer = -1;
    this.emptyBounceId++;
    this.emptyIconScale = 1;
  }

  private selectLibraryFilter(filter: DeepReadLibraryFilter): void {
    if (this.filter === filter) return;
    this.filter = filter;
    this.settleEmptyFeedback();
    if (!this.pageAlive || !this.pageVisible || this.reduceMotion || this.appBackgrounded
      || (this.standaloneRoot && this.activeTab !== 1) || !this.loaded || this.visibleItems().length > 0) return;
    const motion: number = this.emptyBounceId;
    this.emptyBounceTimer = setTimeout((): void => {
      if (motion !== this.emptyBounceId) return;
      this.emptyBounceTimer = -1;
      if (!this.pageAlive || !this.pageVisible || this.reduceMotion || this.appBackgrounded
        || (this.standaloneRoot && this.activeTab !== 1) || this.visibleItems().length > 0) return;
      this.getUIContext().keyframeAnimateTo({ iterations: 1, onFinish: (): void => {
        if (motion === this.emptyBounceId) this.settleEmptyFeedback();
      } }, [
        { duration: 100, curve: Curve.EaseOut, event: (): void => {
          if (motion === this.emptyBounceId && this.pageAlive && this.pageVisible) this.emptyIconScale = 1.15;
        } },
        { duration: 180, curve: curves.springCurve(0, 1, 240, 24), event: (): void => {
          if (motion === this.emptyBounceId && this.pageAlive && this.pageVisible) this.emptyIconScale = 1;
        } },
      ]);
    }, 16);
  }

  private restoreLibraryScroll(): void {
    if (!this.restoreScrollPending || !this.loaded || !this.standaloneRoot) return;
    this.restoreScrollPending = false;
    const offset: number = AppStorage.get<number>('deepreadLibraryOffset') ?? 0;
    this.libraryScroller.scrollTo({ xOffset: 0, yOffset: offset, animation: false });
  }

  private stopObservation(): void {
    this.observationToken++;
    this.unsubscribeLibrary?.();
    this.unsubscribeLibrary = null;
  }

  private startObservation(): void {
    this.stopObservation();
    const token: number = this.observationToken;
    this.runtimeError = '';
    this.attachObservation(null, token);
    this.connectScheduler(token).catch((error: Error): void => {
      if (this.pageAlive && this.pageVisible && token === this.observationToken) this.runtimeError = error.message;
    });
  }

  private attachObservation(scheduler: DeepReadScheduler | null, token: number): void {
    this.unsubscribeLibrary?.();
    this.unsubscribeLibrary = observeDeepReadLibrary(getRepository(), scheduler).subscribe((snapshot: DeepReadLibrarySnapshot): void => {
      if (!this.pageAlive || !this.pageVisible || token !== this.observationToken) return;
      this.loadToken++;
      this.entries = snapshot.entries;
      this.activeRuns = snapshot.activeRuns;
      this.loadError = '';
      this.loaded = true;
    });
  }

  private async connectScheduler(token: number): Promise<void> {
    const scheduler: DeepReadScheduler = await getDeepReadScheduler();
    if (!this.pageAlive || !this.pageVisible || token !== this.observationToken) return;
    this.attachObservation(scheduler, token);
  }

  private reloadHistory(): void {
    const token: number = ++this.loadToken;
    this.loadHistorySnapshot(token).catch((e: Error): void => {
      if (!this.pageAlive || !this.pageVisible || token !== this.loadToken) return;
      this.loadError = e.message;
      this.loaded = true;
    });
  }

  private async loadHistorySnapshot(token: number): Promise<void> {
    const list: DeepReadCacheEntry[] = await getRepository().listHistory(0);
    if (!this.pageAlive || !this.pageVisible || token !== this.loadToken) return;
    this.entries = list;
    this.loadError = '';
    this.loaded = true;
  }

  private visibleItems(): HistoryEntry[] {
    const snapshot: DeepReadLibrarySnapshot = { entries: this.entries, activeRuns: this.activeRuns };
    return queryDeepReadLibraryRows(snapshot, this.query, this.filter).map(
      (row: DeepReadLibraryRow): HistoryEntry => ({
        id: row.topicId, title: row.title, date: historyDate(row.updatedAt),
        createdDate: historyCreatedDate(row.entry?.createdAt ?? row.updatedAt),
        status: historyStatusLabel(row.status), expired: row.entry !== null && isCacheEntryExpired(row.entry),
        updatedAt: row.updatedAt, cached: row.entry !== null,
        statusKind: row.status, summary: this.sourceSummary(row.entry),
        error: row.entry === null ? '' : firstFailureMessage(row.entry.output) ?? row.entry.lastError ?? '',
      }),
    );
  }

  private sourceSummary(entry: DeepReadCacheEntry | null): string {
    if (entry === null) return '';
    const sources: DeepReadInputSource[] = entry.output.inputSources ?? [];
    const files: number = sources.filter((source: DeepReadInputSource): boolean => source.kind === 'file').length;
    const texts: number = sources.filter((source: DeepReadInputSource): boolean => source.kind === 'text').length;
    const websites: Set<string> = new Set<string>();
    for (const source of sources) {
      if ((source.kind === 'web' || source.kind === 'search') && source.url !== null) websites.add(source.url);
    }
    for (const reference of entry.output.references) websites.add(reference.url);
    if (entry.sourceUrl !== null) websites.add(entry.sourceUrl);
    const parts: string[] = [];
    if (files > 0) parts.push(`文件 ${files}`);
    if (texts > 0) parts.push(`手动文本 ${texts}`);
    if (websites.size > 0) parts.push(`搜索结果 ${websites.size}`);
    return parts.join(' · ');
  }

  private reopen(item: HistoryEntry): void {
    const params: Record<string, string> = {};
    params['topicId'] = item.id;
    params['title'] = item.title;
    params['fromHistory'] = 'true';
    router.pushUrl({ url: 'pages/DeepReadArticlePage', params: params });
  }

  private rowKey(item: HistoryEntry): string {
    return JSON.stringify([item.id, item.updatedAt, item.title, item.date, item.status, item.expired, item.cached,
      item.createdDate, item.summary, item.error]);
  }

  private createArticle(): void {
    if (getProductKind() === 'deepread') { this.composeOpen = true; return; }
    router.pushUrl({ url: 'pages/DeepReadComposePage' }).catch((error: Error): void => {
      if (this.pageAlive && this.pageVisible) this.loadError = error.message;
    });
  }

  @Builder
  ComposerSheet(): void {
    DeepReadComposerSheet({ embedded: true,
      onDismiss: (): void => { this.composeOpen = false; },
      onCreated: async (topicId: string, title: string): Promise<void> => {
        await router.pushUrl({ url: 'pages/DeepReadArticlePage', params: { topicId, title } });
        this.composeOpen = false;
      } })
  }

  @Builder
  HostLibrary(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      CenteredTitleBar({ title: '阅读库', actionText: '创建', onAction: (): void => { this.createArticle(); } })
      Row({ space: 8 }) {
        TextInput({ text: this.query, placeholder: '搜索标题与正文' })
          .fontSize(BODY).fontColor(INK).placeholderColor(INK3).backgroundColor(SURFACE).layoutWeight(1).height(44)
          .onChange((value: string): void => { this.query = value; })
        Button('刷新').fontSize(META).fontColor(INK).backgroundColor(SURFACE)
          .constraintSize({ minWidth: 56, minHeight: 44 })
          .onClick((): void => { this.reloadHistory(); })
      }.width('100%').padding({ left: 16, right: 16, top: 8, bottom: 8 })
      Scroll() {
        Row({ space: 8 }) {
          ForEach(this.filters, (filter: DeepReadLibraryFilter): void => {
            Button(historyStatusLabel(filter)).fontSize(META)
              .fontColor(this.filter === filter ? ACCENT : INK3)
              .backgroundColor(this.filter === filter ? ACCENT_LIGHT : SURFACE)
              .constraintSize({ minHeight: 44 }).padding({ left: 12, right: 12 })
              .onClick((): void => { this.filter = filter; })
          }, (filter: DeepReadLibraryFilter): string => filter)
        }.padding({ left: 16, right: 16, bottom: 8 })
      }.scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Off).width('100%').height(52).align(Alignment.TopStart)

      if (this.loadError.length > 0 && this.visibleItems().length > 0) {
        Text('刷新失败：' + this.loadError).fontSize(META).fontColor(ERROR)
          .width('100%').padding({ left: 16, right: 16, bottom: 8 })
      }
      if (this.runtimeError.length > 0) {
        Text('运行状态暂不可用：' + this.runtimeError).fontSize(META).fontColor(ERROR).lineHeight(20)
          .width('100%').padding({ left: 16, right: 16, bottom: 8 })
      }
      if (!this.loaded) {
        Text('加载中…').fontSize(BODY).fontColor(INK3).padding({ top: 40 })
      } else if (this.visibleItems().length === 0) {
        Column() {
          // 空态图标座替换裸 glyph
          Row() {
            Icon({ name: 'book', iconSize: 30, color: ACCENT })
          }
          .width(76).height(76).borderRadius(38)
          .backgroundColor(ACCENT_LIGHT)
          .justifyContent(FlexAlign.Center)
          .alignItems(VerticalAlign.Center)
          Text(this.loadError.length > 0 ? '历史记录加载失败' : this.entries.length === 0 && this.activeRuns.length === 0 ? '还没有阅读历史' : '没有匹配的文章')
            .fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK2).margin({ top: 18 })
          Text(this.loadError.length > 0
            ? this.loadError
            : this.entries.length === 0 && this.activeRuns.length === 0 ? '从一个主题、一段文字或几个来源开始。' : '尝试其他关键词或状态')
            .fontSize(META).fontColor(INK3).lineHeight(20).width('100%').textAlign(TextAlign.Center).margin({ top: 6 })
        }.margin({ top: 64 }).padding({ left: 24, right: 24 })
      } else {
        List() {
          ForEach(this.visibleItems(), (item: HistoryEntry): void => {
            ListItem() {
              Column() {
                Text(item.title).fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
                  .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
                Row() {
                  Text(item.date).fontSize(LABEL).fontColor(INK3)
                  if (item.cached) {
                    Text(item.expired ? '已失效' : '有效').fontSize(LABEL)
                      .fontColor(item.expired ? '#D19A66' : INK3)
                      .margin({ left: 12 })
                  }
                  Text(item.status).fontSize(LABEL)
                    .fontColor(item.status === '完成' ? INK3 : ERROR)
                    .margin({ left: 12 })
                  Blank()
                  Text('重读 ›').fontSize(LABEL).fontColor(INK4)
                }
                .width('100%').margin({ top: 6 })
              }
              .width('100%').alignItems(HorizontalAlign.Start)
              .padding({ left: 16, right: 16, top: 14, bottom: 14 })
              .backgroundColor(SURFACE)
              .onClick((): void => { this.reopen(item); })
            }
          }, (item: HistoryEntry): string => this.rowKey(item))
        }
        .width('100%').layoutWeight(1)
        .divider({ strokeWidth: 1, color: LINE })
      }
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%').backgroundColor(BG)
  }

  @Builder
  StandaloneLibrary(): void {
    Stack({ alignContent: Alignment.Bottom }) {
      DeepReadPaperBackground({ nightReading: true })
      Column() {
        DeepReadSettingsHeader({ title: '阅读库', root: this.standaloneRoot, action: this.embeddedRoot ? '' : '创建',
          onAction: (): void => { this.createArticle(); } })
        Column() {
        Row({ space: 8 }) {
          Icon({ name: 'search', iconSize: 18, color: D.muted })
          TextInput({ text: this.query, placeholder: '搜索标题与正文' }).fontSize(17)
            .fontColor(D.ink).placeholderColor(D.muted).backgroundColor(Color.Transparent).layoutWeight(1).height(40)
            .padding({ left: 0, right: 0 }).onChange((value: string): void => { this.query = value; })
        }.width('100%').padding({ left: 12, right: 12 }).backgroundColor(D.rule).borderRadius(11)
        }.width('100%').padding({ left: D.gutter, right: D.gutter, bottom: 8 })
        Scroll(this.libraryScroller) {
          Column({ space: 12 }) {
            DeepReadLibraryFilters({ selected: this.filter, filters: this.filters,
              labels: this.filters.map((filter: DeepReadLibraryFilter): string => historyStatusLabel(filter)),
              onSelect: (filter: DeepReadLibraryFilter): void => { this.selectLibraryFilter(filter); } })
            if (this.loadError.length > 0 || this.runtimeError.length > 0) {
              Text(this.loadError.length > 0 ? '读取失败：' + this.loadError : '运行状态暂不可用：' + this.runtimeError)
                .fontSize(13).fontColor(D.danger).width('100%').padding({ left: D.gutter, right: D.gutter })
              Button('重新读取').fontSize(15).fontColor(D.accent).backgroundColor(D.card)
                .onClick((): void => { this.startObservation(); this.reloadHistory(); })
            }
            if (!this.loaded) {
              Text('加载中…').fontSize(17).fontColor(D.muted).padding({ top: 40 })
            } else if (this.visibleItems().length === 0) {
              Column({ space: 12 }) {
                Icon({ name: 'book', iconSize: 48, color: D.muted }).scale({ x: this.emptyIconScale, y: this.emptyIconScale })
                Text(this.query.trim().length === 0 && this.filter === 'all' ? '你的阅读库' : '没有匹配的文章')
                  .fontSize(22).fontWeight(FontWeight.Bold).fontColor(D.ink)
                Text(this.query.trim().length === 0 && this.filter === 'all'
                  ? '从一个主题、一段文字或几个来源开始。' : '尝试其他关键词或状态。')
                  .fontSize(15).fontColor(D.muted).textAlign(TextAlign.Center)
              }.width('100%').padding({ left: 24, right: 24, top: 48, bottom: 40 })
            } else {
              Column({ space: 12 }) {
                ForEach(this.visibleItems(), (item: HistoryEntry): void => {
                  DeepReadLibraryCard({ title: item.title, date: item.createdDate, status: item.status,
                    statusKind: item.statusKind, active: this.pageVisible && (!this.standaloneRoot || this.activeTab === 1), summary: item.summary, error: item.error,
                    onTap: (): void => { this.reopen(item); } })
                }, (item: HistoryEntry): string => this.rowKey(item))
              }.width('100%').padding({ left: D.gutter, right: D.gutter })
                .onAreaChange((_oldArea: Area, _newArea: Area): void => { this.restoreLibraryScroll(); })
            }
          }.width('100%').padding({ bottom: this.standaloneRoot ? this.navBarHeight + 104 : this.navBarHeight + 28 })
        }.width('100%').layoutWeight(1).scrollBar(BarState.Off).align(Alignment.TopStart)
          .onDidScroll((_x: number, y: number, state: ScrollState): void => {
            if (this.standaloneRoot && this.pageVisible && this.activeTab === 1) {
              if (!this.embeddedRoot) this.dockScroll.update(y, this.libraryScroller.currentOffset().yOffset, state !== ScrollState.Idle);
            }
          })
      }.width('100%').height('100%').translate({ x: this.reduceMotion ? 0 : this.arrivalOffset })
        .opacity(this.embeddedRoot || !this.standaloneRoot || this.activeTab === 1 ? 1 : 0)
      if (this.standaloneRoot && !this.embeddedRoot) { DeepReadTabBar({ selected: 1 }) }
    }.width('100%').height('100%').backgroundColor(D.paper)
      .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }

  build(): void {
    Stack() {
      ForEach([this.themeEpoch], (_epoch: number): void => {
        if (getProductKind() === 'deepread') { this.StandaloneLibrary(); } else { this.HostLibrary(); }
      }, (epoch: number): string => `theme-${epoch}`)
    }.width('100%').height('100%').backgroundColor(D.paper)
      // Keep the form state owner outside the theme-remounted page content.
      .bindSheet($$this.composeOpen, this.ComposerSheet(), { height: '92%', showClose: false, backgroundColor: D.paper })
  }
}


@Entry
@Component
struct DeepReadHistoryPage {
  @State private visible: boolean = false;
  private backHandler: () => boolean = (): boolean => false;
  onBackPress(): boolean { return this.backHandler(); }
  onPageShow(): void { this.visible = true; }
  onPageHide(): void { this.visible = false; }

  aboutToAppear(): void {
    if (getProductKind() === 'deepread') {
      const boundRouter = this.getUIContext().getRouter();
      for (let index: number = 1; index <= parseInt(router.getLength(), 10); index++) {
        const state: router.RouterState | undefined = boundRouter.getStateByIndex(index);
        if (state?.name !== 'DeepReadRootPage' && state?.path !== 'pages/DeepReadRootPage') continue;
        AppStorage.setOrCreate<number>('deepreadDesiredTab', 1);
        boundRouter.back({ url: 'pages/DeepReadRootPage' });
        return;
      }
      boundRouter.replaceUrl({ url: 'pages/DeepReadRootPage', params: { tab: 1 } }).then((): void => { boundRouter.clear(); });
    }
  }

  build(): void {
    if (getProductKind() !== 'deepread') { DeepReadHistoryPageContent({ rootVisible: this.visible, onBackHandler: (handler: () => boolean): void => { this.backHandler = handler; } }) }
    else { Column().width('100%').height('100%').backgroundColor(D.paper) }
  }
}
