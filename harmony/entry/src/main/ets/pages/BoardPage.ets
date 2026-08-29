// BoardPage — 深度阅读热榜页(今日看板概念已下线,对齐 Android 产品方向)
//
// 内容 = HOTLIST 多源热榜(NewsNowProvider.fetchAllSources 真实网络抓取,逐源诚实报错),
//   条目点击 → DeepReadArticlePage 生成深度文章;顶栏「历史」→ DeepReadHistoryPage。
//   原「今日概览」统计区(今日会话/消息条数/已置顶)随今日看板下线移除。

import type { SourceSection, HotItem } from '../platform_impl/NewsNowProvider.ets';
import { createNewsNowProvider } from '../platform_impl/NewsNowProvider.ets';
import { aggregateHotTopics } from '../platform_impl/HotListAggregator.ets';
import type { HotTopic } from '../platform_impl/HotListAggregator.ets';
import { getAppContainer } from '../di/AppContainer.ets';

import {
  BG, SURFACE2, INK, INK2, INK3, INK4, ACCENT, ERROR, OVERLAY, SURFACE, LINE, LABEL,
  BODY2, META, TEXT_STYLES,
} from '../design/tokens.ets';
import { FONT_MONO_MEDIUM, FONT_SANS_SEMIBOLD } from '../design/fonts.ets';
import { PageTitleBar, PageSpacer, PaperSeparator, SectionHeader } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';
import { router, promptAction } from '@kit.ArkUI';
import { pasteboard } from '@kit.BasicServicesKit';
import type { common } from '@kit.AbilityKit';

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
  @State topics: HotTopic[] = [];
  @State lastUpdatedAt: number = 0;
  @State loading: boolean = false;
  @State hotlistError: string = '';
  // 条目操作 sheet(Android HotListActionSheet:深度阅读/重新生成/原文/分享)
  @State sheetItem: HotItem | null = null;
  @State sheetForce: boolean = false;
  aboutToAppear(): void {
    this.loadHotlist();
  }

  private timeAgo(ts: number): string {
    if (ts <= 0) return '';
    const diff: number = Math.max(0, Date.now() - ts);
    const minutes: number = Math.floor(diff / 60_000);
    if (minutes < 1) return '刚刚更新';
    if (minutes < 60) return `${minutes}分钟前更新`;
    if (minutes < 24 * 60) return `${Math.floor(minutes / 60)}小时前更新`;
    return `${Math.floor(minutes / (24 * 60))}天前更新`;
  }

  // 热榜条目 → 深度阅读文章页(topicId = 条目 url,run seedUrl 同源直抓)
  // 聚合话题条目:index 0 = 头条(大字),其余索引行
  @Builder
  HotTopicEntry(tp: HotTopic, isLead: boolean, isLast: boolean): void {
    Column() {
      Row() {
        Text(`${tp.bestRank}`)
          .fontSize(isLead ? 22 : TEXT_STYLES.meta.size)
          .fontWeight(isLead ? FontWeight.Bold : FontWeight.Medium)
          .lineHeight(isLead ? 28 : TEXT_STYLES.meta.lineHeight)
          .fontFamily(FONT_MONO_MEDIUM)
          .fontColor(isLead ? ACCENT : INK4)
          .width(isLead ? 36 : 28)
        Column() {
          Text(tp.title)
            .fontSize(isLead ? 18 : BODY2)
            .fontWeight(isLead ? 600 : FontWeight.Normal)
            .lineHeight(isLead ? 25 : TEXT_STYLES.secondary.lineHeight)
            .fontFamily(TEXT_STYLES.secondary.family)
            .fontColor(isLead ? INK : INK2)
            .layoutWeight(1)
            .maxLines(isLead ? 3 : 2)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
          Row() {
            if (tp.sourceCount > 1) {
              Text(`${tp.sourceCount} 源`)
                .fontSize(TEXT_STYLES.meta.size).fontColor(ACCENT)
                .fontWeight(FontWeight.Medium)
              Text(' · ').fontSize(TEXT_STYLES.meta.size).fontColor(INK4)
            }
            if (tp.sources.length > 0 && tp.sources[0].heat.length > 0) {
              Text(tp.sources[0].heat).fontSize(TEXT_STYLES.meta.size).fontColor(INK4)
            }
          }
          .margin({ top: isLead ? 5 : 2 })
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 8 })
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: isLead ? 72 : 60 })
      .padding({ left: 16, right: 16, top: isLead ? 14 : 12, bottom: isLead ? 14 : 12 })
      .onClick((): void => {
        // 生成入口:话题第一名来源的 url 兼 topicId/seedUrl
        const first: HotItem = {
          rank: tp.bestRank,
          title: tp.title,
          url: tp.sources.length > 0 ? tp.sources[0].url : '',
          heat: tp.sources.length > 0 ? tp.sources[0].heat : '',
        };
        this.sheetItem = first;
      })
      if (!isLast) {
        PaperSeparator()
      }
    }
    .width('100%')
  }

  private openArticle(item: HotItem, force: boolean = false): void {
    const params: Record<string, string> = {};
    params['topicId'] = item.url;
    params['title'] = item.title;
    params['sourceUrl'] = item.url;
    if (force) params['force'] = 'true';
    router.pushUrl({ url: 'pages/DeepReadArticlePage', params: params });
  }

  private openOriginal(url: string): void {
    if (url.trim().length === 0) return;
    try {
      const ctx: common.UIAbilityContext = getContext(this) as common.UIAbilityContext;
      ctx.startAbility({
        action: 'ohos.want.action.viewData',
        entities: ['entity.system.browsable'],
        uri: url,
      }).catch((): void => {
        promptAction.showToast({ message: '无法打开浏览器，已复制链接', duration: 1400 });
      });
    } catch (_e) {
      promptAction.showToast({ message: '无法打开浏览器，已复制链接', duration: 1400 });
    }
  }

  private shareTopic(item: HotItem): void {
    let text: string = item.title;
    if (item.url.length > 0) text += `\n${item.url}`;
    try {
      const board: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
      board.setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, text));
      promptAction.showToast({ message: '已复制，可粘贴分享', duration: 1400 });
    } catch (_e) {
      promptAction.showToast({ message: '分享准备失败', duration: 1400 });
    }
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
            if (!storageFailed) {
              this.sections = acc;
              this.topics = aggregateHotTopics(acc, 10);
              this.lastUpdatedAt = Date.now();
            }
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
    Stack() {
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

      if (this.loading && this.topics.length === 0 && this.sections.length === 0) {
        // 骨架屏(首载):标题条×6
        Column() {
          ForEach([0, 1, 2, 3, 4, 5], (i: number): void => {
            Row() {
              Column().width(24).height(16).borderRadius(4).backgroundColor(SURFACE2)
              Column().layoutWeight(1).height(16).borderRadius(4).backgroundColor(SURFACE2)
                .margin({ left: 12 })
            }
            .width('100%').padding({ left: 16, right: 16, top: 16, bottom: 16 })
          }, (i: number): string => `sk-${i}`)
        }
        .width('100%').margin({ top: 8 })
      } else {
        List() {
          if (this.topics.length > 0) {
            ListItem() {
              Column() {
                Row() {
                  Text('//')
                    .fontSize(META).fontWeight(500).lineHeight(TEXT_STYLES.meta.lineHeight)
                    .fontFamily(FONT_MONO_MEDIUM).fontColor(ACCENT)
                  Text(' 综合热点')
                    .fontSize(META).fontWeight(500).lineHeight(TEXT_STYLES.meta.lineHeight)
                    .fontFamily(FONT_MONO_MEDIUM).fontColor(INK3)
                  Blank()
                  Text(this.timeAgo(this.lastUpdatedAt))
                    .fontSize(META).fontColor(INK4)
                }
                .width('100%').padding({ left: 16, right: 16, top: 14, bottom: 4 })
                ForEach(this.topics, (tp: HotTopic, index: number): void => {
                  this.HotTopicEntry(tp, index === 0, index === Math.min(this.topics.length, 10) - 1)
                }, (tp: HotTopic): string => tp.id)
              }
              .width('100%')
            }
          }
          ForEach(this.sections, (sec: SourceSection): void => {
            ListItem() {
              Column() {
                Row() {
                  Text('//')
                    .fontSize(META).fontWeight(500).lineHeight(TEXT_STYLES.meta.lineHeight)
                    .fontFamily(FONT_MONO_MEDIUM).fontColor(ACCENT)
                  Text(` ${sec.sourceName}`)
                    .fontSize(META).fontWeight(500).lineHeight(TEXT_STYLES.meta.lineHeight)
                    .fontFamily(FONT_MONO_MEDIUM).fontColor(INK3)
                  Blank()
                  if (sec.error !== null) {
                    Text(`⚠ ${this.timeAgo(sec.fetchedAt)}`)
                      .fontSize(META).fontColor(INK4)
                  } else if (sec.fetchedAt > 0) {
                    Text(this.timeAgo(sec.fetchedAt))
                      .fontSize(META).fontColor(INK4)
                  }
                }
                .width('100%').padding({ left: 16, right: 16, top: 14, bottom: 4 })
                if (sec.error !== null || sec.items.length === 0) {
                  BoardHotItemRow({
                    rankText: '',
                    title: sec.error !== null ? `抓取失败:${sec.error}` : '无条目',
                    heat: '',
                    isLast: true,
                  })
                } else {
                  ForEach(sec.items.slice(0, 12), (item: HotItem, index: number): void => {
                    BoardHotItemRow({
                      rankText: `${item.rank}`,
                      title: item.title,
                      heat: item.heat,
                      isLast: index === Math.min(sec.items.length, 12) - 1,
                      onRowClick: (): void => { this.sheetItem = item; },
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
      // 条目操作 sheet(半模态 overlay)
      if (this.sheetItem !== null) {
        Column() {
          Blank()
            .onClick((): void => { this.sheetItem = null; })
          Column() {
            Text(this.sheetItem.title)
              .fontSize(BODY2).fontWeight(FontWeight.Medium).fontColor(INK)
              .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
              .width('100%').padding({ left: 20, right: 20, top: 18, bottom: 10 })
            this.SheetAction('深度阅读', 'book', 'read')
            this.SheetDivider()
            this.SheetAction('重新生成', 'retry', 'regenerate')
            this.SheetDivider()
            this.SheetAction('打开原文', 'link', 'original')
            this.SheetDivider()
            this.SheetAction('分享', 'share', 'share')
          }
          .width('100%')
          .padding({ bottom: 12 })
          .backgroundColor(SURFACE)
          .borderRadius({ topLeft: 20, topRight: 20 })
        }
        .width('100%').height('100%')
        .backgroundColor(OVERLAY)
        .justifyContent(FlexAlign.End)
      }
    }
    .width('100%').height('100%')
  }

  @Builder
  SheetDivider(): void {
    Row().width('100%').height(0.5).backgroundColor(LINE)
  }

  // action: 'read' | 'regenerate' | 'original' | 'share'
  @Builder
  SheetAction(label: string, icon: string, action: string): void {
    Row() {
      Icon({ name: icon, iconSize: 18, color: INK2 }).margin({ right: 14 })
      Text(label).fontSize(BODY2).fontColor(INK)
      Blank()
    }
    .width('100%')
    .constraintSize({ minHeight: 48 })
    .padding({ left: 20, right: 20 })
    .onClick((): void => {
      const it: HotItem | null = this.sheetItem;
      this.sheetItem = null;
      if (it === null) return;
      if (action === 'read') this.openArticle(it, false);
      else if (action === 'regenerate') this.openArticle(it, true);
      else if (action === 'original') this.openOriginal(it.url);
      else this.shareTopic(it);
    })
  }
}
