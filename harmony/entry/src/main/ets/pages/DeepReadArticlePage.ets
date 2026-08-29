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
import { displayHeroImageUrl, firstFailureMessage, firstFailedStage, isComplete, isCacheEntryExpired, deepReadProgressSnapshot, STAGE_ORDER } from '@amber/deepread-domain';
import type { DeepReadProgressSnapshot } from '@amber/deepread-domain';
import { MarkdownText } from '../components/MarkdownText.ets';
import { FONT_SERIF } from '../design/fonts.ets';
import { DeepReadConfirmationDialog } from '../components/DeepReadConfirmationDialog.ets';
import { getDeepReadScheduler, getRepository, getChatKvStore } from '../di/AppContainer.ets';
import { renderEditorialSlantHtml, DEEPREAD_TEMPLATE_EDITORIAL_SLANT } from '../platform_impl/DeepReadTemplate.ets';
import { webview } from '@kit.ArkWeb';
import {
  BG, INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, ACCENT, ACCENT_LIGHT,
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

@Entry
@Component
struct DeepReadArticlePage {
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State topicId: string = '';
  @State title: string = '';
  // 种子链接(热榜条目 url;run 的 seedUrl — 来源收集直接抓该页而非仅按标题搜索)
  private sourceUrl: string = '';
  @State output: DeepReadOutput | null = null;
  @State running: boolean = true;
  @State errorMsg: string = '';
  @State currentStage: string = '';
  @State cancelled: boolean = false;
  @State confirmed: boolean = true;      // 首用确认(KV deepread_first_use_confirmed)
  @State progress: DeepReadProgressSnapshot = { percent: 0, label: '未开始' };
  @State fromHistory: boolean = false;
  @State historyExpired: boolean = false;
  @State templateId: string = 'none';   // 模板选择(editorial_slant → WebView 渲染)
  private webController: webview.WebviewController = new webview.WebviewController();

  private openTemplateInWeb(): void {
    // 预留:外部打开
  }
  // G5-S1:字号倍率(驱动 ArticleRenderer)
  @State fontScale: number = FONT_SCALE_DEFAULT;
  @State fontSerif: boolean = false;
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
    this.sourceUrl = params.sourceUrl ?? '';
    const wantsForce: boolean = params.force === 'true';
    this.fromHistory = params.fromHistory === 'true';
    if (this.topicId.length === 0 || this.title.length === 0) {
      this.errorMsg = '文章参数缺失';
      this.running = false;
      return;
    }
    hilog.info(DOMAIN, TAG, 'aboutToAppear: topicId=%{public}s title=%{public}s', this.topicId, this.title);
    this.loadArticle(token, wantsForce).catch((e: Error): void => {
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

  // 基础稿判定:fallback 稿部分 READY 但未完成
  private basicDraftNotice(): string {
    if (this.output === null || this.running || isComplete(this.output)) return '';
    if (this.partialError().length > 0) return '';
    const hasReady: boolean = STAGE_ORDER.some((st: DeepReadGenerationStage): boolean =>
      this.output !== null && this.output.sectionStates[st]?.status === 'READY');
    return hasReady ? '基础稿，可继续增强' : '';
  }

  // 仅重试第一个失败段(Android retryFirstFailure → runOne)
  private retryFirstFailure(): void {
    if (this.output === null) {
      this.generateArticle();
      return;
    }
    const stage: DeepReadGenerationStage | null = firstFailedStage(this.output);
    if (stage === null) {
      this.generateArticle();
      return;
    }
    this.running = true;
    this.errorMsg = '';
    getDeepReadScheduler().then((sched: DeepReadScheduler): Promise<RunResult> => {
      this.scheduler = sched;
      return sched.runSection(this.topicId, this.title, stage,
        this.sourceUrl.length > 0 ? this.sourceUrl : null);
    }).then((result: RunResult): void => {
      if (!this.pageAlive) return;
      if (result.output !== null) this.applyOutput(result.output, ++this.pageToken);
      if (!result.ok && result.error !== 'aborted') {
        this.errorMsg = result.error ?? '该段重试失败';
      }
      this.running = false;
    }).catch((e: Error): void => {
      if (this.pageAlive) {
        this.errorMsg = e.message;
        this.running = false;
      }
    });
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
        if (!this.confirmed) {
          // 首用确认页(Android DeepReadConfirmation)
          Column() {
            Blank()
            Column() {
              Text('深度阅读').fontSize(TITLE).fontWeight(FontWeight.Medium)
                .fontFamily(FONT_SERIF).fontColor(INK)
              Text('将围绕该话题收集资料并生成一篇深度文章')
                .fontSize(BODY2).fontColor(INK2).margin({ top: 10 })
                .textAlign(TextAlign.Center)
              Text('深度阅读会消耗更多 tokens')
                .fontSize(LABEL).fontColor(INK3).margin({ top: 6 })
              Text('开始使用').fontSize(BODY).fontColor(ACCENT)
                .padding({ left: 28, right: 28, top: 12, bottom: 12 })
                .margin({ top: 24 })
                .onClick((): void => {
                  getChatKvStore().put('deepread_first_use_confirmed', 'true')
                    .then((): void => {
                      this.confirmed = true;
                      this.loadArticle(++this.pageToken, false).catch((): void => {});
                    });
                })
            }
            .alignItems(HorizontalAlign.Center)
            .width('86%')
            .padding({ top: 32, bottom: 32 })
            .backgroundColor(SURFACE)
            .borderRadius(16)
            Blank()
          }.width('100%').layoutWeight(1)
        } else if (this.running) {
          // G5-S1:生成中 — RunningStageNotice(百分比胶囊 + 阶段)
          Column() {
            LoadingProgress().width(40).height(40).color(ACCENT)
            Row() {
              Text(`${this.progress.label} ${this.progress.percent}%`)
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
        } else if (this.output !== null && this.templateId === 'editorial_slant') {
          // P8:模板渲染路径(editorial_slant → WebView;Web 组件 full-size)
          Column() {
            Web({ src: 'about:blank', controller: this.webController })
              .width('100%').layoutWeight(1)
              .backgroundColor(BG)
              .javaScriptAccess(false)
              .onControllerAttached((): void => {
                // data: URL 带 '#' 色值会白屏 → loadData + 官方空格兜底参数
                const html: string = renderEditorialSlantHtml(this.title, this.output);
                this.webController.loadData(
                  encodeURIComponent(html).replace(/#/g, '%23'),
                  'text/html', 'UTF-8', ' ', ' ');
              })
          }
          .width('100%').layoutWeight(1)
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
              if (this.fromHistory && this.historyExpired) {
                Text('内容已过 24 小时，可能需要重新生成')
                  .fontSize(META).fontColor(INK3)
                  .width('100%').textAlign(TextAlign.Center)
                  .padding({ left: 12, right: 12, top: 8, bottom: 8 })
                  .backgroundColor(SURFACE2)
                  .borderRadius(50)
                  .margin({ top: 10 })
              }
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
              if (this.basicDraftNotice().length > 0) {
                // 基础稿提示(Android "基础稿，可继续增强")
                Text('基础稿，可继续增强')
                  .fontSize(META).fontColor(INK3)
                  .width('100%').textAlign(TextAlign.Center)
                  .padding({ top: 8, bottom: 4 })
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
