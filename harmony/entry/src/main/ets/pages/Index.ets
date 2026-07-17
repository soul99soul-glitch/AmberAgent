// DeepReadHomePage — 杂志风热榜首页(设计系统驱动)
// 用 design/tokens + design/components 统一视觉规范

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
  BG, SURFACE, SURFACE2, INK, INK2, INK3, INK4, LINE, ACCENT, ACCENT_LIGHT,
  TITLE, BODY, META, LABEL, PAD_CARD, PAD_ITEM, RADIUS,
} from '../design/tokens.ets';
import { RubricHead, LeadCard, IndexRow, EmptyState, SkeletonRow } from '../design/components.ets';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.Index';

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
  private newsNow: NewsNowProvider | null = null;

  aboutToAppear(): void { this.loadHotlist(); }

  async loadHotlist(): Promise<void> {
    this.loadState = 'loading';
    this.errorMsg = '';
    try {
      if (this.newsNow === null) {
        this.newsNow = createNewsNowProvider(getAppContainer().httpClient);
      }
      const all: SourceSection[] = await this.newsNow.fetchAllSources();
      this.sections = all.filter((s: SourceSection): boolean => s.items.length > 0);
      this.combined = this.newsNow.buildCombinedTopics(all);
      this.loadState = this.sections.length > 0 ? 'loaded' : 'error';
      this.errorMsg = this.sections.length === 0 ? '所有源抓取失败' : '';
      this.lastUpdated = new Date().toLocaleTimeString();
    } catch (e) {
      this.loadState = 'error';
      this.errorMsg = String(e).substring(0, 60);
    }
  }

  async startDeepRead(topicTitle: string, seedUrl: string | null): Promise<void> {
    if (topicTitle.trim().length === 0 || this.running) return;
    this.running = true;
    this.status = topicTitle.substring(0, 20);
    this.articleText = '';
    const notifier = getNotifier();
    const topicId = deriveTopicIdArkTs({ url: seedUrl, title: topicTitle });
    await notifier.notifyRunning(topicId, topicTitle);
    try {
      const deps: RunManagerDeps = {
        prefetcher: getPrefetcher(),
        collectRun: async (m: UIMessage[]): Promise<UIMessage[]> => m,
        aiClient: new MockAiClient(),
        model: 'mock', playbookMarkdown: '', nowIso: (): string => '2026-06-25',
      };
      const result = await run(deps, topicId, topicTitle, { force: true, seedUrl });
      if (result.ok) {
        const o = result.output;
        this.articleText =
          (o.summary.length > 0 ? o.summary : '(待生成)') + '\n\n' +
          '时间线 ' + (o.timeline !== null ? o.timeline.length : 0) + ' 条 · ' +
          '扩展阅读 ' + o.extendedReading.length + ' 条';
        this.status = '✓ 完成';
      } else { this.status = '✗ ' + (result.error ?? ''); }
    } catch (e) { this.status = '✗ ' + String(e).substring(0, 30); }
    finally { await notifier.cancelRunning(topicId); this.running = false; }
  }

  build(): void {
    Column() {
      // ── 顶栏:品牌(衬线)+ 操作 ──
      Row() {
        Text('DeepRead')
          .fontSize(24).fontWeight(FontWeight.Bold).fontColor(INK)
          .fontFamily('serif')
        Blank()
        if (this.loadState === 'loading') {
          LoadingProgress().width(16).height(16).color(ACCENT).margin({ right: 20 })
        } else {
          Text('刷新').fontSize(13).fontColor(INK3).margin({ right: 20 })
            .onClick(() => { this.loadHotlist(); })
        }
        Text('设置').fontSize(13).fontColor(INK3)
          .onClick(() => { router.pushUrl({ url: 'pages/HistoryPage' }); })
      }
      .width('100%').height(56).padding({ left: 24, right: 24 })
      .backgroundColor(BG)

      // ── 搜索框:视觉主角 ──
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
      .width('92%').height(48).padding({ left: 14, right: 14 })
      .backgroundColor(SURFACE).borderRadius(RADIUS)
      .border({ width: 1, color: LINE })
      .margin({ bottom: 8 })

      // ── 运行状态 ──
      if (this.running) {
        Row() {
          LoadingProgress().width(12).height(12).color(ACCENT)
          Text(this.status).fontSize(META).fontColor(INK2).margin({ left: 6 })
        }.margin({ bottom: 8 })
      }

      // ── 内容区 ──
      if (this.loadState === 'loading') {
        Column() {
          ForEach([1, 2, 3, 4, 5], () => {
            SkeletonRow()
          })
        }
        .width('100%').layoutWeight(1)
        .padding({ left: 24, right: 24, top: 24 })
      } else if (this.loadState === 'error') {
        Column() {
          EmptyState({
            icon: '',
            title: '抓取失败',
            subtitle: 'NewsNow 热榜源暂时无法连接,请检查网络后重试',
            actionLabel: '重试',
            onAction: () => { this.loadHotlist(); },
          })
        }.width('100%').layoutWeight(1).justifyContent(FlexAlign.Center)
      } else {
        List({ space: 0 }) {
          // ── 综合热点 ──
          if (this.combined.length > 0) {
            ListItem() {
              RubricHead({ label: '综合热点', status: this.lastUpdated.length > 0 ? '更新于 ' + this.lastUpdated : '' })
            }
            // 头条卡片
            ListItem() {
              LeadCard({
                title: this.combined[0].title,
                heat: this.combined[0].heat,
                onTap: () => { this.startDeepRead(this.combined[0].title, this.combined[0].url); },
              })
            }
            // 第 2~8 条
            ForEach(this.combined.slice(1, 8), (item: HotItem, idx: number) => {
              ListItem() {
                IndexRow({
                  rank: idx + 2,
                  title: item.title,
                  heat: item.heat,
                  last: idx === 6,
                  onTap: () => { this.startDeepRead(item.title, item.url); },
                })
              }
            })
          }

          // ── 分源列表 ──
          ForEach(this.sections, (section: SourceSection) => {
            ListItem() {
              RubricHead({ label: section.sourceName, status: section.items.length + ' 条' })
            }
            ForEach(section.items.slice(0, 6), (item: HotItem, idx: number) => {
              ListItem() {
                IndexRow({
                  rank: idx + 1,
                  title: item.title,
                  heat: item.heat,
                  last: idx === 5,
                  onTap: () => { this.startDeepRead(item.title, item.url); },
                })
              }
            })
          })

          // ── 文章摘要 ──
          if (this.articleText.length > 0) {
            ListItem() {
              Column() {
                RubricHead({ label: '文章' })
                Text(this.articleText).fontSize(BODY).fontColor(INK2).lineHeight(22)
              }
              .width('100%').padding(PAD_CARD).backgroundColor(ACCENT_LIGHT).borderRadius(RADIUS).margin({ top: 8, bottom: 24 })
            }
          }
        }
        .width('100%').layoutWeight(1)
        .padding({ left: 20, right: 20 })
        .backgroundColor(BG)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
