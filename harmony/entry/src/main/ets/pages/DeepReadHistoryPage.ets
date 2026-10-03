// DeepReadHistoryPage — 深度阅读历史
// 列出已生成的 DeepRead 文章(标题 + 日期 + 状态),点击重新打开;空状态
// Android 基准: feature/ui/pages/deepread/DeepReadHistoryPage.kt
import { router, KeyboardAvoidMode } from '@kit.ArkUI';
import { Icon } from '../components/ui/Icon.ets';
import type {
  DeepReadCacheEntry, DeepReadLibraryFilter, DeepReadScheduler, DeepReadActiveRun,
  DeepReadLibrarySnapshot, DeepReadLibraryRow,
} from '@amber/deepread-domain';
import { isCacheEntryExpired, observeDeepReadLibrary, queryDeepReadLibraryRows } from '@amber/deepread-domain';
import { getRepository, getDeepReadScheduler } from '../di/AppContainer.ets';
import { INK, INK2, INK3, INK4, SURFACE, LINE, BG, ERROR, ACCENT, ACCENT_LIGHT, BODY, META, LABEL, TITLE } from '../design/tokens.ets';
import { CenteredTitleBar } from '../components/PageTemplate.ets';


interface HistoryEntry {
  id: string;
  title: string;
  date: string;
  status: string;
  expired: boolean;
  updatedAt: number;
  cached: boolean;
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

@Entry
@Component
struct DeepReadHistoryPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State entries: DeepReadCacheEntry[] = [];
  @State activeRuns: DeepReadActiveRun[] = [];
  @State query: string = '';
  @State filter: DeepReadLibraryFilter = 'all';
  private readonly filters: DeepReadLibraryFilter[] = ['all', 'running', 'complete', 'failed', 'incomplete'];
  @State loaded: boolean = false;
  @State loadError: string = '';
  @State runtimeError: string = '';

  private pageAlive: boolean = false;
  private pageVisible: boolean = false;
  private loadToken: number = 0;
  private observationToken: number = 0;
  private unsubscribeLibrary: (() => void) | null = null;
  private previousKeyboardAvoidMode: KeyboardAvoidMode = KeyboardAvoidMode.OFFSET;
  private keyboardAdjusted: boolean = false;

  aboutToAppear(): void {
    this.pageAlive = true;
  }

  onPageShow(): void {
    this.pageVisible = true;
    if (!this.keyboardAdjusted) {
      this.previousKeyboardAvoidMode = this.getUIContext().getKeyboardAvoidMode();
      this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
      this.keyboardAdjusted = true;
    }
    this.startObservation();
    this.reloadHistory();
  }

  onPageHide(): void { this.leaveVisible(); }

  aboutToDisappear(): void {
    this.pageAlive = false;
    this.leaveVisible();
  }

  private leaveVisible(): void {
    this.pageVisible = false;
    this.loadToken++;
    this.stopObservation();
    if (this.keyboardAdjusted) {
      this.getUIContext().setKeyboardAvoidMode(this.previousKeyboardAvoidMode);
      this.keyboardAdjusted = false;
    }
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
        status: historyStatusLabel(row.status), expired: row.entry !== null && isCacheEntryExpired(row.entry),
        updatedAt: row.updatedAt, cached: row.entry !== null,
      }),
    );
  }

  private reopen(item: HistoryEntry): void {
    const params: Record<string, string> = {};
    params['topicId'] = item.id;
    params['title'] = item.title;
    params['fromHistory'] = 'true';
    router.pushUrl({ url: 'pages/DeepReadArticlePage', params: params });
  }

  private rowKey(item: HistoryEntry): string {
    return JSON.stringify([item.id, item.updatedAt, item.title, item.date, item.status, item.expired, item.cached]);
  }

  private createArticle(): void {
    router.pushUrl({ url: 'pages/DeepReadComposePage' }).catch((error: Error): void => {
      if (this.pageAlive && this.pageVisible) this.loadError = error.message;
    });
  }

  build(): void {
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
}
