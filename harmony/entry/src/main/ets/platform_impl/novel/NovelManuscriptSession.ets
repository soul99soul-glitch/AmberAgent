import type {
  NovelCreation, NovelProject, NovelMessage, NovelChapter, NovelWorkspaceStatus, NovelWorkspaceCas,
  NovelCollectionTarget, NovelSuggestionRefreshResult,
} from '@amber/deepread-domain';
import { canCloneCollectedMessage, novelMessageText, nextNovelChapterOrdinal } from '@amber/deepread-domain';
import {
  selectedParagraphDraft, draftAfterSelection, canCollectSelection, defaultCollectionLocation,
} from '../../novel/NovelComposerState';
import type { CollectionLocation } from '../../novel/NovelComposerState';
import { ERROR } from '../../design/tokens.ets';

export interface NovelManuscriptPorts {
  creation: NovelCreation;
  currentProject: () => NovelProject | null;
  workspaceStatus: () => NovelWorkspaceStatus | null;
  projectId: () => string;
  busy: () => boolean;
  setBusy: (busy: boolean) => void;
  error: () => string;
  setError: (error: string) => void;
  reload: () => Promise<void>;
  pageAlive: () => boolean;
  pageHidden: () => boolean;
  hasBlockingNovelRun: () => boolean;
  novelJobLocksWorkspace: () => boolean;
  visibleCycle: () => number;
  onCollected: (projectId: string, branchId: string, visibleCycle: number) => void;
}

// The page owns the workspace snapshot and operation lock; this session owns editable manuscript drafts.
@Observed
export class NovelManuscriptSession {
  collectMessageId: string = '';
  collectTitle: string = '';
  collectParas: string[] = [];
  collectSelected: boolean[] = [];
  collectDraft: string = '';
  collectManuallyEdited: boolean = false;
  collectMode: number = 0;
  collectChapterId: string = '';
  collectionFeedback: string = '';
  private collectionFeedbackToken: number = 0;
  chapterEditorOpen: boolean = false;
  editingChapterId: string = '';
  chapterTitle: string = '';
  chapterContent: string = '';
  collectDraftController: TextAreaController = new TextAreaController();
  collectTitleController: TextInputController = new TextInputController();
  chapterTitleController: TextInputController = new TextInputController();
  chapterContentController: TextAreaController = new TextAreaController();
  private collectOriginalTitle: string = '';
  private collectOriginalDraft: string = '';
  private collectOriginalMode: number = 0;
  private collectOriginalChapterId: string = '';
  private chapterOriginalTitle: string = '';
  private chapterOriginalContent: string = '';
  private ports: NovelManuscriptPorts | null = null;

  start(ports: NovelManuscriptPorts): void { this.ports = ports; }

  private get host(): NovelManuscriptPorts {
    if (this.ports === null) throw new Error('书稿会话尚未启动');
    return this.ports;
  }
  private get project(): NovelProject | null { return this.host.currentProject(); }
  private get workspaceStatus(): NovelWorkspaceStatus | null { return this.host.workspaceStatus(); }
  private get projectId(): string { return this.host.projectId(); }
  private get busy(): boolean { return this.host.busy(); }
  private set busy(value: boolean) { this.host.setBusy(value); }
  private get errorMsg(): string { return this.host.error(); }
  private set errorMsg(value: string) { this.host.setError(value); }
  private get pageAlive(): boolean { return this.host.pageAlive(); }
  private get pageHiddenOnce(): boolean { return this.host.pageHidden(); }
  private reload(): Promise<void> { return this.host.reload(); }
  private hasBlockingNovelRun(): boolean { return this.host.hasBlockingNovelRun(); }
  private novelJobLocksWorkspace(): boolean { return this.host.novelJobLocksWorkspace(); }

  clearCollectionFeedback(): void {
    this.collectionFeedback = '';
    this.collectionFeedbackToken++;
  }

  chapterDirty(): boolean {
    return this.chapterTitle !== this.chapterOriginalTitle
      || this.chapterContent !== this.chapterOriginalContent;
  }

  confirmDiscardChapter(): void {
    if (this.busy) return;
    this.chapterTitleController.stopEditing();
    this.chapterContentController.stopEditing();
    setTimeout((): void => { this.confirmDiscardCommittedChapter(); }, 0);
  }

  confirmDiscardCommittedChapter(): void {
    if (!this.pageAlive || !this.chapterEditorOpen || this.busy) return;
    if (!this.chapterDirty()) {
      this.chapterEditorOpen = false;
      return;
    }
    AlertDialog.show({
      title: '放弃编辑？',
      message: '未保存的章节内容将丢失。',
      primaryButton: { value: '继续编辑', action: (): void => {} },
      secondaryButton: {
        value: '放弃',
        fontColor: ERROR,
        action: (): void => { if (!this.busy) this.chapterEditorOpen = false; },
      },
    });
  }

  confirmCloneCollected(message: NovelMessage): void {
    const project: NovelProject | null = this.project;
    const cas: NovelWorkspaceCas | undefined = this.workspaceStatus?.cas;
    if (project === null || cas === undefined || this.busy || this.hasBlockingNovelRun() ||
      !canCloneCollectedMessage(project, message, cas.branchId)) return;
    const projectId: string = this.projectId;
    AlertDialog.show({ title: '再次收录这份回复？',
      message: '根据当前书稿再次收录这份原回复。将创建一份带来源标记的新候选，再由你选择段落与收录位置；原收录记录保留。',
      primaryButton: { value: '取消', action: (): void => {} },
      secondaryButton: { value: '创建收录候选', action: (): void => {
        if (this.pageAlive && !this.pageHiddenOnce && this.projectId === projectId && this.workspaceStatus?.activeBranchId === cas.branchId) {
          this.cloneCollectedForCollection(message.id, cas);
        }
      } } });
  }

  async cloneCollectedForCollection(messageId: string, cas: NovelWorkspaceCas): Promise<void> {
    if (this.busy || this.hasBlockingNovelRun() || this.workspaceStatus?.activeBranchId !== cas.branchId) return;
    const projectId: string = this.projectId;
    this.busy = true;
    this.errorMsg = '';
    try {
      const cloned: NovelMessage = await this.host.creation.cloneCollectedMessage(projectId, messageId, cas);
      if (!this.pageAlive || this.pageHiddenOnce || this.projectId !== projectId) return;
      await this.reload();
      if (this.pageAlive && !this.pageHiddenOnce && this.projectId === projectId && this.workspaceStatus?.activeBranchId === cas.branchId) {
        if (this.project?.messages.some((message: NovelMessage): boolean => message.id === cloned.id)) this.openCollect(cloned.id);
        else if (this.errorMsg.length === 0) this.errorMsg = '收录候选已保存，但页面尚未读取到。请重新打开项目后再收录。';
      }
    } catch (error) {
      if (this.pageAlive && this.projectId === projectId) this.errorMsg = String(error);
    } finally { if (this.pageAlive && this.projectId === projectId) this.busy = false; }
  }

  openCollect(messageId: string): void {
    const p: NovelProject | null = this.project;
    const msg: NovelMessage | null = p === null ? null
      : p.messages.find((m: NovelMessage): boolean => m.id === messageId) ?? null;
    const location: CollectionLocation = defaultCollectionLocation(
      msg?.candidate?.kind ?? 'write', msg?.candidate?.sourceChapterId ?? null,
      msg?.granularity ?? null, p?.chapters ?? []);
    try {
      this.collectTitle = p !== null ? `第 ${nextNovelChapterOrdinal(p.chapters)} 章` : '第 1 章';
    } catch (error) {
      this.collectTitle = '';
      this.errorMsg = error instanceof Error ? error.message : String(error);
    }
    this.collectMessageId = messageId;
    const text: string = msg === null ? '' : novelMessageText(msg);
    this.collectParas = text.split(/\n\s*\n/).map((s: string): string => s.trim())
      .filter((s: string): boolean => s.length > 0);
    this.collectSelected = this.collectParas.map((): boolean => true);
    this.collectDraft = this.collectParas.join('\n\n');
    this.collectManuallyEdited = false;
    this.collectMode = location.mode;
    this.collectChapterId = location.chapterId;
    this.collectOriginalTitle = this.collectTitle;
    this.collectOriginalDraft = this.collectDraft;
    this.collectOriginalMode = this.collectMode;
    this.collectOriginalChapterId = this.collectChapterId;
  }

  togglePara(idx: number): void {
    const next: boolean[] = this.collectSelected.slice();
    next[idx] = !next[idx];
    this.collectSelected = next;
    this.refreshCollectDraft();
  }

  selectCollectParagraphs(selected: boolean): void {
    this.collectSelected = this.collectParas.map((): boolean => selected);
    this.refreshCollectDraft();
  }

  refreshCollectDraft(): void {
    this.collectDraft = draftAfterSelection(
      this.collectParas, this.collectSelected, this.collectDraft, this.collectManuallyEdited);
  }

  collectSelectedCount(): number {
    return this.collectSelected.filter((v: boolean): boolean => v).length;
  }

  resetDraftFromSelection(): void {
    this.collectManuallyEdited = false;
    this.collectDraft = selectedParagraphDraft(this.collectParas, this.collectSelected);
  }

  confirmDiscardCollect(): void {
    if (this.busy) return;
    const messageId: string = this.collectMessageId;
    this.collectDraftController.stopEditing();
    this.collectTitleController.stopEditing();
    // 退出编辑先接收输入法最终 onChange，再判断脏稿。
    setTimeout((): void => {
      if (!this.pageAlive || this.collectMessageId !== messageId || this.busy) return;
      const dirty: boolean = this.collectDraft !== this.collectOriginalDraft ||
        this.collectTitle !== this.collectOriginalTitle || this.collectMode !== this.collectOriginalMode ||
        this.collectChapterId !== this.collectOriginalChapterId ||
        this.collectSelected.some((selected: boolean): boolean => !selected);
      if (!dirty) {
        this.collectMessageId = '';
        return;
      }
      AlertDialog.show({
        title: '放弃收录编辑？',
        message: '尚未收录的正文修改和段落选择将丢失。',
        primaryButton: { value: '继续编辑', action: (): void => {} },
        secondaryButton: { value: '放弃', fontColor: ERROR,
          action: (): void => { this.collectMessageId = ''; } },
      });
    }, 0);
  }

  collectDisabled(): boolean {
    if (this.busy || !canCollectSelection(this.collectSelected, this.collectDraft)) return true;
    return this.collectMode !== 0 && this.collectChapterId.length === 0;
  }

  async submitCollect(): Promise<void> {
    if (this.collectMessageId.length === 0 || this.collectDisabled()) return;
    const messageId: string = this.collectMessageId;
    const visibleCycle: number = this.host.visibleCycle();
    this.collectDraftController.stopEditing();
    this.collectTitleController.stopEditing();
    await new Promise<void>((resolve: () => void): void => { setTimeout(resolve, 0); });
    if (this.collectMessageId !== messageId || this.collectDisabled()) return;
    let target: NovelCollectionTarget;
    if (this.collectMode === 0) target = { kind: 'new_chapter', title: this.collectTitle };
    else if (this.collectMode === 1) target = { kind: 'append', chapterId: this.collectChapterId };
    else target = { kind: 'replace', chapterId: this.collectChapterId };
    this.busy = true;
    try {
      const projectId: string = this.projectId;
      const branchId: string = this.workspaceStatus?.activeBranchId ?? '';
      const collected = await this.host.creation.collectMessage(projectId, this.collectMessageId, target,
        this.collectDraft.trim());
      if (!this.pageAlive || this.pageHiddenOnce || this.projectId !== projectId ||
        this.workspaceStatus?.activeBranchId !== branchId) return;
      const feedbackToken: number = ++this.collectionFeedbackToken;
      this.collectionFeedback = '正文已收录，剧情同步点已保存。正在分析本章资料建议…';
      void collected.analysisFinished.then(
        (result: NovelSuggestionRefreshResult): Promise<void> => this.refreshCollectedAnalysis(projectId, branchId, feedbackToken, result),
        (): Promise<void> => this.refreshCollectedAnalysis(projectId, branchId, feedbackToken, null),
      ).catch((): void => {});
      this.collectMessageId = '';
      this.errorMsg = '';
      await this.reload();
      this.host.onCollected(projectId, branchId, visibleCycle);
    } catch (e) {
      this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }

  materialAnalysisFailureText(error: string): string {
    if (error.includes('资料建议解析失败')) {
      return '模型返回的资料建议格式不完整，请重新分析此章。';
    }
    return error.replace(/^(NovelError|Error):\s*/, '');
  }

  async refreshCollectedAnalysis(projectId: string, branchId: string, token: number,
    result: NovelSuggestionRefreshResult | null): Promise<void> {
    if (!this.pageAlive || this.projectId !== projectId ||
      this.workspaceStatus?.activeBranchId !== branchId || token !== this.collectionFeedbackToken) return;
    this.collectionFeedback = result !== null
      ? `正文已收录，剧情同步点已保存。${result.warning ?? `资料分析完成，新增 ${result.count} 条建议。`}`
      : '正文已收录，剧情同步点已保存。资料分析未完成，可在「设定」中重新分析此章。';
    await this.reload();
  }

  openNewChapter(): void {
    if (this.busy || this.novelJobLocksWorkspace()) return;
    this.errorMsg = '';
    this.editingChapterId = '';
    const p: NovelProject | null = this.project;
    try {
      this.chapterTitle = p !== null ? `第 ${nextNovelChapterOrdinal(p.chapters)} 章` : '第 1 章';
    } catch (error) {
      this.errorMsg = error instanceof Error ? error.message : String(error);
      return;
    }
    this.chapterContent = '';
    this.chapterOriginalTitle = this.chapterTitle;
    this.chapterOriginalContent = this.chapterContent;
    this.chapterEditorOpen = true;
  }

  async saveChapter(): Promise<void> {
    if (this.busy || !this.chapterEditorOpen || this.novelJobLocksWorkspace()) return;
    this.chapterTitleController.stopEditing();
    this.chapterContentController.stopEditing();
    await new Promise<void>((resolve: () => void): void => { setTimeout(resolve, 0); });
    if (!this.pageAlive || !this.chapterEditorOpen || this.busy || this.novelJobLocksWorkspace()) return;
    this.busy = true;
    try {
      const saved: NovelChapter = await this.host.creation.saveChapter(
        this.projectId, this.editingChapterId.length > 0 ? this.editingChapterId : null,
        this.chapterTitle, this.chapterContent);
      this.chapterEditorOpen = false;
      this.errorMsg = '';
      await this.reload();
    } catch (e) {
      this.errorMsg = e instanceof Error ? e.message : String(e);
    } finally {
      this.busy = false;
    }
  }

  async deleteChapter(id: string): Promise<void> {
    if (this.novelJobLocksWorkspace()) return;
    this.busy = true;
    try {
      await this.host.creation.deleteChapter(this.projectId, id);
      await this.reload();
    } catch (e) {
      this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }

  async reanalyze(chapterId: string = ''): Promise<void> {
    const p: NovelProject | null = this.project;
    if (this.busy || this.hasBlockingNovelRun() || p === null || p.chapters.length === 0) return;
    this.busy = true;
    this.errorMsg = '';
    this.collectionFeedback = '';
    this.collectionFeedbackToken++;
    try {
      const chapter: NovelChapter | undefined = chapterId.length > 0
        ? p.chapters.find((item: NovelChapter): boolean => item.id === chapterId) : p.chapters[p.chapters.length - 1];
      if (chapter === undefined) return;
      await this.host.creation.refreshMaterialSuggestions(this.projectId, chapter.id);
      await this.reload();
    } catch (e) {
      // 失败信息已由 domain 持久化到章上，重读后保留本次错误。
      await this.reload();
      if (this.pageAlive) this.errorMsg = this.materialAnalysisFailureText(String(e));
    } finally {
      this.busy = false;
    }
  }

  charCount(s: string): number {
    return s.replace(/\s/g, '').length;
  }
}
