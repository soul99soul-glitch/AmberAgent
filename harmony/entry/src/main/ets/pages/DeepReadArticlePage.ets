// DeepReadArticlePage — 杂志风文章页(真实 run() 数据,非 mock)
// 对照 Android:点击热点 → 文章页触发 run() → 渲染真实 DeepReadOutput

import { router } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type {
  DeepReadOutput, DeepReadGenerationStage, ReadingLink,
  RunManagerDeps, UIMessage, AiClient, DeepReadSource,
} from '@amber/deepread-domain';
import {
  STAGE_ORDER, STAGE_LABELS, statusOf, displayHeroImageUrl, run,
} from '@amber/deepread-domain';
import { getAppContainer, getNotifier, getPrefetcher,
  getDeepReadPlaybookRepository,
} from '../di/AppContainer.ets';
import {
  BG, SURFACE, SURFACE2, INK, INK2, INK3, INK4, LINE, ACCENT, ACCENT_LIGHT,
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

  // 从 DeepReadOutput 提取 timeline 文本(事件描述)
  private timelineText(o: DeepReadOutput): string[] {
    if (o.timeline === null || o.timeline.length === 0) return [];
    return o.timeline.map((e) => e.event);
  }

  // 从 DeepReadOutput 提取 corePoints 文本(point 描述)
  private keyPointsText(o: DeepReadOutput): string[] {
    if (o.corePoints === null || o.corePoints.length === 0) return [];
    return o.corePoints.map((p) => p.point);
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
        // 真实文章渲染
        Scroll() {
          Column() {
            // ── 标题:衬线大字 ──
            Text(this.title)
              .fontSize(24).fontWeight(FontWeight.Bold).fontColor(INK)
              .transition(TransitionEffect.OPACITY.animation({ duration: 400, curve: Curve.EaseIn }))
              .fontFamily('serif')
              .lineHeight(34).maxLines(3)

            // ── 状态 ──
            Text(this.currentStage).fontSize(12).fontColor(INK3).margin({ top: 10 })

            // ── 分隔线 ──
            Column() {}.height(1).width('100%').backgroundColor(LINE).margin({ top: 16, bottom: 20 })

            // ── 概览 summary ──
            if (this.output.summary.length > 0) {
              Text(this.output.summary)
                .transition(TransitionEffect.OPACITY.animation({ duration: 300, curve: Curve.EaseIn }))
                .fontSize(16).fontColor(INK2)
                .fontFamily('serif')
                .lineHeight(26)
                .margin({ bottom: 24 })
            }

            // ── 时间线 ──
            if (this.timelineText(this.output).length > 0) {
              Row() {
                Text('//').fontSize(13).fontColor(ACCENT).fontWeight(FontWeight.Bold)
                Text(' 时间线').fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK2)
              }
              .width('100%').margin({ bottom: 10 })
              .transition(TransitionEffect.OPACITY.animation({ duration: 300, delay: 100, curve: Curve.EaseIn }))

              ForEach(this.timelineText(this.output), (item: string, idx: number) => {
                Row() {
                  Text((idx + 1) + '.').fontSize(13).fontColor(INK4).width(28)
                  Text(item).fontSize(14).fontColor(INK2).layoutWeight(1)
                }
                .width('100%').padding({ top: 10, bottom: 10 })
                .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
              })
            }

            // ── 核心要点 ──
            if (this.keyPointsText(this.output).length > 0) {
              Row() {
                Text('//').fontSize(13).fontColor(ACCENT).fontWeight(FontWeight.Bold)
                Text(' 核心要点').fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK2)
              }
              .width('100%').margin({ top: 24, bottom: 10 })

              ForEach(this.keyPointsText(this.output), (point: string, idx: number) => {
                Row() {
                  Text((idx + 1) + '.').fontSize(13).fontColor(INK4).width(28)
                  Text(point).fontSize(14).fontColor(INK2).layoutWeight(1)
                }
                .width('100%').padding({ top: 10, bottom: 10 })
                .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
              })
            }

            // ── 深度分析 ──
            if (this.output.analysis.coreDispute !== null && this.output.analysis.coreDispute.length > 0) {
              Row() {
                Text('//').fontSize(13).fontColor(ACCENT).fontWeight(FontWeight.Bold)
                Text(' 核心分歧').fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK2)
              }
              .width('100%').margin({ top: 24, bottom: 10 })

              Text(this.output.analysis.coreDispute)
                .fontSize(15).fontColor(INK2)
                .fontFamily('serif')
                .lineHeight(24)
            }

            if (this.output.analysis.implications !== null && this.output.analysis.implications.length > 0) {
              Row() {
                Text('//').fontSize(13).fontColor(ACCENT).fontWeight(FontWeight.Bold)
                Text(' 影响分析').fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK2)
              }
              .width('100%').margin({ top: 24, bottom: 10 })

              Text(this.output.analysis.implications)
                .fontSize(15).fontColor(INK2)
                .fontFamily('serif')
                .lineHeight(24)
            }

            // ── 扩展阅读 ──
            if (this.output.extendedReading.length > 0) {
              Row() {
                Text('//').fontSize(13).fontColor(ACCENT).fontWeight(FontWeight.Bold)
                Text(' 扩展阅读').fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK2)
              }
              .width('100%').margin({ top: 24, bottom: 10 })

              ForEach(this.output.extendedReading, (ref: ReadingLink) => {
                Row() {
                  Text(ref.title)
                    .fontSize(14).fontColor(ACCENT).layoutWeight(1).maxLines(1)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                  Text(ref.source ?? '').fontSize(11).fontColor(INK4).margin({ left: 8 })
                }
                .width('100%').padding({ top: 10, bottom: 10 })
                .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
              })
            }

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
