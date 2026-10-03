import { getProductKind } from '../platform_impl/ProductIdentity.ets';
import { markWantRoutingReady } from '../entryability/EntryAbility.ets';
// BoardPage — 深度阅读热榜页(今日看板概念已下线,对齐 Android 产品方向)
//
// 内容 = HOTLIST 多源热榜(NewsNowProvider.fetchAllSources 真实网络抓取,逐源诚实报错),
//   条目点击 → DeepReadArticlePage 生成深度文章;顶栏「历史」→ DeepReadHistoryPage。
//   原「今日概览」统计区(今日会话/消息条数/已置顶)随今日看板下线移除。

import type { SourceSection, HotItem } from '../platform_impl/NewsNowProvider.ets';
import { createNewsNowProvider, HOTLIST_PRESETS } from '../platform_impl/NewsNowProvider.ets';
import { aggregateHotTopics } from '../platform_impl/HotListAggregator.ets';
import type { HotTopic, HotTopicSource } from '../platform_impl/HotListAggregator.ets';
import {
  hotTopicSeedUrls, hotTopicMatchesFocus, parseHotListFocusKeywords, projectHotListSections,
  mergeHotListSections, shouldRefreshHotList, latestHotListUpdate,
  untranslatedHotListTitles, withHotListTitleTranslations,
} from '../platform_impl/HotListState.ets';
import type { HotListCacheSnapshot } from '../platform_impl/HotListState.ets';
import { translateHotListTitles } from '../platform_impl/HotListTitleTranslator.ets';
import { createEntryAbortController } from '../platform_impl/EntryAbortController.ets';
import type { AbortControllerLike, DeepReadInputSource, DeepReadHotspot, DeepReadActiveRun, DeepReadScheduler } from '@amber/deepread-domain';
import { discoveryHotspotInputs, discoveryArticleParams } from '@amber/deepread-domain';
import { getAppContainer, getDeepReadScheduler } from '../di/AppContainer.ets';
import { DeepReadNewsroomCard } from '../components/DeepReadNewsroomCard.ets';

import {
  BG, SURFACE2, INK, INK2, INK3, INK4, ACCENT, ERROR, MOTION_ENTER, MOTION_OVERLAY, MOTION_STAGGER,
  OVERLAY, SHADOW_OVERLAY, SURFACE, LINE, LABEL, BODY2, META, TEXT_STYLES,
} from '../design/tokens.ets';
import { FONT_MONO_MEDIUM, FONT_SANS_SEMIBOLD } from '../design/fonts.ets';
import { PlainSection, CardGroup, PageSpacer, PaperSeparator } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';
import { router, promptAction } from '@kit.ArkUI';
import { pasteboard } from '@kit.BasicServicesKit';
import type { common } from '@kit.AbilityKit';
import { connection } from '@kit.NetworkKit';

const HOTLIST_CACHE_KEY: string = 'deepread_hotlist_cache';

interface BoardHotActionItem extends HotItem {
  seedUrls: string[];
  inputSources: DeepReadInputSource[];
}

@Component
struct BoardHotItemRow {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop rankText: string = '';
  @Prop title: string = '';
  @Prop heat: string = '';
  @Prop isLast: boolean = false;
  @Prop idx: number = 0;
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
          .fontSize(TEXT_STYLES.body.size)
          .fontWeight(400)
          .lineHeight(21)
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
      .padding({ left: 16, right: 16, top: 11, bottom: 11 })
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
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .transition(TransitionEffect.OPACITY
      .combine(TransitionEffect.translate({ y: 12 }))
      .animation({ duration: MOTION_ENTER, curve: Curve.EaseOut,
        delay: Math.min(this.idx * MOTION_STAGGER, 300) }))
  }
}

@Entry
@Component
struct BoardPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State sections: SourceSection[] = [];
  @State topics: HotTopic[] = [];
  @State lastUpdatedAt: number = 0;
  @State loading: boolean = false;
  @State refreshing: boolean = false;
  @State hotlistError: string = '';
  @State hotlistNote: string = '';
  @State focusKeywords: string[] = [];
  @State focusMode: string = 'all';
  @State activeRuns: DeepReadActiveRun[] = [];
  @State activityError: string = '';
  // 条目操作 sheet(Android HotListActionSheet:深度阅读/重新生成/原文/分享)
  @State sheetItem: BoardHotActionItem | null = null;
  @State sheetForce: boolean = false;
  private rawSections: SourceSection[] = [];
  private enabledSourceIds: string[] = [];
  private refreshMinutes: number = 60;
  private wifiOnly: boolean = false;
  private translateTitles: boolean = false;
  private cacheLoaded: boolean = false;
  private firstShowDone: boolean = false;
  private pageVisible: boolean = false;
  private refreshToken: number = 0;
  private requestController: AbortControllerLike | null = null;
  private runObservationToken: number = 0;
  private unsubscribeRuns: (() => void) | null = null;

  aboutToAppear(): void {
    this.pageVisible = true;
    this.loadHotlist(false);
  }

  onPageShow(): void {
    this.pageVisible = true;
    if (getProductKind() === 'deepread') {
      markWantRoutingReady(this.getUIContext().getRouter());
      this.observeRuns();
    }
    if (this.firstShowDone) this.loadHotlist(false);
    else this.firstShowDone = true;
  }

  onPageHide(): void {
    this.stopRefresh();
  }

  aboutToDisappear(): void {
    this.stopRefresh();
  }

  onBackPress(): boolean {
    if (this.sheetItem === null) return false;
    animateTo({ duration: 200, curve: Curve.EaseIn }, (): void => {
      this.sheetItem = null;
    });
    return true;
  }

  private stopRefresh(): void {
    this.pageVisible = false;
    this.runObservationToken++;
    this.unsubscribeRuns?.();
    this.unsubscribeRuns = null;
    this.refreshToken++;
    this.requestController?.abort();
    this.requestController = null;
    this.loading = false;
    this.refreshing = false;
  }

  private async observeRuns(): Promise<void> {
    this.unsubscribeRuns?.();
    this.unsubscribeRuns = null;
    const token: number = ++this.runObservationToken;
    this.activityError = '';
    try {
      const scheduler: DeepReadScheduler = await getDeepReadScheduler();
      if (!this.pageVisible || token !== this.runObservationToken) return;
      this.unsubscribeRuns = scheduler.observeActiveRuns().subscribe((runs: DeepReadActiveRun[]): void => {
        if (!this.pageVisible || token !== this.runObservationToken) return;
        this.activeRuns = runs.slice();
      });
    } catch (error) {
      if (this.pageVisible && token === this.runObservationToken) {
        this.activityError = '任务进度暂不可用：' + (error instanceof Error ? error.message : String(error));
      }
    }
  }

  private runKey(run: DeepReadActiveRun): string {
    return JSON.stringify([run.topicId, run.startedAt, run.title, run.stage ?? '', run.label ?? '']);
  }

  private openRun(run: DeepReadActiveRun): void {
    const token: number = this.runObservationToken;
    router.pushUrl({ url: 'pages/DeepReadArticlePage', params: { topicId: run.topicId, title: run.title } })
      .catch((error: Error): void => {
        if (this.pageVisible && token === this.runObservationToken) this.activityError = '无法打开任务：' + error.message;
      });
  }

  private randomItems(): BoardHotActionItem[] {
    const items: BoardHotActionItem[] = [];
    for (const topic of this.topics) {
      if (topic.title.trim().length === 0) continue;
      const inputs: DeepReadInputSource[] = discoveryHotspotInputs(topic.sources.map((source: HotTopicSource): DeepReadHotspot => ({
        title: source.title, source: source.providerName, rank: source.rank,
        url: source.url, summary: source.summary,
      })));
      if (inputs.length === 0) continue;
      const seeds: string[] = hotTopicSeedUrls(topic);
      items.push({ title: topic.title, displayTitle: topic.displayTitle, rank: topic.bestRank,
        url: seeds[0] ?? '', heat: topic.sources[0]?.heat ?? '', seedUrls: seeds, inputSources: inputs });
    }
    for (const section of this.sections) {
      for (const item of section.items.slice(0, 12)) {
        if (item.title.trim().length === 0) continue;
        const inputs: DeepReadInputSource[] = discoveryHotspotInputs([{ title: item.title,
          source: section.sourceName, rank: item.rank, url: item.url, summary: item.summary }]);
        items.push({ title: item.title, displayTitle: item.displayTitle, rank: item.rank,
          url: item.url, heat: item.heat, seedUrls: [item.url], inputSources: inputs });
      }
    }
    return items;
  }

  private randomRead(): void {
    const items: BoardHotActionItem[] = this.randomItems();
    if (items.length === 0) return;
    this.activityError = '';
    this.openArticle(items[Math.floor(Math.random() * items.length)]);
  }

  private applyProjection(): void {
    const visible: SourceSection[] = projectHotListSections(this.rawSections, this.enabledSourceIds,
      [], 'all', this.translateTitles);
    let topicLimit: number = 0;
    for (const section of visible) topicLimit += section.items.length;
    const allTopics: HotTopic[] = aggregateHotTopics(visible, topicLimit);
    const matchedTopics: HotTopic[] = allTopics.filter((topic: HotTopic): boolean =>
      hotTopicMatchesFocus(topic, this.focusKeywords));
    const filtered: boolean = this.focusKeywords.length > 0 && this.focusMode !== 'all';
    const projectedTopics: HotTopic[] = !filtered ? allTopics : this.focusMode === 'focus_only' ? matchedTopics
      : matchedTopics.concat(allTopics.filter((topic: HotTopic): boolean => !hotTopicMatchesFocus(topic, this.focusKeywords)));
    this.topics = projectedTopics.slice(0, 10);
    this.sections = projectHotListSections(this.rawSections, this.enabledSourceIds,
      this.focusKeywords, this.focusMode, this.translateTitles);
    this.lastUpdatedAt = latestHotListUpdate(this.rawSections, this.enabledSourceIds);
  }

  private showTopicActions(item: HotItem, urls: string[], inputs: DeepReadInputSource[]): void {
    const snapshot: BoardHotActionItem = {
      rank: item.rank, title: item.title, url: item.url, heat: item.heat,
      displayTitle: item.displayTitle, seedUrls: urls.slice(), inputSources: inputs.slice(),
    };
    animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
      this.sheetItem = snapshot;
    });
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
  HotTopicEntry(tp: HotTopic, isLead: boolean, isLast: boolean, idx: number): void {
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
          // 标题宽度交给外层 Column 的 layoutWeight;此处禁 layoutWeight(竖向 Column 里会吃满剩余高)
          Text(tp.displayTitle ?? tp.title)
            .fontSize(isLead ? 18 : TEXT_STYLES.body.size)
            .fontWeight(isLead ? 600 : FontWeight.Normal)
            .lineHeight(isLead ? 25 : 21)
            .fontFamily(TEXT_STYLES.secondary.family)
            .fontColor(isLead ? INK : INK2)
            .width('100%')
            .maxLines(isLead ? 3 : 2)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
          Row() {
            if (tp.sourceCount > 1) {
              Text(`${tp.sourceCount} 源`)
                .fontSize(TEXT_STYLES.meta.size).fontColor(INK3)
                .fontWeight(FontWeight.Medium)
              if (tp.sources.length > 0 && tp.sources[0].heat.length > 0) {
                Text(' · ').fontSize(TEXT_STYLES.meta.size).fontColor(INK4)
              }
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
      .constraintSize({ minHeight: isLead ? 72 : 0 })
      .padding({ left: 16, right: 16, top: isLead ? 14 : 11, bottom: isLead ? 14 : 11 })
      .onClick((): void => {
        // 第一项可能没有链接；从聚合中保留所有有效链接并用首个作为既有身份。
        const seeds: string[] = hotTopicSeedUrls(tp);
        const first: HotItem = {
          rank: tp.bestRank,
          title: tp.title,
          url: seeds.length > 0 ? seeds[0] : '',
          heat: tp.sources.length > 0 ? tp.sources[0].heat : '',
          displayTitle: tp.displayTitle,
        };
        const hotspots: DeepReadHotspot[] = tp.sources.map((source: HotTopicSource): DeepReadHotspot => ({
          title: source.title, source: source.providerName, rank: source.rank,
          url: /^https?:\/\//i.test(source.url) ? source.url : null, summary: source.summary,
        }));
        this.showTopicActions(first, seeds, discoveryHotspotInputs(hotspots));
      })
      if (!isLast) {
        PaperSeparator()
      }
    }
    .width('100%')
    .transition(TransitionEffect.OPACITY
      .combine(TransitionEffect.translate({ y: 12 }))
      .animation({ duration: MOTION_ENTER, curve: Curve.EaseOut,
        delay: Math.min(idx * MOTION_STAGGER, 300) }))
  }

  private openArticle(item: BoardHotActionItem, force: boolean = false): void {
    const params: Record<string, string> = discoveryArticleParams(
      item.title, item.inputSources, item.seedUrls, force);
    const token: number = this.runObservationToken;
    router.pushUrl({ url: 'pages/DeepReadArticlePage', params: params }).catch((error: Error): void => {
      if (this.pageVisible && token === this.runObservationToken) this.activityError = '无法打开文章：' + error.message;
    });
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

  private async loadHotlist(force: boolean): Promise<void> {
    if (this.loading) return; // 防连点并行抓取
    this.loading = true;
    const token: number = ++this.refreshToken;
    const controller: AbortControllerLike = createEntryAbortController();
    this.requestController = controller;
    try {
      const storage = getAppContainer().storage;
      const enabledIds: string[] = [];
      for (const preset of HOTLIST_PRESETS) {
        if (await storage.get<string>('source_' + preset.id, preset.defaultEnabled === false ? 'false' : 'true') === 'true') {
          enabledIds.push(preset.id);
        }
      }
      const keywords: string[] = parseHotListFocusKeywords(await storage.get<string>('deepread_focus_keywords', ''));
      const mode: string = await storage.get<string>('deepread_focus_mode', 'all');
      const minutes: number = await storage.get<number>('deepread_hotlist_refresh_minutes', 60);
      const wifiOnly: boolean = await storage.get<string>('deepread_hotlist_wifi_only', 'false') === 'true';
      const translate: boolean = await storage.get<string>('deepread_hotlist_translate_zh', 'false') === 'true';
      if (!this.pageVisible || token !== this.refreshToken) return;
      this.enabledSourceIds = enabledIds;
      this.focusKeywords = keywords;
      this.focusMode = mode;
      this.refreshMinutes = Number.isFinite(minutes) ? Math.max(15, minutes) : 60;
      this.wifiOnly = wifiOnly;
      this.translateTitles = translate;
      this.hotlistError = '';
      this.hotlistNote = '';
      if (!this.cacheLoaded) {
        try {
          const raw: string = await storage.get<string>(HOTLIST_CACHE_KEY, '');
          if (!this.pageVisible || token !== this.refreshToken) return;
          if (raw.length > 0) {
            const cached: HotListCacheSnapshot = JSON.parse(raw) as HotListCacheSnapshot;
            if (cached.sections !== undefined && cached.sections !== null) this.rawSections = cached.sections;
          }
        } catch (_cacheError) {
          if (this.pageVisible && token === this.refreshToken) this.hotlistNote = '热榜缓存无法读取，将重新获取';
        }
        if (!this.pageVisible || token !== this.refreshToken) return;
        this.cacheLoaded = true;
      }
      if (!this.pageVisible || token !== this.refreshToken) return;
      this.applyProjection();
      if (enabledIds.length === 0) {
        this.hotlistNote = getProductKind() === 'deepread'
          ? '尚未启用热榜来源，可在发现设置中选择' : '尚未启用热榜来源，可在深度阅读设置中选择';
        return;
      }
      const needsFetch: boolean = force || shouldRefreshHotList(this.rawSections, enabledIds, this.refreshMinutes, Date.now());
      const pendingBeforeFetch: string[] = translate ? untranslatedHotListTitles(this.rawSections, enabledIds) : [];
      if (this.wifiOnly && (needsFetch || pendingBeforeFetch.length > 0)) {
        // API 8，离线官方文档已核对；GET_NETWORK_INFO 权限已由应用声明。
        let wifi: boolean = false;
        try {
          const net: connection.NetHandle = await connection.getDefaultNet();
          wifi = net.netId !== 0 && (await connection.getNetCapabilities(net))
            .bearerTypes.includes(connection.NetBearType.BEARER_WIFI);
        } catch (_networkError) {
          if (this.pageVisible && token === this.refreshToken) this.hotlistNote = '无法确认 Wi-Fi 连接，已保留缓存';
          return;
        }
        if (!this.pageVisible || token !== this.refreshToken) return;
        if (!wifi) {
          this.hotlistNote = '仅 Wi-Fi 刷新：当前未连接 Wi-Fi，已保留缓存';
          return;
        }
      }
      if (needsFetch) {
        const fetched: SourceSection[] = await createNewsNowProvider(getAppContainer().httpClient)
          .fetchAllSources(controller.signal, enabledIds);
        if (!this.pageVisible || token !== this.refreshToken || controller.signal.aborted) return;
        this.rawSections = mergeHotListSections(this.rawSections, fetched);
        this.applyProjection();
        const snapshot: HotListCacheSnapshot = { sections: this.rawSections };
        await storage.set<string>(HOTLIST_CACHE_KEY, JSON.stringify(snapshot));
      }
      if (!this.pageVisible || token !== this.refreshToken) return;
      const pending: string[] = translate ? untranslatedHotListTitles(this.rawSections, enabledIds) : [];
      if (pending.length > 0) {
        try {
          const translations: Record<string, string> = await translateHotListTitles(pending, controller.signal);
          if (!this.pageVisible || token !== this.refreshToken) return;
          this.rawSections = withHotListTitleTranslations(this.rawSections, translations);
          this.applyProjection();
          const snapshot: HotListCacheSnapshot = { sections: this.rawSections };
          await storage.set<string>(HOTLIST_CACHE_KEY, JSON.stringify(snapshot));
        } catch (_translationError) {
          if (this.pageVisible && token === this.refreshToken) this.hotlistNote = '标题翻译暂不可用，已保留原文';
        }
      }
    } catch (e) {
      if (this.pageVisible && token === this.refreshToken) {
        this.hotlistError = `热榜刷新未完成，已保留当前结果：${String(e)}`;
      }
    } finally {
      if (token === this.refreshToken) {
        this.loading = false;
        this.refreshing = false;
        this.requestController = null;
      }
    }
  }

  // iOS BoardView 顶栏:返回 / 居中标题 / 历史 + 设置圆钮
  @Builder
  BoardHeader(): void {
    Stack({ alignContent: Alignment.Center }) {
      Row() {
        Row() {
          Icon({ name: getProductKind() === 'deepread' ? 'plus' : 'back', iconSize: 18, color: INK })
        }
        .width(44).height(44).borderRadius(22).backgroundColor(SURFACE)
        .border({ width: 0.5, color: LINE })
        .justifyContent(FlexAlign.Center)
        .accessibilityText(getProductKind() === 'deepread' ? '创建深度阅读' : '返回')
        .onClick((): void => {
          if (getProductKind() === 'deepread') router.pushUrl({ url: 'pages/DeepReadComposePage' });
          else router.back();
        })
        Blank()
        Row() {
          Icon({ name: 'history', iconSize: 17, color: INK })
        }
        .width(44).height(44).borderRadius(22).backgroundColor(SURFACE)
        .border({ width: 0.5, color: LINE })
        .justifyContent(FlexAlign.Center)
        .accessibilityText('深度阅读历史')
        .onClick((): void => { router.pushUrl({ url: 'pages/DeepReadHistoryPage' }); })
        Row() {
          Icon({ name: 'gear', iconSize: 17, color: INK })
        }
        .width(44).height(44).borderRadius(22).backgroundColor(SURFACE)
        .border({ width: 0.5, color: LINE })
        .justifyContent(FlexAlign.Center)
        .margin({ left: 8 })
        .accessibilityText('深度阅读设置')
        .onClick((): void => { router.pushUrl({ url: getProductKind() === 'deepread' ? 'pages/StandaloneSettingsPage' : 'pages/SettingDeepReadPage' }); })
      }
      .width('100%')

      Text('深度阅读')
        .fontSize(16).fontWeight(FontWeight.Medium).fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK)
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: this.statusBarHeight + 10, bottom: 10 })
    .backgroundColor(BG)
  }

  // 卡片内容(@Builder:组件构造必须在 builder 上下文,铁律 4b)
  @Builder
  TopicsCardContent(): void {
    ForEach(this.topics, (tp: HotTopic, index: number): void => {
      this.HotTopicEntry(tp, index === 0, index === Math.min(this.topics.length, 10) - 1, index)
    }, (tp: HotTopic): string => tp.id)
  }

  @Builder
  SourceCardContent(sec: SourceSection): void {
    if (sec.items.length === 0) {
      BoardHotItemRow({
        rankText: '',
        title: sec.error !== null ? `抓取失败:${sec.error}` : '无条目',
        heat: '',
        isLast: true,
        idx: 0,
      })
    } else {
      if (sec.error !== null) {
        Text(sec.stale === true ? `刷新失败，显示上次结果：${sec.error}` : `部分订阅源刷新失败：${sec.error}`)
          .fontSize(META).fontColor(INK3).width('100%')
          .padding({ left: 16, right: 16, top: 10, bottom: 6 })
      }
      ForEach(sec.items.slice(0, 12), (item: HotItem, index: number): void => {
        BoardHotItemRow({
          rankText: `${item.rank}`,
          title: item.displayTitle ?? item.title,
          heat: item.heat,
          isLast: index === Math.min(sec.items.length, 12) - 1,
          idx: index,
          onRowClick: (): void => {
            const hotspot: DeepReadHotspot = { title: item.title, source: sec.sourceName,
              rank: item.rank, url: /^https?:\/\//i.test(item.url) ? item.url : null, summary: item.summary };
            this.showTopicActions(item, /^https?:\/\//i.test(item.url) ? [item.url] : [],
              discoveryHotspotInputs([hotspot]));
          },
        })
      }, (item: HotItem): string => `${sec.sourceId}-${item.rank}`)
    }
  }

  build(): void {
    Stack() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      Column() {
      this.BoardHeader()

      // 综合热榜组标 + 右侧刷新(iOS hotTopicSection)
      Row() {
        Text('综合热榜')
          .fontSize(LABEL).fontWeight(600).lineHeight(TEXT_STYLES.meta.lineHeight)
          .fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK3).letterSpacing(0.4)
        Blank()
        if (getProductKind() === 'deepread') {
          Row() {
            Icon({ name: 'gear', iconSize: 15, color: INK3 })
          }
          .width(44).height(44).justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
          .accessibilityText('发现来源与关注设置')
          .onClick((): void => { router.pushUrl({ url: 'pages/DeepReadDiscoverySettingsPage' }); })
        }
        if (this.lastUpdatedAt > 0) {
          Text(this.timeAgo(this.lastUpdatedAt))
            .fontSize(META).fontColor(INK4).margin({ right: 4 })
        }
        Row() {
          Icon({ name: 'retry', iconSize: 15, color: INK3 })
        }
        .width(44).height(44).justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
        .opacity(this.loading ? 0.45 : 1)
        .enabled(!this.loading)
        .accessibilityText(this.loading ? '刷新中' : '刷新热榜')
        .onClick((): void => { this.loadHotlist(true); })
      }
      .width('100%').padding({ left: 16, right: 4, top: 10, bottom: 2 })
      .alignItems(VerticalAlign.Center)

      if (getProductKind() === 'deepread') {
        Row({ space: 12 }) {
          Text('随机阅读').fontSize(META).fontColor(ACCENT).textAlign(TextAlign.Center)
            .width(96).constraintSize({ minHeight: 44 }).backgroundColor(SURFACE2).borderRadius(10)
            .enabled(this.randomItems().length > 0).opacity(this.randomItems().length > 0 ? 1 : 0.45)
            .accessibilityText('从当前热点中随机选择并深度阅读')
            .onClick((): void => { this.randomRead(); })
          Text(this.randomItems().length > 0 ? '从当前热点中选一篇' : '暂无可阅读热点，刷新后再试')
            .fontSize(META).fontColor(INK3).layoutWeight(1)
      }.width('100%').padding({ left: 16, right: 16, bottom: 4 }).alignItems(VerticalAlign.Center)
      }

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

      if (this.hotlistNote.length > 0) {
        Text(this.hotlistNote).fontSize(META).fontColor(INK3).width('100%')
          .padding({ left: 16, right: 16, top: 8, bottom: 4 })
      }

      if (this.activityError.length > 0) {
        Text(this.activityError).fontSize(META).fontColor(ERROR).width('100%')
          .padding({ left: 16, right: 16, top: 8, bottom: 4 })
      }

      if (this.loading && this.topics.length === 0 && this.sections.length === 0 && this.activeRuns.length === 0) {
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
        // 热榜内容量小,Scroll+Column 顶对齐;List 会把单条 ListItem 拉伸到视口高(大空隙根因)
        Refresh({ refreshing: $$this.refreshing }) {
          Scroll() {
            Column() {
              if (getProductKind() === 'deepread') {
                ForEach(this.activeRuns, (run: DeepReadActiveRun): void => {
                  DeepReadNewsroomCard({ run: run, onOpen: (): void => { this.openRun(run); } })
                    .margin({ left: 16, right: 16, bottom: 12 })
              }, (run: DeepReadActiveRun): string => this.runKey(run))
              }
              if (this.topics.length > 0) {
                CardGroup({ radius: 18, stroke: true, content: (): void => { this.TopicsCardContent(); } })
                  .margin({ left: 16, right: 16 })
              }
              ForEach(this.sections, (sec: SourceSection): void => {
                PlainSection({ label: sec.sourceName })
                CardGroup({ radius: 18, stroke: true, content: (): void => { this.SourceCardContent(sec); } })
                  .margin({ left: 16, right: 16 })
              }, (sec: SourceSection): string => sec.sourceId)
            }
            .width('100%').alignItems(HorizontalAlign.Start)
          }
          .width('100%').height('100%').align(Alignment.TopStart)
        }
        .width('100%').layoutWeight(1).padding({ top: 8 })
        .onRefreshing((): void => {
          if (this.loading) this.refreshing = false;
          else this.loadHotlist(true);
        })
      }
      PageSpacer()
      }
      .width('100%').height('100%').backgroundColor(BG)
      // 条目操作 sheet(半模态 overlay);item 走 @Builder 参数快照——
      // 关闭动画把 sheetItem 置 null 的同一帧,树内不能再读 this.sheetItem.xxx(空指针崩溃根因)
      if (this.sheetItem !== null) {
        this.SheetOverlay(this.sheetItem)
      }
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%')
  }

  @Builder
  SheetOverlay(item: BoardHotActionItem): void {
    Column() {
      Blank()
        .onClick((): void => {
          animateTo({ duration: 200, curve: Curve.EaseIn }, (): void => {
            this.sheetItem = null;
          });
        })
      Column() {
        Text(item.displayTitle ?? item.title)
          .fontSize(BODY2).fontWeight(FontWeight.Medium).fontColor(INK)
          .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
          .width('100%').padding({ left: 20, right: 20, top: 18, bottom: 10 })
        this.SheetAction('深度阅读', 'book', 'read')
        this.SheetDivider()
        this.SheetAction('重新生成', 'retry', 'regenerate')
        this.SheetDivider()
        if (item.seedUrls.length > 0) {
          this.SheetAction('打开原文', 'link', 'original')
          this.SheetDivider()
        }
        this.SheetAction('分享', 'share', 'share')
      }
      .width('100%')
      .padding({ bottom: 12 })
      .backgroundColor(SURFACE)
      .borderRadius({ topLeft: 20, topRight: 20 })
      .shadow({ radius: 20, color: SHADOW_OVERLAY, offsetY: -4 })
      .transition(TransitionEffect.translate({ y: 160 })
        .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
    }
    .width('100%').height('100%')
    .backgroundColor(OVERLAY)
    .justifyContent(FlexAlign.End)
    .transition(TransitionEffect.OPACITY
      .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
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
      const it: BoardHotActionItem | null = this.sheetItem;
      animateTo({ duration: 200, curve: Curve.EaseIn }, (): void => {
        this.sheetItem = null;
      });
      if (it === null) return;
      if (action === 'read') this.openArticle(it, false);
      else if (action === 'regenerate') this.openArticle(it, true);
      else if (action === 'original') this.openOriginal(it.url);
      else this.shareTopic(it);
    })
  }
}
