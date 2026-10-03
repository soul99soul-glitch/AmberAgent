// NovelGhostwriteCard — C6 durable ghostwrite job projection and controls.
// Job facts remain in the domain; this component holds only local detail expansion state.
// E13:联合审稿段由 NovelChapterVerdictCard 承接(阻断/需重写/通过的真实裁定展示)。

import { MOTION_OVERLAY,
  ACCENT, ACCENT_INK, ACCENT_LIGHT, ERROR, INK, INK2, INK3, INK4,
  LINE, LINE2, RAISED, SURFACE, SURFACE2,
} from '../design/tokens.ets';
import {
  DEFAULT_GHOSTWRITE_CHAPTER_COUNT, MAX_GHOSTWRITE_CHAPTER_COUNT,
  MIN_GHOSTWRITE_CHAPTER_COUNT,
} from '@amber/deepread-domain';
import type {
  DurableGhostwriteJob, GhostwriteStage,
} from '@amber/deepread-domain';
import { NovelChapterVerdictCard } from './NovelChapterVerdictCard.ets';

@Component
export struct NovelGhostwriteCard {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop job: DurableGhostwriteJob | null = null;
  @Prop planReady: boolean = false;
  @Prop modelReady: boolean = false;
  @Prop planDisabledReason: string = '请先在设定中保存本章计划。';
  @Prop modelDisabledReason: string = '写作或审稿模型当前不可用。';
  @Prop busy: boolean = false;
  @Prop @Watch('applySuggestedCount') suggestedChapterCount: number = DEFAULT_GHOSTWRITE_CHAPTER_COUNT;

  onStart: (targetChapterCount: number) => void = (_targetChapterCount: number): void => {};
  onPause: () => void = (): void => {};
  onResume: () => void = (): void => {};
  onRetry: () => void = (): void => {};
  onCancel: () => void = (): void => {};

  @State private targetChapterCount: number = DEFAULT_GHOSTWRITE_CHAPTER_COUNT;
  @State private planExpanded: boolean = false;
  @State private candidateExpanded: boolean = false;

  aboutToAppear(): void { this.applySuggestedCount(); }
  private applySuggestedCount(): void {
    if (this.job === null) this.targetChapterCount = this.suggestedChapterCount;
  }

  build(): void {
    // 根容器不内滚:内容各段已 maxLines 封顶,滚动一律交给宿主(聊天顶/项目控制 sheet);
    // 此前根 Scroll 嵌在宿主滚动里,部分机型手势嵌套卡顿、无法下滑
    Column() {
      if (this.job === null) this.startBody();
      else this.jobBody();
    }
    .width('100%').padding({ left: 16, right: 16, top: 10, bottom: 18 })
    .alignItems(HorizontalAlign.Start)
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').backgroundColor(SURFACE)
  }

  @Builder
  private startBody(): void {
    Column() {
      this.header(false)
      // 目标章节滑杆(规范:蓝轨道要 accent 选中色 + LINE2 轨道;原自绘步进盒已撤)
      Row() {
        Text('目标章节').fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK).layoutWeight(1)
        Text(`${this.targetChapterCount} 章`).fontSize(13).fontWeight(FontWeight.Medium)
          .fontColor(INK).textAlign(TextAlign.End)
      }
      .width('100%').margin({ top: 14 })
      Row({ space: 12 }) {
        Slider({
          value: this.targetChapterCount,
          min: MIN_GHOSTWRITE_CHAPTER_COUNT,
          max: MAX_GHOSTWRITE_CHAPTER_COUNT,
          step: 1,
          style: SliderStyle.OutSet,
        })
          .layoutWeight(1)
          .selectedColor(ACCENT)
          .trackColor(LINE2)
          .onChange((value: number): void => {
            this.targetChapterCount = Math.round(value);
          })
          .accessibilityText('目标章节数')
      }
      .width('100%').alignItems(VerticalAlign.Center)
      Text(`每批 ${MIN_GHOSTWRITE_CHAPTER_COUNT}–${MAX_GHOSTWRITE_CHAPTER_COUNT} 章，默认 ${DEFAULT_GHOSTWRITE_CHAPTER_COUNT} 章。`)
        .fontSize(12).fontColor(INK3).lineHeight(18).margin({ top: 4 })
      this.startGate('本章计划')
      this.startGate('职责模型')
      this.primaryButton('开始代笔', (): void => {
        if (this.canStart()) this.onStart(this.targetChapterCount);
      })
    }
    .width('100%').padding(14).backgroundColor(SURFACE2).borderRadius(14)
    .border({ width: 1, color: LINE })
  }

  @Builder
  private startGate(title: '本章计划' | '职责模型'): void {
    Row() {
      Text(this.gateReady(title) ? '已就绪' : '需处理').fontSize(11).fontColor(this.gateReady(title) ? ACCENT : ERROR)
        .width(52).height(24).textAlign(TextAlign.Center).borderRadius(12)
        .backgroundColor(this.gateReady(title) ? ACCENT_LIGHT : SURFACE)
      Column() {
        Text(title).fontSize(13).fontColor(INK2)
        Text(this.gateReady(title) ? '启动时会重新读取并冻结。' : this.gateReason(title)).fontSize(11)
          .fontColor(this.gateReady(title) ? INK3 : ERROR).lineHeight(16).maxLines(2)
          .textOverflow({ overflow: TextOverflow.Ellipsis }).margin({ top: 2 })
      }
      .layoutWeight(1).alignItems(HorizontalAlign.Start).margin({ left: 10 })
    }
    .width('100%').constraintSize({ minHeight: 52 }).padding({ top: 7, bottom: 7 })
    .border({ width: { top: 0.5 }, color: { top: LINE } }).margin({ top: 10 })
    .alignItems(VerticalAlign.Center)
  }

  @Builder
  private jobBody(): void {
    if (this.job !== null) {
      Column() {
        this.header(true)
        Row() {
          Column() {
            Text(`${this.job.progress.length}/${this.job.targetChapterCount}`).fontSize(22).fontWeight(FontWeight.Bold).fontColor(INK)
            Text('receipt / 目标').fontSize(11).fontColor(INK3).margin({ top: 2 })
          }
          .alignItems(HorizontalAlign.Start)
          Blank()
          Column() {
            Text(`第 ${this.job.currentChapterOrdinal} 章`).fontSize(14).fontWeight(FontWeight.Medium).fontColor(INK)
            Text(this.stageLabel(this.job.stage)).fontSize(12).fontColor(this.stageColor(this.job.stage)).margin({ top: 3 })
          }
          .alignItems(HorizontalAlign.End)
        }
        .width('100%').margin({ top: 12, bottom: 10 })
        this.progressBar()
        if (this.job.stage === 'waiting_user') {
          Text('正在等待你的回答；请在创作对话中的共享 ask_user 卡继续。')
            .fontSize(12).fontColor(INK2).lineHeight(18).margin({ top: 10 })
        }
        if (this.job.failure !== null && this.job.failure.trim().length > 0) {
          Text(this.job.failure).fontSize(12).fontColor(ERROR).lineHeight(18).maxLines(4)
            .textOverflow({ overflow: TextOverflow.Ellipsis }).margin({ top: 10 })
        }
        this.frozenPlanSection()
        this.candidateSection()
        NovelChapterVerdictCard({ review: this.job.review, candidate: this.job.candidate, frozenPlan: this.job.frozenPlan })
        this.actionRow()
      }
      .width('100%').padding(14).backgroundColor(SURFACE2).borderRadius(14)
      .border({ width: 1, color: this.stageColor(this.job.stage) }).alignItems(HorizontalAlign.Start)
    }
  }

  @Builder
  private frozenPlanSection(): void {
    if (this.job !== null && this.job.frozenPlan !== null) {
      Column() {
        this.detailToggle('plan')
        if (this.planExpanded) {
          Text(this.job.frozenPlan.content).fontSize(12).fontColor(INK2).lineHeight(18).maxLines(12)
            .textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%').margin({ top: 8 })
          .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
            .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
        }
      }
      .width('100%').padding({ top: 11, bottom: 3 }).clip(true)
      .border({ width: { top: 0.5 }, color: { top: LINE } }).margin({ top: 12 })
    }
  }

  @Builder
  private candidateSection(): void {
    if (this.job !== null && this.job.candidate !== null) {
      Column() {
        this.detailToggle('candidate')
        if (this.candidateExpanded) {
          Column() {
            Text(this.job.candidate.title).fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK).margin({ top: 8 })
            Text('候选未收录前不会改变正文。').fontSize(11).fontColor(INK3).margin({ top: 3 })
            Text(this.job.candidate.content).fontSize(12).fontColor(INK2).lineHeight(18).maxLines(16)
              .textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%').margin({ top: 8 })
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
  private detailToggle(kind: 'plan' | 'candidate'): void {
    Row() {
      Column() {
        Text(kind === 'plan' ? '冻结计划' : '候选正文').fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK)
        Text(this.job === null ? '' : kind === 'plan' ? this.frozenPlanSummary(this.job)
          : this.job.candidate === null ? '' : `第 ${this.job.candidate.chapterOrdinal} 章 · ${this.job.candidate.attempt === 0 ? '本轮候选' : `自动返修 ${this.job.candidate.attempt} 次`}`)
          .fontSize(11).fontColor(INK3).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          .width('100%').margin({ top: 2 })
      }
      .layoutWeight(1).alignItems(HorizontalAlign.Start)
      Text((kind === 'plan' ? this.planExpanded : this.candidateExpanded) ? '收起' : '查看')
        .fontSize(12).fontColor(ACCENT).width(52).height(44)
        .textAlign(TextAlign.Center).padding({ top: 13, bottom: 13 })
        .accessibilityText(`${(kind === 'plan' ? this.planExpanded : this.candidateExpanded) ? '收起' : '查看'}${kind === 'plan' ? '冻结计划' : '候选正文'}`)
        .onClick((): void => {
          this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
            if (kind === 'plan') this.planExpanded = !this.planExpanded;
            else this.candidateExpanded = !this.candidateExpanded;
          });
        })
    }
    .width('100%').constraintSize({ minHeight: 44 }).alignItems(VerticalAlign.Center)
  }

  @Builder
  private actionRow(): void {
    if (this.job !== null) {
      Row() {
        if (this.job.stage === 'paused') {
          this.smallButton('继续', ACCENT, ACCENT_INK, (): void => { this.onResume(); })
          this.smallButton('取消', SURFACE, ERROR, (): void => { this.onCancel(); })
        } else if (this.job.stage === 'failed') {
          this.smallButton('重试', ACCENT, ACCENT_INK, (): void => { this.onRetry(); })
          this.smallButton('取消', SURFACE, ERROR, (): void => { this.onCancel(); })
        } else if (this.job.stage !== 'cancelled' && this.job.stage !== 'completed' && this.job.stage !== 'waiting_user') {
          this.smallButton('暂停', SURFACE, ACCENT, (): void => { this.onPause(); })
          this.smallButton('取消', SURFACE, ERROR, (): void => { this.onCancel(); })
        } else if (this.job.stage === 'waiting_user') {
          this.smallButton('取消', SURFACE, ERROR, (): void => { this.onCancel(); })
        }
      }
      .width('100%').margin({ top: 14 }).justifyContent(FlexAlign.End)
    }
  }

  @Builder
  private primaryButton(label: string, action: () => void): void {
    Text(label).fontSize(14).fontWeight(FontWeight.Medium).fontColor(this.canStart() ? ACCENT_INK : INK3)
      .width('100%').height(44).textAlign(TextAlign.Center).padding({ top: 12, bottom: 12 })
      .backgroundColor(this.canStart() ? ACCENT : SURFACE).borderRadius(12)
      .border({ width: 1, color: this.canStart() ? ACCENT : LINE }).margin({ top: 14 })
      .opacity(this.canStart() ? 1 : 0.5).onClick((): void => { if (this.canStart()) action(); })
  }

  @Builder
  private smallButton(label: string, background: string, color: string, action: () => void): void {
    Text(label).fontSize(13).fontWeight(FontWeight.Medium).fontColor(!this.busy ? color : INK4)
      .constraintSize({ minWidth: 72, minHeight: 44 }).height(44).textAlign(TextAlign.Center)
      .padding({ left: 12, right: 12, top: 12, bottom: 12 }).backgroundColor(background).borderRadius(12)
      .border({ width: 1, color: color === ERROR ? ERROR : LINE }).margin({ left: 8 })
      .opacity(!this.busy ? 1 : 0.5).onClick((): void => { if (!this.busy) action(); })
  }

  @Builder
  private header(jobMode: boolean): void {
    Column() {
      Text('批量代笔').fontSize(17).fontWeight(FontWeight.Bold).fontColor(INK)
      Text(jobMode && this.job !== null ? this.stageDescription(this.job.stage) : '按冻结计划逐章写作、审稿并收录。').fontSize(12).fontColor(INK3).lineHeight(18).maxLines(2)
        .textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%').margin({ top: 4 })
    }
    .width('100%').alignItems(HorizontalAlign.Start)
  }

  @Builder
  private progressBar(): void {
    Row() {
      Row() {}.width(`${this.job !== null ? this.progressPercent(this.job.progress.length, this.job.targetChapterCount) : 0}%`).height(6).backgroundColor(ACCENT).borderRadius(3)
    }
    .width('100%').height(6).backgroundColor(RAISED).borderRadius(3)
  }

  private gateReady(title: '本章计划' | '职责模型'): boolean {
    return title === '本章计划' ? this.planReady : this.modelReady;
  }

  private gateReason(title: '本章计划' | '职责模型'): string {
    return title === '本章计划' ? this.planDisabledReason : this.modelDisabledReason;
  }

  private canStart(): boolean {
    return !this.busy && this.planReady && this.modelReady;
  }

  private progressPercent(done: number, total: number): number {
    if (total <= 0) return 0;
    return Math.max(0, Math.min(100, Math.floor(done * 100 / total)));
  }

  private stageLabel(stage: GhostwriteStage): string {
    if (stage === 'planning') return '准备下一章计划';
    if (stage === 'writing') return '正在写作';
    if (stage === 'reviewing') return '正在联合审稿';
    if (stage === 'rewriting_1') return '定向重写 1/2';
    if (stage === 'rewriting_2') return '定向重写 2/2';
    if (stage === 'committing') return '正在原子收录';
    if (stage === 'paused') return '已暂停';
    if (stage === 'waiting_user') return '等待你的回答';
    if (stage === 'failed') return '执行失败';
    if (stage === 'cancelled') return '已取消';
    return '已完成';
  }

  private stageDescription(stage: GhostwriteStage): string {
    if (stage === 'waiting_user') return '请在创作对话中回答共享 ask_user 卡。';
    if (stage === 'failed') return '保留冻结计划与进度，可重试同一任务。';
    if (stage === 'paused') return '暂停会在安全检查点生效，正文不会被半途写入。';
    if (stage === 'completed') return '本批次已完成，提交记录保留在当前分支。';
    return '候选与审稿结果在原子收录前均不改变正文。';
  }

  private stageColor(stage: GhostwriteStage): string {
    if (stage === 'failed') return ERROR;
    if (stage === 'cancelled' || stage === 'paused' || stage === 'waiting_user') return INK3;
    return ACCENT;
  }

  private shortDigest(digest: string): string {
    return digest.length <= 12 ? digest : `${digest.slice(0, 12)}…`;
  }

  private frozenPlanSummary(job: DurableGhostwriteJob): string {
    if (job.frozenPlan === null) return '';
    const content: string = job.frozenPlan.content.trim();
    const preview: string = content.length <= 32 ? content : `${content.slice(0, 32)}…`;
    return `${job.frozenPlan.planId} · ${this.shortDigest(job.frozenPlan.digest)} · ${preview}`;
  }
}
