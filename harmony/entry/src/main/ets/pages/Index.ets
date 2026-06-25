// DeepReadHomePage — 杂志风热榜首页
// 对照 Android AmberTokens 配色 + RubricHead/LeadStory/IndexRow 结构

import {
  createTopicIdWithRuntime, normalizeUrlArkTs, run,
} from '@amber/deepread-domain';
import type { TopicIdRuntime, RunManagerDeps, UIMessage, AiClient } from '@amber/deepread-domain';
import { getAppContainer, getNotifier, getPrefetcher } from '../di/AppContainer.ets';
import { createNewsNowProvider } from '../platform_impl/NewsNowProvider.ets';
import type { NewsNowProvider, HotItem, SourceSection } from '../platform_impl/NewsNowProvider.ets';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { router } from '@kit.ArkUI';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.Index';

// ── AmberTokens 配色(照搬 Android AmberLight) ──
const BG: string = '#F4F2EC';
const SURFACE: string = '#FAF9F5';
const SURFACE2: string = '#EFECE4';
const INK: string = '#1B1A17';
const INK2: string = '#57544C';
const INK3: string = '#8F8B80';
const INK4: string = '#B6B1A4';
const LINE: string = '#E4E0D6';
const ACCENT: string = '#B8623A';

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

function padRank(n: number): string {
  return n < 10 ? '0' + n.toString() : n.toString();
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
  private newsNow: NewsNowProvider | null = null;

  aboutToAppear(): void { this.loadHotlist(); }

  async loadHotlist(): Promise<void> {
    this.loadState = 'loading';
    try {
      if (this.newsNow === null) {
        this.newsNow = createNewsNowProvider(getAppContainer().httpClient);
      }
      const all: SourceSection[] = await this.newsNow.fetchAllSources();
      this.sections = all.filter((s: SourceSection): boolean => s.items.length > 0);
      this.combined = this.newsNow.buildCombinedTopics(all);
      this.loadState = this.sections.length > 0 ? 'loaded' : 'error';
      this.errorMsg = this.sections.length === 0 ? '所有源抓取失败' : '';
    } catch (e) {
      this.loadState = 'error';
      this.errorMsg = String(e).substring(0, 60);
    }
  }

  async startDeepRead(topicTitle: string, seedUrl: string | null): Promise<void> {
    if (topicTitle.trim().length === 0 || this.running) return;
    this.running = true;
    this.status = topicTitle.substring(0, 24);
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
        this.status = '✓';
      } else { this.status = '✗ ' + (result.error ?? ''); }
    } catch (e) { this.status = '✗ ' + String(e).substring(0, 30); }
    finally { await notifier.cancelRunning(topicId); this.running = false; }
  }

  build(): void {
    Column() {
      // ── 顶栏 ──
      Row() {
        Text('DeepRead').fontSize(22).fontWeight(FontWeight.Bold).fontColor(INK)
        Blank()
        if (this.loadState === 'loading') {
          LoadingProgress().width(16).height(16).color(ACCENT).margin({ right: 20 })
        } else {
          Text('↻').fontSize(18).fontColor(INK3).margin({ right: 20 })
            .onClick(() => { this.loadHotlist(); })
        }
        Text('⚙').fontSize(16).fontColor(INK3)
          .onClick(() => { router.pushUrl({ url: 'pages/HistoryPage' }); })
      }
      .width('100%').height(52).padding({ left: 20, right: 20 })
      .backgroundColor(BG)

      // ── 搜索框 ──
      Row() {
        TextInput({ text: this.title, placeholder: '搜索或输入话题…' })
          .layoutWeight(1).height(42).fontSize(14)
          .fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(10)
          .onChange((v: string) => { this.title = v; })
          .onSubmit(() => {
            if (this.title.trim().length > 0 && !this.running) this.startDeepRead(this.title, null);
          })
      }
      .width('92%').margin({ bottom: 6 })

      if (this.running) {
        Row() {
          LoadingProgress().width(12).height(12).color(ACCENT)
          Text(this.status).fontSize(12).fontColor(INK2).margin({ left: 6 })
        }.margin({ bottom: 6 })
      }

      // ── 内容区 ──
      if (this.loadState === 'loading') {
        Column() { LoadingProgress().width(36).height(36).color(ACCENT) }
          .width('100%').layoutWeight(1).justifyContent(FlexAlign.Center)
      } else if (this.loadState === 'error') {
        Column() {
          Text('⚠ ' + this.errorMsg).fontSize(13).fontColor(INK3)
          Text('重试').fontSize(13).fontColor(ACCENT).margin({ top: 10 })
            .onClick(() => { this.loadHotlist(); })
        }.width('100%').layoutWeight(1).justifyContent(FlexAlign.Center)
      } else {
        List({ space: 0 }) {
          // ── 综合热点 ──
          if (this.combined.length > 0) {
            ListItem() {
              Row() {
                Text('//').fontSize(12).fontColor(ACCENT)
                Text(' 综合热点').fontSize(12).fontWeight(FontWeight.Medium).fontColor(INK2)
                Blank()
                Text('9 源聚合').fontSize(10).fontColor(INK4)
              }
              .width('100%').padding({ top: 20, bottom: 8 })
            }

            // 第 1 条 = LeadStory(大标题)
            ListItem() {
              Row() {
                Text(padRank(1)).fontSize(28).fontWeight(FontWeight.Bold).fontColor(ACCENT)
                  .width(44).textAlign(TextAlign.Start)
                Column() {
                  Text(this.combined[0].title)
                    .fontSize(17).fontWeight(FontWeight.Bold).fontColor(INK)
                    .maxLines(2).lineHeight(24)
                  Row() {
                    if (this.combined[0].heat.length > 0) {
                      Text(this.combined[0].heat).fontSize(10).fontColor(INK4)
                    }
                  }.margin({ top: 4 })
                }
                .layoutWeight(1).alignItems(HorizontalAlign.Start).margin({ left: 10 })
              }
              .width('100%').padding({ top: 8, bottom: 14 })
              .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
              .onClick(() => { this.startDeepRead(this.combined[0].title, this.combined[0].url); })
            }

            // 第 2~10 条 = IndexRow(紧凑列表)
            ForEach(this.combined.slice(1, 10), (item: HotItem, idx: number) => {
              ListItem() {
                Row() {
                  Text(padRank(idx + 2)).fontSize(12).fontWeight(FontWeight.Medium).fontColor(INK4)
                    .width(24)
                  Text(item.title)
                    .fontSize(14).fontColor(INK).layoutWeight(1).maxLines(1)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                  if (item.heat.length > 0) {
                    Text(item.heat).fontSize(10).fontColor(INK4).margin({ left: 6 })
                  }
                }
                .width('100%').padding({ top: 12, bottom: 12 })
                .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
                .onClick(() => { this.startDeepRead(item.title, item.url); })
              }
            })
          }

          // ── 分源列表 ──
          ForEach(this.sections, (section: SourceSection) => {
            // 栏目标题
            ListItem() {
              Row() {
                Text('//').fontSize(12).fontColor(ACCENT)
                Text(' ' + section.sourceName).fontSize(12).fontWeight(FontWeight.Medium).fontColor(INK2)
              }
              .width('100%').padding({ top: 22, bottom: 6 })
            }

            // 源条目(前 6 条)
            ForEach(section.items.slice(0, 6), (item: HotItem, idx: number) => {
              ListItem() {
                Row() {
                  Text(padRank(idx + 1)).fontSize(12).fontColor(INK4).width(24)
                  Text(item.title)
                    .fontSize(14).fontColor(INK).layoutWeight(1).maxLines(1)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                  if (item.heat.length > 0) {
                    Text(item.heat).fontSize(10).fontColor(INK4).margin({ left: 6 })
                  }
                }
                .width('100%').padding({ top: 11, bottom: 11 })
                .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
                .onClick(() => { this.startDeepRead(item.title, item.url); })
              }
            })
          })

          // ── 文章摘要 ──
          if (this.articleText.length > 0) {
            ListItem() {
              Column() {
                Text('//').fontSize(12).fontColor(ACCENT)
                Text(' 文章').fontSize(12).fontWeight(FontWeight.Medium).fontColor(INK2).margin({ bottom: 8 })
                Text(this.articleText).fontSize(14).fontColor(INK2).lineHeight(22)
              }
              .width('100%').padding(16).backgroundColor(SURFACE).borderRadius(10).margin({ top: 16, bottom: 20 })
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
