// DeepReadArticlePage — 杂志风文章页(真实 run() 数据,非 mock)
// 对照 Android:点击热点 → 文章页触发 run() → 渲染真实 DeepReadOutput
//
// G5-S1 增强(对照 Android DeepReadScreen.kt):
//   - RunningStageNotice:生成中显示当前阶段 + 进度(314-318)
//   - DeepReadPartialErrorNotice:部分 stage 失败但已有基础稿(318-323)
//   - TemplateFallbackNotice:模板降级提示(329-333)
//   - 确认对话框:重新生成前确认覆盖(DeepReadConfirmation 组件)
//   - 字号缩放:标题栏 zoom in/out 按钮(对齐 Android deepReadFontScale)
//
// 保留原有 run() 逻辑,仅增强 UI 层。

import { router } from '@kit.ArkUI';
import { Icon } from '../components/ui/Icon.ets';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type {
  DeepReadCacheEntry, DeepReadGenerationStage, DeepReadImageAsset, DeepReadOutput, DeepReadScheduler, RunResult,
} from '@amber/deepread-domain';
import { displayHeroImageUrl, firstFailureMessage, isComplete, STAGE_ORDER } from '@amber/deepread-domain';
import { MarkdownText } from '../components/MarkdownText.ets';
import { FONT_SERIF } from '../design/fonts.ets';
import { DeepReadConfirmationDialog } from '../components/DeepReadConfirmationDialog.ets';
import { getDeepReadScheduler, getRepository } from '../di/AppContainer.ets';
import {
  BG, INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, ACCENT, ACCENT_LIGHT,
  BODY, META, TITLE, ERROR,
} from '../design/tokens.ets';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.Article';

// 字号缩放上下限 — 照搬 Android BoardSettings.kt:131-133
const FONT_SCALE_MIN: number = 0.85;
const FONT_SCALE_MAX: number = 1.25;
const FONT_SCALE_STEP: number = 0.05;
const FONT_SCALE_DEFAULT: number = 1.0;
const GENERATION_STEP_COUNT: number = 6;

interface ArticleParams {
  topicId: string;
  title: string;
}

@Entry
@Component
struct DeepReadArticlePage {
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State topicId: string = '';
  @State title: string = '';
  @State output: DeepReadOutput | null = null;
  @State running: boolean = true;
  @State errorMsg: string = '';
  @State currentStage: string = '';
  @State cancelled: boolean = false;
  // G5-S1:字号倍率(驱动 ArticleRenderer)
  @State fontScale: number = FONT_SCALE_DEFAULT;
  // G5-S1:重新生成确认对话框
  @State showRegenerateDialog: boolean = false;
  private pageAlive: boolean = false;
  private pageToken: number = 0;
  private unsubscribeOutput: (() => void) | null = null;
  private unsubscribeRunning: (() => void) | null = null;
  private scheduler: DeepReadScheduler | null = null;

  aboutToAppear(): void {
    this.pageAlive = true;
    const token: number = ++this.pageToken;
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
    if (this.topicId.length === 0 || this.title.length === 0) {
      this.errorMsg = '文章参数缺失';
      this.running = false;
      return;
    }
    hilog.info(DOMAIN, TAG, 'aboutToAppear: topicId=%{public}s title=%{public}s', this.topicId, this.title);
    this.loadArticle(token, false).catch((e: Error): void => {
      if (this.pageAlive && token === this.pageToken) {
        this.errorMsg = this.cancelled ? '生成已取消' : e.message;
        this.running = false;
      }
    });
  }

  aboutToDisappear(): void {
    this.pageAlive = false;
    this.pageToken++;
    if (this.unsubscribeOutput !== null) {
      this.unsubscribeOutput();
      this.unsubscribeOutput = null;
    }
    if (this.unsubscribeRunning !== null) {
      this.unsubscribeRunning();
      this.unsubscribeRunning = null;
    }
  }

  private async loadArticle(token: number, force: boolean): Promise<void> {
    const repository = getRepository();
    const cached: DeepReadCacheEntry | null = await repository.get(this.topicId);
    if (!this.pageAlive || token !== this.pageToken) return;
    if (!force && cached !== null) {
      this.applyOutput(cached.output, token);
      if (isComplete(cached.output)) {
        this.running = false;
        return;
      }
    }
    this.scheduler = await getDeepReadScheduler();
    if (!this.pageAlive || token !== this.pageToken) return;
    if (this.unsubscribeOutput !== null) this.unsubscribeOutput();
    this.unsubscribeOutput = repository.observe(this.topicId).subscribe((entry: DeepReadCacheEntry | null): void => {
      if (!this.pageAlive || token !== this.pageToken || entry === null) return;
      this.applyOutput(entry.output, token);
    });
    if (this.unsubscribeRunning !== null) this.unsubscribeRunning();
    this.unsubscribeRunning = this.scheduler.observeRunning(this.topicId).subscribe((running: boolean): void => {
      if (!this.pageAlive || token !== this.pageToken) return;
      this.running = this.cancelled ? false : running;
      if (running && this.currentStage.length === 0) this.currentStage = this.progressLabel('收集来源', 1);
    });
    this.running = true;
    this.cancelled = false;
    this.errorMsg = '';
    this.currentStage = force ? this.progressLabel('收集来源', 1) : this.phaseLabel(cached?.output ?? null);
    const result: RunResult = await this.scheduler.run(this.topicId, this.title, { force });
    if (!this.pageAlive || token !== this.pageToken) return;
    if (result.output !== null) this.applyOutput(result.output, token);
    if (!result.ok) {
      if (result.error === 'aborted') {
        this.cancelled = true;
        this.errorMsg = '生成已取消';
      } else {
        this.errorMsg = result.error ?? '深度阅读生成失败';
      }
    }
    this.running = false;
  }

  private applyOutput(next: DeepReadOutput, token: number): void {
    if (!this.pageAlive || token !== this.pageToken) return;
    this.output = next;
    this.currentStage = this.phaseLabel(next);
    if (next.generationPhase === 'COMPLETE' || isComplete(next)) this.errorMsg = '';
  }

  private phaseLabel(output: DeepReadOutput | null): string {
    if (output === null) return this.progressLabel('收集来源', 1);
    if (output.generationPhase === 'COLLECTING') return this.progressLabel('收集来源', 1);
    if (output.generationPhase === 'PLANNING') return this.progressLabel('规划文章', 2);
    if (output.generationPhase === 'WRITING') {
      for (let index: number = 0; index < STAGE_ORDER.length; index++) {
        const stage: DeepReadGenerationStage = STAGE_ORDER[index];
        if (output.sectionStates[stage]?.status === 'RUNNING') {
          return this.progressLabel(this.stageLabel(stage), index + 3);
        }
      }
      return this.progressLabel('撰写文章', 3);
    }
    if (output.generationPhase === 'COMPLETE' || isComplete(output)) return '完成';
    if (firstFailureMessage(output) !== null) return '生成失败';
    return '等待生成';
  }

  private progressLabel(label: string, step: number): string {
    return `${label}（第 ${step}/${GENERATION_STEP_COUNT} 步）`;
  }

  private stageLabel(stage: DeepReadGenerationStage): string {
    if (stage === 'OVERVIEW') return '撰写概览';
    if (stage === 'NARRATIVE') return '撰写时间轴叙事';
    if (stage === 'ANALYSIS') return '撰写深度分析';
    return '整理扩展阅读';
  }

  private heroImageUrl(): string | null {
    return this.output === null ? null : displayHeroImageUrl(this.output);
  }

  private inlineImageAssets(): DeepReadImageAsset[] {
    if (this.output === null) return [];
    const hero: string | null = this.heroImageUrl();
    return this.output.imageAssets.filter((asset: DeepReadImageAsset): boolean => asset.url !== hero);
  }

  private diagramCode(): string {
    if (this.output === null || this.output.diagram === null) return '';
    return '```json\\n' + JSON.stringify(this.output.diagram, null, 2) + '\\n```';
  }

  private markdownContent(): string {
    if (this.output === null) return '';
    const o: DeepReadOutput = this.output;
    const lines: string[] = [];
    if (o.summary.trim().length > 0) lines.push('## 概览', '', o.summary.trim(), '');
    if (o.keyEntities.length > 0) lines.push('**关键实体**：' + o.keyEntities.join('、'), '');
    if (o.timeline !== null && o.timeline.length > 0) {
      lines.push('## 时间轴叙事', '');
      for (const item of o.timeline) lines.push(`- **${item.date}** ${item.event}`);
      lines.push('');
    }
    if (o.corePoints !== null && o.corePoints.length > 0) {
      lines.push('## 核心要点', '');
      for (const item of o.corePoints) {
        lines.push(`- ${item.point}`);
        if (item.supporting !== null && item.supporting.trim().length > 0) lines.push(`  ${item.supporting}`);
      }
      lines.push('');
    }
    if (o.analysis.coreDispute !== null && o.analysis.coreDispute.trim().length > 0) {
      lines.push('## 深度分析', '', '### 核心分歧', '', o.analysis.coreDispute.trim(), '');
    }
    if (o.analysis.perspectives.length > 0) {
      lines.push('### 各方立场', '');
      for (const item of o.analysis.perspectives) lines.push(`- **${item.holder ?? '观点'}**：${item.viewpoint}`);
      lines.push('');
    }
    if (o.analysis.implications !== null && o.analysis.implications.trim().length > 0) {
      lines.push('### 影响分析', '', o.analysis.implications.trim(), '');
    }
    if (o.analysis.quotes.length > 0) {
      lines.push('### 引述', '');
      for (const item of o.analysis.quotes) lines.push(`> ${item.text}${item.attribution === null ? '' : ` ——${item.attribution}`}`);
      lines.push('');
    }
    if (o.extendedReading.length > 0) {
      lines.push('## 扩展阅读', '');
      for (const item of o.extendedReading) lines.push(`- [${item.title}](${item.url})`);
      lines.push('');
    }
    if (o.references.length > 0) {
      lines.push('## 参考来源', '');
      o.references.forEach((item, index): void => {
        lines.push(`${index + 1}. [${item.title}](${item.url})`);
      });
    }
    return lines.join('\n');
  }

  private cancelGeneration(): void {
    if (!this.running || this.scheduler === null || this.topicId.length === 0) return;
    this.cancelled = true;
    this.running = false;
    this.errorMsg = '生成已取消';
    this.scheduler.abort(this.topicId);
  }

  generateArticle(): void {
    const token: number = ++this.pageToken;
    this.output = null;
    this.errorMsg = '';
    this.cancelled = false;
    this.running = true;
    this.currentStage = this.progressLabel('收集来源', 1);
    this.loadArticle(token, true).catch((e: Error): void => {
      if (this.pageAlive && token === this.pageToken) {
        this.errorMsg = this.cancelled ? '生成已取消' : e.message;
        this.running = false;
      }
    });
  }

  // G5-S1:字号缩放 — zoom in/out
  private zoomIn(): void {
    const next: number = this.fontScale + FONT_SCALE_STEP;
    this.fontScale = next > FONT_SCALE_MAX ? FONT_SCALE_MAX : next;
  }
  private zoomOut(): void {
    const next: number = this.fontScale - FONT_SCALE_STEP;
    this.fontScale = next < FONT_SCALE_MIN ? FONT_SCALE_MIN : next;
  }

  // G5-S1:部分失败判定 — 有 output 但 firstFailureMessage 非空
  private partialError(): string {
    if (this.output === null) { return ''; }
    if (this.currentStage === '完成') { return ''; }
    const msg = firstFailureMessage(this.output);
    if (msg === null) { return ''; }
    return msg;
  }

  // G5-S1:模板降级判定 — 未完成且无部分失败但已有基础稿(走 fallback 模板)
  private templateFallbackMessage(): string {
    if (this.output === null) { return ''; }
    if (this.currentStage === '完成') { return ''; }
    if (this.partialError().length > 0) { return ''; }
    const complete = isComplete(this.output);
    if (complete) { return ''; }
    if (this.output.summary.trim().length > 0) {
      return '使用基础模板生成,内容可能不完整';
    }
    return '';
  }

  build(): void {
    Stack() {
      Column() {
        // ── 顶栏:返回 + 标题(衬线) + 字号缩放按钮 ──
        Row() {
          Icon({ name: 'back', iconSize: 20, color: INK3 })
            .onClick(() => { router.back(); })
          Text('深度阅读').fontSize(18).fontWeight(FontWeight.Bold).fontColor(INK)
            .fontFamily(FONT_SERIF).margin({ left: 12 }).layoutWeight(1)

          // G5-S1:字号缩放按钮(zoom out / zoom in)
          Row() {
            Text('A-').fontSize(13).fontColor(INK3)
              .padding({ left: 8, right: 8, top: 4, bottom: 4 })
              .onClick((): void => { this.zoomOut(); })
            Text('A+').fontSize(15).fontColor(INK3)
              .padding({ left: 8, right: 8, top: 4, bottom: 4 })
              .onClick((): void => { this.zoomIn(); })
          }
          .borderRadius(8)
          .backgroundColor(SURFACE2)
        }
        .width('100%').padding({ left: 14, right: 14, top: this.statusBarHeight + 2, bottom: 11 })
        .backgroundColor(BG)

        // ── 内容区 ──
        if (this.running) {
          // G5-S1:生成中 — RunningStageNotice(阶段 + 步骤指示)
          Column() {
            LoadingProgress().width(40).height(40).color(ACCENT)
            Row() {
              Text(this.currentStage)
                .fontSize(META)
                .fontColor(INK2)
            }
            .padding({ left: 14, right: 14, top: 8, bottom: 8 })
            .backgroundColor(SURFACE)
            .borderRadius(50)
            .margin({ top: 16 })
            Text('取消生成')
              .fontSize(BODY)
              .fontColor(ERROR)
              .height(40)
              .padding({ left: 16, right: 16 })
              .margin({ top: 12 })
              .onClick((): void => { this.cancelGeneration(); })
          }.width('100%').layoutWeight(1).justifyContent(FlexAlign.Center)
        } else if (this.errorMsg.length > 0 && this.output === null) {
          // 首次生成失败或显式取消,保留明确重试入口
          Column() {
            Text(this.cancelled ? '生成已取消' : '生成失败')
              .fontSize(TITLE).fontWeight(FontWeight.Medium).fontColor(INK2).margin({ top: 12 })
            Text(this.errorMsg).fontSize(META).fontColor(INK3).margin({ top: 6 })
            Text('重试').fontSize(BODY).fontColor(ACCENT).margin({ top: 16 })
              .onClick((): void => { this.generateArticle(); })
          }.width('100%').layoutWeight(1).justifyContent(FlexAlign.Center)
        } else if (this.output !== null) {
          // DeepReadOutput 统一组装为 Markdown,复用聊天侧原生 MarkdownText
          Scroll() {
            Column() {
              Text(this.title)
                .fontSize(24).fontWeight(FontWeight.Bold).fontColor(INK)
                .fontFamily(FONT_SERIF)
                .lineHeight(34).maxLines(3)
              if (this.heroImageUrl() !== null) {
                Image(this.heroImageUrl() as string)
                  .width('100%').height(220)
                  .objectFit(ImageFit.Cover)
                  .borderRadius(14).clip(true)
                  .margin({ top: 14 })
              }
              Text(this.currentStage).fontSize(META).fontColor(INK3).margin({ top: 10 })
              if (this.cancelled) {
                Row() {
                  Text(this.errorMsg.length > 0 ? this.errorMsg : '生成已取消')
                    .fontSize(META)
                    .fontColor(ERROR)
                    .layoutWeight(1)
                  Text('重试')
                    .fontSize(META)
                    .fontColor(ACCENT)
                    .padding({ left: 12, right: 12, top: 4, bottom: 4 })
                    .onClick((): void => { this.generateArticle(); })
                }
                .width('100%')
                .padding({ left: 12, right: 12, top: 8, bottom: 8 })
                .backgroundColor(SURFACE2)
                .borderRadius(8)
                .margin({ top: 12 })
              }
              Column() {}.height(1).width('100%').backgroundColor(LINE).margin({ top: 16, bottom: 4 })
              if (this.partialError().length > 0) {
                this.PartialErrorNotice(this.partialError())
              }
              if (this.templateFallbackMessage().length > 0) {
                this.TemplateFallbackNotice(this.templateFallbackMessage())
              }
              MarkdownText({
                content: this.markdownContent(),
                fontSize: BODY * this.fontScale,
                fontColor: INK2,
              })
              if (this.inlineImageAssets().length > 0) {
                ForEach(this.inlineImageAssets(), (asset: DeepReadImageAsset) => {
                  Column() {
                    Image(asset.url)
                      .width('100%').height(180)
                      .objectFit(ImageFit.Cover)
                      .borderRadius(14).clip(true)
                    if (asset.caption !== null && asset.caption.trim().length > 0) {
                      Text(asset.caption).fontSize(META).fontColor(INK3).margin({ top: 4 })
                    }
                  }
                  .width('100%').margin({ top: 14 })
                })
              }
              if (this.output.diagram !== null) {
                Column() {
                  Text('图解').fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
                  MarkdownText({
                    content: this.diagramCode(),
                    fontSize: META,
                    fontColor: INK3,
                  })
                }
                .width('100%').margin({ top: 14 })
              }
              Row() {
                Text('重新生成').fontSize(BODY).fontColor(ACCENT)
              }
              .width('100%')
              .height(48)
              .justifyContent(FlexAlign.Center)
              .margin({ top: 24, bottom: 40 })
              .onClick((): void => { this.showRegenerateDialog = true; })
            }
            .width('100%').padding({ left: 14, right: 14, top: 2, bottom: 11 })
          }
        .align(Alignment.TopStart)
          .layoutWeight(1)
          .backgroundColor(BG)
        }
      }
      .width('100%').height('100%').backgroundColor(BG)
      .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])

      // G5-S1:重新生成确认对话框(覆盖层)
      DeepReadConfirmationDialog({
        show: this.showRegenerateDialog,
        dialogTitle: '重新生成?',
        message: '重新生成将覆盖当前文章',
        confirmText: '重新生成',
        dismissText: '取消',
        onConfirm: (): void => {
          this.showRegenerateDialog = false;
          this.generateArticle();
        },
        onDismiss: (): void => {
          this.showRegenerateDialog = false;
        },
      })
    }
    .width('100%').height('100%')
  }

  // G5-S1:部分错误提示(对齐 Android DeepReadPartialErrorNotice:515-548)
  @Builder
  PartialErrorNotice(errText: string): void {
    Row() {
      Text(errText.length > 0 ? errText : '后续段落生成中断,已保留已写出的内容')
        .fontSize(META)
        .fontColor('#7A1F1A')
        .layoutWeight(1)
        .maxLines(2)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
      Text('重试')
        .fontSize(META)
        .fontColor(ACCENT)
        .padding({ left: 12, right: 12, top: 4, bottom: 4 })
        .onClick((): void => { this.generateArticle(); })
    }
    .width('100%')
    .padding({ left: 14, right: 14, top: 8, bottom: 8 })
    .backgroundColor('#FBE9E7')
    .borderRadius(22)
    .margin({ top: 12, bottom: 8 })
  }

  // G5-S1:模板降级提示(对齐 Android TemplateFallbackNotice:551-564)
  @Builder
  TemplateFallbackNotice(msg: string): void {
    Row() {
      Text(msg)
        .fontSize(META)
        .fontColor('#7A1F1A')
    }
    .padding({ left: 12, right: 12, top: 7, bottom: 7 })
    .backgroundColor('#FBE9E7')
    .borderRadius(50)
    .margin({ top: 12, bottom: 8 })
  }
}
