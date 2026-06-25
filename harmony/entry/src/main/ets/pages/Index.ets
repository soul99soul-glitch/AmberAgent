// DeepReadHomePage — 首页热榜(对照 Android BoardPage.HotListTab)
// 综合热点 + 分源列表(知乎/微博/抖音…)+ 点击 Deep Read + 手动输入

import {
  createTopicIdWithRuntime, normalizeUrlArkTs, run,
} from '@amber/deepread-domain';
import type { TopicIdRuntime, RunManagerDeps, DeepReadSource, UIMessage, AiClient } from '@amber/deepread-domain';
import { getAppContainer, getNotifier, getPrefetcher } from '../di/AppContainer.ets';
import { createNewsNowProvider } from '../platform_impl/NewsNowProvider.ets';
import type { NewsNowProvider, HotItem, SourceSection } from '../platform_impl/NewsNowProvider.ets';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { router } from '@kit.ArkUI';

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

interface GenTextParams {
  model: string;
  messages: UIMessage[];
}
class MockAiClient implements AiClient {
  async generateText(_params: GenTextParams): Promise<UIMessage[]> { return []; }
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
  @State loadState: string = 'idle';  // idle | loading | loaded | error
  @State errorMsg: string = '';
  private newsNow: NewsNowProvider | null = null;

  aboutToAppear(): void {
    this.loadHotlist();
  }

  async loadHotlist(): Promise<void> {
    this.loadState = 'loading';
    this.errorMsg = '';
    try {
      if (this.newsNow === null) {
        const container = getAppContainer();
        this.newsNow = createNewsNowProvider(container.httpClient);
      }
      const allSections: SourceSection[] = await this.newsNow.fetchAllSources();
      this.sections = allSections.filter((s: SourceSection): boolean => s.items.length > 0);
      this.combined = this.newsNow.buildCombinedTopics(allSections);
      if (this.sections.length > 0) {
        this.loadState = 'loaded';
      } else {
        this.loadState = 'error';
        this.errorMsg = '所有源都抓取失败,可能网络不通';
      }
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'loadHotlist: %{public}s', String(e));
      this.loadState = 'error';
      this.errorMsg = String(e).substring(0, 80);
    }
  }

  async startDeepRead(topicTitle: string, seedUrl: string | null): Promise<void> {
    if (topicTitle.trim().length === 0 || this.running) return;
    this.running = true;
    this.status = '正在生成: ' + topicTitle.substring(0, 20);
    this.articleText = '';
    const notifier = getNotifier();
    const topicId = deriveTopicIdArkTs({ url: seedUrl, title: topicTitle });
    await notifier.notifyRunning(topicId, topicTitle);
    try {
      const mockAi: AiClient = new MockAiClient();
      const deps: RunManagerDeps = {
        prefetcher: getPrefetcher(),
        collectRun: async (messages: UIMessage[]): Promise<UIMessage[]> => { return messages; },
        aiClient: mockAi,
        model: 'mock',
        playbookMarkdown: '',
        nowIso: (): string => '2026-06-25',
      };
      const result = await run(deps, topicId, topicTitle, { force: true, seedUrl: seedUrl });
      if (result.ok) {
        const out = result.output;
        this.articleText = `概览: ${out.summary.length > 0 ? out.summary : '(待生成)'}\n\n` +
          `时间线: ${out.timeline !== null ? out.timeline.length + ' 条' : '无'}\n` +
          `扩展阅读: ${out.extendedReading.length} 条\n` +
          `阶段: ${out.sectionStates.OVERVIEW?.status ?? '无'}`;
        this.status = '✓ 完成';
      } else {
        this.status = '✗ ' + (result.error ?? '未知错误');
      }
    } catch (e) {
      this.status = '✗ ' + String(e).substring(0, 40);
    } finally {
      await notifier.cancelRunning(topicId);
      this.running = false;
    }
  }

  @Builder
  SectionHeader(title: string): void {
    Text(title)
      .fontSize(15).fontWeight(FontWeight.Bold).fontColor('#333')
      .margin({ top: 16, bottom: 4 }).width('100%')
  }

  build(): void {
    Column() {
      // 顶栏:标题(左)+ 刷新 + 设置(右)
      Row() {
        Text('深度阅读').fontSize(20).fontWeight(FontWeight.Bold)
        Blank()
        if (this.loadState === 'loading') {
          LoadingProgress().width(16).height(16).margin({ right: 16 })
        } else {
          Text('刷新').fontSize(13).fontColor('#1976D2')
            .margin({ right: 16 })
            .onClick(() => { this.loadHotlist(); })
        }
        Text('设置').fontSize(13).fontColor('#1976D2')
          .onClick(() => { router.pushUrl({ url: 'pages/HistoryPage' }); })
      }
      .width('100%').height(52).padding({ left: 16, right: 16 })

      // 手动输入
      Row() {
        TextInput({ text: this.title, placeholder: '输入话题开始深读' })
          .layoutWeight(1).height(40).fontSize(14)
          .backgroundColor('#F5F5F5').borderRadius(20)
          .onChange((v: string) => { this.title = v; })
          .onSubmit(() => {
            if (this.title.trim().length > 0 && !this.running) {
              this.startDeepRead(this.title, null);
            }
          })
      }
      .width('92%').margin({ bottom: 8 })

      // 运行状态
      if (this.running || this.articleText.length > 0) {
        Text(this.status).fontSize(11).fontColor('#1976D2').margin({ bottom: 4 })
      }

      // 主内容区
      if (this.loadState === 'loading') {
        Column() {
          LoadingProgress().width(40).height(40)
          Text('正在抓取热榜…').fontSize(12).fontColor('#999').margin({ top: 8 })
        }.width('100%').layoutWeight(1).justifyContent(FlexAlign.Center)
      } else if (this.loadState === 'error') {
        Column() {
          Text('⚠️ ' + this.errorMsg).fontSize(12).fontColor('#E53935').textAlign(TextAlign.Center)
          Text('请检查网络后刷新').fontSize(11).fontColor('#999').margin({ top: 8 })
          Button('重试').fontSize(12).margin({ top: 12 }).onClick(() => { this.loadHotlist(); })
        }.width('92%').layoutWeight(1).justifyContent(FlexAlign.Center)
      } else {
        // 热榜列表
        List({ space: 0 }) {
          // 综合热点
          if (this.combined.length > 0) {
            ListItem() {
              Text('🔥 综合热点').fontSize(15).fontWeight(FontWeight.Bold).margin({ top: 4 })
            }

            ForEach(this.combined.slice(0, 10), (item: HotItem) => {
              ListItem() {
                Row() {
                  Text(item.title.length > 0 ? item.title : '(无标题)')
                    .fontSize(13).layoutWeight(1).maxLines(1)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                  if (item.heat.length > 0) {
                    Text(item.heat).fontSize(10).fontColor('#999').margin({ left: 4 })
                  }
                }
                .width('100%').padding({ top: 10, bottom: 10 })
                .onClick(() => { this.startDeepRead(item.title, item.url); })
              }
            })
          }

          // 分源列表
          ForEach(this.sections, (section: SourceSection) => {
            ListItem() {
              Text('📋 ' + section.sourceName).fontSize(15).fontWeight(FontWeight.Bold)
                .margin({ top: 12 }).width('100%')
            }

            ForEach(section.items.slice(0, 8), (item: HotItem) => {
              ListItem() {
                Row() {
                  Text(item.rank + '.')
                    .fontSize(12).fontColor(item.rank <= 3 ? '#E53935' : '#999').width(24)
                  Text(item.title)
                    .fontSize(12).layoutWeight(1).maxLines(1)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                  if (item.heat.length > 0) {
                    Text(item.heat).fontSize(9).fontColor('#999').margin({ left: 4 })
                  }
                }
                .width('100%').padding({ top: 8, bottom: 8 })
                .onClick(() => { this.startDeepRead(item.title, item.url); })
              }
            })
          })

          // 文章渲染
          if (this.articleText.length > 0) {
            ListItem() {
              Column() {
                Text('📄 文章').fontSize(14).fontWeight(FontWeight.Bold).margin({ bottom: 6 })
                Text(this.articleText).fontSize(12).lineHeight(20)
              }
              .width('100%').padding(12).backgroundColor('#F5F5F5').borderRadius(8).margin({ top: 12 })
            }
          }
        }
        .width('100%').layoutWeight(1)
        .padding({ left: 16, right: 16 })
        .divider({ strokeWidth: 0.5, color: '#F0F0F0', startMargin: 0, endMargin: 0 })
      }
    }
    .width('100%').height('100%')
  }
}
