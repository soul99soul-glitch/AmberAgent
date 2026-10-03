// NovelPolishJobCard — durable batch-polish 作业在正文页的只读投影与控制。

import { MOTION_OVERLAY,
  ACCENT, ACCENT_INK, ERROR, INK, INK2, INK3, INK4,
  LINE, RAISED, SURFACE, SURFACE2,
} from '../design/tokens.ets';
import type {
  DurablePolishJob, GhostwriteFinding, PolishChapterTarget, PolishProgress, PolishStage,
  PolishWarning, PolishChapterResult,
} from '@amber/deepread-domain';
import { projectPolishOutcomes } from '@amber/deepread-domain';

@Component
export struct NovelPolishJobCard {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop job: DurablePolishJob | null = null;
  @Prop busy: boolean = false;
  @Prop fullHeight: boolean = false;
  @Prop retryReady: boolean = true;

  onPause: () => void = (): void => {};
  onResume: () => void = (): void => {};
  onRetry: () => void = (): void => {};
  onCancel: () => void = (): void => {};

  @State private candidateExpanded: boolean = false;
  @State private reviewExpanded: boolean = false;
  @State private resultsExpanded: boolean = true;

  build(): void {
    if (this.themeEpoch >= 0 && this.job !== null) {
      if (this.fullHeight) {
        this.jobBody()
      } else {
        Scroll() {
          this.jobBody()
        }
        .width('100%').constraintSize({ maxHeight: 380 }).scrollBar(BarState.Auto).align(Alignment.TopStart)
      }
    }
  }

  @Builder
  private jobBody(): void {
    Column() {
      this.header()
      this.progressSection()
      this.stageSection()
      this.warningSection()
      this.candidateSection()
      this.reviewSection()
      this.failureSection()
      this.resultSection()
      this.actionRow()
    }
    .width('100%').padding(14).backgroundColor(SURFACE2).borderRadius(14)
    .border({ width: 1, color: this.stageColor(this.job!.stage) }).alignItems(HorizontalAlign.Start)
  }

  @Builder
  private header(): void {
    Column() {
      Text(this.job!.targets.length === 1 ? '整章润色' : '批量润色').fontSize(17).fontWeight(FontWeight.Bold).fontColor(INK)
      Text(this.stageDescription(this.job!.stage)).fontSize(12).fontColor(INK3).lineHeight(18)
        .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%').margin({ top: 4 })
    }
    .width('100%').alignItems(HorizontalAlign.Start)
  }

  @Builder
  private progressSection(): void {
    Row() {
      Column() {
        Text(`${this.processedCount(this.job!)}/${this.job!.targets.length}`).fontSize(22).fontWeight(FontWeight.Bold).fontColor(INK)
        Text('已处理 / 目标').fontSize(11).fontColor(INK3).margin({ top: 2 })
      }
      .alignItems(HorizontalAlign.Start)
      Blank()
      Column() {
        Text(this.currentChapterLabel(this.job!)).fontSize(14).fontWeight(FontWeight.Medium).fontColor(INK)
        Text(this.stageLabel(this.job!.stage)).fontSize(12).fontColor(this.stageColor(this.job!.stage)).margin({ top: 3 })
      }
      .alignItems(HorizontalAlign.End)
    }
    .width('100%').margin({ top: 12, bottom: 10 })
    this.progressBar()
  }

  @Builder
  private stageSection(): void {
    Column() {
      Text('执行阶段').fontSize(11).fontColor(INK3)
      Text(this.stageFlow(this.job!.stage)).fontSize(12).fontColor(INK2).lineHeight(18)
        .width('100%').margin({ top: 4 })
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding({ top: 11, bottom: 3 })
    .border({ width: { top: 0.5 }, color: { top: LINE } }).margin({ top: 12 })
  }

  @Builder
  private warningSection(): void {
    if (this.job!.warnings.length > 0) {
      Column() {
        Text('注意事项').fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK)
        ForEach(this.job!.warnings, (warning: PolishWarning): void => {
          Text(`${this.warningLabel(warning.kind)}：${warning.message}`).fontSize(12).fontColor(INK2)
            .lineHeight(18).maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis })
            .width('100%').margin({ top: 7 })
        }, (warning: PolishWarning): string => warning.kind)
      }
      .width('100%').alignItems(HorizontalAlign.Start).padding({ top: 11, bottom: 3 })
      .border({ width: { top: 0.5 }, color: { top: LINE } }).margin({ top: 12 })
    }
  }

  @Builder
  private candidateSection(): void {
    if (this.job!.candidate !== null) {
      Column() {
        this.detailToggle('candidate')
        if (this.candidateExpanded) {
          Column() {
            Text('候选未原子收录前不会改变正文。').fontSize(11).fontColor(INK3).margin({ top: 8 })
            Text(this.job!.candidate.content).fontSize(12).fontColor(INK2).lineHeight(18)
              .width('100%').margin({ top: 7 })
          }
          .width('100%')
          .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
            .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
        }
      }
      .width('100%').padding({ top: 11, bottom: 3 }).clip(true)
      .border({ width: { top: 0.5 }, color: { top: LINE } }).margin({ top: 12 })
    }
  }

  @Builder
  private reviewSection(): void {
    if (this.job!.review !== null) {
      Column() {
        this.detailToggle('review')
        if (this.reviewExpanded) {
          Column() {
            if (this.job!.review.rewriteInstructions.trim().length > 0) {
              Text(`重写说明：${this.job!.review.rewriteInstructions}`).fontSize(12).fontColor(INK2).lineHeight(18)
                .width('100%').margin({ top: 8 })
            }
            ForEach(this.job!.review.findings, (finding: GhostwriteFinding): void => {
              Text(`${this.findingLabel(finding)}：${finding.message}`).fontSize(12).fontColor(INK2)
                .lineHeight(18)
                .width('100%').margin({ top: 7 })
            }, (finding: GhostwriteFinding): string => `${finding.code}:${finding.location}`)
          }
          .width('100%')
          .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
            .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
        }
      }
      .width('100%').padding({ top: 11, bottom: 3 }).clip(true)
      .border({ width: { top: 0.5 }, color: { top: LINE } }).margin({ top: 12 })
    }
  }

  @Builder
  private failureSection(): void {
    if (this.job!.failure !== null && this.job!.failure.trim().length > 0) {
      Text(this.job!.failure).fontSize(12).fontColor(ERROR).lineHeight(18)
        .width('100%').margin({ top: 12 })
    }
  }

  @Builder
  private resultSection(): void {
    if (this.showResults(this.job!.stage)) {
      Column() {
        this.detailToggle('results')
        if (this.resultsExpanded) {
          Column() {
            ForEach(projectPolishOutcomes(this.job!), (result: PolishChapterResult): void => {
              this.outcomeRow(result)
            }, (result: PolishChapterResult): string => `${result.chapterId}:${result.status}:${result.message ?? ''}:${result.updatedAt}`)
          }
          .width('100%')
          .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
            .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
        }
      }
      .width('100%').padding({ top: 11, bottom: 3 }).clip(true)
      .border({ width: { top: 0.5 }, color: { top: LINE } }).margin({ top: 12 })
    }
  }

  @Builder
  private outcomeRow(result: PolishChapterResult): void {
    Row() {
      Column() {
        Text(`第 ${result.chapterOrdinal} 章 · ${this.targetTitle(this.job!, result.chapterId)}`).fontSize(12).fontColor(INK).maxLines(2)
          .textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%')
        Text(this.outcomeLabel(result)).fontSize(11).fontColor(result.status === 'failed' ? ERROR : result.status === 'success' ? ACCENT : INK3)
          .lineHeight(18).width('100%').margin({ top: 2 })
        if (result.message !== null && result.message.length > 0) {
          Text(result.message).fontSize(12).fontColor(INK2).lineHeight(18).width('100%').margin({ top: 4 })
        }
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1)
    }
    .width('100%').constraintSize({ minHeight: 44 }).padding({ top: 6, bottom: 6 })
    .border({ width: { top: 0.5 }, color: { top: LINE } })
  }

  @Builder
  private detailToggle(section: string): void {
    Row() {
      Column() {
        Text(section === 'candidate' ? '候选正文' : section === 'review' ? '独立审稿' : '本次结果')
          .fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK)
        Text(section === 'candidate'
          ? `第 ${this.job!.candidate!.chapterOrdinal} 章 · ${this.job!.candidate!.attempt === 0 ? '本轮候选' : `自动返修 ${this.job!.candidate!.attempt} 次`}`
          : section === 'review' ? this.reviewSummary(this.job!) : this.resultSummary(this.job!))
          .fontSize(11).fontColor(INK3).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          .width('100%').margin({ top: 2 })
      }
      .layoutWeight(1).alignItems(HorizontalAlign.Start)
      Text((section === 'candidate' ? this.candidateExpanded : section === 'review' ? this.reviewExpanded : this.resultsExpanded) ? '收起' : '查看')
        .fontSize(12).fontColor(ACCENT).width(52).height(44)
        .textAlign(TextAlign.Center).padding({ top: 13, bottom: 13 })
        .accessibilityText(`${(section === 'candidate' ? this.candidateExpanded : section === 'review' ? this.reviewExpanded : this.resultsExpanded) ? '收起' : '查看'}${section === 'candidate' ? '候选正文' : section === 'review' ? '独立审稿' : '本次结果'}`)
        .onClick((): void => { this.toggleDetail(section); })
    }
    .width('100%').constraintSize({ minHeight: 44 }).alignItems(VerticalAlign.Center)
  }

  @Builder
  private actionRow(): void {
    Row() {
      if (this.job!.stage === 'paused' || this.job!.stage === 'waiting_system') {
        this.smallButton('resume')
        this.smallButton('cancel')
      } else if (this.job!.stage === 'failed') {
        this.smallButton('retry')
        this.smallButton('cancel')
      } else if (this.job!.stage !== 'cancelled' && this.job!.stage !== 'completed') {
        this.smallButton('pause')
        this.smallButton('cancel')
      }
    }
    .width('100%').margin({ top: 14 }).justifyContent(FlexAlign.End)
  }

  @Builder
  private smallButton(actionKind: string): void {
    Text(actionKind === 'resume' ? '继续' : actionKind === 'retry' ? '重试' : actionKind === 'pause' ? '暂停' : '取消')
      .fontSize(13).fontWeight(FontWeight.Medium)
      .fontColor(this.busy || (actionKind === 'retry' && !this.retryReady) ? INK4
        : actionKind === 'cancel' ? ERROR : actionKind === 'pause' ? ACCENT : ACCENT_INK)
      .constraintSize({ minWidth: 72, minHeight: 44 }).height(44).textAlign(TextAlign.Center)
      .padding({ left: 12, right: 12, top: 12, bottom: 12 })
      .backgroundColor(actionKind === 'resume' || actionKind === 'retry' ? ACCENT : SURFACE).borderRadius(12)
      .border({ width: 1, color: actionKind === 'cancel' ? ERROR : LINE }).margin({ left: 8 })
      .opacity(this.busy || (actionKind === 'retry' && !this.retryReady) ? 0.5 : 1)
      .enabled(!this.busy && (actionKind !== 'retry' || this.retryReady))
      .onClick((): void => { if (!this.busy && (actionKind !== 'retry' || this.retryReady)) this.performAction(actionKind); })
  }

  @Builder
  private progressBar(): void {
    Row() {
      Row() {}.width(`${this.progressPercent(this.processedCount(this.job!), this.job!.targets.length)}%`).height(6).backgroundColor(ACCENT).borderRadius(3)
    }
    .width('100%').height(6).backgroundColor(RAISED).borderRadius(3)
  }

  private currentChapterLabel(job: DurablePolishJob): string {
    if (job.targets.length === 0) return '未选择章节';
    const index: number = Math.max(0, Math.min(job.cursor, job.targets.length - 1));
    return `第 ${job.targets[index].ordinal} 章`;
  }

  private toggleDetail(section: string): void {
    this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
      if (section === 'candidate') this.candidateExpanded = !this.candidateExpanded;
      else if (section === 'review') this.reviewExpanded = !this.reviewExpanded;
      else this.resultsExpanded = !this.resultsExpanded;
    });
  }

  private performAction(actionKind: string): void {
    if (actionKind === 'resume') this.onResume();
    else if (actionKind === 'retry') this.onRetry();
    else if (actionKind === 'pause') this.onPause();
    else this.onCancel();
  }

  private stageFlow(stage: PolishStage): string {
    const flow: string = '排队 → 写作 → 审稿 → 重写 1/2 → 重写 2/2 → 原子收录';
    if (stage === 'waiting_system') return `${flow}（等待系统执行窗口）`;
    if (stage === 'paused') return `${flow}（已暂停）`;
    if (stage === 'failed') return `${flow}（执行失败，可重试）`;
    if (stage === 'cancelled') return `${flow}（已取消）`;
    if (stage === 'completed') return `${flow}（已完成）`;
    return flow;
  }

  private stageLabel(stage: PolishStage): string {
    if (stage === 'queued') return '等待执行';
    if (stage === 'writing') return '正在润色';
    if (stage === 'reviewing') return '正在独立审稿';
    if (stage === 'rewriting_1') return '定向重写 1/2';
    if (stage === 'rewriting_2') return '定向重写 2/2';
    if (stage === 'committing') return '正在原子收录';
    if (stage === 'waiting_system') return '等待系统继续';
    if (stage === 'paused') return '已暂停';
    if (stage === 'failed') return '执行失败';
    if (stage === 'cancelled') return '已取消';
    return '已完成';
  }

  private stageDescription(stage: PolishStage): string {
    if (stage === 'waiting_system') return '已在安全检查点保存，系统窗口恢复后会继续。';
    if (stage === 'paused') return '暂停会在安全检查点生效，正文不会被半途写入。';
    if (stage === 'failed') return '已保留冻结输入与进度，可重试同一任务。';
    if (stage === 'cancelled') return '任务已取消；已收录章节保留在工作区版本记录中。';
    if (stage === 'completed') return '本批次已结束，逐章结果见下方；已收录章节可从工作区版本记录恢复。';
    return '候选与审稿结果在原子收录前均不改变正文。';
  }

  private stageColor(stage: PolishStage): string {
    if (stage === 'failed') return ERROR;
    if (stage === 'cancelled' || stage === 'paused' || stage === 'waiting_system') return INK3;
    return ACCENT;
  }

  private warningLabel(kind: string): string {
    if (kind === 'plot_stale') return '剧情摘要可能过期';
    return '存在未处理影响';
  }

  private reviewSummary(job: DurablePolishJob): string {
    if (job.review === null) return '';
    if (job.review.rewriteRequired) return `需重写 · ${job.review.findings.length} 项发现`;
    return job.review.blocking ? `存在阻断问题 · ${job.review.findings.length} 项发现` :
      `已通过 · ${job.review.findings.length} 项发现`;
  }

  private findingLabel(finding: GhostwriteFinding): string {
    if (finding.kind === 'missing_required') return '缺失必写';
    if (finding.kind === 'forbidden_violation') return '违反禁写';
    if (finding.kind === 'hard_continuity') return '连续性硬伤';
    return '提示';
  }

  private showResults(stage: PolishStage): boolean {
    return stage === 'failed' || stage === 'cancelled' || stage === 'completed';
  }

  private processedCount(job: DurablePolishJob): number {
    return projectPolishOutcomes(job).filter((result: PolishChapterResult): boolean => result.status !== 'unprocessed').length;
  }

  private incompleteCount(job: DurablePolishJob): number {
    return projectPolishOutcomes(job).filter((result: PolishChapterResult): boolean => result.status === 'unprocessed').length;
  }

  private progressFor(job: DurablePolishJob, chapterId: string): PolishProgress | null {
    for (let index: number = 0; index < job.progress.length; index++) {
      if (job.progress[index].chapterId === chapterId) return job.progress[index];
    }
    return null;
  }

  private targetTitle(job: DurablePolishJob, chapterId: string): string {
    return job.targets.find((target: PolishChapterTarget): boolean => target.id === chapterId)?.title ?? '未命名章节';
  }

  private outcomeLabel(result: PolishChapterResult): string {
    if (result.status === 'success') return '已润色并收录';
    if (result.status === 'driftSkipped') return '剧情漂移，跳过并保留原稿';
    if (result.status === 'failed') return '失败，可定向重试';
    return '尚未处理';
  }

  private resultSummary(job: DurablePolishJob): string {
    const results: PolishChapterResult[] = projectPolishOutcomes(job);
    return `${results.filter(result => result.status === 'success').length} 已收录 · `
      + `${results.filter(result => result.status === 'driftSkipped').length} 漂移跳过 · `
      + `${results.filter(result => result.status === 'failed').length} 失败 · `
      + `${results.filter(result => result.status === 'unprocessed').length} 未处理`;
  }

  private shortReceipt(receipt: string): string {
    return receipt.length <= 12 ? receipt : `${receipt.slice(0, 12)}…`;
  }

  private progressPercent(done: number, total: number): number {
    if (total <= 0) return 0;
    return Math.max(0, Math.min(100, Math.floor(done * 100 / total)));
  }
}
