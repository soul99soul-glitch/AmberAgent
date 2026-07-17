// DeepReadHomePage — 高端杂志排版(预置占位内容验证视觉)
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
  @State useMock: boolean = true;  // 演示模式:用预置内容
  private newsNow: NewsNowProvider | null = null;

  aboutToAppear(): void {
    if (this.useMock) {
      // 演示模式:直接加载预置内容
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
      // ── 顶栏:品牌(衬线大字) ──
      Row() {
        Text('DeepRead')
          .fontSize(28).fontWeight(FontWeight.Bold).fontColor(INK)
          .fontFamily('serif')
        Blank()
        Text('↻').fontSize(18).fontColor(INK3).margin({ right: 20 })
          .onClick(() => { this.loadHotlist(); })
        Text('⚙').fontSize(18).fontColor(INK3)
          .onClick(() => { router.pushUrl({ url: 'pages/HistoryPage' }); })
      }
      .width('100%').height(64).padding({ left: 28, right: 28 })
      .backgroundColor(BG)

      // ── 搜索框:白底大圆角 ──
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
      .width('92%').height(52).padding({ left: 18, right: 18 })
      .backgroundColor(SURFACE).borderRadius(14)
      .border({ width: 1, color: LINE })
      .margin({ bottom: 24 })

      // ── 运行状态 ──
      if (this.running) {
        Row() {
          LoadingProgress().width(14).height(14).color(ACCENT)
          Text(this.status).fontSize(13).fontColor(INK2).margin({ left: 8 })
        }.margin({ bottom: 16 })
      }

      // ── 内容区 ──
      if (this.loadState === 'loading') {
        Column() {
          LoadingProgress().width(40).height(40).color(ACCENT)
          Text('正在抓取热榜…').fontSize(13).fontColor(INK3).margin({ top: 12 })
        }.width('100%').layoutWeight(1).justifyContent(FlexAlign.Center)
      } else if (this.loadState === 'error') {
        Column() {
          Text('⚠️').fontSize(28)
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
              .width('100%').padding({ top: 24, bottom: 12 })
            }

            // 头条卡片:超大标题 + 衬线
            ListItem() {
              Column() {
                Text(this.combined[0].title)
                  .fontSize(22).fontWeight(FontWeight.Bold).fontColor(INK)
                  .fontFamily('serif')
                  .lineHeight(30).maxLines(3)
                Row() {
                  if (this.combined[0].heat.length > 0) {
                    Text(this.combined[0].heat).fontSize(12).fontColor(INK3)
                  }
                }.margin({ top: 10 })
              }
              .width('100%').padding({ top: 4, bottom: 20 })
              .border({ width: { bottom: 1 }, color: { bottom: LINE } })
              .onClick(() => { this.startDeepRead(this.combined[0].title, this.combined[0].url); })
            }

            // 第 2~7 条:紧凑列表(大留白)
            ForEach(this.combined.slice(1, 7), (item: HotItem, idx: number) => {
              ListItem() {
                Row() {
                  Text((idx + 2) < 10 ? '0' + (idx + 2).toString() : (idx + 2).toString())
                    .fontSize(14).fontWeight(FontWeight.Medium).fontColor(INK4)
                    .width(32).textAlign(TextAlign.Start)
                  Text(item.title)
                    .fontSize(15).fontColor(INK).layoutWeight(1).maxLines(1)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                    .lineHeight(22)
                  if (item.heat.length > 0) {
                    Text(item.heat).fontSize(11).fontColor(INK4).margin({ left: 12 })
                  }
                }
                .width('100%').padding({ top: 16, bottom: 16 })
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
                Text('//').fontSize(13).fontColor(ACCENT).fontWeight(FontWeight.Bold)
                Text(' ' + section.sourceName).fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK2)
                Blank()
                Text(section.items.length + ' 条').fontSize(11).fontColor(INK4)
              }
              .width('100%').padding({ top: 28, bottom: 12 })
            }

            // 源条目(前 5 条)
            ForEach(section.items.slice(0, 5), (item: HotItem, idx: number) => {
              ListItem() {
                Row() {
                  Text((idx + 1) < 10 ? '0' + (idx + 1).toString() : (idx + 1).toString())
                    .fontSize(14).fontColor(INK4).width(32)
                  Text(item.title)
                    .fontSize(15).fontColor(INK).layoutWeight(1).maxLines(1)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                    .lineHeight(22)
                  if (item.heat.length > 0) {
                    Text(item.heat).fontSize(11).fontColor(INK4).margin({ left: 12 })
                  }
                }
                .width('100%').padding({ top: 14, bottom: 14 })
                .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
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
                .width('100%').margin({ bottom: 12 })
                Text(this.articleText).fontSize(15).fontColor(INK2).lineHeight(24)
              }
              .width('100%').padding(20).backgroundColor(ACCENT_LIGHT).borderRadius(14).margin({ top: 8, bottom: 32 })
            }
          }
        }
        .width('100%').layoutWeight(1)
        .padding({ left: 24, right: 24 })
        .backgroundColor(BG)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
