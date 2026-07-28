// DeepReadHomePage — 高端杂志排版(细节精修版)
// 设计原则:大量留白 / 衬线标题 / 清晰层级 / hairline 节奏 / 铁锈橙 accent

import {
  createTopicIdWithRuntime, normalizeUrlArkTs, run,
} from '@amber/deepread-domain';
import type { TopicIdRuntime, RunManagerDeps, UIMessage, AiClient } from '@amber/deepread-domain';
import { getAppContainer, getNotifier, getPrefetcher } from '../di/AppContainer.ets';
import { createNewsNowProvider } from '../platform_impl/NewsNowProvider.ets';
import type { NewsNowProvider, HotItem, SourceSection } from '../platform_impl/NewsNowProvider.ets';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { router } from '@kit.ArkUI';
import {
  MOCK_COMBINED, MOCK_SECTIONS, MOCK_ARTICLE,
} from '../design/mock_data.ets';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.Index';

// ── 配色(照搬 Android AmberTokens AmberLight) ──
const BG: string = '#F4F2EC';
const SURFACE: string = '#FAF9F5';
const SURFACE2: string = '#EFECE4';
const INK: string = '#1B1A17';
const INK2: string = '#57544C';
const INK3: string = '#8F8B80';
const INK4: string = '#B6B1A4';
const LINE: string = '#E4E0D6';
const LINE2: string = '#D6D1C4';
const ACCENT: string = '#B8623A';
const ACCENT_LIGHT: string = '#FFF5F0';

const arktsTopicRuntime: TopicIdRuntime = {
  normalizeUrlInput: (url: string): string => normalizeUrlArkTs(url),
  sha256Hex32: (input: string): string => {
    let h = 0x811c9dc5;
    for (let i = 0; i < input.length; i++) {
      h ^= input.charCodeAt(i);
      h = (h + ((h << 1) + (h << 4) + (h << 7) + (h << 8) + (h << 24))) >>> 0;
    }
    const hex = h.toString(16).padStart(8, '0');
    return hex + hex + hex + hex;
  },
};
const deriveTopicIdArkTs = createTopicIdWithRuntime(arktsTopicRuntime);

interface GenTextParams { model: string; messages: UIMessage[]; }
class MockAiClient implements AiClient {
  async generateText(_p: GenTextParams): Promise<UIMessage[]> { return []; }
}

@Entry
@Component
struct DeepReadHomePage {
  @State title: string = '';
  @State status: string = '';
  @State running: boolean = false;
  @State articleText: string = '';
  @State sections: SourceSection[] = [];
  @State combined: HotItem[] = [];
  @State loadState: string = 'idle';
  @State errorMsg: string = '';
  @State lastUpdated: string = '';
  @State useMock: boolean = false;
  private newsNow: NewsNowProvider | null = null;

  aboutToAppear(): void {
    if (this.useMock) {
      this.combined = MOCK_COMBINED;
      this.sections = MOCK_SECTIONS;
      this.articleText = MOCK_ARTICLE;
      this.loadState = 'loaded';
    } else {
      this.loadHotlist();
    }
  }

  async loadHotlist(): Promise<void> {
    this.loadState = 'loading';
    this.errorMsg = '';
    try {
      hilog.info(DOMAIN, TAG, 'loadHotlist start');
      if (this.newsNow === null) {
        this.newsNow = createNewsNowProvider(getAppContainer().httpClient);
      }
      const all: SourceSection[] = await this.newsNow.fetchAllSources();
      hilog.info(DOMAIN, TAG, 'fetchAllSources done: %{public}d sections', all.length);
      // 过滤禁用的源(设置页开关)
      const storage = getAppContainer().storage;
      const enabledSections: SourceSection[] = [];
      for (let i = 0; i < all.length; i++) {
        const s: SourceSection = all[i];
        let enabled: string = 'true';
        try {
          enabled = await storage.get<string>('source_' + s.sourceId, 'true');
        } catch (_) {
          // storage 异常时默认启用
        }
        if (enabled === 'true' && s.items.length > 0) {
          enabledSections.push(s);
        }
      }
      hilog.info(DOMAIN, TAG, 'enabledSections: %{public}d', enabledSections.length);
      this.sections = enabledSections;
      this.combined = this.newsNow.buildCombinedTopics(all);
      hilog.info(DOMAIN, TAG, 'combined topics: %{public}d', this.combined.length);
      this.loadState = enabledSections.length > 0 ? 'loaded' : 'error';
      this.errorMsg = enabledSections.length === 0 ? '所有源抓取失败或全部禁用' : '';
      this.lastUpdated = new Date().toLocaleTimeString();
      hilog.info(DOMAIN, TAG, 'loadState=%{public}s', this.loadState);
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'loadHotlist EXCEPTION: %{public}s', String(e).substring(0, 120));
      this.loadState = 'error';
      this.errorMsg = String(e).substring(0, 60);
    }
  }

  async startDeepRead(topicTitle: string, seedUrl: string | null): Promise<void> {
    if (topicTitle.trim().length === 0 || this.running) return;
    // 跳转到独立文章阅读页(对照 Android:点击热点 → DeepReadArticlePage)
    const topicId = deriveTopicIdArkTs({ url: seedUrl, title: topicTitle });
    const notifier = getNotifier();
    await notifier.notifyRunning(topicId, topicTitle);
    const params: Record<string, string> = {};
    params['topicId'] = topicId;
    params['title'] = topicTitle;
    router.pushUrl({ url: 'pages/DeepReadArticlePage', params: params });
  }

  build(): void {
    Column() {
      // ── 顶栏:品牌(衬线大字) ──
      Row() {
        Text('DeepRead')
          .fontSize(26).fontWeight(FontWeight.Bold).fontColor(INK)
          .fontFamily('serif')
        Blank()
        Text('对话').fontSize(13).fontColor(ACCENT).margin({ right: 20 })
          .onClick(() => { router.pushUrl({ url: 'pages/ChatListPage' }); })
        Text('议会').fontSize(13).fontColor(ACCENT).margin({ right: 20 })
          .onClick(() => { router.pushUrl({ url: 'pages/CouncilPage' }); })
        Text('小说').fontSize(13).fontColor(ACCENT).margin({ right: 20 })
          .onClick(() => { router.pushUrl({ url: 'pages/NovelProjectsPage' }); })
        Text('刷新').fontSize(13).fontColor(INK3).margin({ right: 20 })
          .onClick(() => { this.loadHotlist(); })
        Text('设置').fontSize(13).fontColor(INK3)
          .onClick(() => { router.pushUrl({ url: 'pages/HistoryPage' }); })
      }
      .width('100%').height(56).padding({ left: 24, right: 24, top: 16 })
      .backgroundColor(BG)
      .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP])

      // ── 搜索框 ──
      Row() {
        TextInput({ text: this.title, placeholder: '搜索或输入话题…' })
          .layoutWeight(1).fontSize(15)
          .fontColor(INK).placeholderColor(INK4)
          .backgroundColor('rgba(0,0,0,0)')
          .onChange((v: string) => { this.title = v; })
          .onSubmit(() => {
            if (this.title.trim().length > 0 && !this.running) this.startDeepRead(this.title, null);
          })
      }
      .width('92%').height(48).padding({ left: 16, right: 16 })
      .backgroundColor(SURFACE).borderRadius(12)
      .border({ width: 1, color: LINE })
      .margin({ bottom: 16 })

      // ── 运行状态 ──
      if (this.running) {
        Row() {
          LoadingProgress().width(14).height(14).color(ACCENT)
          Text(this.status).fontSize(13).fontColor(INK2).margin({ left: 8 })
        }.margin({ bottom: 12 })
      }

      // ── 内容区 ──
      if (this.loadState === 'loading') {
        Column() {
          LoadingProgress().width(40).height(40).color(ACCENT)
          Text('正在抓取热榜…').fontSize(13).fontColor(INK3).margin({ top: 12 })
        }.width('100%').layoutWeight(1).justifyContent(FlexAlign.Center)
      } else if (this.loadState === 'error') {
        Column() {
          Text('抓取失败').fontSize(18).fontWeight(FontWeight.Medium).fontColor(INK2).margin({ top: 12 })
          Text(this.errorMsg).fontSize(13).fontColor(INK3).margin({ top: 6 })
          Text('重试').fontSize(15).fontColor(ACCENT).margin({ top: 16 })
            .onClick(() => { this.loadHotlist(); })
        }.width('100%').layoutWeight(1).justifyContent(FlexAlign.Center)
      } else {
        List({ space: 0 }) {
          // ── 综合热点:头条大图位 ──
          if (this.combined.length > 0) {
            // 栏目标题
            ListItem() {
              Row() {
                Text('//').fontSize(13).fontColor(ACCENT).fontWeight(FontWeight.Bold)
                Text(' 综合热点').fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK2)
                Blank()
                Text('9 源聚合').fontSize(11).fontColor(INK4)
              }
              .width('100%').padding({ top: 20, bottom: 10 })
              .transition(TransitionEffect.OPACITY.animation({ duration: 300, delay: 50, curve: Curve.EaseIn }))
            }

            // 头条:衬线大标题 + 热度
            ListItem() {
              Column() {
                Text(this.combined[0].title)
                  .fontSize(20).fontWeight(FontWeight.Bold).fontColor(INK)
                  .fontFamily('serif')
                  .lineHeight(28).maxLines(3)
                if (this.combined[0].heat.length > 0) {
                  Text(this.combined[0].heat).fontSize(12).fontColor(INK3).margin({ top: 8 })
                }
              }
              .width('100%').padding({ top: 4, bottom: 16 })
              .border({ width: { bottom: 1 }, color: { bottom: LINE } })
              .transition(TransitionEffect.OPACITY.animation({ duration: 300, delay: 50 }))
              .onClick(() => { this.startDeepRead(this.combined[0].title, this.combined[0].url); })
            }

            // 第 2~7 条:紧凑列表
            ForEach(this.combined.slice(1, 7), (item: HotItem, idx: number) => {
              ListItem() {
                Row() {
                  Text((idx + 2) < 10 ? '0' + (idx + 2).toString() : (idx + 2).toString())
                    .fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK4)
                    .width(28).textAlign(TextAlign.Start)
                  Text(item.title)
                    .fontSize(14).fontColor(INK).layoutWeight(1).maxLines(1)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                    .lineHeight(21)
                  if (item.heat.length > 0) {
                    Text(item.heat).fontSize(11).fontColor(INK4).margin({ left: 12 })
                  }
                }
                .width('100%').padding({ top: 14, bottom: 14 })
                .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
                .transition(TransitionEffect.OPACITY.animation({ duration: 300, delay: 100 + idx * 60, curve: Curve.EaseIn }))
                .transition(TransitionEffect.translate({ x: 20 }).animation({ duration: 300, delay: 100 + idx * 60, curve: Curve.EaseOut }))
                .onClick(() => { this.startDeepRead(item.title, item.url); })
              }
            })
          }

          // ── 分源列表 ──
          ForEach(this.sections, (section: SourceSection) => {
            // 栏目标题
            ListItem() {
              Row() {
                Text('//').fontSize(13).fontColor(ACCENT).fontWeight(FontWeight.Bold)
                Text(' ' + section.sourceName).fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK2)
                Blank()
                Text(section.items.length + ' 条').fontSize(11).fontColor(INK4)
              }
              .width('100%').padding({ top: 24, bottom: 10 })
              .transition(TransitionEffect.OPACITY.animation({ duration: 300, delay: 50 }))
            }

            // 源条目(前 5 条)
            ForEach(section.items.slice(0, 5), (item: HotItem, idx: number) => {
              ListItem() {
                Row() {
                  Text((idx + 1) < 10 ? '0' + (idx + 1).toString() : (idx + 1).toString())
                    .fontSize(13).fontColor(INK4).width(28)
                  Text(item.title)
                    .fontSize(14).fontColor(INK).layoutWeight(1).maxLines(1)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                    .lineHeight(21)
                  if (item.heat.length > 0) {
                    Text(item.heat).fontSize(11).fontColor(INK4).margin({ left: 12 })
                  }
                }
                .width('100%').padding({ top: 13, bottom: 13 })
                .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
                .transition(TransitionEffect.OPACITY.animation({ duration: 300, delay: 100 + idx * 50 }))
                .onClick(() => { this.startDeepRead(item.title, item.url); })
              }
            })
          })

          // ── 文章摘要:暖色底大卡片 ──
          if (this.articleText.length > 0) {
            ListItem() {
              Column() {
                Row() {
                  Text('//').fontSize(13).fontColor(ACCENT).fontWeight(FontWeight.Bold)
                  Text(' 文章').fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK2)
                }
                .width('100%').margin({ bottom: 10 })
                Text(this.articleText).fontSize(14).fontColor(INK2).lineHeight(22)
              }
              .width('100%').padding(18).backgroundColor(ACCENT_LIGHT).borderRadius(12).margin({ top: 8, bottom: 28 })
              .transition(TransitionEffect.OPACITY.animation({ duration: 400, delay: 200 }))
              .transition(TransitionEffect.translate({ y: 20 }).animation({ duration: 400, delay: 200, curve: Curve.EaseOut }))
            }
          }
        }
        .width('100%').layoutWeight(1)
        .padding({ left: 20, right: 20 })
        .backgroundColor(BG)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.BOTTOM])
  }
}
