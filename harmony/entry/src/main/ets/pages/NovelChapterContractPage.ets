import { router, KeyboardAvoidMode } from '@kit.ArkUI';
import { getNovelCreation } from '../di/AppContainer.ets';
import { NovelAuditController } from '@amber/deepread-domain';
import type {
  NovelChapterContract, NovelChapterContractInput, NovelProject, NovelWorkspaceCas, NovelWorkspaceSnapshot,
} from '@amber/deepread-domain';
import { BG, INK, INK3, ACCENT, ERROR, LINE, SURFACE2 } from '../design/tokens.ets';
import { PageTitleBar } from '../components/PageTemplate.ets';

interface ChapterContractPageParams { projectId: string; }

@Entry
@Component
struct NovelChapterContractPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @State projectName: string = '';
  @State branchName: string = '';
  @State contract: NovelChapterContract | null = null;
  @State legacyPlanText: string = '';
  @State outlinePlacement: string = '';
  @State goalAndConflict: string = '';
  @State mustHappenText: string = '';
  @State mustNotHappenText: string = '';
  @State endingHook: string = '';
  @State visibleFactsText: string = '';
  @State arcText: string = '';
  @State legacyArcText: string = '';
  @State hasSavedArc: boolean = false;
  @State guidance: string = '';
  @State loading: boolean = true;
  @State busy: boolean = false;
  @State proposing: boolean = false;
  @State error: string = '';
  @State notice: string = '';
  private projectId: string = '';
  private expectedCas: NovelWorkspaceCas | null = null;
  private contractBaseline: string = '';
  private arcBaseline: string = '';
  private alive: boolean = false;
  private hidden: boolean = false;
  private loadToken: number = 0;
  private proposalToken: number = 0;
  private proposalController: NovelAuditController | null = null;
  private previousKeyboardAvoidMode: KeyboardAvoidMode = KeyboardAvoidMode.OFFSET;
  private keyboardAvoidanceActive: boolean = false;

  aboutToAppear(): void {
    this.alive = true;
    const params: ChapterContractPageParams = router.getParams() as ChapterContractPageParams;
    this.projectId = params.projectId;
    this.load();
  }
  onPageShow(): void { this.hidden = false; this.enableKeyboardAvoidance(); }
  onPageHide(): void { this.hidden = true; this.cancelProposal(); this.restoreKeyboardAvoidance(); }
  aboutToDisappear(): void {
    this.alive = false;
    this.loadToken++;
    this.cancelProposal();
    this.restoreKeyboardAvoidance();
  }
  onBackPress(): boolean {
    if (this.busy) return true;
    if (!this.loading && this.hasDirtyDraft()) {
      AlertDialog.show({ title: '放弃未保存的修改？', message: '本章计划或后续走向有未保存内容。',
        primaryButton: { value: '继续编辑', action: (): void => {} },
        secondaryButton: { value: '放弃并返回', action: (): void => { router.back(); } } });
      return true;
    }
    return false;
  }
  private enableKeyboardAvoidance(): void {
    if (this.keyboardAvoidanceActive) return;
    this.previousKeyboardAvoidMode = this.getUIContext().getKeyboardAvoidMode();
    this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
    this.keyboardAvoidanceActive = true;
  }
  private restoreKeyboardAvoidance(): void {
    if (!this.keyboardAvoidanceActive) return;
    this.getUIContext().setKeyboardAvoidMode(this.previousKeyboardAvoidMode);
    this.keyboardAvoidanceActive = false;
  }
  private lines(text: string): string[] {
    return text.split('\n').map((line: string): string => line.trim()).filter((line: string): boolean => line.length > 0);
  }
  private contractInput(): NovelChapterContractInput {
    return { outlinePlacement: this.outlinePlacement.trim(), goalAndConflict: this.goalAndConflict.trim(),
      mustHappen: this.lines(this.mustHappenText), mustNotHappen: this.lines(this.mustNotHappenText),
      endingHook: this.endingHook.trim(), visibleFacts: this.lines(this.visibleFactsText) };
  }
  private hasDirtyDraft(): boolean {
    return JSON.stringify(this.contractInput()) !== this.contractBaseline
      || JSON.stringify(this.lines(this.arcText)) !== this.arcBaseline;
  }
  private editable(): boolean { return !this.loading && !this.busy && this.expectedCas !== null; }
  private fillContract(contract: NovelChapterContract | null): void {
    this.outlinePlacement = contract === null ? '' : contract.outlinePlacement;
    this.goalAndConflict = contract === null ? '' : contract.goalAndConflict;
    this.mustHappenText = contract === null ? '' : contract.mustHappen.join('\n');
    this.mustNotHappenText = contract === null ? '' : contract.mustNotHappen.join('\n');
    this.endingHook = contract === null ? '' : contract.endingHook;
    this.visibleFactsText = contract === null ? '' : contract.visibleFacts.join('\n');
  }
  private acceptSnapshot(snapshot: NovelWorkspaceSnapshot, section: string = 'all'): void {
    this.projectName = snapshot.project.name;
    this.branchName = snapshot.status.activeBranchName;
    this.expectedCas = snapshot.status.cas;
    if (section !== 'arc') {
      this.contract = snapshot.project.branchSettings.chapterContract ?? null;
      this.legacyPlanText = this.contract === null ? snapshot.project.branchSettings.thisChapterPlan : '';
      this.fillContract(this.contract);
      this.contractBaseline = JSON.stringify(this.contractInput());
    }
    if (section !== 'contract') {
      const beats: string[] = snapshot.project.branchSettings.upcomingArc?.beats ?? [];
      this.legacyArcText = snapshot.project.branchSettings.upcomingArc === undefined
        ? snapshot.project.branchSettings.futurePlan : '';
      this.hasSavedArc = beats.length > 0 || this.legacyArcText.length > 0;
      this.arcText = beats.join('\n');
      this.arcBaseline = JSON.stringify(beats);
    }
  }
  private async load(): Promise<void> {
    const token: number = ++this.loadToken;
    this.loading = true;
    try {
      if (this.projectId.length === 0) throw new Error('未指定小说项目');
      const snapshot: NovelWorkspaceSnapshot = await getNovelCreation().readWorkspaceSnapshot(this.projectId);
      if (!this.alive || token !== this.loadToken) return;
      this.acceptSnapshot(snapshot);
      this.error = '';
    } catch (error) { if (this.alive && token === this.loadToken) this.error = String(error); }
    finally { if (this.alive && token === this.loadToken) this.loading = false; }
  }
  private reload(): void {
    if (this.busy || this.loading) return;
    if (this.hasDirtyDraft()) {
      AlertDialog.show({ title: '重新读取已保存内容？', message: '未保存的计划和后续走向会被替换。',
        primaryButton: { value: '继续编辑', action: (): void => {} },
        secondaryButton: { value: '重新读取', action: (): void => { this.load(); } } });
    } else this.load();
  }
  private async refreshSaved(project: NovelProject, section: string): Promise<void> {
    const branchId: string = this.expectedCas === null ? '' : this.expectedCas.branchId;
    const snapshot: NovelWorkspaceSnapshot = await getNovelCreation().readWorkspaceSnapshot(this.projectId);
    if (!this.alive) return;
    if (snapshot.status.activeBranchId !== branchId
      || JSON.stringify(snapshot.project.branchSettings) !== JSON.stringify(project.branchSettings)) {
      this.expectedCas = null;
      throw new Error('保存后工作区又发生变化。当前表单仍保留，请重新读取当前分支再编辑。');
    }
    this.acceptSnapshot(snapshot, section);
  }
  private async saveContract(status: string): Promise<void> {
    if (!this.editable() || this.expectedCas === null) return;
    const cas: NovelWorkspaceCas = this.expectedCas;
    this.busy = true;
    this.error = ''; this.notice = '';
    try {
      const project: NovelProject = await getNovelCreation().setChapterContract(
        this.projectId, this.contractInput(), status === 'confirmed' ? 'confirmed' : 'draft', cas);
      await this.refreshSaved(project, 'contract');
      if (this.alive) this.notice = status === 'confirmed' ? '本章计划已确认，写整章时会使用此计划' : '计划草稿已保存';
    } catch (error) { if (this.alive) this.error = String(error); }
    finally { if (this.alive) this.busy = false; }
  }
  private async saveArc(): Promise<void> {
    if (!this.editable() || this.expectedCas === null) return;
    const beats: string[] = this.lines(this.arcText);
    if (beats.length > 8 || beats.some((beat: string): boolean => beat.length > 160)) {
      this.error = '后续走向最多 8 条，每条不超过 160 字。'; return;
    }
    const cas: NovelWorkspaceCas = this.expectedCas;
    this.busy = true;
    this.error = ''; this.notice = '';
    try {
      const project: NovelProject = await getNovelCreation().setUpcomingArc(this.projectId, beats, cas);
      await this.refreshSaved(project, 'arc');
      if (this.alive) this.notice = beats.length === 0 ? '后续走向已清除' : '后续走向已保存';
    } catch (error) { if (this.alive) this.error = String(error); }
    finally { if (this.alive) this.busy = false; }
  }
  private confirmClear(section: string): void {
    if (!this.editable() || this.expectedCas === null) return;
    const cas: NovelWorkspaceCas = this.expectedCas;
    AlertDialog.show({ title: section === 'contract' ? '清除本章计划？' : '清除后续走向？',
      message: '仅清除当前分支的对应内容，正文和其他分支保持原样。',
      primaryButton: { value: '取消', action: (): void => {} },
      secondaryButton: { value: '清除', fontColor: ERROR, action: (): void => { this.clear(section, cas); } } });
  }
  private async clear(section: string, cas: NovelWorkspaceCas): Promise<void> {
    if (!this.editable()) return;
    this.busy = true;
    this.error = ''; this.notice = '';
    try {
      const creation = getNovelCreation();
      const project: NovelProject = section === 'contract'
        ? await creation.clearChapterContract(this.projectId, cas)
        : await creation.setUpcomingArc(this.projectId, [], cas);
      await this.refreshSaved(project, section);
      if (this.alive) this.notice = section === 'contract' ? '本章计划已清除' : '后续走向已清除';
    } catch (error) { if (this.alive) this.error = String(error); }
    finally { if (this.alive) this.busy = false; }
  }
  private confirmProposal(): void {
    if (!this.editable()) return;
    if (JSON.stringify(this.contractInput()) !== this.contractBaseline) {
      AlertDialog.show({ title: '用模型建议替换计划草稿？', message: '本章计划有未保存修改。建议返回后会填入表单，仍需你保存或确认。',
        primaryButton: { value: '继续编辑', action: (): void => {} },
        secondaryButton: { value: '生成建议', action: (): void => { this.propose(); } } });
    } else this.propose();
  }
  private cancelProposal(): void {
    if (this.proposalController === null) return;
    this.proposalToken++;
    this.proposalController.cancel();
    this.proposalController = null;
    this.proposing = false;
    this.busy = false;
  }
  private async propose(): Promise<void> {
    if (!this.editable() || this.expectedCas === null) return;
    const token: number = ++this.proposalToken;
    const controller: NovelAuditController = new NovelAuditController();
    this.proposalController = controller;
    this.busy = true; this.proposing = true;
    this.error = ''; this.notice = '';
    try {
      const result = await getNovelCreation().proposeChapterContract(this.projectId, this.guidance, this.expectedCas, controller);
      if (!this.alive || this.hidden || token !== this.proposalToken) return;
      this.expectedCas = result.expectedCas;
      this.fillContract(result.contract);
      this.notice = '建议已填入草稿。保存草稿或确认计划后才写入工作区。';
    } catch (error) {
      if (this.alive && !this.hidden && token === this.proposalToken) this.error = String(error);
    } finally {
      if (this.alive && token === this.proposalToken) {
        this.proposalController = null;
        this.proposing = false; this.busy = false;
      }
    }
  }

  @Builder
  private Label(text: string): void {
    Text(text).fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK).width('100%').textAlign(TextAlign.Start)
  }

  build(): void {
    Column() {
      ForEach([this.themeEpoch], (_epoch: number): void => {
        Column() {
          PageTitleBar({ title: '本章计划与走向', actionText: '重新读取',
            onBack: (): void => { if (!this.onBackPress()) router.back(); }, onAction: (): void => { this.reload(); } })
          Scroll() {
            Column({ space: 10 }) {
              Text(this.projectName).fontSize(18).fontWeight(FontWeight.Medium).fontColor(INK).width('100%')
              Text(`当前分支：${this.branchName}`).fontSize(13).fontColor(INK3).width('100%')
              if (this.loading) { Text('正在读取计划…').fontSize(14).fontColor(INK3) }
              if (this.error.length > 0) { Text(this.error).fontSize(13).lineHeight(21).fontColor(ERROR).width('100%') }
              if (this.notice.length > 0) { Text(this.notice).fontSize(13).lineHeight(21).fontColor(ACCENT).width('100%') }
              Text('本章计划').fontSize(17).fontWeight(FontWeight.Medium).fontColor(INK).width('100%').margin({ top: 10 })
              Text(this.contract === null ? (this.legacyPlanText.length > 0 ? '已有文字计划' : '未设置')
                : this.contract.status === 'confirmed' ? '已确认（当前已保存计划）' : '已保存草稿')
                .fontSize(13).fontColor(INK3).width('100%')
              Text('确认的计划用于整章写作与代笔审稿；修改后请再次确认。草稿不会作为已确认计划使用。')
                .fontSize(13).lineHeight(21).fontColor(INK3).width('100%')
              if (this.legacyPlanText.length > 0) {
                Text('原有文字计划继续生效。下面保存新计划会替换它，请先核对内容。')
                  .fontSize(13).lineHeight(21).fontColor(INK3).width('100%')
                Text(this.legacyPlanText).fontSize(14).lineHeight(22).fontColor(INK).width('100%')
                  .padding(12).backgroundColor(SURFACE2).borderRadius(10).copyOption(CopyOptions.LocalDevice)
              }
              this.Label('大纲位置')
              TextInput({ text: this.outlinePlacement, placeholder: '例如：第 3 章 · 中段转折' })
                .height(44).fontSize(14).fontColor(INK).placeholderColor(INK3).backgroundColor(SURFACE2)
                .enabled(this.editable()).onChange((value: string): void => { this.outlinePlacement = value; })
              this.Label('目标与冲突')
              TextArea({ text: this.goalAndConflict, placeholder: '本章要解决什么' })
                .height(104).fontSize(14).fontColor(INK).placeholderColor(INK3).backgroundColor(SURFACE2)
                .enabled(this.editable()).onChange((value: string): void => { this.goalAndConflict = value; })
              this.Label('必发生（每行一条）')
              TextArea({ text: this.mustHappenText, placeholder: '至少一条' })
                .height(96).fontSize(14).fontColor(INK).placeholderColor(INK3).backgroundColor(SURFACE2)
                .enabled(this.editable()).onChange((value: string): void => { this.mustHappenText = value; })
              this.Label('禁止发生（每行一条）')
              TextArea({ text: this.mustNotHappenText, placeholder: '可空' })
                .height(88).fontSize(14).fontColor(INK).placeholderColor(INK3).backgroundColor(SURFACE2)
                .enabled(this.editable()).onChange((value: string): void => { this.mustNotHappenText = value; })
              this.Label('章末钩子')
              TextArea({ text: this.endingHook, placeholder: '留下什么悬念（可空）' })
                .height(80).fontSize(14).fontColor(INK).placeholderColor(INK3).backgroundColor(SURFACE2)
                .enabled(this.editable()).onChange((value: string): void => { this.endingHook = value; })
              this.Label('POV 可见要点（每行一条）')
              TextArea({ text: this.visibleFactsText, placeholder: '视角人物可以知道的事实（可空）' })
                .height(88).fontSize(14).fontColor(INK).placeholderColor(INK3).backgroundColor(SURFACE2)
                .enabled(this.editable()).onChange((value: string): void => { this.visibleFactsText = value; })
              Row({ space: 12 }) {
                Text('保存草稿').fontSize(14).fontColor(ACCENT).layoutWeight(1).constraintSize({ minHeight: 48 })
                  .enabled(this.editable()).onClick((): void => { this.saveContract('draft'); })
                Text('确认计划').fontSize(14).fontColor(ACCENT).layoutWeight(1).constraintSize({ minHeight: 48 })
                  .enabled(this.editable()).onClick((): void => { this.saveContract('confirmed'); })
                if (this.contract !== null || this.legacyPlanText.length > 0) {
                  Text('清除').fontSize(14).fontColor(ERROR).constraintSize({ minHeight: 48, minWidth: 44 })
                    .enabled(this.editable()).onClick((): void => { this.confirmClear('contract'); })
                }
              }.width('100%')
              this.Label('模型建议的调整方向（可选）')
              TextArea({ text: this.guidance, placeholder: '例如：加强人物冲突，不提前揭开秘密' })
                .height(88).fontSize(14).fontColor(INK).placeholderColor(INK3).backgroundColor(SURFACE2)
                .enabled(this.editable()).onChange((value: string): void => { this.guidance = value; })
              Text(this.proposing ? '正在生成计划建议…' : '一键拟本章计划').fontSize(15).fontColor(ACCENT)
                .width('100%').constraintSize({ minHeight: 48 }).enabled(this.editable())
                .onClick((): void => { this.confirmProposal(); })
              if (this.proposing) {
                Text('取消生成').fontSize(14).fontColor(ERROR).constraintSize({ minHeight: 44 })
                  .onClick((): void => { this.cancelProposal(); })
              }
              Text('往后几章').fontSize(17).fontWeight(FontWeight.Medium).fontColor(INK).width('100%')
                .padding({ top: 18 }).border({ width: { top: 1 }, color: LINE })
              Text('每行一条，最多 8 条，每条最多 160 字。写整章时会参考，不替代本章计划。')
                .fontSize(13).lineHeight(21).fontColor(INK3).width('100%')
              if (this.legacyArcText.length > 0) {
                Text('原有文字方向继续生效，保存新走向会替换它。')
                  .fontSize(13).lineHeight(21).fontColor(INK3).width('100%')
                Text(this.legacyArcText).fontSize(14).lineHeight(22).fontColor(INK).width('100%')
                  .padding(12).backgroundColor(SURFACE2).borderRadius(10).copyOption(CopyOptions.LocalDevice)
              }
              TextArea({ text: this.arcText, placeholder: '例如：使者身份曝光' })
                .height(144).fontSize(14).fontColor(INK).placeholderColor(INK3).backgroundColor(SURFACE2)
                .enabled(this.editable()).onChange((value: string): void => { this.arcText = value; })
              Text(`已填写 ${this.lines(this.arcText).length} / 8 条`).fontSize(12).fontColor(INK3).width('100%')
              Row({ space: 20 }) {
                Text('保存后续走向').fontSize(14).fontColor(ACCENT).layoutWeight(1).constraintSize({ minHeight: 48 })
                  .enabled(this.editable()).onClick((): void => { this.saveArc(); })
                if (this.hasSavedArc) {
                  Text('清除后续走向').fontSize(14).fontColor(ERROR).constraintSize({ minHeight: 48 })
                    .enabled(this.editable()).onClick((): void => { this.confirmClear('arc'); })
                }
              }.width('100%')
              if (this.busy && !this.proposing) { Text('正在保存…').fontSize(13).fontColor(INK3) }
            }.width('100%').alignItems(HorizontalAlign.Start)
              .padding({ left: 20, right: 20, top: 16, bottom: this.navBarHeight + 24 })
          }.width('100%').layoutWeight(1).align(Alignment.TopStart).scrollBar(BarState.Off)
        }.width('100%').height('100%').backgroundColor(BG)
          .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
      }, (epoch: number): string => `theme-${epoch}`)
    }.width('100%').height('100%')
  }
}
