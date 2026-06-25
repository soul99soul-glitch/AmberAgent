// DeepReadHomePage — 首页热榜(对照 Android BoardPage.HotListTab)
// 综合热点 + 分源列表(知乎/微博/抖音…) + 点击 Deep Read + 手动输入框

import {
  createTopicIdWithRuntime, normalizeUrlArkTs, run,
} from '@amber/deepread-domain';
import type { TopicIdRuntime, RunManagerDeps, DeepReadOutput, DeepReadSource, UIMessage, AiClient } from '@amber/deepread-domain';
import { getAppContainer, getNotifier, getPrefetcher } from '../di/AppContainer.ets';
import { createNewsNowProvider } from '../platform_impl/NewsNowProvider.ets';
import type { NewsNowProvider, HotItem, SourceSection } from '../platform_impl/NewsNowProvider.ets';
import { promptAction } from '@kit.ArkUI';
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

// generateText 参数
interface GenTextParams {
  model: string;
  messages: UIMessage[];
  tools?: object[];
  maxSteps?: number;
  stream?: boolean;
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
  @State loadingHotlist: boolean = false;
  private newsNow: NewsNowProvider | null = null;

  aboutToAppear(): void {
    this.loadHotlist();
  }

  async loadHotlist(): Promise<void> {
    this.loadingHotlist = true;
    try {
      if (this.newsNow === null) {
        const container = getAppContainer();
        this.newsNow = createNewsNowProvider(container.httpClient);
      }
      const allSections: SourceSection[] = await this.newsNow.fetchAllSources();
      this.sections = allSections;
      this.combined = this.newsNow.buildCombinedTopics(allSections);
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'loadHotlist: %{public}s', String(e));
      this.status = '热榜加载失败';
    } finally {
      this.loadingHotlist = false;
    }
  }

  // 点击热榜条目 → Deep Read
  async startDeepRead(topicTitle: string, seedUrl: string | null): Promise<void> {
    if (topicTitle.trim().length === 0) return;
    this.running = true;
    this.status = '正在生成深度阅读…';
    this.articleText = '';
    const notifier = getNotifier();
    const topicId = deriveTopicIdArkTs({ url: seedUrl, title: topicTitle });
    await notifier.notifyRunning(topicId, topicTitle);
    try {
      const mockAi: AiClient = new MockAiClient();
      const prefetcher = getPrefetcher();
      const deps: RunManagerDeps = {
        prefetcher: prefetcher,
        collectRun: async (messages: UIMessage[]): Promise<UIMessage[]> => { return messages; },
        aiClient: mockAi,
        model: 'mock',
        playbookMarkdown: '',
        nowIso: (): string => '2026-06-24',
      };
      const result = await run(deps, topicId, topicTitle, { force: true, seedUrl: seedUrl });
      if (result.ok) {
        const out = result.output;
        const summary: string = out.summary.length > 0 ? out.summary : '(概览待生成)';
        this.articleText = `概览: ${summary}\n\n` +
          `时间线: ${out.timeline !== null ? out.timeline.length + ' 条' : '无'}\n` +
          `核心要点: ${out.corePoints !== null ? out.corePoints.length + ' 条' : '无'}\n` +
          `分析: ${out.analysis.coreDispute !== null ? out.analysis.coreDispute.substring(0, 60) : '无'}\n` +
          `扩展阅读: ${out.extendedReading.length} 条\n` +
          `OVERVIEW: ${out.sectionStates.OVERVIEW?.status ?? '无'}`;
        this.status = `完成 ✓`;
        await notifier.notifyCompleted(topicId, topicTitle, true);
      } else {
        this.status = `失败: ${result.error ?? '未知'}`;
        await notifier.notifyFailed(topicId, topicTitle, result.error ?? '未知');
      }
    } catch (e) {
      this.status = `出错: ${String(e).substring(0, 60)}`;
      await notifier.notifyFailed(topicId, topicTitle, String(e).substring(0, 60));
    } finally {
      await notifier.cancelRunning(topicId);
      this.running = false;
    }
  }

  build(): void {
    Column() {
      // 顶栏
      Row() {
        Text('深度阅读').fontSize(22).fontWeight(FontWeight.Bold).layoutWeight(1)
        if (this.loadingHotlist) {
          LoadingProgress().width(20).height(20)
        } else {
          Button('刷新').fontSize(12).onClick(() => { this.loadHotlist(); })
        }
      }
      .width('100%').height(48).padding({ left: 16, right: 16 })

      // 手动输入
      Row() {
        TextInput({ text: this.title, placeholder: '输入话题开始深读' })
          .layoutWeight(1).height(40).fontSize(13)
          .onChange((v: string) => { this.title = v; })
        Button('深读').fontSize(12).margin({ left: 8 })
          .enabled(this.title.trim().length > 0 && !this.running)
          .onClick(() => { this.startDeepRead(this.title, null); })
      }
      .width('92%').margin({ bottom: 8 })

      if (this.running) {
        Text(this.status).fontSize(12).fontColor('#1976D2').margin({ bottom: 4 })
      }

      // 热榜列表(滚动)
      Scroll() {
        Column() {
          // 综合热点
          if (this.combined.length > 0) {
            Text('综合热点').fontSize(16).fontWeight(FontWeight.Bold)
              .margin({ top: 8, bottom: 6, left: 4 }).width('100%')
            ForEach(this.combined.slice(0, 10), (item: HotItem, idx: number) => {
              Row() {
                Text(`${idx + 1}`).fontSize(14).fontColor(idx < 3 ? '#E53935' : '#999').width(28)
                Text(item.title).fontSize(13).layoutWeight(1).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                if (item.heat.length > 0) {
                  Text(item.heat).fontSize(10).fontColor('#999')
                }
              }
              .width('100%').padding({ top: 8, bottom: 8 })
              .onClick(() => { this.startDeepRead(item.title, item.url); })
            })
          }

          // 分源列表
          ForEach(this.sections, (section: SourceSection) => {
            if (section.items.length > 0) {
              // 源标题
              Text(section.sourceName).fontSize(16).fontWeight(FontWeight.Bold)
                .margin({ top: 16, bottom: 6, left: 4 }).width('100%')
              // 源条目
              ForEach(section.items.slice(0, 8), (item: HotItem, idx: number) => {
                Row() {
                  Text(`${item.rank}`).fontSize(13).fontColor(idx < 3 ? '#E53935' : '#999').width(28)
                  Text(item.title).fontSize(12).layoutWeight(1).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                  if (item.heat.length > 0) {
                    Text(item.heat).fontSize(9).fontColor('#999')
                  }
                }
                .width('100%').padding({ top: 6, bottom: 6 })
                .onClick(() => { this.startDeepRead(item.title, item.url); })
              })
            }
          })

          // 文章渲染
          if (this.articleText.length > 0) {
            Text('—— 文章 ——').fontSize(14).fontWeight(FontWeight.Bold)
              .margin({ top: 20, bottom: 8 }).width('100%')
            Text(this.articleText).fontSize(12).lineHeight(20).padding(12)
              .backgroundColor('#F5F5F5').borderRadius(8).width('100%')
          }

          // 空状态
          if (!this.loadingHotlist && this.sections.length === 0) {
            Text('暂无热榜数据').fontSize(13).fontColor('#999').margin({ top: 60 })
          }
        }
        .width('92%').padding({ bottom: 40 })
      }
      .layoutWeight(1)
    }
    .width('100%').height('100%')
  }
}
