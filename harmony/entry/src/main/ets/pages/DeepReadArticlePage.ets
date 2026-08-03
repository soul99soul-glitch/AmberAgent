// DeepReadArticlePage — 杂志风文章页(真实 run() 数据,非 mock)
// 对照 Android:点击热点 → 文章页触发 run() → 渲染真实 DeepReadOutput

import { router } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type {
  DeepReadOutput, RunManagerDeps, UIMessage, AiClient,
} from '@amber/deepread-domain';
import { run } from '@amber/deepread-domain';
import { getAppContainer, getNotifier, getPrefetcher,
  getDeepReadPlaybookRepository,
} from '../di/AppContainer.ets';
import { ArticleRenderer } from '../components/ArticleRenderer.ets';
import {
  BG, INK, INK2, INK3, LINE, ACCENT,
} from '../design/tokens.ets';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.Article';

interface ArticleParams {
  topicId: string;
  title: string;
}

interface GenTextParams { model: string; messages: UIMessage[]; }
class MockAiClient implements AiClient {
  async generateText(_p: GenTextParams): Promise<UIMessage[]> { return []; }
}

@Entry
@Component
struct DeepReadArticlePage {
  @State topicId: string = '';
  @State title: string = '';
  @State output: DeepReadOutput | null = null;
  @State running: boolean = true;
  @State errorMsg: string = '';
  @State currentStage: string = '';

  async aboutToAppear(): Promise<void> {
    const rawParams: object = router.getParams() as object;
    if (rawParams === null || rawParams === undefined) {
      hilog.error(DOMAIN, TAG, 'aboutToAppear: router.getParams() is null');
      this.errorMsg = '参数缺失';
      this.running = false;
      return;
    }
    const params: ArticleParams = rawParams as ArticleParams;
    this.topicId = params.topicId ?? '';
    this.title = params.title ?? '';
    hilog.info(DOMAIN, TAG, 'aboutToAppear: topicId=%{public}s title=%{public}s', this.topicId, this.title);
    await this.generateArticle();
  }

  async generateArticle(): Promise<void> {
    this.running = true;
    this.errorMsg = '';
    this.output = null;
    this.currentStage = '正在抓取来源…';
    const notifier = getNotifier();
    await notifier.notifyRunning(this.topicId, this.title);
    try {
      hilog.info(DOMAIN, TAG, 'generateArticle: start run()');
      // 用 AppContainer 注入的 aiClient(自动读 settings 里配的 API Key + Base URL)
      const container = getAppContainer();
      const aiClient = container.aiClient;
      const storage = container.storage;
      const model = await storage.get<string>('ai_model', 'deepseek-chat');
      const apiKey = await storage.get<string>('ai_api_key', '');
      hilog.info(DOMAIN, TAG, 'AI config: model=%{public}s hasKey=%{public}s', model, String(apiKey.length > 0));
      const prefetcher = getPrefetcher();
      // D-127:DeepReadAgentRunManager.kt:350 — 规划前读 playbook.markdown
      //   (此前硬编码 '' = 登记缺口,本切片闭环)
      const playbook = await getDeepReadPlaybookRepository().read();
      const deps: RunManagerDeps = {
        prefetcher: prefetcher,
        collectRun: async (messages: UIMessage[]): Promise<UIMessage[]> => {
          // 无 API key → 返回原 messages,走 fallbackPlan + writeFallbackSection
          return messages;
        },
        aiClient: aiClient,
        model: model,
        playbookMarkdown: playbook.markdown,
        nowIso: (): string => '2026-06-25',
      };
      const result = await run(deps, this.topicId, this.title, { force: true, seedUrl: null });
      hilog.info(DOMAIN, TAG, 'run() result: ok=%{public}s error=%{public}s',
        String(result.ok), result.error ?? 'null');
      if (result.ok) {
        this.output = result.output;
        this.currentStage = '完成';
        await notifier.notifyCompleted(this.topicId, this.title, true);
      } else {
        this.errorMsg = result.error ?? '生成失败';
        this.currentStage = '失败';
        await notifier.notifyFailed(this.topicId, this.title, result.error ?? '未知错误');
      }
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'generateArticle EXCEPTION: %{public}s', String(e).substring(0, 120));
      this.errorMsg = String(e).substring(0, 80);
      this.currentStage = '失败';
      await notifier.notifyFailed(this.topicId, this.title, String(e).substring(0, 60));
    } finally {
      this.running = false;
      await notifier.cancelRunning(this.topicId);
    }
  }

  build(): void {
    Column() {
      // ── 顶栏:返回 + 标题(衬线) ──
      Row() {
        Text('←').fontSize(18).fontColor(INK3)
          .onClick(() => { router.back(); })
        Text('深度阅读').fontSize(18).fontWeight(FontWeight.Bold).fontColor(INK)
          .fontFamily('serif').margin({ left: 12 }).layoutWeight(1)
      }
      .width('100%').height(56).padding({ left: 24, right: 24 })
      .backgroundColor(BG)

      // ── 内容区 ──
      if (this.running) {
        // 加载中:显示阶段进度(只保留中间一个 loading + 一个状态文字)
        Column() {
          LoadingProgress().width(40).height(40).color(ACCENT)
          Text(this.currentStage).fontSize(14).fontColor(INK2).margin({ top: 16 })
        }.width('100%').layoutWeight(1).justifyContent(FlexAlign.Center)
      } else if (this.errorMsg.length > 0) {
        // 错误状态
        Column() {
          Text('生成失败').fontSize(18).fontWeight(FontWeight.Medium).fontColor(INK2).margin({ top: 12 })
          Text(this.errorMsg).fontSize(13).fontColor(INK3).margin({ top: 6 })
          Text('重试').fontSize(15).fontColor(ACCENT).margin({ top: 16 })
            .onClick(() => { this.generateArticle(); })
        }.width('100%').layoutWeight(1).justifyContent(FlexAlign.Center)
      } else if (this.output !== null) {
        // 真实文章渲染 — ArticleRenderer 统一渲染全量块
        //   (Hero/summary-Markdown/keyEntities/timeline/corePoints/analysis/diagram/refs)
        Scroll() {
          Column() {
            // ── 标题:衬线大字(页级,非文章块) ──
            Text(this.title)
              .fontSize(24).fontWeight(FontWeight.Bold).fontColor(INK)
              .fontFamily('serif')
              .lineHeight(34).maxLines(3)

            // ── 状态 ──
            Text(this.currentStage).fontSize(12).fontColor(INK3).margin({ top: 10 })

            // ── 分隔线 ──
            Column() {}.height(1).width('100%').backgroundColor(LINE).margin({ top: 16, bottom: 4 })

            // ── 文章全量块(Hero/summary/entities/timeline/corePoints/analysis/diagram/refs) ──
            ArticleRenderer({ output: this.output })

            // 底部留白
            Column() {}.height(40)
          }
          .width('100%').padding({ left: 24, right: 24 })
        }
        .layoutWeight(1)
        .backgroundColor(BG)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
}
