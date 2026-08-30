// NovelPolishJobCard — durable batch-polish 作业在正文页的只读投影与控制。

import {
  ACCENT, ACCENT_INK, ERROR, INK, INK2, INK3, INK4,
  LINE, RAISED, SURFACE, SURFACE2,
} from '../design/tokens.ets';
import type {
  DurablePolishJob, GhostwriteFinding, PolishChapterTarget, PolishProgress, PolishStage,
  PolishWarning,
} from '@amber/deepread-domain';

@Component
export struct NovelPolishJobCard {
  @Prop job: DurablePolishJob | null = null;
  @Prop busy: boolean = false;

  onPause: () => void = (): void => {};
  onResume: () => void = (): void => {};
  onRetry: () => void = (): void => {};
  onCancel: () => void = (): void => {};

  @State private candidateExpanded: boolean = false;
  @State private reviewExpanded: boolean = false;
  @State private resultsExpanded: boolean = true;

  build(): void {
    if (this.job !== null) {
      Scroll() {
        this.jobBody(this.job)
      }
      .width('100%').constraintSize({ maxHeight: 380 }).scrollBar(BarState.Auto)
    }
  }

  @Builder
  private jobBody(job: DurablePolishJob): void {
    Column() {
      this.header(job)
      this.progressSection(job)
      this.stageSection(job)
      this.warningSection(job)
      this.candidateSection(job)
      this.reviewSection(job)
      this.failureSection(job)
      this.resultSection(job)
      this.actionRow(job)
    }
    .width('100%').padding(14).backgroundColor(SURFACE2).borderRadius(14)
    .border({ width: 1, color: this.stageColor(job.stage) }).alignItems(HorizontalAlign.Start)
  }

  @Builder
  private header(job: DurablePolishJob): void {
    Column() {
      Text('批量润色').fontSize(17).fontWeight(FontWeight.Bold).fontColor(INK)
      Text(this.stageDescription(job.stage)).fontSize(12).fontColor(INK3).lineHeight(18)
        .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%').margin({ top: 4 })
    }
    .width('100%').alignItems(HorizontalAlign.Start)
  }

  @Builder
  private progressSection(job: DurablePolishJob): void {
    Row() {
      Column() {
        Text(`${job.progress.length}/${job.targets.length}`).fontSize(22).fontWeight(FontWeight.Bold).fontColor(INK)
        Text('已原子收录 / 目标').fontSize(11).fontColor(INK3).margin({ top: 2 })
      }
      .alignItems(HorizontalAlign.Start)
      Blank()
      Column() {
        Text(this.currentChapterLabel(job)).fontSize(14).fontWeight(FontWeight.Medium).fontColor(INK)
        Text(this.stageLabel(job.stage)).fontSize(12).fontColor(this.stageColor(job.stage)).margin({ top: 3 })
      }
      .alignItems(HorizontalAlign.End)
    }
    .width('100%').margin({ top: 12, bottom: 10 })
    this.progressBar(job.progress.length, job.targets.length)
  }

  @Builder
  private stageSection(job: DurablePolishJob): void {
    Column() {
      Text('执行阶段').fontSize(11).fontColor(INK3)
      Text(this.stageFlow(job.stage)).fontSize(12).fontColor(INK2).lineHeight(18)
        .width('100%').margin({ top: 4 })
    }
    .width('100%').padding({ top: 11, bottom: 3 })
    .border({ width: { top: 0.5 }, color: { top: LINE } }).margin({ top: 12 })
  }

  @Builder
  private warningSection(job: DurablePolishJob): void {
    if (job.warnings.length > 0) {
      Column() {
        Text('注意事项').fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK)
        ForEach(job.warnings, (warning: PolishWarning): void => {
          Text(`${this.warningLabel(warning.kind)}：${warning.message}`).fontSize(12).fontColor(INK2)
            .lineHeight(18).maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis })
            .width('100%').margin({ top: 7 })
        }, (warning: PolishWarning): string => warning.kind)
      }
      .width('100%').padding({ top: 11, bottom: 3 })
      .border({ width: { top: 0.5 }, color: { top: LINE } }).margin({ top: 12 })
    }
  }

  @Builder
  private candidateSection(job: DurablePolishJob): void {
    if (job.candidate !== null) {
      Column() {
        this.detailToggle('候选正文', `第 ${job.candidate.chapterOrdinal} 章 · 第 ${job.candidate.attempt + 1} 稿`,
          this.candidateExpanded, (): void => { this.candidateExpanded = !this.candidateExpanded; })
        if (this.candidateExpanded) {
          Text('候选未原子收录前不会改变正文。').fontSize(11).fontColor(INK3).margin({ top: 8 })
          Text(job.candidate.content).fontSize(12).fontColor(INK2).lineHeight(18)
            .width('100%').margin({ top: 7 })
        }
      }
      .width('100%').padding({ top: 11, bottom: 3 })
      .border({ width: { top: 0.5 }, color: { top: LINE } }).margin({ top: 12 })
    }
  }

  @Builder
  private reviewSection(job: DurablePolishJob): void {
    if (job.review !== null) {
      Column() {
        this.detailToggle('独立审稿', this.reviewSummary(job), this.reviewExpanded,
          (): void => { this.reviewExpanded = !this.reviewExpanded; })
        if (this.reviewExpanded) {
          if (job.review.rewriteInstructions.trim().length > 0) {
            Text(`重写说明：${job.review.rewriteInstructions}`).fontSize(12).fontColor(INK2).lineHeight(18)
              .width('100%').margin({ top: 8 })
          }
          ForEach(job.review.findings, (finding: GhostwriteFinding): void => {
            Text(`${this.findingLabel(finding)}：${finding.message}`).fontSize(12).fontColor(INK2)
              .lineHeight(18)
              .width('100%').margin({ top: 7 })
          }, (finding: GhostwriteFinding): string => `${finding.code}:${finding.location}`)
        }
      }
      .width('100%').padding({ top: 11, bottom: 3 })
      .border({ width: { top: 0.5 }, color: { top: LINE } }).margin({ top: 12 })
    }
  }

  @Builder
  private failureSection(job: DurablePolishJob): void {
    if (job.failure !== null && job.failure.trim().length > 0) {
      Text(job.failure).fontSize(12).fontColor(ERROR).lineHeight(18)
        .width('100%').margin({ top: 12 })
    }
  }

  @Builder
  private resultSection(job: DurablePolishJob): void {
    if (this.showResults(job.stage)) {
      Column() {
        this.detailToggle('本次结果', `${job.progress.length} 已完成，${this.incompleteCount(job)} 未完成`,
          this.resultsExpanded, (): void => { this.resultsExpanded = !this.resultsExpanded; })
        if (this.resultsExpanded) {
          ForEach(job.targets, (target: PolishChapterTarget): void => {
            this.resultRow(target, this.progressFor(job, target.id))
          }, (target: PolishChapterTarget): string => target.id)
        }
      }
      .width('100%').padding({ top: 11, bottom: 3 })
      .border({ width: { top: 0.5 }, color: { top: LINE } }).margin({ top: 12 })
    }
  }

  @Builder
  private resultRow(target: PolishChapterTarget, progress: PolishProgress | null): void {
    Row() {
      Column() {
        Text(`第 ${target.ordinal} 章 · ${target.title}`).fontSize(12).fontColor(INK).maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%')
        if (progress !== null) {
          Text(`已完成 · ${this.shortReceipt(progress.receipt)}`).fontSize(11).fontColor(ACCENT).margin({ top: 2 })
        } else {
          Text('未完成').fontSize(11).fontColor(INK3).margin({ top: 2 })
        }
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1)
    }
    .width('100%').constraintSize({ minHeight: 44 }).padding({ top: 6, bottom: 6 })
    .border({ width: { top: 0.5 }, color: { top: LINE } })
  }

  @Builder
  private detailToggle(title: string, detail: string, expanded: boolean, action: () => void): void {
    Row() {
      Column() {
        Text(title).fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK)
        Text(detail).fontSize(11).fontColor(INK3).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          .width('100%').margin({ top: 2 })
      }
      .layoutWeight(1).alignItems(HorizontalAlign.Start)
      Text(expanded ? '收起' : '查看').fontSize(12).fontColor(ACCENT).width(52).height(44)
        .textAlign(TextAlign.Center).padding({ top: 13, bottom: 13 })
        .accessibilityText(`${expanded ? '收起' : '查看'}${title}`).onClick(action)
    }
    .width('100%').constraintSize({ minHeight: 44 }).alignItems(VerticalAlign.Center)
  }

  @Builder
  private actionRow(job: DurablePolishJob): void {
    Row() {
      if (job.stage === 'paused' || job.stage === 'waiting_system') {
        this.smallButton('继续', !this.busy, ACCENT, ACCENT_INK, (): void => { this.onResume(); })
        this.smallButton('取消', !this.busy, SURFACE, ERROR, (): void => { this.onCancel(); })
      } else if (job.stage === 'failed') {
        this.smallButton('重试', !this.busy, ACCENT, ACCENT_INK, (): void => { this.onRetry(); })
        this.smallButton('取消', !this.busy, SURFACE, ERROR, (): void => { this.onCancel(); })
      } else if (job.stage !== 'cancelled' && job.stage !== 'completed') {
        this.smallButton('暂停', !this.busy, SURFACE, ACCENT, (): void => { this.onPause(); })
        this.smallButton('取消', !this.busy, SURFACE, ERROR, (): void => { this.onCancel(); })
      }
    }
    .width('100%').margin({ top: 14 }).justifyContent(FlexAlign.End)
  }

  @Builder
  private smallButton(label: string, enabled: boolean, background: string, color: string, action: () => void): void {
    Text(label).fontSize(13).fontWeight(FontWeight.Medium).fontColor(enabled ? color : INK4)
      .constraintSize({ minWidth: 72, minHeight: 44 }).height(44).textAlign(TextAlign.Center)
      .padding({ left: 12, right: 12, top: 12, bottom: 12 }).backgroundColor(background).borderRadius(12)
      .border({ width: 1, color: color === ERROR ? ERROR : LINE }).margin({ left: 8 })
      .opacity(enabled ? 1 : 0.5).onClick((): void => { if (enabled) action(); })
  }

  @Builder
  private progressBar(done: number, total: number): void {
    Row() {
      Row() {}.width(`${this.progressPercent(done, total)}%`).height(6).backgroundColor(ACCENT).borderRadius(3)
    }
    .width('100%').height(6).backgroundColor(RAISED).borderRadius(3)
  }

  private currentChapterLabel(job: DurablePolishJob): string {
    if (job.targets.length === 0) return '未选择章节';
    const index: number = Math.max(0, Math.min(job.cursor, job.targets.length - 1));
    return `第 ${job.targets[index].ordinal} 章`;
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
    if (stage === 'completed') return '本批次已完成，可从工作区版本记录恢复。';
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

  private incompleteCount(job: DurablePolishJob): number {
    return job.targets.length - job.progress.length;
  }

  private progressFor(job: DurablePolishJob, chapterId: string): PolishProgress | null {
    for (let index: number = 0; index < job.progress.length; index++) {
      if (job.progress[index].chapterId === chapterId) return job.progress[index];
    }
    return null;
  }

  private shortReceipt(receipt: string): string {
    return receipt.length <= 12 ? receipt : `${receipt.slice(0, 12)}…`;
  }

  private progressPercent(done: number, total: number): number {
    if (total <= 0) return 0;
    return Math.max(0, Math.min(100, Math.floor(done * 100 / total)));
  }
}
