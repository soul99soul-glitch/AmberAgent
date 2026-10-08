// DeepRead article controller. All layouts share recovery actions and the same content snapshot.
import { router, promptAction } from '@kit.ArkUI';
import { common } from '@kit.AbilityKit';
import { Icon } from '../components/ui/Icon.ets';
import { DeepReadNewsroomDesk } from '../components/deepread/DeepReadNewsroomDesk.ets';
import { DeepReadArticleToolbar } from '../components/deepread/DeepReadArticleToolbar.ets';
import { DeepReadArticleHostToolbar } from '../components/deepread/DeepReadArticleHostToolbar.ets';
import { DeepReadArticleStatusPanel } from '../components/deepread/DeepReadArticleStatusPanel.ets';
import { DeepReadArticleHostRecovery } from '../components/deepread/DeepReadArticleHostRecovery.ets';
import { DeepReadArticlePressMoment } from '../components/deepread/DeepReadArticlePressMoment.ets';
import { loadDeepReadAppearance, readerCanvas } from '../platform_impl/DeepReadAppearance.ets';
import { DeepReadDesign as D } from '../design/DeepReadDesign.ets';
import { StudioButton } from '../components/studio/StudioButton.ets';
import { getProductKind } from '../platform_impl/ProductIdentity.ets';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type {
  DeepReadCacheEntry, DeepReadGenerationStage, DeepReadOutput,
  DeepReadRepository, DeepReadScheduler, RunResult, DeepReadInputSource, DeepReadActiveRun,
} from '@amber/deepread-domain';
import { makeEmptyDeepReadOutput, hasDisplayableDeepReadOutput, firstFailureMessage, firstFailedStage, isComplete, isCacheEntryExpired, deepReadProgressSnapshot, STAGE_ORDER } from '@amber/deepread-domain';
import type { DeepReadProgressSnapshot } from '@amber/deepread-domain';
import { DeepReadNativeArticleBody } from '../components/DeepReadNativeArticleBody.ets';
import { FONT_SERIF } from '../design/fonts.ets';
import { DeepReadConfirmationDialog } from '../components/DeepReadConfirmationDialog.ets';
import { DeepReadExportPanel } from '../components/DeepReadExportPanel.ets';
import { DeepReadSourcesContent } from './DeepReadSourcesPage.ets';
import { DeepReadGoogleSearchHost } from '../components/DeepReadGoogleSearchHost.ets';
import { getDeepReadScheduler, getRepository, getChatKvStore, getAppContainer, getDeepReadArtifactStore } from '../di/AppContainer.ets';
import type { DeepReadArtifactStatus } from '../platform_impl/DeepReadArtifactStore.ets';
import { DeepReadRenderOptions, resolveDeepReadArticleTemplate, renderCapturedDeepReadTemplateHtml,
  captureDeepReadTemplate, DeepReadArticleTemplateResolution } from '../platform_impl/DeepReadTemplate.ets';
import { webview } from '@kit.ArkWeb';
import {
  BG, INK, INK2, INK3, SURFACE, SURFACE2, LINE, ACCENT,
  BODY, BODY2, META, LABEL, TITLE, ERROR,
} from '../design/tokens.ets';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.Article';

// 字号缩放上下限 — 照搬 Android BoardSettings.kt:131-133
const FONT_SCALE_MIN: number = 0.7;
const FONT_SCALE_MAX: number = 1.8;
const FONT_SCALE_STEP: number = 0.05;
const FONT_SCALE_DEFAULT: number = 1.0;
const GENERATION_STEP_COUNT: number = 6;

interface ArticleParams {
  topicId: string;
  title: string;
  sourceUrl?: string;
  seedUrlsJson?: string;
  inputSourcesJson?: string;
  force?: string;
  fromHistory?: string;
}

@Entry
@Component
struct DeepReadArticlePage {
  @StorageProp('themeEpoch') @Watch('clearEditorialMotion') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('appBackgrounded') @Watch('clearEditorialMotion') appBackgrounded: boolean = false;
  @StorageProp('reduceMotion') reduceMotion: boolean = false;
  @StorageProp('darkMode') darkMode: boolean = false;
  @StorageProp('deepread.appearance.readerLayout') readerLayout: string = 'classic';
  @StorageProp('deepread.appearance.readerStyle') readerStyle: string = 'classic';
  @StorageProp('deepreadAppearanceEpoch') @Watch('appearanceChanged') appearanceEpoch: number = 0;
  @State private pageVisible: boolean = true;
  @State private runStartsUnreadable: boolean = false;
  @State private newsroomStage: string = 'COLLECTING';
  @State private newsroomLabel: string = '收集来源';
  @State private completionStamp: number = 0;
  @State private completionInscription: string = '付印';
  @State private completionCaption: string = '文章已排版完成';
  private publicationRequest: number = 0;
  private observedRunStart: number = 0;
  private observedRunCycle: number = -1;
  private observedRunFirstDraft: boolean = false;
  private publishedRunStart: number = 0;
  private motionCycle: number = 0;
  private unsubscribeActivity: (() => void) | null = null;
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @State topicId: string = '';
  @State title: string = '';
  // 种子链接(热榜条目 url;run 的 seedUrl — 来源收集直接抓该页而非仅按标题搜索)
  private sourceUrl: string = '';
  private seedUrls: string[] = [];
  private discoveryInputs: DeepReadInputSource[] = [];
  @State output: DeepReadOutput | null = null;
  @State running: boolean = true;
  @State errorMsg: string = '';
  @State currentStage: string = '';
  @State cancelled: boolean = false;
  // UI-F30:重进时存在未完成/已取消缓存但无活动 run → 显示显式「继续生成」,不自动 REPLACE
  @State awaitingContinue: boolean = false;
  @State confirmed: boolean = true;      // 首用确认(KV deepread_first_use_confirmed)
  @State progress: DeepReadProgressSnapshot = { percent: 0, label: '未开始' };
  @State fromHistory: boolean = false;
  @State historyExpired: boolean = false;
  @State templateId: string = 'none';   // 模板选择(editorial_slant/custom_* → WebView 渲染)
  @State templateName: string = '默认排版';
  @State templateMessage: string = '';
  @State nativeReadingOverride: boolean = false;
  private webController: webview.WebviewController = new webview.WebviewController();
  // Web 组件是否已 attach:未 attach 时 loadData 会失败,zoom 刷新需门控
  private webAttached: boolean = false;
  private webInitialized: boolean = false;
  private webSession: number = 0;
  @State webRenderError: string = '';
  @State workspaceError: string = '';
  @State workspaceSaving: boolean = false;
  private workspaceRequest: number = 0;
  private workspaceSaveOwner: number = 0;

  // G5-S1:字号倍率(驱动 ArticleRenderer)
  @State fontScale: number = FONT_SCALE_DEFAULT;
  @State fontSerif: boolean = false;
  // G5-S1:重新生成确认对话框
  @State showRegenerateDialog: boolean = false;
  @State private articlePanel: string = '';
  @State private articlePanelSession: number = 0;
  private articleScroller: Scroller = new Scroller();
  private pendingAppearanceRefresh: boolean = false;
  private pageAlive: boolean = false;
  private pageToken: number = 0;
  private unsubscribeOutput: (() => void) | null = null;
  private unsubscribeRunning: (() => void) | null = null;
  private scheduler: DeepReadScheduler | null = null;

  aboutToAppear(): void {
    this.pageAlive = true;
    this.pageVisible = true;
    this.clearEditorialMotion();
    this.webSession++;
    this.webAttached = false;
    this.webInitialized = false;
    this.articlePanel = '';
    this.workspaceSaving = false;
    this.workspaceSaveOwner = ++this.workspaceRequest;
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
    try {
      const seeds: string[] = JSON.parse(params.seedUrlsJson ?? '[]') as string[];
      this.seedUrls = Array.isArray(seeds) ? seeds.filter((url: string): boolean =>
        typeof url === 'string' && /^https?:\/\//i.test(url)) : [];
    } catch (_) { this.seedUrls = []; }
    try {
      const inputs: DeepReadInputSource[] = JSON.parse(params.inputSourcesJson ?? '[]') as DeepReadInputSource[];
      this.discoveryInputs = Array.isArray(inputs) ? inputs : [];
    } catch (_) {
      this.errorMsg = '阅读来源参数无法读取';
      this.running = false;
      return;
    }
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
    this.pageVisible = false;
    this.clearEditorialMotion();
    if (this.unsubscribeActivity !== null) {
      this.unsubscribeActivity();
      this.unsubscribeActivity = null;
    }
    this.articlePanel = '';
    this.webAttached = false;
    this.webInitialized = false;
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

  onPageHide(): void {
    this.pageVisible = false;
    this.clearEditorialMotion();
  }

  onPageShow(): void {
    if (!this.pageVisible) this.clearEditorialMotion();
    this.pageVisible = true;
    if (this.pendingAppearanceRefresh) {
      this.pendingAppearanceRefresh = false;
      this.refreshTemplateWeb();
    }
  }

  private clearEditorialMotion(): void {
    this.motionCycle++;
    this.publicationRequest++;
    this.observedRunCycle = -1;
    this.completionStamp = 0;
  }

  private editorialActive(): boolean {
    return getProductKind() === 'deepread' && this.pageAlive && this.pageVisible && !this.appBackgrounded;
  }

  private celebratePublished(result: RunResult, cycle: number, firstDraft: boolean = false): void {
    if (!this.editorialActive() || cycle !== this.motionCycle || this.cancelled
      || !result.ok || result.output === null || !isComplete(result.output)) return;
    if (this.observedRunCycle === cycle && this.observedRunStart > 0) {
      if (this.publishedRunStart === this.observedRunStart) return;
      this.publishedRunStart = this.observedRunStart;
    }
    const request: number = ++this.publicationRequest;
    const show: (count: number) => void = (count: number): void => {
      if (!this.editorialActive() || cycle !== this.motionCycle || request !== this.publicationRequest || this.cancelled) return;
      this.completionInscription = firstDraft && count === 1 ? '首篇' : firstDraft && count === 10 ? '十篇'
        : firstDraft && count === 100 ? '百篇' : '付印';
      this.completionCaption = this.completionInscription === '付印' ? '文章已排版完成' : `你的第 ${count} 篇深度阅读`;
      this.completionStamp++;
    };
    if (!firstDraft) { show(0); return; }
    // RdbRepository.listHistory(0) means all retained rows, so failed rows do not truncate milestone counts.
    getRepository().listHistory(0).then((entries: DeepReadCacheEntry[]): void => {
      show(entries.filter((entry: DeepReadCacheEntry): boolean => isComplete(entry.output)).length);
    }).catch((): void => { show(0); });
  }

  private async celebrateObservedRun(run: DeepReadActiveRun, token: number): Promise<void> {
    const cycle: number = this.observedRunCycle;
    if (run.stage !== 'COMPLETE' || run.startedAt !== this.observedRunStart || cycle !== this.motionCycle) return;
    try {
      const entry: DeepReadCacheEntry | null = await getRepository().get(this.topicId);
      if (!this.pageAlive || token !== this.pageToken || run.startedAt !== this.observedRunStart
        || entry === null || !isComplete(entry.output)) return;
      // Scheduler publishes COMPLETE only after its final repository save has succeeded.
      this.celebratePublished({ ok: true, output: entry.output, error: null }, cycle, this.observedRunFirstDraft);
    } catch (_) { /* Decorative read failure does not change the saved article or its recovery status. */ }
  }

  onBackPress(): boolean {
    if (this.articlePanel.length > 0) {
      this.closeArticlePanel(this.articlePanelSession);
      return true;
    }
    if (!this.showRegenerateDialog) return false;
    this.showRegenerateDialog = false;
    return true;
  }

  private handleBack(): void {
    if (!this.onBackPress()) router.back();
  }

  private openExport(): void {
    if (!this.pageAlive || this.running || !this.hasReadableOutput()
      || this.output === null) return;
    this.openArticlePanel('export');
  }

  private openSources(): void {
    if (!this.pageAlive || this.topicId.length === 0) return;
    if (getProductKind() !== 'deepread') {
      router.pushUrl({ url: 'pages/DeepReadSourcesPage', params: { topicId: this.topicId } });
      return;
    }
    this.openArticlePanel('sources');
  }

  private openArticlePanel(kind: string): void {
    if (!this.pageAlive || !this.pageVisible || this.articlePanel.length > 0) return;
    this.articlePanelSession++;
    this.articlePanel = kind;
  }

  private isArticlePanelActive(kind: string, session: number): boolean {
    return this.pageAlive && this.articlePanel === kind && session === this.articlePanelSession;
  }

  private isExportActive(session: number): boolean {
    return this.pageVisible && this.isArticlePanelActive('export', session);
  }

  private closeArticlePanel(session: number): void {
    if (session === this.articlePanelSession) this.articlePanel = '';
  }

  private articlePanelTransition(): TransitionEffect {
    if (getProductKind() !== 'deepread' || this.appBackgrounded) return TransitionEffect.IDENTITY;
    if (this.reduceMotion) return TransitionEffect.OPACITY.animation({ duration: 160 });
    return TransitionEffect.asymmetric(
      TransitionEffect.move(TransitionEdge.BOTTOM).combine(TransitionEffect.OPACITY)
        .animation({ duration: 320, curve: Curve.EaseOut }),
      TransitionEffect.move(TransitionEdge.BOTTOM).combine(TransitionEffect.OPACITY)
        .animation({ duration: 250, curve: Curve.EaseIn }));
  }

  @Builder
  ArticlePanel(kind: string, session: number): void {
    Column() {
      if (kind === 'sources') {
        DeepReadSourcesContent({ topicId: this.topicId,
          isActive: (): boolean => this.isArticlePanelActive('sources', session),
          onClose: (): void => { this.closeArticlePanel(session); } })
      } else {
        DeepReadExportPanel({ topicId: this.topicId, title: this.title,
          fontScale: this.fontScale, fontSerif: this.fontSerif,
          customTemplateHtml: this.output?.templateSnapshot?.html ?? '',
          isActive: (): boolean => this.isExportActive(session),
          onClose: (): void => { this.closeArticlePanel(session); } })
      }
    }.width('100%').height('100%').transition(this.articlePanelTransition())
  }

  private openAppearanceSettings(): void {
    router.pushUrl({ url: 'pages/DeepReadAppearancePage' }).catch((error: Error): void => {
      if (this.pageAlive) promptAction.showToast({ message: '无法打开版式与样式：' + error.message, duration: 1400 });
    });
  }

  private async loadArticle(token: number, force: boolean): Promise<void> {
    const repository = getRepository();
    let cached: DeepReadCacheEntry | null = await repository.get(this.topicId);
    const hadCache: boolean = cached !== null;
    if (!this.pageAlive || token !== this.pageToken) return;
    if (cached === null && this.discoveryInputs.length > 0) {
      cached = await this.saveDiscoveryInputs(token);
      if (!this.pageAlive || token !== this.pageToken) return;
    }
    // UI-X01:显示偏好先于缓存早退读取(完整缓存也生效,不再只用默认字号/模板)
    await this.loadDisplaySettings(token);
    if (!this.pageAlive || token !== this.pageToken) return;
    if (cached !== null) {
      this.historyExpired = isCacheEntryExpired(cached);
      if (this.sourceUrl.length === 0 && this.discoveryInputs.length === 0) this.sourceUrl = cached.sourceUrl ?? '';
      if (this.seedUrls.length === 0 && this.discoveryInputs.length === 0) this.seedUrls = cached.output.inputSourceUrls ?? [];
      this.applyOutput(cached.output, token);
      await this.refreshWorkspaceStatus(token);
      if (!this.pageAlive || token !== this.pageToken) return;
    }
    // 首用确认门(Android deepReadFirstUseConfirmed):未确认不自动跑
    const confirmedRaw: string | null = await getChatKvStore().get('deepread_first_use_confirmed');
    if (!this.pageAlive || token !== this.pageToken) return;
    this.confirmed = confirmedRaw === 'true';
    if (!this.confirmed) {
      this.running = false;
      return;
    }
    this.scheduler = await getDeepReadScheduler();
    if (!this.pageAlive || token !== this.pageToken) return;
    this.runStartsUnreadable = !this.hasReadableOutput();
    this.subscribeToUpdates(repository, token);

    const isRunning: boolean = this.scheduler.isRunning(this.topicId);
    if (force && hadCache && this.discoveryInputs.length > 0 && cached !== null) {
      if (isRunning) {
        this.running = true;
        this.errorMsg = '当前任务仍在运行，请先取消，再使用最新热点资料重新生成。';
        return;
      }
      cached = await this.saveDiscoveryInputs(token, cached);
      if (!this.pageAlive || token !== this.pageToken || cached === null) return;
      this.applyOutput(cached.output, token);
    }
    // 用户显式重新生成:直接跑(force 覆盖旧轮)
    if (force) {
      await this.runNow(token, true);
      return;
    }
    // UI-F30:完整缓存 → 仅展示;已有 run 或部分/取消缓存 → 仅订阅/显式继续,不自动 REPLACE
    const cachedOutput: DeepReadOutput | null = cached !== null ? cached.output : null;
    if (isRunning) {
      // 已有活动 run:只订阅,等它自己推进/收口
      this.awaitingContinue = false;
      this.running = true;
      this.currentStage = this.phaseLabel(cachedOutput);
      return;
    }
    if (cachedOutput !== null && (isComplete(cachedOutput))) {
      this.running = false;
      this.awaitingContinue = false;
      return;
    }
    if (cachedOutput !== null) {
      // 部分进度或已取消的缓存:显式「继续生成」,不隐式新建并 REPLACE。
      // 无任何进度痕迹的空壳缓存(phase=IDLE 且无 sectionStates)按首次处理。
      const hasProgress: boolean = cachedOutput.generationPhase !== 'IDLE'
        || Object.keys(cachedOutput.sectionStates).length > 0;
      if (hasProgress) {
        this.awaitingContinue = true;
        this.running = false;
        this.errorMsg = '';
        this.currentStage = this.phaseLabel(cachedOutput);
        return;
      }
    }
    // 首次(无缓存或无进度痕迹):正常启动
    await this.runNow(token, false);
  }

  private async saveDiscoveryInputs(token: number, existing: DeepReadCacheEntry | null = null): Promise<DeepReadCacheEntry | null> {
    const output: DeepReadOutput = existing === null ? makeEmptyDeepReadOutput()
      : JSON.parse(JSON.stringify(existing.output)) as DeepReadOutput;
    output.inputSources = this.discoveryInputs;
    output.inputText = this.discoveryInputs.filter((source: DeepReadInputSource): boolean => source.kind === 'text')
      .map((source: DeepReadInputSource): string => source.content).join('\n\n');
    output.inputSourceUrls = this.seedUrls;
    if (existing === null) {
      const selected: string | null = await getChatKvStore().get('deepread_template_id');
      output.templateSnapshot = await captureDeepReadTemplate(getAppContainer().storage, selected ?? 'none');
      output.templateId = output.templateSnapshot.id;
    }
    if (!this.pageAlive || token !== this.pageToken) return null;
    const now: number = Date.now();
    const entry: DeepReadCacheEntry = existing === null ? { topicId: this.topicId, title: this.title,
      sourceUrl: this.sourceUrl.length > 0 ? this.sourceUrl : null, output: output, phase: 'IDLE',
      attemptCount: 0, lastError: null, createdAt: now, updatedAt: now, expiresAt: now + 86400000,
      templateId: output.templateId } : JSON.parse(JSON.stringify(existing)) as DeepReadCacheEntry;
    entry.output = output;
    entry.title = this.title;
    entry.sourceUrl = this.sourceUrl.length > 0 ? this.sourceUrl : null;
    entry.updatedAt = now;
    await getRepository().upsert(entry);
    return entry;
  }

  // 字号与字体为阅读偏好，文章模板由持久快照决定。
  private async loadDisplaySettings(token: number = this.pageToken): Promise<void> {
    if (getProductKind() === 'deepread') await loadDeepReadAppearance(getAppContainer().storage);
    const scalePct: number = Number(await getAppContainer().storage.get<number>('deepread_font_scale', 100));
    const fontMode: string | null = await getChatKvStore().get('deepread_font_mode');
    if (!this.pageAlive || token !== this.pageToken) return;
    this.fontScale = Math.max(FONT_SCALE_MIN, Math.min(FONT_SCALE_MAX, (Number.isFinite(scalePct) ? scalePct : 100) / 100));
    this.fontSerif = fontMode === 'serif' || (fontMode === null && getProductKind() === 'deepread');
  }

  // D1-1:WebView 模板判定(editorial_slant 或存在的自定义模板;自定义被删回退 false)
  private isWebTemplate(): boolean {
    if (this.nativeReadingOverride) return false;
    return resolveDeepReadArticleTemplate(this.output).template.kind !== 'native';
  }

  // 订阅 output/running(重复调用先退订,避免叠加)
  private subscribeToUpdates(repository: DeepReadRepository, token: number): void {
    if (this.unsubscribeOutput !== null) this.unsubscribeOutput();
    this.unsubscribeOutput = repository.observe(this.topicId).subscribe((entry: DeepReadCacheEntry | null): void => {
      if (!this.pageAlive || token !== this.pageToken || entry === null) return;
      this.applyOutput(entry.output, token);
    });
    if (this.scheduler === null) return;
    if (this.unsubscribeActivity !== null) this.unsubscribeActivity();
    this.unsubscribeActivity = this.scheduler.observeActiveRuns().subscribe((runs: DeepReadActiveRun[]): void => {
      if (!this.pageAlive || token !== this.pageToken) return;
      const run: DeepReadActiveRun | undefined = runs.find((item: DeepReadActiveRun): boolean => item.topicId === this.topicId);
      if (run === undefined) return;
      this.newsroomStage = run.stage ?? 'COLLECTING';
      this.newsroomLabel = run.label ?? '准备资料';
      if (run.startedAt > 0 && run.stage !== 'COMPLETE' && this.editorialActive()) {
        if (run.startedAt !== this.observedRunStart) this.observedRunFirstDraft = !this.hasReadableOutput();
        this.observedRunStart = run.startedAt;
        this.observedRunCycle = this.motionCycle;
      } else if (run.stage === 'COMPLETE' && run.startedAt > 0 && run.startedAt === this.observedRunStart) {
        this.celebrateObservedRun(run, token);
      }
    });
    if (this.unsubscribeRunning !== null) this.unsubscribeRunning();
    this.unsubscribeRunning = this.scheduler.observeRunning(this.topicId).subscribe((running: boolean): void => {
      if (!this.pageAlive || token !== this.pageToken) return;
      // 显式继续/已取消态下不因残留中断 phase 假装在跑(避免无限 spinner)
      this.running = (this.cancelled || this.awaitingContinue) ? false : running;
      if (running && this.currentStage.length === 0) this.currentStage = this.progressLabel('收集来源', 1);
      if (!running) this.refreshWorkspaceStatus(token);
    });
  }

  // 实际发起 run(force=false 时 run_manager 走缓存/续跑;不隐式 clear)
  private async runNow(token: number, force: boolean): Promise<void> {
    if (this.scheduler === null) return;
    const firstDraft: boolean = !this.hasReadableOutput();
    this.runStartsUnreadable = firstDraft;
    this.awaitingContinue = false;
    this.running = true;
    this.cancelled = false;
    this.errorMsg = '';
    this.currentStage = force ? this.progressLabel('收集来源', 1) : this.phaseLabel(this.output);
    this.clearEditorialMotion();
    this.newsroomStage = 'COLLECTING';
    this.newsroomLabel = '收集来源';
    const cycle: number = this.motionCycle;
    const result: RunResult = await this.scheduler.run(this.topicId, this.title,
      { force, seedUrl: this.sourceUrl.length > 0 ? this.sourceUrl : null, seedUrls: this.seedUrls,
        templateSnapshot: this.output?.templateSnapshot });
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
    this.celebratePublished(result, cycle, firstDraft);
  }

  // UI-F30:显式继续(部分/取消缓存),不 REPLACE 已在执行的轮次
  private continueGeneration(): void {
    const token: number = ++this.pageToken;
    this.runStartsUnreadable = !this.hasReadableOutput();
    this.subscribeToUpdates(getRepository(), token);
    this.runNow(token, false).catch((e: Error): void => {
      if (this.pageAlive && token === this.pageToken) {
        this.errorMsg = this.cancelled ? '生成已取消' : e.message;
        this.running = false;
      }
    });
  }

  private applyOutput(next: DeepReadOutput, token: number): void {
    if (!this.pageAlive || token !== this.pageToken) return;
    this.output = next;
    const resolution: DeepReadArticleTemplateResolution = resolveDeepReadArticleTemplate(next);
    this.templateId = resolution.template.id;
    this.templateName = resolution.template.name;
    this.templateMessage = resolution.message;
    this.currentStage = this.phaseLabel(next);
    const snap: DeepReadProgressSnapshot = deepReadProgressSnapshot(next, this.running);
    this.progress = snap;
    if (isComplete(next)) this.errorMsg = '';
    this.refreshTemplateWeb();
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
    if (isComplete(output)) return '完成';
    if (firstFailureMessage(output) !== null) return '生成失败';
    return '等待生成';
  }

  private progressLabel(label: string, step: number): string {
    return `${label}（第 ${step}/${GENERATION_STEP_COUNT} 步）`;
  }

  private stageLabel(stage: DeepReadGenerationStage): string {
    if (stage === 'OVERVIEW') return '撰写结论与导语';
    if (stage === 'NARRATIVE') return '梳理关键判断与脉络';
    if (stage === 'ANALYSIS') return '分析立场与影响';
    return '整理来源';
  }

  private openLink(url: string): void {
    if (!/^https?:\/\//i.test(url.trim())) return;
    try {
      const ctx: common.UIAbilityContext = getContext(this) as common.UIAbilityContext;
      ctx.startAbility({
        action: 'ohos.want.action.viewData',
        entities: ['entity.system.browsable'],
        uri: url,
      }).catch((): void => {
        promptAction.showToast({ message: '无法打开浏览器', duration: 1400 });
      });
    } catch (_e) {
      promptAction.showToast({ message: '无法打开浏览器', duration: 1400 });
    }
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
    this.runStartsUnreadable = !this.hasReadableOutput();
    this.errorMsg = '';
    this.cancelled = false;
    this.awaitingContinue = false;
    this.running = true;
    this.currentStage = this.progressLabel('收集来源', 1);
    this.loadArticle(token, true).catch((e: Error): void => {
      if (this.pageAlive && token === this.pageToken) {
        this.errorMsg = this.cancelled ? '生成已取消' : e.message;
        this.running = false;
      }
    });
  }

  // G5-S1:字号缩放 — zoom in/out;F29:模板路径重载 HTML 让 --ds 生效
  private zoomIn(): void {
    const next: number = this.fontScale + FONT_SCALE_STEP;
    this.fontScale = next > FONT_SCALE_MAX ? FONT_SCALE_MAX : next;
    this.refreshTemplateWeb();
    this.persistFontScale();
  }
  private zoomOut(): void {
    const next: number = this.fontScale - FONT_SCALE_STEP;
    this.fontScale = next < FONT_SCALE_MIN ? FONT_SCALE_MIN : next;
    this.refreshTemplateWeb();
    this.persistFontScale();
  }

  private persistFontScale(): void {
    getAppContainer().storage.set<number>('deepread_font_scale', Math.round(this.fontScale * 100))
      .catch((): void => { promptAction.showToast({ message: '字号设置保存失败', duration: 1400 }); });
  }

  private appearanceChanged(): void {
    if (!this.pageAlive || resolveDeepReadArticleTemplate(this.output).template.kind === 'custom') return;
    if (this.pageVisible) this.refreshTemplateWeb();
    else this.pendingAppearanceRefresh = true;
  }

  private readerBackground(): string {
    if (getProductKind() !== 'deepread') return BG;
    return resolveDeepReadArticleTemplate(this.output).template.kind === 'custom' ? D.paper
      : readerCanvas(this.readerStyle, this.darkMode).background;
  }

  private renderOptions(): DeepReadRenderOptions {
    const standalone: boolean = getProductKind() === 'deepread';
    return { fontScale: this.fontScale, fontSerif: this.fontSerif,
      readerLayout: standalone ? this.readerLayout : 'classic', readerStyle: standalone ? this.readerStyle : 'classic', dark: this.darkMode,
      background: standalone ? D.paper : BG, foreground: standalone ? D.ink : INK,
      muted: standalone ? D.muted : INK3, surface: standalone ? D.card : SURFACE,
      border: standalone ? D.rule : LINE, accent: standalone ? D.accent : ACCENT };
  }

  private editorialHtml(): string {
    return renderCapturedDeepReadTemplateHtml(this.title, this.output, this.renderOptions());
  }

  private handleWebNavigation(request: WebResourceRequest): boolean {
    const url: string = request.getRequestUrl();
    const documentUrl: string = 'https://amber-deepread.invalid/article';
    if (url === documentUrl || url.startsWith(documentUrl + '#') ||
      url === 'about:blank' || url.startsWith('about:blank#') || url.startsWith('#')) return false;
    if (this.pageAlive && request.isMainFrame() && request.isRequestGesture() && /^https?:\/\//i.test(url)) {
      this.openLink(url);
    }
    return true;
  }

  private loadTemplateHtml(): void {
    if (!this.pageAlive || !this.webAttached || !this.webInitialized) return;
    try {
      const html: string = this.editorialHtml();
      // A valid base URL gives relative source citations a same-document target.
      const documentUrl: string = 'https://amber-deepread.invalid/article';
      this.webController.loadData(html, 'text/html', 'UTF-8', documentUrl, documentUrl);
      this.webRenderError = '';
    } catch (error) {
      hilog.warn(DOMAIN, TAG, 'Template render failed: %{public}s', String(error));
      this.webRenderError = '模板显示失败，文章内容仍已保留';
    }
  }

  private attachTemplateWeb(session: number, epoch: number): void {
    if (!this.pageAlive || session !== this.webSession || epoch !== this.themeEpoch) return;
    this.webAttached = true;
    this.webInitialized = false;
  }

  private onTemplatePageEnd(url: string, session: number, epoch: number): void {
    if (!this.pageAlive || !this.webAttached || session !== this.webSession || epoch !== this.themeEpoch ||
      this.webInitialized || url !== 'about:blank') return;
    // Initial src loads after attached. Wait for it before submitting the article.
    this.webInitialized = true;
    this.loadTemplateHtml();
  }

  private detachTemplateWeb(session: number, epoch: number): void {
    if (session !== this.webSession || epoch !== this.themeEpoch) return;
    this.webAttached = false;
    this.webInitialized = false;
  }

  private onTemplateError(mainFrame: boolean, session: number = this.webSession, epoch: number = this.themeEpoch): void {
    if (!this.pageAlive || !this.webAttached || !this.isWebTemplate() || !mainFrame ||
      session !== this.webSession || epoch !== this.themeEpoch) return;
    this.webRenderError = '模板显示失败，文章内容仍已保留';
  }

  private refreshTemplateWeb(): void {
    if (!this.isWebTemplate()) return;
    this.loadTemplateHtml();
  }

  private switchToNativeReading(): void {
    this.nativeReadingOverride = true;
    this.webAttached = false;
    this.webInitialized = false;
    this.webRenderError = '';
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
    this.cancelled = false;
    this.awaitingContinue = false;
    this.clearEditorialMotion();
    this.newsroomStage = 'WRITING';
    this.newsroomLabel = this.stageLabel(stage);
    const cycle: number = this.motionCycle;
    const firstDraft: boolean = !this.hasReadableOutput();
    this.runStartsUnreadable = firstDraft;
    const token: number = ++this.pageToken;
    this.subscribeToUpdates(getRepository(), token);
    getDeepReadScheduler().then((sched: DeepReadScheduler): Promise<RunResult> => {
      if (!this.pageAlive || token !== this.pageToken) {
        const abortedResult: RunResult = { ok: false, output: this.output ?? makeEmptyDeepReadOutput(), error: 'aborted' };
        return Promise.resolve(abortedResult);
      }
      this.scheduler = sched;
      return sched.runSection(this.topicId, this.title, stage,
        this.sourceUrl.length > 0 ? this.sourceUrl : null, this.seedUrls);
    }).then((result: RunResult): void => {
      if (!this.pageAlive || token !== this.pageToken) return;
      if (result.output !== null) this.applyOutput(result.output, token);
      if (!result.ok) {
        this.cancelled = result.error === 'aborted';
        this.errorMsg = this.cancelled ? '生成已取消' : result.error ?? '该段重试失败';
      }
      this.running = false;
      this.celebratePublished(result, cycle, firstDraft);
    }).catch((e: Error): void => {
      if (this.pageAlive && token === this.pageToken) {
        this.errorMsg = e.message;
        this.running = false;
      }
    });
  }

  // G5-S1:部分失败判定 — 有 output 但 firstFailureMessage 非空
  private partialError(): string {
    if (this.output === null) { return ''; }
    if (isComplete(this.output)) { return ''; }
    const stage: DeepReadGenerationStage | null = firstFailedStage(this.output);
    if (stage === null) return '';
    return firstFailureMessage(this.output) || `${this.stageLabel(stage)}失败`;
  }

  private hasReadableOutput(): boolean {
    return this.output !== null && hasDisplayableDeepReadOutput(this.output);
  }

  private showNewsroom(): boolean {
    return getProductKind() === 'deepread' && this.running && this.runStartsUnreadable;
  }

  private readingTransition(): TransitionEffect {
    if (getProductKind() !== 'deepread') return TransitionEffect.IDENTITY;
    return TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: this.reduceMotion ? 0 : 24 }))
      .animation({ duration: this.editorialActive() ? 500 : 0, curve: Curve.EaseOut });
  }

  private sourceFailureCount(): number {
    return (this.output?.inputSources ?? []).filter((source: DeepReadInputSource): boolean => source.status === 'failed').length;
  }

  private applyWorkspaceStatus(status: DeepReadArtifactStatus | null): void {
    const messages: string[] = [];
    if (status?.error !== null && status?.error !== undefined) {
      messages.push('文章已保留，工作区保存未完成：' + status.error);
    }
    if (status?.statusError !== null && status?.statusError !== undefined) {
      messages.push('保存状态记录失败：' + status.statusError);
    }
    this.workspaceError = messages.join('；');
  }

  private async refreshWorkspaceStatus(token: number): Promise<void> {
    const request: number = ++this.workspaceRequest;
    try {
      const status: DeepReadArtifactStatus | null = await getDeepReadArtifactStore().getStatus(this.topicId);
      if (this.pageAlive && token === this.pageToken && request === this.workspaceRequest) {
        this.applyWorkspaceStatus(status);
      }
    } catch (error) {
      if (this.pageAlive && token === this.pageToken && request === this.workspaceRequest) {
        this.workspaceError = '无法读取工作区保存状态：' + String(error);
      }
    }
  }

  private async retryWorkspaceSave(): Promise<void> {
    if (this.workspaceSaving || this.running) return;
    this.workspaceSaving = true;
    const token: number = this.pageToken;
    const request: number = ++this.workspaceRequest;
    this.workspaceSaveOwner = request;
    try {
      const status: DeepReadArtifactStatus = await getDeepReadArtifactStore().save(this.topicId);
      if (!this.pageAlive || token !== this.pageToken || request !== this.workspaceRequest) return;
      this.applyWorkspaceStatus(status);
      if (this.workspaceError.length === 0) promptAction.showToast({ message: '已保存到工作区', duration: 1400 });
    } catch (error) {
      if (this.pageAlive && token === this.pageToken && request === this.workspaceRequest) {
        this.workspaceError = String(error);
      }
    } finally {
      if (this.pageAlive && this.workspaceSaveOwner === request) this.workspaceSaving = false;
    }
  }

  private retryGeneration(): void {
    if (this.output !== null && firstFailedStage(this.output) !== null) {
      this.retryFirstFailure();
    } else if (this.output !== null && !isComplete(this.output)) {
      this.continueGeneration();
    } else {
      this.generateArticle();
    }
  }

  private visibleError(): string {
    return this.errorMsg.length > 0 ? this.errorMsg : this.partialError();
  }

  private continuationNotice(): string {
    if (!this.hasReadableOutput()) return this.cancelled ? '已取消，可重新开始' : '上次生成未完成，可重新开始';
    return this.cancelled ? '生成已取消，已保存的进度仍可用' : '文章尚未完成，已保存的进度仍可用';
  }

  @Builder
  RecoveryPanel(): void {
    if (getProductKind() === 'deepread') {
      DeepReadArticleStatusPanel({ active: this.editorialActive(), running: this.running, cancelled: this.cancelled,
        awaitingContinue: this.awaitingContinue,
        statusLabel: this.running ? this.newsroomLabel : this.output !== null && isComplete(this.output)
          && this.visibleError().length > 0 ? '已保留原文' : this.cancelled ? '生成已取消'
            : this.awaitingContinue ? '文章尚未完成' : this.currentStage,
        error: this.cancelled ? '' : this.visibleError(), workspaceError: this.workspaceError,
        webError: this.isWebTemplate() ? this.webRenderError : '', saving: this.workspaceSaving,
        notice: this.awaitingContinue || (this.cancelled && this.output !== null && !isComplete(this.output))
          ? this.continuationNotice() : this.basicDraftNotice(),
        templateMessage: this.templateMessage, complete: this.output !== null && isComplete(this.output),
        readable: this.hasReadableOutput(), sourceFailureCount: this.sourceFailureCount(),
        onCancel: (): void => { this.cancelGeneration(); },
        onContinue: (): void => { this.continueGeneration(); },
        onRetry: (): void => { this.retryGeneration(); },
        onSave: (): void => { this.retryWorkspaceSave(); },
        onNative: (): void => { this.switchToNativeReading(); } })
    } else {
      DeepReadArticleHostRecovery({ output: this.output, running: this.running, cancelled: this.cancelled,
        awaitingContinue: this.awaitingContinue, workspaceError: this.workspaceError, workspaceSaving: this.workspaceSaving,
        webError: this.isWebTemplate() ? this.webRenderError : '', continuationNotice: this.continuationNotice(),
        error: this.visibleError(), failedSection: this.output !== null && firstFailedStage(this.output) !== null,
        draftNotice: this.basicDraftNotice(), currentStage: this.currentStage, historyExpired: this.historyExpired,
        progressLabel: `${this.progress.label} ${this.progress.percent}%`,
        onSave: (): void => { this.retryWorkspaceSave(); }, onNative: (): void => { this.switchToNativeReading(); },
        onCancel: (): void => { this.cancelGeneration(); }, onContinue: (): void => { this.continueGeneration(); },
        onRetry: (): void => { this.retryGeneration(); },
        onRegenerate: (): void => { this.showRegenerateDialog = true; } })
    }
  }

  @Builder
  TemplateWeb(session: number, epoch: number): void {
    Web({ src: 'about:blank', controller: this.webController })
      .width('100%').layoutWeight(1).backgroundColor(this.readerBackground())
      .transition(this.readingTransition())
      .javaScriptAccess(false).fileAccess(false).domStorageAccess(false).multiWindowAccess(false)
      .onControllerAttached((): void => { this.attachTemplateWeb(session, epoch); })
      .onPageEnd((event: OnPageEndEvent): void => { this.onTemplatePageEnd(event.url, session, epoch); })
      .onErrorReceive((event: OnErrorReceiveEvent): void => {
        this.onTemplateError(event.request.isMainFrame(), session, epoch);
      })
      .onDisAppear((): void => { this.detachTemplateWeb(session, epoch); })
      .onOverrideUrlLoading((request: WebResourceRequest): boolean => this.handleWebNavigation(request))
  }

  build(): void {
    Stack() {
      // Epoch remount ensures every child consumes the current theme tokens.
      ForEach([this.themeEpoch], (_epoch: number): void => {
        Column() {
          if (getProductKind() === 'deepread') {
            DeepReadArticleToolbar({ fontScale: this.fontScale, canvasColor: this.readerBackground(), hasSources: (this.output?.inputSources?.length ?? 0) > 0,
              canShare: this.hasReadableOutput() && !this.running, hasArticle: this.output !== null,
              running: this.running,
              canStyle: resolveDeepReadArticleTemplate(this.output).template.kind !== 'custom',
              onBack: (): void => { this.handleBack(); },
              onSources: (): void => { this.openSources(); },
              onShare: (): void => { this.openExport(); },
              onZoomIn: (): void => { this.zoomIn(); }, onZoomOut: (): void => { this.zoomOut(); },
              onTemplates: (): void => { this.openAppearanceSettings(); },
              onRegenerate: (): void => { this.showRegenerateDialog = true; },
              onSave: (): void => { this.retryWorkspaceSave(); } })
          } else {
            DeepReadArticleHostToolbar({ fontScale: this.fontScale,
              hasSources: (this.output?.inputSources?.length ?? 0) > 0,
              canShare: this.hasReadableOutput() && !this.running, hasArticle: this.output !== null,
              templateName: this.templateName, templateMessage: this.templateMessage,
              nativeReadingOverride: this.nativeReadingOverride,
              onBack: (): void => { this.handleBack(); },
              onSources: (): void => { this.openSources(); },
              onShare: (): void => { this.openExport(); },
              onZoomIn: (): void => { this.zoomIn(); }, onZoomOut: (): void => { this.zoomOut(); } })
          }
          if (!this.confirmed) {
            Column() {
              Blank()
              Column() {
                Text('深度阅读').fontSize(TITLE).fontFamily(FONT_SERIF).fontColor(INK)
                Text('将围绕该话题收集资料并生成一篇深度文章')
                  .fontSize(BODY2).fontColor(INK2).margin({ top: 10 }).textAlign(TextAlign.Center)
                Text('深度阅读会消耗更多 tokens').fontSize(LABEL).fontColor(INK3).margin({ top: 6 })
                if (getProductKind() === 'deepread') {
                  Column() {
                    StudioButton({ label: '开始使用', iconName: 'hi_book_open', primary: true,
                      onTap: (): void => {
                        getChatKvStore().put('deepread_first_use_confirmed', 'true').then((): void => {
                          if (!this.pageAlive) return;
                          this.confirmed = true;
                          const token: number = ++this.pageToken;
                          this.loadArticle(token, false).catch((error: Error): void => {
                            if (!this.pageAlive || token !== this.pageToken) return;
                            this.errorMsg = error.message;
                            this.running = false;
                          });
                        }).catch((error: Error): void => {
                          if (this.pageAlive) promptAction.showToast({ message: error.message });
                        });
                      } })
                  }.width(184).margin({ top: 24 })
                } else {
                  Text('开始使用').fontSize(BODY).fontColor(ACCENT).constraintSize({ minHeight: 44 })
                    .padding({ left: 28, right: 28 }).margin({ top: 24 })
                    .onClick((): void => {
                      getChatKvStore().put('deepread_first_use_confirmed', 'true').then((): void => {
                        if (!this.pageAlive) return;
                        this.confirmed = true;
                        const token: number = ++this.pageToken;
                        this.loadArticle(token, false).catch((error: Error): void => {
                          if (!this.pageAlive || token !== this.pageToken) return;
                          this.errorMsg = error.message;
                          this.running = false;
                        });
                      }).catch((error: Error): void => {
                        if (this.pageAlive) promptAction.showToast({ message: error.message });
                      });
                    })
                }
              }.alignItems(HorizontalAlign.Center).width('86%')
                .padding({ left: 20, right: 20, top: 32, bottom: 32 })
                .backgroundColor(SURFACE).borderRadius(16)
              Blank()
            }.width('100%').layoutWeight(1)
          } else if (this.hasReadableOutput() && this.output !== null && !this.showNewsroom()) {
            this.RecoveryPanel()
            if (this.isWebTemplate()) {
              this.TemplateWeb(this.webSession, _epoch)
            } else {
              Scroll(this.articleScroller) {
                DeepReadNativeArticleBody({ title: this.title, output: this.output,
                  fontScale: this.fontScale, fontSerif: this.fontSerif,
                  onOpenLink: (url: string): void => { this.openLink(url); } })
              }.align(Alignment.TopStart).width('100%').layoutWeight(1).backgroundColor(this.readerBackground())
                .transition(this.readingTransition())
            }
          } else if (this.running && getProductKind() === 'deepread') {
            this.RecoveryPanel()
            Column() {
              DeepReadNewsroomDesk({ title: this.title, stage: this.newsroomStage, detail: this.newsroomLabel,
                active: this.editorialActive() })
            }.width('100%').layoutWeight(1)
              .transition(TransitionEffect.OPACITY.animation({ duration: this.editorialActive() ? 500 : 0 }))
          } else if (getProductKind() === 'deepread') {
            this.RecoveryPanel()
            Column({ space: 10 }) {
              Icon({ name: 'doc', iconSize: 44, color: D.muted })
              Text('文章尚未生成').fontSize(22).fontWeight(FontWeight.Medium).fontColor(D.ink)
              Text('检查来源与模型设置后重试。').fontSize(15).fontColor(D.muted).lineHeight(22)
                .textAlign(TextAlign.Center)
            }.width('100%').layoutWeight(1).justifyContent(FlexAlign.Center).padding(24)
          } else {
            Column() {
              Blank()
              if (!this.running || getProductKind() !== 'deepread') {
                Text(this.running ? '正在生成文章' : this.awaitingContinue ? '文章尚未完成'
                  : this.cancelled ? '生成已取消' : '生成失败')
                  .fontSize(TITLE).fontColor(INK2).width('100%').textAlign(TextAlign.Center)
                  .margin({ bottom: 20 })
              }
              this.RecoveryPanel()
              Blank()
            }.width('100%').layoutWeight(1)
          }
        }.width('100%').height('100%').backgroundColor(this.readerBackground())
          .padding({ bottom: getProductKind() === 'deepread' ? this.navBarHeight : 0 })
          .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
        DeepReadGoogleSearchHost({ topicId: this.topicId })
        if (getProductKind() === 'deepread') {
          DeepReadArticlePressMoment({ trigger: this.completionStamp, inscription: this.completionInscription,
            caption: this.completionCaption, active: this.editorialActive() })
        }
        DeepReadConfirmationDialog({ show: this.showRegenerateDialog, dialogTitle: '重新生成?',
          message: '新文章完整生成并保存后将替换当前文章，失败或取消会保留原文章',
          confirmText: '重新生成', dismissText: '取消',
          onConfirm: (): void => { this.showRegenerateDialog = false; this.generateArticle(); },
          onDismiss: (): void => { this.showRegenerateDialog = false; } })
        if (this.articlePanel.length > 0) {
          this.ArticlePanel(this.articlePanel, this.articlePanelSession)
        }
      }, (epoch: number): string => `theme-${epoch}`)
    }.width('100%').height('100%')
  }
}
