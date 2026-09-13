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

import { router, promptAction } from '@kit.ArkUI';
import { common } from '@kit.AbilityKit';
import { Icon } from '../components/ui/Icon.ets';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type {
  CorePoint, DeepReadCacheEntry, DeepReadGenerationStage, DeepReadImageAsset, DeepReadOutput,
  DeepReadScheduler, Perspective, Quote, ReadingLink, RunResult, TimelineEvent,
} from '@amber/deepread-domain';
import { displayHeroImageUrl, firstFailureMessage, firstFailedStage, isComplete, isCacheEntryExpired, deepReadProgressSnapshot, STAGE_ORDER } from '@amber/deepread-domain';
import type { DeepReadProgressSnapshot } from '@amber/deepread-domain';
import { MarkdownText } from '../components/MarkdownText.ets';
import { FONT_SERIF, FONT_MONO_SEMIBOLD, FONT_SANS_MEDIUM } from '../design/fonts.ets';
import { DeepReadConfirmationDialog } from '../components/DeepReadConfirmationDialog.ets';
import { getDeepReadScheduler, getRepository, getChatKvStore } from '../di/AppContainer.ets';
import { renderEditorialSlantHtml, DEEPREAD_TEMPLATE_EDITORIAL_SLANT } from '../platform_impl/DeepReadTemplate.ets';
import { webview } from '@kit.ArkWeb';
import {
  BG, INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, LINE2, ACCENT, ACCENT_LIGHT,
  BODY, BODY2, META, LABEL, TITLE, ERROR,
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
  sourceUrl?: string;
  force?: string;
  fromHistory?: string;
}
class DeepReadArticlePage {
topicId: string = '';
title: string = '';
private sourceUrl: string = '';
output: DeepReadOutput | null = null;
running: boolean = true;
errorMsg: string = '';
currentStage: string = '';
cancelled: boolean = false;
confirmed: boolean = true;
progress: DeepReadProgressSnapshot = { percent: 0, label: '未开始' };
historyExpired: boolean = false;
templateId: string = 'none';
fontScale: number = FONT_SCALE_DEFAULT;
fontSerif: boolean = false;
private pageAlive: boolean = false;
private pageToken: number = 0;
private unsubscribeOutput: (() => void) | null = null;
private unsubscribeRunning: (() => void) | null = null;
private scheduler: DeepReadScheduler | null = null;
private async loadArticle(token: number, force: boolean): Promise<void> {
    const repository = getRepository();
    const cached: DeepReadCacheEntry | null = await repository.get(this.topicId);
    if (!this.pageAlive || token !== this.pageToken) return;
    if (cached !== null) {
      this.historyExpired = isCacheEntryExpired(cached);
      if (!force) {
        this.applyOutput(cached.output, token);
        if (isComplete(cached.output)) {
          this.running = false;
          return;
        }
      }
    }
    // P5:阅读体验初始值(字号百分比/字体模式;ChatKvStore 只存字符串)
    const scaleRaw: string | null = await getChatKvStore().get('deepread_font_scale');
    const scalePct: number = scaleRaw !== null ? parseFloat(scaleRaw) : 100;
    this.fontScale = Math.max(0.8, Math.min(1.3, (Number.isNaN(scalePct) ? 100 : scalePct) / 100));
    const fontMode: string | null = await getChatKvStore().get('deepread_font_mode');
    this.fontSerif = fontMode === 'serif';
    const tpl: string | null = await getChatKvStore().get('deepread_template_id');
    this.templateId = tpl !== null ? tpl : 'none';
    // 首用确认门(Android deepReadFirstUseConfirmed):未确认不自动跑
    const confirmedRaw: string | null = await getChatKvStore().get('deepread_first_use_confirmed');
    this.confirmed = confirmedRaw === 'true';
    if (!this.confirmed) {
      this.running = false;
      return;
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
    const result: RunResult = await this.scheduler.run(this.topicId, this.title,
      { force, seedUrl: this.sourceUrl.length > 0 ? this.sourceUrl : null });
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
    const snap: DeepReadProgressSnapshot = deepReadProgressSnapshot(next, this.running);
    this.progress = snap;
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
}
module.exports = { DeepReadArticlePage };