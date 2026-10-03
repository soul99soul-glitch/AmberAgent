import { router } from '@kit.ArkUI';
import { getNovelCreation } from '../di/AppContainer.ets';
import { loadNovelPolishContext } from '../platform_impl/NovelStandaloneDefaults.ets';
import { novelChapterOrdinal, projectPolishOutcomes } from '@amber/deepread-domain';
import type {
  NovelChapter, NovelProject, NovelWorkspaceCas, NovelWorkspaceSnapshot, DurablePolishJob,
  PolishChapterResult, PolishContextOptions, PolishContextSnapshotItem, NovelPolishStartPreview,
} from '@amber/deepread-domain';
import { BG, INK, INK3, ACCENT, ERROR, LINE, SURFACE2 } from '../design/tokens.ets';
import { PageTitleBar } from '../components/PageTemplate.ets';
import { NovelPolishJobCard } from '../components/NovelPolishJobCard.ets';

interface PolishPageParams { projectId: string; }

@Entry
@Component
struct NovelPolishPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @State project: NovelProject | null = null;
  @State branchName: string = '';
  @State selectedIds: string[] = [];
  @State retrySelectedIds: string[] = [];
  @State includePlot: boolean = true;
  @State includeForeshadows: boolean = true;
  @State includeCharacters: boolean = true;
  @State includeDecisions: boolean = true;
  @State preview: NovelPolishStartPreview | null = null;
  @State job: DurablePolishJob | null = null;
  @State loading: boolean = true;
  @State busy: boolean = false;
  @State modelReady: boolean = false;
  @State ordinaryRunning: boolean = false;
  @State modelError: string = '';
  @State error: string = '';
  @State notice: string = '';
  private projectId: string = '';
  private cas: NovelWorkspaceCas | null = null;
  private alive: boolean = false;
  private visible: boolean = false;
  private initialized: boolean = false;
  private retryInitializedJobId: string = '';
  private loadToken: number = 0;
  private pageToken: number = 0;
  private pollToken: number = 0;
  private pollTimer: number = -1;

  aboutToAppear(): void {
    this.alive = true;
    const params: PolishPageParams = router.getParams() as PolishPageParams;
    this.projectId = params.projectId;
  }
  onPageShow(): void { this.visible = true; this.reload(); }
  onPageHide(): void { this.visible = false; this.pageToken++; this.loadToken++; this.stopPolling(); }
  aboutToDisappear(): void { this.alive = false; this.pageToken++; this.loadToken++; this.stopPolling(); }
  onBackPress(): boolean { return this.busy; }

  private chapters(): NovelChapter[] { return this.project?.chapters.filter((chapter: NovelChapter): boolean => !chapter.discarded) ?? []; }
  private ordinal(chapter: NovelChapter): number {
    return novelChapterOrdinal(chapter, (this.project?.chapters.indexOf(chapter) ?? 0) + 1);
  }
  private options(): PolishContextOptions {
    return { includePlot: this.includePlot, includeForeshadows: this.includeForeshadows,
      includeCharacters: this.includeCharacters, includeDecisions: this.includeDecisions };
  }
  private terminal(job: DurablePolishJob): boolean { return job.stage === 'completed' || job.stage === 'cancelled'; }
  private running(job: DurablePolishJob): boolean {
    return !this.terminal(job) && job.stage !== 'paused' && job.stage !== 'failed' && job.stage !== 'waiting_system';
  }
  private canPrepare(): boolean { return !this.loading && !this.busy && !this.ordinaryRunning && this.project !== null && (this.job === null || this.terminal(this.job)); }
  private outcomes(): PolishChapterResult[] { return this.job === null ? [] : projectPolishOutcomes(this.job); }
  private retryable(): PolishChapterResult[] {
    return this.outcomes().filter((result: PolishChapterResult): boolean => result.status === 'failed' || result.status === 'unprocessed');
  }
  private retryTitle(result: PolishChapterResult): string {
    const target = this.job?.targets.find(item => item.id === result.chapterId);
    return `第 ${result.chapterOrdinal} 章 · ${target?.title ?? '未命名章节'}`;
  }
  private applyJob(job: DurablePolishJob | null): void {
    this.job = job;
    const eligible: string[] = this.retryable().map((result: PolishChapterResult): string => result.chapterId);
    if (job !== null && (this.terminal(job) || job.stage === 'failed') && this.retryInitializedJobId !== job.jobId) {
      this.retryInitializedJobId = job.jobId;
      this.retrySelectedIds = eligible;
    } else this.retrySelectedIds = this.retrySelectedIds.filter((id: string): boolean => eligible.includes(id));
  }
  private sameCas(left: NovelWorkspaceCas | null, right: NovelWorkspaceCas): boolean {
    return left !== null && left.branchId === right.branchId && left.head === right.head && left.treeDigest === right.treeDigest;
  }
  private async reload(): Promise<void> {
    const token: number = ++this.loadToken;
    this.loading = true;
    this.stopPolling();
    try {
      if (this.projectId.length === 0) throw new Error('未指定小说项目');
      const snapshot: NovelWorkspaceSnapshot = await getNovelCreation().readWorkspaceSnapshot(this.projectId);
      const job: DurablePolishJob | null = await getNovelCreation().polishJob(this.projectId);
      let options: PolishContextOptions | null = null;
      if (!this.initialized) options = await loadNovelPolishContext();
      if (!this.alive || !this.visible || token !== this.loadToken) return;
      if (this.cas !== null && this.cas.branchId !== snapshot.status.activeBranchId) {
        this.selectedIds = [];
        this.preview = null;
        this.notice = '当前分支已切换，请重新选择章节。';
      }
      this.project = snapshot.project;
      this.branchName = snapshot.status.activeBranchName;
      this.cas = snapshot.status.cas;
      if (this.preview !== null && !this.sameCas(this.preview.cas, snapshot.status.cas)) this.preview = null;
      if (!this.initialized) {
        this.selectedIds = this.chapters().map((chapter: NovelChapter): string => chapter.id);
        if (options !== null) {
          this.includePlot = options.includePlot; this.includeForeshadows = options.includeForeshadows;
          this.includeCharacters = options.includeCharacters; this.includeDecisions = options.includeDecisions;
        }
        this.initialized = true;
      } else this.selectedIds = this.selectedIds.filter((id: string): boolean => this.chapters().some((chapter: NovelChapter): boolean => chapter.id === id));
      this.ordinaryRunning = getNovelCreation().activeRun(this.projectId) !== null;
      this.applyJob(job !== null && job.branchId === snapshot.status.activeBranchId ? job : null);
      this.modelReady = false;
      this.modelError = '';
      try {
        await getNovelCreation().validateGhostwriteModels(this.projectId, snapshot.project);
        if (!this.alive || !this.visible || token !== this.loadToken) return;
        this.modelReady = true;
      } catch (error) {
        if (!this.alive || !this.visible || token !== this.loadToken) return;
        this.modelError = String(error);
      }
      this.error = '';
    } catch (error) { if (this.alive && this.visible && token === this.loadToken) this.error = String(error); }
    finally {
      if (this.alive && this.visible && token === this.loadToken) { this.loading = false; this.schedulePoll(); }
    }
  }
  private stopPolling(): void {
    this.pollToken++;
    if (this.pollTimer !== -1) clearTimeout(this.pollTimer);
    this.pollTimer = -1;
  }
  private schedulePoll(): void {
    this.stopPolling();
    if (!this.alive || !this.visible || this.job === null || !this.running(this.job)) return;
    const token: number = this.pollToken;
    this.pollTimer = setTimeout((): void => {
      this.pollTimer = -1;
      if (this.alive && this.visible && token === this.pollToken) this.poll(token);
    }, 1200);
  }
  private async poll(token: number): Promise<void> {
    const jobId: string = this.job?.jobId ?? '';
    try {
      const next: DurablePolishJob | null = await getNovelCreation().polishJob(this.projectId, jobId);
      if (!this.alive || !this.visible || token !== this.pollToken) return;
      this.applyJob(next);
      if (next !== null && this.terminal(next)) await this.reload();
      else this.schedulePoll();
    } catch (error) {
      if (this.alive && this.visible && token === this.pollToken) this.error = `润色进度无法读取：${String(error)}`;
    }
  }
  private invalidatePreview(): void { this.preview = null; }
  private toggleChapter(id: string): void {
    if (!this.canPrepare()) return;
    this.selectedIds = this.selectedIds.includes(id) ? this.selectedIds.filter((value: string): boolean => value !== id) : this.selectedIds.concat(id);
    this.invalidatePreview();
  }
  private setOption(key: string, value: boolean): void {
    if (!this.canPrepare()) return;
    if (key === 'plot') this.includePlot = value;
    else if (key === 'foreshadows') this.includeForeshadows = value;
    else if (key === 'characters') this.includeCharacters = value;
    else this.includeDecisions = value;
    this.invalidatePreview();
  }
  private async prepare(): Promise<void> {
    if (!this.canPrepare() || this.selectedIds.length === 0) return;
    const token: number = this.pageToken;
    this.busy = true; this.error = ''; this.notice = '';
    try {
      const preview: NovelPolishStartPreview = await getNovelCreation().previewPolishSelected(this.projectId, this.selectedIds.slice(), this.options());
      if (!this.alive || !this.visible || token !== this.pageToken) return;
      if (!this.sameCas(this.cas, preview.cas)) throw new Error('工作区已变化，请重新读取章节后再预览。');
      this.preview = preview;
    } catch (error) { if (this.alive && this.visible && token === this.pageToken) this.error = String(error); }
    finally { if (this.alive) this.busy = false; }
  }
  private async start(): Promise<void> {
    if (!this.canPrepare() || !this.modelReady || this.preview === null) return;
    const preview: NovelPolishStartPreview = this.preview;
    const token: number = this.pageToken;
    this.busy = true; this.error = ''; this.notice = '';
    try {
      const job: DurablePolishJob = await getNovelCreation().startPolishSelected(
        this.projectId, preview.chapterIds, preview.contextOptions, preview.cas);
      if (!this.alive || !this.visible || token !== this.pageToken) return;
      this.applyJob(job);
      this.invalidatePreview();
      this.notice = '润色任务已保存。通过审稿的章节会逐章原子收录并保留旧版本。';
      this.schedulePoll();
    } catch (error) { if (this.alive && this.visible && token === this.pageToken) this.error = String(error); }
    finally { if (this.alive) this.busy = false; }
  }
  private async control(action: string): Promise<void> {
    if (this.busy || this.job === null) return;
    const jobId: string = this.job.jobId;
    const token: number = this.pageToken;
    this.busy = true; this.error = ''; this.notice = '';
    try {
      const creation = getNovelCreation();
      const job: DurablePolishJob = action === 'pause' ? await creation.pausePolish(this.projectId, jobId)
        : action === 'resume' ? await creation.resumePolish(this.projectId, jobId)
        : await creation.cancelPolish(this.projectId, jobId);
      if (!this.alive || !this.visible || token !== this.pageToken) return;
      this.applyJob(job);
      if (this.terminal(job)) await this.reload();
      else this.schedulePoll();
    } catch (error) { if (this.alive && this.visible && token === this.pageToken) this.error = String(error); }
    finally { if (this.alive) this.busy = false; }
  }
  private async retryFailed(): Promise<void> {
    if (this.busy || this.job === null || this.retrySelectedIds.length === 0) return;
    const ids: string[] = this.retrySelectedIds.slice();
    const jobId: string = this.job.jobId;
    const token: number = this.pageToken;
    this.busy = true; this.error = ''; this.notice = '';
    try {
      const job: DurablePolishJob = await getNovelCreation().retryPolish(this.projectId, jobId, ids);
      if (!this.alive || !this.visible || token !== this.pageToken) return;
      this.applyJob(job);
      this.notice = '只重试已勾选的失败或未处理章节；成功及剧情漂移跳过章节保持原样。';
      this.schedulePoll();
    } catch (error) { if (this.alive && this.visible && token === this.pageToken) this.error = String(error); }
    finally { if (this.alive) this.busy = false; }
  }
  private editPreference(): void {
    if (!this.canPrepare()) return;
    this.invalidatePreview();
    router.pushUrl({ url: 'pages/NovelProjectSetupPage', params: { projectId: this.projectId } })
      .catch((error: Error): void => { if (this.alive && this.visible) this.error = String(error); });
  }
  private contextLabel(kind: string): string {
    return kind === 'plot' ? '剧情摘要' : kind === 'foreshadow' ? '伏笔' : kind === 'character' ? '人物资料'
      : kind === 'material' ? '必需创作资料' : '作者已确认决定';
  }
  private jobKey(job: DurablePolishJob): string {
    return `${job.jobId}:${job.updatedAt}:${job.stage}:${job.cursor}:${JSON.stringify(job.outcomes ?? [])}:${this.busy}:${this.retrySelectedIds.join(',')}`;
  }

  @Builder
  private ContextToggle(label: string, key: string, checked: boolean): void {
    Row() {
      Text(label).fontSize(14).fontColor(INK).layoutWeight(1)
      Toggle({ type: ToggleType.Switch, isOn: checked }).selectedColor(ACCENT).enabled(this.canPrepare())
        .onChange((value: boolean): void => { this.setOption(key, value); })
    }.width('100%').constraintSize({ minHeight: 48 })
  }
  @Builder
  private PreviewBody(preview: NovelPolishStartPreview): void {
    Column({ space: 10 }) {
      Text(`本次冻结第 ${preview.targets.map(target => target.ordinal).join('、')} 章，共 ${preview.targets.length} 章。`)
        .fontSize(14).fontColor(INK).lineHeight(22).width('100%')
      Text(`润色偏好来源：${preview.preferenceSource === 'project' ? '项目设置' : '未设置'}`)
        .fontSize(13).fontColor(INK3).width('100%')
      Text(preview.polishPreference.length > 0 ? preview.polishPreference : '未设置润色偏好，将按保留剧情事实的默认润色要求执行。')
        .fontSize(14).fontColor(INK).lineHeight(22).width('100%')
      Text(`冻结上下文 ${preview.contextSnapshot.length} 项。以下内容与来源将随任务保存，修改设置不会改变本次输入。`)
        .fontSize(13).fontColor(INK3).lineHeight(21).width('100%')
      ForEach(preview.contextSnapshot, (item: PolishContextSnapshotItem): void => {
        Column({ space: 5 }) {
          Text(this.contextLabel(item.kind)).fontSize(14).fontColor(INK).width('100%')
          Text(item.sourcePath).fontSize(12).fontColor(INK3).lineHeight(18).width('100%')
          Text(item.content).fontSize(13).fontColor(INK).lineHeight(21).width('100%').copyOption(CopyOptions.LocalDevice)
        }.width('100%').alignItems(HorizontalAlign.Start).padding(12).backgroundColor(SURFACE2).borderRadius(10)
      }, (item: PolishContextSnapshotItem): string => `${item.sourcePath}:${item.digest}`)
    }.width('100%').alignItems(HorizontalAlign.Start)
  }

  build(): void {
    Column() {
      ForEach([this.themeEpoch], (_epoch: number): void => {
        Column() {
          PageTitleBar({ title: '整章与批量润色', actionText: '刷新', onBack: (): void => { if (!this.busy) router.back(); },
            onAction: (): void => { if (!this.busy && !this.loading) this.reload(); } })
          Scroll() {
            Column({ space: 12 }) {
              Text(this.project?.name ?? '正在读取项目…').fontSize(18).fontWeight(FontWeight.Medium).fontColor(INK).width('100%')
              Text(`当前分支：${this.branchName}`).fontSize(13).fontColor(INK3).width('100%')
              if (this.error.length > 0) { Text(this.error).fontSize(13).fontColor(ERROR).lineHeight(21).width('100%') }
              if (this.notice.length > 0) { Text(this.notice).fontSize(13).fontColor(ACCENT).lineHeight(21).width('100%') }
              if (this.job !== null) {
                ForEach([this.job], (job: DurablePolishJob): void => {
                  NovelPolishJobCard({ job, fullHeight: true, busy: this.busy,
                    retryReady: this.retrySelectedIds.length > 0 && job.stage === 'failed',
                    onPause: (): void => { this.control('pause'); }, onResume: (): void => { this.control('resume'); },
                    onRetry: (): void => { this.retryFailed(); }, onCancel: (): void => { this.control('cancel'); } })
                }, (job: DurablePolishJob): string => this.jobKey(job))
                Text('任务冻结的项目润色偏好').fontSize(14).fontColor(INK).width('100%')
                Text(this.job.polishPreferenceAtStart === undefined ? '旧任务未记录此字段。'
                  : this.job.polishPreferenceAtStart.length > 0 ? this.job.polishPreferenceAtStart : '启动时未设置')
                  .fontSize(13).fontColor(INK3).lineHeight(21).width('100%')
                if (this.retryable().length > 0 && (this.terminal(this.job) || this.job.stage === 'failed')) {
                  Text('定向重试失败与未处理章节').fontSize(16).fontColor(INK).width('100%')
                  Text('默认勾选可重试项。剧情漂移跳过项不自动重试，已成功收录的章节不重复处理。')
                    .fontSize(13).fontColor(INK3).lineHeight(21).width('100%')
                  Row({ space: 20 }) {
                    Text('全选失败项').fontSize(14).fontColor(ACCENT).constraintSize({ minHeight: 44 }).enabled(!this.busy)
                      .onClick((): void => { this.retrySelectedIds = this.retryable().map(result => result.chapterId); })
                    Text('清空').fontSize(14).fontColor(INK3).constraintSize({ minHeight: 44, minWidth: 44 }).enabled(!this.busy)
                      .onClick((): void => { this.retrySelectedIds = []; })
                  }.width('100%')
                  ForEach(this.retryable(), (result: PolishChapterResult): void => {
                    Text(`${this.retrySelectedIds.includes(result.chapterId) ? '☑' : '☐'} ${this.retryTitle(result)}`)
                      .fontSize(14).fontColor(INK).lineHeight(22).width('100%').constraintSize({ minHeight: 48 })
                      .padding(10).backgroundColor(SURFACE2).borderRadius(10).enabled(!this.busy)
                      .onClick((): void => { this.retrySelectedIds = this.retrySelectedIds.includes(result.chapterId)
                        ? this.retrySelectedIds.filter((id: string): boolean => id !== result.chapterId) : this.retrySelectedIds.concat(result.chapterId); })
                  }, (result: PolishChapterResult): string => `${result.chapterId}:${result.status}:${result.updatedAt}:${this.retrySelectedIds.includes(result.chapterId)}`)
                  Text('重试所选失败与未处理章节').fontSize(15).fontColor(ACCENT).constraintSize({ minHeight: 48 }).width('100%')
                    .enabled(!this.busy && this.retrySelectedIds.length > 0).opacity(this.busy || this.retrySelectedIds.length === 0 ? 0.45 : 1)
                    .onClick((): void => { this.retryFailed(); })
                }
              }
              if (this.ordinaryRunning) {
                Text('普通创作正在进行。结束或停止后刷新此页，再创建润色任务。')
                  .fontSize(13).fontColor(INK3).lineHeight(21).width('100%')
              }
              Text('选择本次章节').fontSize(17).fontWeight(FontWeight.Medium).fontColor(INK).width('100%').margin({ top: 8 })
              if (this.job !== null && !this.terminal(this.job)) {
                Text('当前任务尚未结束。可继续、重试或取消后再创建另一批。').fontSize(13).fontColor(INK3).lineHeight(21).width('100%')
              }
              Row({ space: 20 }) {
                Text('全选').fontSize(14).fontColor(ACCENT).constraintSize({ minHeight: 44, minWidth: 44 }).enabled(this.canPrepare())
                  .onClick((): void => { this.selectedIds = this.chapters().map(chapter => chapter.id); this.invalidatePreview(); })
                Text('清空').fontSize(14).fontColor(INK3).constraintSize({ minHeight: 44, minWidth: 44 }).enabled(this.canPrepare())
                  .onClick((): void => { this.selectedIds = []; this.invalidatePreview(); })
              }.width('100%')
              if (!this.loading && this.chapters().length === 0) { Text('当前没有可润色的章节。').fontSize(14).fontColor(INK3) }
              ForEach(this.chapters(), (chapter: NovelChapter): void => {
                Text(`${this.selectedIds.includes(chapter.id) ? '☑' : '☐'} 第 ${this.ordinal(chapter)} 章 · ${chapter.title}`)
                  .fontSize(14).fontColor(INK).lineHeight(22).width('100%').constraintSize({ minHeight: 48 })
                  .padding(10).backgroundColor(SURFACE2).borderRadius(10).enabled(this.canPrepare())
                  .onClick((): void => { this.toggleChapter(chapter.id); })
              }, (chapter: NovelChapter): string => `${chapter.id}:${chapter.updatedAt}:${chapter.title}:${this.selectedIds.includes(chapter.id)}`)
              Text(`已选择 ${this.selectedIds.length} 章，可跳过任意章节。`).fontSize(13).fontColor(INK3).width('100%')
              Text('项目润色偏好').fontSize(16).fontColor(INK).width('100%')
              Text((this.project?.polishPreference ?? '').trim().length > 0 ? this.project?.polishPreference ?? '' : '还未设置，先确定文风方向有助于统一整批章节。')
                .fontSize(13).fontColor(INK3).lineHeight(21).width('100%')
              Text('编辑项目润色偏好').fontSize(14).fontColor(ACCENT).constraintSize({ minHeight: 44 }).enabled(this.canPrepare())
                .onClick((): void => { this.editPreference(); })
              Text('冻结上下文').fontSize(16).fontColor(INK).width('100%')
              this.ContextToggle('剧情摘要', 'plot', this.includePlot)
              this.ContextToggle('伏笔', 'foreshadows', this.includeForeshadows)
              this.ContextToggle('人物资料', 'characters', this.includeCharacters)
              this.ContextToggle('作者已确认决定', 'decisions', this.includeDecisions)
              Text('预览冻结输入与来源').fontSize(15).fontColor(ACCENT).constraintSize({ minHeight: 48 }).width('100%')
                .enabled(this.canPrepare() && this.selectedIds.length > 0).opacity(this.canPrepare() && this.selectedIds.length > 0 ? 1 : 0.45)
                .onClick((): void => { this.prepare(); })
              if (this.preview !== null) {
                this.PreviewBody(this.preview)
                Text('确认并开始润色').fontSize(15).fontColor(ACCENT).constraintSize({ minHeight: 48 }).width('100%')
                  .enabled(this.canPrepare() && this.modelReady).opacity(this.canPrepare() && this.modelReady ? 1 : 0.45)
                  .onClick((): void => { this.start(); })
              }
              if (this.modelError.length > 0) {
                Text(`职责模型尚不可用：${this.modelError}`).fontSize(13).fontColor(ERROR).lineHeight(21).width('100%')
                Text('配置应用职责模型').fontSize(14).fontColor(ACCENT).constraintSize({ minHeight: 44 }).enabled(!this.busy)
                  .onClick((): void => { router.pushUrl({ url: 'pages/NovelStandaloneSettingsPage' }); })
              }
              if (this.busy) { Text('正在处理…').fontSize(13).fontColor(INK3).width('100%') }
            }.width('100%').alignItems(HorizontalAlign.Start).padding({ left: 20, right: 20, top: 16, bottom: this.navBarHeight + 24 })
          }.width('100%').layoutWeight(1).align(Alignment.TopStart).scrollBar(BarState.Auto)
        }.width('100%').height('100%').backgroundColor(BG)
          .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
      }, (epoch: number): string => `theme-${epoch}`)
    }.width('100%').height('100%')
  }
}
