// BoardPage — 深度阅读热榜页(今日看板概念已下线,对齐 Android 产品方向)
//
// 内容 = HOTLIST 多源热榜(NewsNowProvider.fetchAllSources 真实网络抓取,逐源诚实报错),
//   条目点击 → DeepReadArticlePage 生成深度文章;顶栏「历史」→ DeepReadHistoryPage。
//   原「今日概览」统计区(今日会话/消息条数/已置顶)随今日看板下线移除。

import type { SourceSection, HotItem } from '../platform_impl/NewsNowProvider.ets';
import { createNewsNowProvider } from '../platform_impl/NewsNowProvider.ets';
import { getAppContainer } from '../di/AppContainer.ets';

import {
  BG, SURFACE2, INK, INK2, INK3, INK4, ACCENT,
  BODY2, META, TEXT_STYLES,
} from '../design/tokens.ets';
import { FONT_MONO_MEDIUM, FONT_SANS_SEMIBOLD } from '../design/fonts.ets';
import { PageTitleBar, PageSpacer, PaperSeparator, SectionHeader } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';
import { router } from '@kit.ArkUI';

@Component
struct BoardHotItemRow {
  @Prop rankText: string = '';
  @Prop title: string = '';
  @Prop heat: string = '';
  @Prop isLast: boolean = false;
  // 条目点击 → 深度阅读文章页(热榜条目 url 兼作 topicId 与 run seedUrl)
  onRowClick: () => void = (): void => {};
  @State private pressed: boolean = false;

  build(): void {
    Column() {
      Row() {
        Text(this.rankText)
          .fontSize(TEXT_STYLES.meta.size)
          .fontWeight(500)
          .lineHeight(TEXT_STYLES.meta.lineHeight)
          .fontFamily(FONT_MONO_MEDIUM)
          .fontColor(INK4)
          .width(28)
        Text(this.title)
          .fontSize(BODY2)
          .fontWeight(400)
          .lineHeight(TEXT_STYLES.secondary.lineHeight)
          .fontFamily(TEXT_STYLES.secondary.family)
          .fontColor(INK2)
          .layoutWeight(1)
          .maxLines(2)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
        if (this.heat.length > 0) {
          Text(this.heat)
            .fontSize(TEXT_STYLES.meta.size)
            .fontWeight(500)
            .lineHeight(TEXT_STYLES.meta.lineHeight)
            .fontFamily(FONT_MONO_MEDIUM)
            .fontColor(INK4)
            .margin({ left: 8 })
        }
      }
      .width('100%')
      .constraintSize({ minHeight: 64 })
      .padding({ left: 16, right: 16, top: 12, bottom: 12 })
      .backgroundColor(this.pressed ? SURFACE2 : Color.Transparent)
      .onTouch((event: TouchEvent): void => {
        if (event.type === TouchType.Down) this.pressed = true;
        else if (event.type === TouchType.Up || event.type === TouchType.Cancel) this.pressed = false;
      })
      .onClick((): void => { this.onRowClick(); })
      if (!this.isLast) {
        PaperSeparator()
      }
    }
    .width('100%')
  }
}

@Entry
@Component
struct BoardPage {
  @State sections: SourceSection[] = [];
  @State loading: boolean = false;
  @State hotlistError: string = '';
  aboutToAppear(): void {
    this.loadHotlist();
  }

  // 热榜条目 → 深度阅读文章页(topicId = 条目 url,run seedUrl 同源直抓)
  private openArticle(item: HotItem): void {
    const params: Record<string, string> = {};
    params['topicId'] = item.url;
    params['title'] = item.title;
    params['sourceUrl'] = item.url;
    router.pushUrl({ url: 'pages/DeepReadArticlePage', params: params });
  }

  private loadHotlist(): void {
    if (this.loading) return; // 防连点并行抓取
    this.loading = true;
    createNewsNowProvider(getAppContainer().httpClient)
      .fetchAllSources()
      .then((all: SourceSection[]): Promise<void> => {
        // 消费深度阅读设置的热榜开关(source_<id>),关掉的源不上看板
        const storage = getAppContainer().storage;
        const acc: SourceSection[] = [];
        let storageFailed: boolean = false;
        const step = (i: number): Promise<void> => {
          if (i >= all.length) {
            if (!storageFailed) this.sections = acc;
            return Promise.resolve();
          }
          const sec: SourceSection = all[i];
          return storage.get<string>(`source_${sec.sourceId}`, 'true')
            .then((v: string): Promise<void> => {
              if (v === 'true') acc.push(sec);
              return step(i + 1);
            })
            .catch((): Promise<void> => {
              storageFailed = true;
              this.hotlistError = '热榜设置读取失败，已保留上次结果';
              return step(i + 1);
            });
        };
        this.hotlistError = '';
        return step(0);
      })
      .catch((e: Error): void => {
        this.hotlistError = `热榜加载失败：${e.message}`;
      })
      .finally((): void => {
        this.loading = false;
      });
  }

  build(): void {
    Column() {
      PageTitleBar({
        title: '深度阅读',
        actionText: '历史',
        onAction: (): void => { router.pushUrl({ url: 'pages/DeepReadHistoryPage' }); },
      })

      Row() {
        Text('//')
          .fontSize(META).fontWeight(500).lineHeight(TEXT_STYLES.meta.lineHeight)
          .fontFamily(FONT_MONO_MEDIUM).fontColor(ACCENT)
        Text(' HOTLIST')
          .fontSize(META).fontWeight(500).lineHeight(TEXT_STYLES.meta.lineHeight)
          .fontFamily(FONT_MONO_MEDIUM).fontColor(INK3)
          .layoutWeight(1)
        Row() {
          Icon({ name: 'retry', iconSize: 15, color: INK3 })
        }
        .width(44).height(44).justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
        .onClick((): void => { this.loadHotlist(); })
      }
      .width('100%').padding({ left: 16, right: 16, top: 20, bottom: 8 })

      if (this.hotlistError.length > 0 && !this.loading) {
        Text(this.hotlistError)
          .fontSize(TEXT_STYLES.meta.size)
          .fontWeight(TEXT_STYLES.meta.weight)
          .lineHeight(TEXT_STYLES.meta.lineHeight)
          .fontFamily(FONT_MONO_MEDIUM)
          .fontColor(INK3)
          .width('100%')
          .padding({ left: 16, right: 16, top: 8, bottom: 4 })
      }

      if (this.loading) {
        Text('抓取热榜中…')
          .fontSize(BODY2).fontWeight(400).lineHeight(TEXT_STYLES.secondary.lineHeight)
          .fontFamily(TEXT_STYLES.secondary.family).fontColor(INK3)
          .width('100%').textAlign(TextAlign.Center).margin({ top: 40 })
      } else {
        List() {
          ForEach(this.sections, (sec: SourceSection): void => {
            ListItem() {
              Column() {
                SectionHeader({ label: sec.sourceName })
                if (sec.error !== null || sec.items.length === 0) {
                  BoardHotItemRow({
                    rankText: '',
                    title: sec.error !== null ? `抓取失败:${sec.error}` : '无条目',
                    heat: '',
                    isLast: true,
                  })
                } else {
                  ForEach(sec.items.slice(0, 5), (item: HotItem, index: number): void => {
                    BoardHotItemRow({
                      rankText: `${item.rank}`,
                      title: item.title,
                      heat: item.heat,
                      isLast: index === Math.min(sec.items.length, 5) - 1,
                      onRowClick: (): void => { this.openArticle(item); },
                    })
                  }, (item: HotItem): string => `${sec.sourceId}-${item.rank}`)
                }
              }
              .width('100%')
            }
          }, (sec: SourceSection): string => sec.sourceId)
        }
        .width('100%').layoutWeight(1).padding({ top: 8 })
        .divider({ strokeWidth: 0, color: Color.Transparent })
      }
      PageSpacer()
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
