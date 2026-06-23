// DeepReadArticlePage — 对照设计 §7.1 / §7.4
// 生成进度(phase + per-stage 状态)+ 文章渲染 + 取消/重试按钮
// 订阅 repository.observe(topicId)(1s 轮询),渲染 phase badge + 4 stage 卡 + 流式内容

import { router } from '@kit.ArkUI';
import type { DeepReadOutput, DeepReadGenerationStage } from '@amber/deepread-domain';
import { STAGE_ORDER, STAGE_LABELS, statusOf, isComplete, displayHeroImageUrl, displayHeroCaption } from '@amber/deepread-domain';
import { getScheduler, getRepository } from '../di/AppContainer.ets';
import { ArticleRenderer } from '../components/ArticleRenderer.ets';
import { StageProgressCard } from '../components/StageProgressCard.ets';

interface ArticlePageParams {
  topicId: string;
  title: string;
  seedUrl?: string;
}

@Entry
@Component
struct DeepReadArticlePage {
  @State topicId: string = '';
  @State title: string = '';
  @State seedUrl: string = '';
  @State output: DeepReadOutput | null = null;
  @State running: boolean = false;
  @State errorMsg: string = '';
  private unsubscribe: (() => void) | null = null;

  aboutToAppear(): void {
    const params = router.getParams() as ArticlePageParams;
    this.topicId = params.topicId;
    this.title = params.title;
    this.seedUrl = params.seedUrl ?? '';

    // 订阅 output 变化
    const repo = getRepository();
    this.unsubscribe = repo.observeDeepRead(this.topicId).subscribe((o: DeepReadOutput) => {
      this.output = o;
      this.running = o.generationPhase === 'COLLECTING' || o.generationPhase === 'PLANNING' || o.generationPhase === 'WRITING';
    });

    // 触发 run(若未完成)
    this.startRun();
  }

  aboutToDisappear(): void {
    if (this.unsubscribe !== null) {
      this.unsubscribe();
      this.unsubscribe = null;
    }
  }

  async startRun(): Promise<void> {
    this.errorMsg = '';
    const scheduler = getScheduler();
    const result = await scheduler.run(this.topicId, this.title, {
      force: false,
      seedUrl: this.seedUrl.length > 0 ? this.seedUrl : null,
    });
    if (!result.ok && result.error !== null) {
      this.errorMsg = result.error;
    }
  }

  async retryStage(stage: DeepReadGenerationStage): Promise<void> {
    const scheduler = getScheduler();
    await scheduler.runSection(this.topicId, this.title, stage, this.seedUrl.length > 0 ? this.seedUrl : null);
  }

  cancelRun(): void {
    getScheduler().abort(this.topicId);
  }

  build(): void {
    Column() {
      // 顶部导航
      Row() {
        Button('←').type(ButtonType.Text).onClick(() => router.back())
        Text(this.title).fontSize(18).fontWeight(FontWeight.Medium).layoutWeight(1).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        if (this.running) {
          Button('取消').type(ButtonType.Text).fontColor('#E53935').onClick(() => this.cancelRun())
        }
      }
      .width('100%').height(56).padding({ left: 12, right: 12 })

      // phase badge
      Text(this.output !== null ? this.output.generationPhase : 'IDLE')
        .fontSize(12)
        .fontColor(this.running ? '#1976D2' : '#666')
        .margin({ top: 4 })

      // 4 stage 进度卡
      if (this.output !== null) {
        ForEach(STAGE_ORDER, (stage: DeepReadGenerationStage) => {
          StageProgressCard({
            stage,
            status: statusOf(this.output as DeepReadOutput, stage),
            quality: (this.output as DeepReadOutput).sectionQualities[stage] ?? '',
            onRetry: () => this.retryStage(stage),
          })
        })
      }

      // 错误
      if (this.errorMsg.length > 0) {
        Text(this.errorMsg).fontSize(13).fontColor('#E53935').margin({ top: 8, left: 16, right: 16 })
      }

      // 文章渲染(滚动)
      if (this.output !== null && this.isRenderable(this.output)) {
        Scroll() {
          Column() {
            ArticleRenderer({ output: this.output as DeepReadOutput })
          }
          .width('100%')
        }
        .layoutWeight(1)
        .align(Alignment.TopStart)
      } else if (!this.running) {
        Column() {
          Text(isComplete(this.output as DeepReadOutput) ? '' : '等待生成…')
            .fontSize(14).fontColor('#999').margin({ top: 40 })
        }.layoutWeight(1)
      } else {
        Column() {
          // TODO(phase6): 流式追加(§7.4 — summary/timeline 边生成边显示)
          Text('正在深度阅读…').fontSize(14).fontColor('#1976D2').margin({ top: 40 })
          LoadingProgress().width(48).height(48).margin({ top: 12 })
        }.layoutWeight(1)
      }
    }
    .width('100%').height('100%')
  }

  // 是否值得渲染文章(有 hero 或 任一段落有内容)
  isRenderable(o: DeepReadOutput): boolean {
    if (displayHeroImageUrl(o) !== null) return true;
    if (o.summary.trim().length > 0) return true;
    if (o.timeline !== null && o.timeline.length > 0) return true;
    if (o.corePoints !== null && o.corePoints.length > 0) return true;
    if (o.analysis.coreDispute !== null && o.analysis.coreDispute.length > 0) return true;
    return o.extendedReading.length > 0;
  }
}
