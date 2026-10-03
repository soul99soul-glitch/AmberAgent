import { router } from '@kit.ArkUI';
import { ACCENT, BG, ERROR, INK, INK3, SURFACE2 } from '../design/tokens.ets';
import { PageTitleBar } from '../components/PageTemplate.ets';
import { getNovelCreation } from '../di/AppContainer.ets';
import { defaultGhostwriteDigest, novelChapterOrdinal } from '@amber/deepread-domain';
import type { NovelProject, NovelMaterial, NovelWorkspaceCas, NovelWorkspaceSnapshot, NovelStateOperation, NovelCharacterExperience, NovelStateEvent, NovelIdentityAction, NovelChapter, NovelStructuredStateView } from '@amber/deepread-domain';
interface StateParams { projectId?: string; }

@Entry
@Component
struct NovelStatePage {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @State project: NovelProject | null = null;
  @State operation: NovelStateOperation | null = null;
  @State experiences: NovelCharacterExperience[] = [];
  @State active: boolean = false;
  @State loading: boolean = true;
  @State busy: boolean = false;
  @State error: string = '';
  @State notice: string = '';
  @State mergeMention: string = '';
  private projectId: string = '';
  private cas: NovelWorkspaceCas | null = null;
  private alive: boolean = false;
  private visible: boolean = false;
  private token: number = 0;
  private unsubscribe: (() => void) | null = null;

  aboutToAppear(): void {
    this.alive = true;
    const params: StateParams = router.getParams() as StateParams;
    this.projectId = params?.projectId ?? '';
  }
  onPageShow(): void { this.visible = true; this.subscribe(); this.reload(); }
  onPageHide(): void { this.visible = false; this.token++; this.unlisten(); }
  aboutToDisappear(): void { this.alive = false; this.token++; this.unlisten(); }
  private unlisten(): void { this.unsubscribe?.(); this.unsubscribe = null; }
  private subscribe(): void {
    this.unlisten();
    this.unsubscribe = getNovelCreation().observeStateOperation(this.projectId, (operation: NovelStateOperation | null): void => {
      if (!this.alive || !this.visible) return;
      this.operation = operation;
      this.reload();
    });
  }
  private async reload(): Promise<void> {
    const token: number = ++this.token;
    try {
      const result: NovelStructuredStateView = await getNovelCreation().readStructuredState(this.projectId);
      if (!this.alive || !this.visible || token !== this.token) return;
      this.project = result.project; this.operation = result.operation; this.active = result.active;
      this.experiences = result.experiences; this.cas = result.cas;
    } catch (error) { if (this.alive && this.visible && token === this.token) this.error = String(error); }
    finally { if (this.alive && this.visible && token === this.token) this.loading = false; }
  }
  private async rebuild(retry: boolean): Promise<void> {
    if (this.loading || this.busy || this.active) return;
    this.busy = true; this.error = ''; this.notice = '';
    try {
      const result: NovelStateOperation = retry ? await getNovelCreation().retryStateRebuild(this.projectId)
        : await getNovelCreation().startStateRebuild(this.projectId);
      if (this.alive && this.visible) {
        await this.reload();
        this.notice = result.status === 'completed' ? '剧情状态救援已完成。'
          : result.status === 'cancelled' ? '救援已停止，可继续重试。' : '';
      }
    } catch (error) { if (this.alive && this.visible) this.error = String(error); }
    finally { if (this.alive) this.busy = false; }
  }
  private async syncLatestChapter(): Promise<void> {
    const chapters: NovelChapter[] = this.project?.chapters.filter((item: NovelChapter): boolean => !item.discarded) ?? [];
    const chapter: NovelChapter | undefined = chapters[chapters.length - 1];
    if (chapter === undefined || this.busy || this.active || this.loading) return;
    this.busy = true; this.error = ''; this.notice = '';
    try {
      const result: NovelStateOperation = await getNovelCreation().syncStructuredChapter(this.projectId, chapter.id);
      if (this.alive && this.visible) {
        await this.reload();
        this.notice = result.status === 'completed' ? '末章剧情状态已提取。' : '';
      }
    } catch (error) { if (this.alive && this.visible) this.error = String(error); }
    finally { if (this.alive) this.busy = false; }
  }

  private confirmRebuild(): void {
    if (this.active || this.busy || this.loading) return;
    AlertDialog.show({ title: '重新整理剧情状态？', message: '按当前分支有效章节逐章提取人物事件和身份待确认项。正文不会被改写；进度持久保存，失败后可继续重试。',
      primaryButton: { value: '取消', action: (): void => {} },
      secondaryButton: { value: '开始救援', action: (): void => { if (this.alive && this.visible) this.rebuild(false); } } });
  }
  private async cancel(): Promise<void> {
    try { await getNovelCreation().cancelStateRebuild(this.projectId); if (this.alive && this.visible) await this.reload(); }
    catch (error) { if (this.alive && this.visible) this.error = String(error); }
  }
  private async reasoning(enabled: boolean): Promise<void> {
    if (this.busy || this.active || this.cas === null) return;
    this.busy = true; this.error = '';
    try { await getNovelCreation().setStateReasoningEnabled(this.projectId, enabled, this.cas); if (this.alive && this.visible) await this.reload(); }
    catch (error) { if (this.alive && this.visible) this.error = String(error); }
    finally { if (this.alive) this.busy = false; }
  }
  private characters(): NovelMaterial[] { return this.project?.materials.filter((item: NovelMaterial): boolean => item.kind === 'character') ?? []; }
  private confirmIdentity(mention: string, action: NovelIdentityAction, materialId: string | null): void {
    if (this.busy || this.active || this.cas === null) return;
    const cas: NovelWorkspaceCas = this.cas;
    const target: NovelMaterial | undefined = this.characters().find((item: NovelMaterial): boolean => item.id === materialId);
    AlertDialog.show({ title: '确认人物身份？', message: action === 'create' ? `将“${mention}”创建为新人物资料，供后续状态提取和人物经历关联使用。`
      : action === 'ignore' ? `将“${mention}”标记为非人物身份，保留正文。` : `将“${mention}”合并到人物“${target?.title ?? ''}”，保存身份与别名，供后续状态提取和人物经历关联使用。`,
      primaryButton: { value: '取消', action: (): void => {} }, secondaryButton: { value: '确认', action: (): void => {
        if (this.alive && this.visible) this.resolveIdentity(mention, action, materialId, cas);
      } } });
  }
  private async resolveIdentity(mention: string, action: NovelIdentityAction, materialId: string | null, cas: NovelWorkspaceCas): Promise<void> {
    if (this.busy || this.active) return;
    this.busy = true; this.error = '';
    try {
      await getNovelCreation().resolveIdentityClarification(this.projectId, mention, action, materialId, cas);
      if (this.alive && this.visible) { this.mergeMention = ''; await this.reload(); }
    } catch (error) { if (this.alive && this.visible) this.error = String(error); }
    finally { if (this.alive) this.busy = false; }
  }
  private chapterLabel(chapterId: string): string {
    const chapter: NovelChapter | undefined = this.project?.chapters.find((item: NovelChapter): boolean => item.id === chapterId);
    return chapter === undefined ? '原章已变化' : `第 ${novelChapterOrdinal(chapter, (this.project?.chapters.indexOf(chapter) ?? 0) + 1)} 章 · ${chapter.title}`;
  }
  private async evidence(event: NovelStateEvent): Promise<void> {
    try {
      const snapshot: NovelWorkspaceSnapshot = await getNovelCreation().readWorkspaceSnapshot(this.projectId);
      const chapter: NovelChapter | undefined = snapshot.project.chapters.find((item: NovelChapter): boolean => item.id === event.chapterId);
      const start: number = chapter?.content.indexOf(event.quote) ?? -1;
      if (chapter === undefined || chapter.discarded || start < 0 || defaultGhostwriteDigest(chapter.content) !== event.sourceDigest || snapshot.status.cas.branchId !== this.cas?.branchId) {
        throw new Error('正文或分支已变化，请先更新剧情状态再查看证据。');
      }
      if (!this.alive || !this.visible) return;
      await router.pushUrl({ url: 'pages/NovelChapterReaderPage', params: { projectId: this.projectId, chapterId: event.chapterId,
        evidenceQuote: event.quote, evidenceStart: start, evidenceEnd: start + event.quote.length,
        evidenceDigest: event.sourceDigest, evidenceBranchId: snapshot.status.activeBranchId } });
    } catch (error) { if (this.alive && this.visible) this.error = String(error); }
  }
  private experienceKey(experience: NovelCharacterExperience): string {
    return `${experience.materialId}:${defaultGhostwriteDigest(JSON.stringify(experience))}`;
  }

  private timelineEventKey(event: NovelStateEvent): string {
    return `${event.id}:${defaultGhostwriteDigest(JSON.stringify(event))}`;
  }

  private timelineEvents(): NovelStateEvent[] {
    const events: NovelStateEvent[] = this.project?.structuredState?.events ?? [];
    let ordered: NovelStateEvent[] = [];
    for (const chapter of this.project?.chapters ?? []) {
      if (!chapter.discarded) ordered = ordered.concat(events.filter((event: NovelStateEvent): boolean => event.chapterId === chapter.id));
    }
    return ordered;
  }

  @Builder
  private Timeline(): void {
    Text('当前分支事件时间线').fontSize(16).fontColor(INK).width('100%')
    if (this.timelineEvents().length === 0) {
      Text('尚未提取有效剧情事件。').fontSize(13).fontColor(INK3).width('100%')
    }
    ForEach(this.timelineEvents(), (event: NovelStateEvent): void => {
      this.EventRow(event)
    }, (event: NovelStateEvent): string => this.timelineEventKey(event))
  }

  @Builder
  private EventRow(event: NovelStateEvent): void {
    Column({ space: 6 }) {
      Text(this.chapterLabel(event.chapterId)).fontSize(12).fontColor(INK3).width('100%')
      Text(event.summary).fontSize(14).fontColor(INK).lineHeight(21).width('100%')
      Text(`原文：${event.quote}`).fontSize(13).fontColor(INK3).lineHeight(20).width('100%')
      Text('查看原文证据').fontSize(14).fontColor(ACCENT).constraintSize({ minHeight: 44 })
        .onClick((): void => { this.evidence(event); })
    }.width('100%').alignItems(HorizontalAlign.Start)
  }

  build(): void {
    Column() {
    ForEach([this.themeEpoch], (_epoch: number): void => {
      Column() {
        PageTitleBar({ title: '剧情状态与人物经历' })
        Scroll() {
          Column({ space: 14 }) {
            Text(this.project?.name ?? '正在读取…').fontSize(16).fontColor(INK).width('100%')
            Text('人物经历来自当前分支正文，附逐字证据。身份待确认项由作者决定；剧情状态救援不会改写正文。').fontSize(13).fontColor(INK3).lineHeight(20).width('100%')
            Row({ space: 12 }) {
              Column({ space: 4 }) {
                Text('状态整理使用推理').fontSize(14).fontColor(INK).width('100%')
                Text('默认关闭，仅影响后续剧情状态提取。是否能彻底关闭推理由所选模型支持决定。').fontSize(12).fontColor(INK3).width('100%')
              }.layoutWeight(1)
              Toggle({ type: ToggleType.Switch, isOn: this.project?.stateSyncReasoningEnabled === true })
                .enabled(!this.busy && !this.active && !this.loading).onChange((enabled: boolean): void => { this.reasoning(enabled); })
            }.width('100%').constraintSize({ minHeight: 52 })
            if (this.operation !== null) {
              Text(`救援进度 ${this.operation.cursor} / ${this.operation.targets.length} · ${this.operation.status === 'running' ? '进行中' : this.operation.status === 'completed' ? '已完成' : this.operation.status === 'failed' ? '失败' : '已取消'}`)
                .fontSize(14).fontColor(INK).width('100%')
              if (this.operation.error !== null) { Text(this.operation.error ?? '').fontSize(13).fontColor(ERROR).lineHeight(20).width('100%') }
            }
            Flex({ wrap: FlexWrap.Wrap }) {
              Text('提取末章状态').fontSize(14).fontColor(ACCENT).constraintSize({ minHeight: 44 }).padding({ right: 20 })
                .enabled(!this.active && !this.busy && !this.loading && (this.project?.chapters.length ?? 0) > 0)
                .onClick((): void => { this.syncLatestChapter(); })
              Text('重新救援剧情状态').fontSize(14).fontColor(ACCENT).constraintSize({ minHeight: 44 }).padding({ right: 20 })
                .enabled(!this.active && !this.busy && !this.loading).onClick((): void => { this.confirmRebuild(); })
              if (this.active) {
                Text('停止救援').fontSize(14).fontColor(ACCENT).constraintSize({ minHeight: 44 }).onClick((): void => { this.cancel(); })
              } else if (this.operation?.status === 'failed' || this.operation?.status === 'cancelled') {
                Text('继续重试').fontSize(14).fontColor(ACCENT).constraintSize({ minHeight: 44 }).enabled(!this.busy)
                  .onClick((): void => { this.rebuild(true); })
              }
            }.width('100%')
            if (this.error.length > 0) { Text(this.error).fontSize(13).fontColor(ERROR).lineHeight(20).width('100%') }
            if (this.notice.length > 0) { Text(this.notice).fontSize(13).fontColor(INK3).lineHeight(20).width('100%') }
            this.Timeline()
            Text('人物身份待确认').fontSize(16).fontColor(INK).width('100%')
            if ((this.project?.structuredState?.unresolvedIdentityNames.length ?? 0) === 0) { Text('没有待确认身份。').fontSize(13).fontColor(INK3).width('100%') }
            ForEach(this.project?.structuredState?.unresolvedIdentityNames ?? [], (mention: string): void => {
              Column({ space: 8 }) {
                Text(mention).fontSize(15).fontColor(INK).width('100%')
                Flex({ wrap: FlexWrap.Wrap }) {
                  Text('创建人物').fontSize(14).fontColor(ACCENT).constraintSize({ minHeight: 44 }).padding({ right: 20 }).enabled(!this.active && !this.busy)
                    .onClick((): void => { this.confirmIdentity(mention, 'create', null); })
                  Text('忽略此身份').fontSize(14).fontColor(INK3).constraintSize({ minHeight: 44 }).padding({ right: 20 }).enabled(!this.active && !this.busy)
                    .onClick((): void => { this.confirmIdentity(mention, 'ignore', null); })
                  Text('合并到已有人物').fontSize(14).fontColor(ACCENT).constraintSize({ minHeight: 44 }).enabled(!this.active && !this.busy)
                    .onClick((): void => { this.mergeMention = this.mergeMention === mention ? '' : mention; })
                }.width('100%')
                if (this.mergeMention === mention) {
                  if (this.characters().length === 0) { Text('当前没有可合并的人物资料。').fontSize(13).fontColor(INK3) }
                  ForEach(this.characters(), (material: NovelMaterial): void => {
                    Text(material.title).fontSize(14).fontColor(ACCENT).constraintSize({ minHeight: 44 }).width('100%')
                      .onClick((): void => { this.confirmIdentity(mention, 'merge', material.id); })
                  }, (material: NovelMaterial): string => material.id)
                }
              }.width('100%').padding(12).backgroundColor(SURFACE2).borderRadius(12).alignItems(HorizontalAlign.Start)
            }, (mention: string): string => mention)
            Text('人物经历').fontSize(16).fontColor(INK).width('100%')
            ForEach(this.experiences, (experience: NovelCharacterExperience): void => {
              Column({ space: 10 }) {
                Text(experience.title).fontSize(16).fontColor(INK).width('100%')
                if (experience.aliases.length > 0) { Text(`别名：${experience.aliases.join('、')}`).fontSize(13).fontColor(INK3).lineHeight(20).width('100%') }
                if (experience.events.length === 0) { Text('尚未提取人物经历。').fontSize(13).fontColor(INK3).width('100%') }
                ForEach(experience.events, (event: NovelStateEvent): void => {
                  this.EventRow(event)
                }, (event: NovelStateEvent): string => event.id)
              }.width('100%').alignItems(HorizontalAlign.Start).padding(12).backgroundColor(SURFACE2).borderRadius(12)
            }, (experience: NovelCharacterExperience): string => this.experienceKey(experience))
          }.width('100%').alignItems(HorizontalAlign.Start).padding({ left: 20, right: 20, top: 8, bottom: this.navBarHeight + 24 })
        }.width('100%').layoutWeight(1).align(Alignment.TopStart).scrollBar(BarState.Auto)
      }.width('100%').height('100%').backgroundColor(BG)
    }, (epoch: number): string => `theme-${epoch}`)
    }.width('100%').height('100%').expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
}
