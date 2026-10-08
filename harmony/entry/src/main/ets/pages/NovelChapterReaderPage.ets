// NovelChapterReaderPage — 章节阅读器(iOS NovelChapterReaderView 结构):
// push 独立页:principal(标题+第 N 章·字数)+ ⋯菜单(版本历史/编辑/整章润色/
// 整章重新生成/废弃/删除)+ Markdown 正文 + 底部上一章/下一章胶囊。
// 整章润色/重生成属工作区聊天流任务:此处触发后退回工作区由 reload 重挂进度。
import { router, promptAction, KeyboardAvoidMode } from '@kit.ArkUI';
import {
  ACCENT, BG, ERROR, INK, INK3, INK4, LINE, MOTION_ENTER, MOTION_OVERLAY, OVERLAY, SURFACE, SURFACE2,
  WARNING_FG,
} from '../design/tokens.ets';
import { getProductKind } from '../platform_impl/ProductIdentity.ets';
import { FONT_SANS_MEDIUM, FONT_SANS_SEMIBOLD } from '../design/fonts.ets';
import { CenteredTitleBar } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';
import { NovelPolishCreateSheet, NovelPolishCreateSheetChapter } from '../components/NovelPolishCreateSheet.ets';
import { NovelRevisionBriefSheet } from '../components/NovelRevisionBriefSheet.ets';
import { NovelChapterVersionPreview } from '../components/NovelChapterVersionPreview.ets';
import { MarkdownText } from '../components/MarkdownText.ets';
import { getNovelCreation } from '../di/AppContainer.ets';
import {
  NovelChapter, NovelChapterVersion, NovelChapterVersionKind, NovelProject,
  PolishContextOptions, novelChapterOrdinal, defaultGhostwriteDigest, NovelWorkspaceCas, NovelWorkspaceSnapshot,
  findNovelChapterMatches, replaceNovelChapterMatch,
  WorkspaceCommit,
} from '@amber/deepread-domain';

interface ReaderParams {
  projectId?: string;
  chapterId?: string;
  evidenceQuote?: string;
  evidenceStart?: number;
  evidenceEnd?: number;
  evidenceDigest?: string;
  evidenceBranchId?: string;
}

const charCountOf = (s: string): number => s.replace(/\s/g, '').length;

@Entry
@Component
struct NovelChapterReaderPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @State project: NovelProject | null = null;
  @State chapterId: string = '';
  @State loaded: boolean = false;
  @State errorMsg: string = '';
  @State notice: string = '';
  @State evidenceQuote: string = '';
  @State evidenceFocused: boolean = false;
  private evidenceStart: number = 0;
  private evidenceEnd: number = 0;
  private evidenceDigest: string = '';
  private evidenceBranchId: string = '';
  private readerScroller: Scroller = new Scroller();
  @State busy: boolean = false;
  @State menuOpen: boolean = false;
  @State versionsOpen: boolean = false;
  @State versionPreview: NovelChapterVersion | null = null;
  @State versionForkName: string = '';
  @State versionCheckpoint: WorkspaceCommit | null = null;
  @State versionForkDetail: string = '';
  @State versionForking: boolean = false;
  private versionCheckpointCas: NovelWorkspaceCas | null = null;
  private versionCheckpointToken: number = 0;
  @State editOpen: boolean = false;
  @State revisionOpen: boolean = false;
  @State revisionKind: string = 'revise';
  @State revisionDetail: string = '';
  @State revisionError: string = '';
  @State revisionCloseRequest: number = 0;
  private revisionExpectedCas: NovelWorkspaceCas | null = null;
  private revisionChapterId: string = '';
  private revisionToken: number = 0;
  @State polishOpen: boolean = false;
  @State polishModelReady: boolean = false;
  @State polishModelError: string = '';
  @State polishStartError: string = '';
  @State editTitle: string = '';
  @State editContent: string = '';
  @State findOpen: boolean = false;
  @State findQuery: string = '';
  @State replaceText: string = '';
  @State findMatchIndex: number = 0;
  @State replaceNotice: string = '';
  private editExpectedCas: NovelWorkspaceCas | null = null;
  private editOriginalTitle: string = '';
  private editOriginalContent: string = '';
  private projectId: string = '';
  private pageAlive: boolean = false;
  private previousKeyboardAvoidMode: KeyboardAvoidMode = KeyboardAvoidMode.OFFSET;
  private keyboardAvoidanceActive: boolean = false;
  private editTitleController: TextInputController = new TextInputController();
  private editContentController: TextAreaController = new TextAreaController();
  private findQueryController: TextInputController = new TextInputController();
  private replaceTextController: TextInputController = new TextInputController();

  aboutToAppear(): void {
    this.pageAlive = true;
    const params: ReaderParams = router.getParams() as ReaderParams;
    this.projectId = params?.projectId ?? '';
    this.chapterId = params?.chapterId ?? '';
    this.evidenceQuote = params?.evidenceQuote ?? '';
    this.evidenceStart = params?.evidenceStart ?? 0;
    this.evidenceEnd = params?.evidenceEnd ?? 0;
    this.evidenceDigest = params?.evidenceDigest ?? '';
    this.evidenceBranchId = params?.evidenceBranchId ?? '';
    this.evidenceFocused = this.evidenceQuote.length > 0;
    this.reload();
  }

  onPageShow(): void { this.enableKeyboardAvoidance(); }

  onPageHide(): void { this.restoreKeyboardAvoidance(); }

  aboutToDisappear(): void {
    this.pageAlive = false;
    this.revisionToken++;
    this.restoreKeyboardAvoidance();
  }

  private enableKeyboardAvoidance(): void {
    // 键盘模式属于窗口：在页面显示后取得，避免被上一页的 onPageHide 恢复覆盖。
    if (!this.keyboardAvoidanceActive) {
      this.previousKeyboardAvoidMode = this.getUIContext().getKeyboardAvoidMode();
      this.keyboardAvoidanceActive = true;
    }
    this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
  }

  private restoreKeyboardAvoidance(): void {
    if (!this.keyboardAvoidanceActive) return;
    this.getUIContext().setKeyboardAvoidMode(this.previousKeyboardAvoidMode);
    this.keyboardAvoidanceActive = false;
  }

  onBackPress(): boolean {
    if (this.busy) return true;
    if (this.revisionOpen) {
      this.revisionCloseRequest++;
      return true;
    }
    if (this.editOpen) {
      this.confirmDiscardEdit();
      return true;
    }
    if (this.polishOpen) {
      if (!this.busy) this.polishOpen = false;
      return true;
    }
    if (this.versionsOpen) {
      if (!this.busy) {
        if (this.versionPreview !== null) {
          this.versionPreview = null;
          this.errorMsg = '';
        } else this.versionsOpen = false;
      }
      return true;
    }
    if (this.menuOpen) {
      this.menuOpen = false;
      return true;
    }
    return false;
  }

  private async reload(): Promise<void> {
    try {
      const snapshot: NovelWorkspaceSnapshot = await getNovelCreation().readWorkspaceSnapshot(this.projectId);
      if (!this.pageAlive) return;
      this.project = snapshot.project;
      this.loaded = true;
      this.errorMsg = '';
      if (this.evidenceQuote.length > 0) {
        const chapter: NovelChapter | null = this.chapter();
        if (chapter === null || snapshot.status.activeBranchId !== this.evidenceBranchId ||
          defaultGhostwriteDigest(chapter.content) !== this.evidenceDigest ||
          chapter.content.slice(this.evidenceStart, this.evidenceEnd) !== this.evidenceQuote) {
          this.evidenceQuote = '';
          this.evidenceFocused = false;
          this.notice = '证据来自旧分支或旧稿，当前显示最新全文。请重新审校。';
        }
      }
    } catch (e) {
      if (this.pageAlive) {
        this.loaded = true;
        this.errorMsg = `项目无法打开：${String(e)}`;
      }
    }
  }

  private chapterIndex(): number {
    const p: NovelProject | null = this.project;
    if (p === null) return -1;
    return p.chapters.findIndex((c: NovelChapter): boolean => c.id === this.chapterId);
  }

  private chapter(): NovelChapter | null {
    const idx: number = this.chapterIndex();
    return idx < 0 ? null : (this.project?.chapters[idx] ?? null);
  }

  private chapterOrdinal(): number {
    const chapter: NovelChapter | null = this.chapter();
    return chapter === null ? 0 : novelChapterOrdinal(chapter, this.chapterIndex() + 1);
  }

  private readerContent(): string {
    const body: string = this.chapter()?.content ?? '';
    if (!this.evidenceFocused || this.evidenceQuote.length === 0) return body.length > 0 ? body : '这一章还没有正文。';
    return body.slice(Math.max(0, this.evidenceStart - 300), Math.min(body.length, this.evidenceEnd + 300));
  }

  private toggleEvidenceFocus(): void {
    this.evidenceFocused = !this.evidenceFocused;
    this.readerScroller.scrollEdge(Edge.Top);
  }

  private jumpTo(idx: number): void {
    const p: NovelProject | null = this.project;
    if (!this.pageAlive || !this.loaded || this.busy || p === null || idx < 0 || idx >= p.chapters.length) return;
    const nextChapterId: string = p.chapters[idx].id;
    if (nextChapterId === this.chapterId) return;
    animateTo({ duration: MOTION_ENTER, curve: Curve.EaseOut }, (): void => {
      this.chapterId = nextChapterId;
      this.notice = '';
      this.evidenceQuote = '';
      this.evidenceFocused = false;
    });
    // 正文重挂载，但 Scroll 保留原控制器；同步归顶避免下一章沿用旧章滚动位置。
    this.readerScroller.scrollEdge(Edge.Top);
  }

  private versionKindLabel(kind: NovelChapterVersionKind): string {
    if (kind === 'manual') return '手动保存';
    if (kind === 'polish') return '润色前';
    if (kind === 'regenerate') return '重生成前';
    return '收录';
  }

  private versionTime(ts: number): string {
    const d: Date = new Date(ts);
    const pad = (n: number): string => n < 10 ? `0${n}` : `${n}`;
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }

  private chapterVersions(): NovelChapterVersion[] {
    const p: NovelProject | null = this.project;
    if (p === null) return [];
    return p.chapterVersions.filter((v: NovelChapterVersion): boolean => v.chapterId === this.chapterId)
      .slice().reverse();
  }

  private closeVersions(): void {
    if (this.busy) return;
    this.versionCheckpointToken++;
    this.versionPreview = null;
    this.versionsOpen = false;
    this.errorMsg = '';
  }

  private async previewVersion(version: NovelChapterVersion): Promise<void> {
    if (this.busy) return;
    const token: number = ++this.versionCheckpointToken;
    this.errorMsg = '';
    this.versionPreview = version;
    this.versionForkName = '';
    this.versionCheckpoint = null;
    this.versionCheckpointCas = null;
    this.versionForkDetail = '正在查找此版本关联的完整工作区检查点…';
    try {
      const snapshot: NovelWorkspaceSnapshot = await getNovelCreation().readWorkspaceSnapshot(this.projectId);
      const checkpoint: WorkspaceCommit | null = await getNovelCreation().chapterVersionCheckpoint(
        this.projectId, version.id, snapshot.status.cas);
      if (!this.pageAlive || !this.versionsOpen || this.versionPreview?.id !== version.id || token !== this.versionCheckpointToken) return;
      this.versionCheckpoint = checkpoint;
      this.versionCheckpointCas = checkpoint === null ? null : snapshot.status.cas;
      this.versionForkDetail = checkpoint === null ? '此版本没有关联的完整工作区检查点，不能据此创建分支。仍可恢复本章正文。'
        : '另开分支使用此版本关联检查点的完整正文、资料、讨论和分支偏好；当前分支保留。';
    } catch (error) {
      if (this.pageAlive && this.versionPreview?.id === version.id && token === this.versionCheckpointToken) {
        this.errorMsg = `无法读取关联检查点：${String(error)}`;
        this.versionForkDetail = '关联检查点读取失败，不能创建分支。';
      }
    }
  }

  private confirmVersionFork(): void {
    const version: NovelChapterVersion | null = this.versionPreview;
    const checkpoint: WorkspaceCommit | null = this.versionCheckpoint;
    const cas: NovelWorkspaceCas | null = this.versionCheckpointCas;
    const name: string = this.versionForkName.trim();
    if (this.busy || version === null || checkpoint === null || cas === null || name.length === 0) return;
    AlertDialog.show({
      title: '从历史版本创建分支？',
      message: `创建“${name}”并切换到新分支。新分支使用“${version.title}”关联检查点的完整正文、资料、讨论和分支偏好，不只是替换这一章；当前分支保持原样。`,
      primaryButton: { value: '继续预览', action: (): void => {} },
      secondaryButton: { value: '创建分支', action: (): void => {
        if (this.pageAlive && this.versionsOpen && this.versionPreview?.id === version.id) this.forkVersion(version.id, checkpoint.commitId, name, cas);
      } },
    });
  }

  private async forkVersion(versionId: string, head: string, name: string, cas: NovelWorkspaceCas): Promise<void> {
    if (this.busy || !this.pageAlive || this.versionPreview?.id !== versionId) return;
    this.busy = true;
    this.versionForking = true;
    try {
      const checkpoint: WorkspaceCommit | null = await getNovelCreation().chapterVersionCheckpoint(this.projectId, versionId, cas);
      if (checkpoint === null || checkpoint.commitId !== head) throw new Error('历史版本的关联检查点已变化，请重新打开预览');
      await getNovelCreation().forkFromHistory(this.projectId, head, name, cas);
      if (this.pageAlive) router.back();
    } catch (error) { if (this.pageAlive) this.errorMsg = `创建历史分支失败：${String(error)}`; }
    finally { if (this.pageAlive) { this.busy = false; this.versionForking = false; } }
  }

  private confirmRestoreVersion(): void {
    const version: NovelChapterVersion | null = this.versionPreview;
    if (version === null || this.busy) return;
    AlertDialog.show({
      title: '恢复此版本？',
      message: `将「${version.title.length > 0 ? version.title : '未命名章节'}」的历史标题和完整正文恢复为当前稿件，当前稿件会保留为历史版本。`,
      primaryButton: { value: '继续预览', action: (): void => {} },
      secondaryButton: { value: '恢复', action: (): void => {
        if (this.pageAlive && this.versionsOpen && this.versionPreview?.id === version.id) this.restoreVersion(version.id);
      } },
    });
  }

  private async openRevision(kind: string): Promise<void> {
    if (this.busy) return;
    const token: number = ++this.revisionToken;
    this.busy = true;
    this.errorMsg = '';
    try {
      const snapshot: NovelWorkspaceSnapshot = await getNovelCreation().readWorkspaceSnapshot(this.projectId);
      if (!this.pageAlive || token !== this.revisionToken) return;
      const chapter: NovelChapter | undefined = snapshot.project.chapters.find(
        (item: NovelChapter): boolean => item.id === this.chapterId);
      if (chapter === undefined) throw new Error('本章已不存在，请重新打开章节。');
      if (chapter.discarded) throw new Error('请先恢复本章，再开始修订或重新生成。');
      this.project = snapshot.project;
      this.revisionKind = kind;
      this.revisionExpectedCas = snapshot.status.cas;
      this.revisionChapterId = chapter.id;
      this.revisionError = '';
      this.revisionDetail = `当前分支「${snapshot.status.activeBranchName}」 · 第 ${novelChapterOrdinal(chapter,
        snapshot.project.chapters.indexOf(chapter) + 1)} 章「${chapter.title || '未命名章节'}」\n` +
        (kind === 'revise' ? '按要求定向改写现有全文，可明确调整情节或事实。' : '重新设计本章叙事，可按要求调整情节。') +
        '新稿先作为本章替换候选展示，确认收录前保留现有正文；其他章节不会被替换。';
      this.revisionOpen = true;
    } catch (error) { if (this.pageAlive) this.errorMsg = String(error); }
    finally { if (this.pageAlive && token === this.revisionToken) this.busy = false; }
  }

  private async submitRevision(brief: string): Promise<void> {
    if (this.busy || !this.revisionOpen || this.revisionExpectedCas === null) return;
    if (this.revisionKind === 'revise' && brief.trim().length === 0) return;
    const token: number = this.revisionToken;
    const chapterId: string = this.revisionChapterId;
    const cas: NovelWorkspaceCas = this.revisionExpectedCas;
    this.busy = true;
    this.revisionError = '';
    try {
      if (this.revisionKind === 'revise') {
        await getNovelCreation().reviseChapter(this.projectId, chapterId, brief, cas);
      } else {
        await getNovelCreation().regenerateChapter(this.projectId, chapterId, brief, cas);
      }
      if (!this.pageAlive || token !== this.revisionToken) return;
      this.revisionOpen = false;
      promptAction.showToast({ message: '已开始生成本章替换候选，进度与收录见创作页', duration: 1800 });
      router.back();
    } catch (error) { if (this.pageAlive && token === this.revisionToken) this.revisionError = String(error); }
    finally { if (this.pageAlive && token === this.revisionToken) this.busy = false; }
  }

  private polishChapters(): NovelPolishCreateSheetChapter[] {
    const chapter: NovelChapter | null = this.chapter();
    if (chapter === null) return [];
    return [{ id: chapter.id, ordinal: novelChapterOrdinal(chapter, this.chapterIndex() + 1), title: chapter.title, discarded: chapter.discarded }];
  }

  private async openPolish(): Promise<void> {
    if (this.busy || this.chapter() === null) return;
    this.errorMsg = '';
    this.polishModelReady = false;
    this.polishStartError = '';
    this.polishModelError = '正在检查模型配置…';
    this.polishOpen = true;
    try {
      await getNovelCreation().validateGhostwriteModels(this.projectId);
      if (this.pageAlive) this.polishModelReady = true;
    } catch (error) {
      if (this.pageAlive) this.polishModelError = String(error);
    }
  }

  private async startSinglePolish(options: PolishContextOptions): Promise<void> {
    if (this.busy || !this.polishModelReady) return;
    this.busy = true;
    this.polishStartError = '';
    try {
      await getNovelCreation().startChapterPolish(this.projectId, this.chapterId, options);
      promptAction.showToast({ message: '已创建整章润色任务，进度见创作页', duration: 1800 });
      router.back();
    } catch (error) {
      if (this.pageAlive) this.polishStartError = String(error);
    } finally { this.busy = false; }
  }

  private editDirty(): boolean {
    return this.editTitle !== this.editOriginalTitle || this.editContent !== this.editOriginalContent;
  }

  private async openEdit(showFind: boolean = false): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    this.errorMsg = '';
    try {
      const snapshot: NovelWorkspaceSnapshot = await getNovelCreation().readWorkspaceSnapshot(this.projectId);
      if (!this.pageAlive) return;
      const chapter: NovelChapter | undefined = snapshot.project.chapters.find(
        (item: NovelChapter): boolean => item.id === this.chapterId);
      if (chapter === undefined) throw new Error('本章已不存在，请重新打开章节。');
      this.project = snapshot.project;
      this.editTitle = chapter.title;
      this.editContent = chapter.content;
      this.editOriginalTitle = chapter.title;
      this.editOriginalContent = chapter.content;
      this.editExpectedCas = snapshot.status.cas;
      this.findQuery = '';
      this.replaceText = '';
      this.findMatchIndex = 0;
      this.replaceNotice = '';
      this.findOpen = showFind;
      this.editOpen = true;
    } catch (error) {
      if (this.pageAlive) this.errorMsg = String(error);
    } finally { this.busy = false; }
  }

  private findMatches(): number[] {
    return findNovelChapterMatches(this.editContent, this.findQuery);
  }

  private currentFindIndex(): number {
    const count: number = this.findMatches().length;
    return count === 0 ? 0 : Math.min(this.findMatchIndex, count - 1);
  }

  private findSummary(): string {
    const count: number = this.findMatches().length;
    return this.findQuery.length === 0 ? '输入要查找的文字，仅在本章正文中匹配。'
      : count === 0 ? '本章没有匹配。' : `第 ${this.currentFindIndex() + 1} / ${count} 处匹配`;
  }

  private findPreview(): string {
    const matches: number[] = this.findMatches();
    if (matches.length === 0) return '';
    const offset: number = matches[this.currentFindIndex()];
    return `${this.editContent.slice(Math.max(0, offset - 35), offset)}【${this.findQuery}】${
      this.editContent.slice(offset + this.findQuery.length, offset + this.findQuery.length + 35)}`;
  }

  private moveFindMatch(step: number): void {
    const count: number = this.findMatches().length;
    if (count > 0) this.findMatchIndex = (this.currentFindIndex() + step + count) % count;
  }

  private async replaceMatches(all: boolean): Promise<void> {
    if (this.busy) return;
    this.findQueryController.stopEditing();
    this.replaceTextController.stopEditing();
    this.editContentController.stopEditing();
    await new Promise<void>((resolve: () => void): void => { setTimeout(resolve, 0); });
    if (!this.pageAlive || !this.editOpen || this.busy) return;
    const count: number = this.findMatches().length;
    if (count === 0) return;
    this.editContent = replaceNovelChapterMatch(this.editContent, this.findQuery, this.replaceText,
      all ? null : this.currentFindIndex());
    this.findMatchIndex = 0;
    this.replaceNotice = `已在编辑稿中替换 ${all ? count : 1} 处，点击保存才更新正文。`;
  }

  private confirmDiscardEdit(): void {
    if (this.busy) return;
    this.editTitleController.stopEditing();
    this.editContentController.stopEditing();
    setTimeout((): void => { this.confirmDiscardCommittedEdit(); }, 0);
  }

  private confirmDiscardCommittedEdit(): void {
    if (!this.pageAlive || !this.editOpen || this.busy) return;
    if (!this.editDirty()) {
      this.editOpen = false;
      return;
    }
    AlertDialog.show({
      title: '放弃编辑？',
      message: '未保存的章节内容将丢失。',
      primaryButton: { value: '继续编辑', action: (): void => {} },
      secondaryButton: { value: '放弃', fontColor: ERROR, action: (): void => { this.editOpen = false; } },
    });
  }

  private async saveEdit(): Promise<void> {
    if (this.busy) return;
    this.editTitleController.stopEditing();
    this.editContentController.stopEditing();
    await new Promise<void>((resolve: () => void): void => { setTimeout(resolve, 0); });
    if (!this.pageAlive || !this.editOpen || this.busy || this.editExpectedCas === null) return;
    this.busy = true;
    try {
      await getNovelCreation().saveChapter(this.projectId, this.chapterId, this.editTitle, this.editContent, this.editExpectedCas);
      this.editOpen = false;
      this.notice = '已保存';
      this.evidenceQuote = '';
      await this.reload();
    } catch (e) {
      if (this.pageAlive) this.errorMsg = e instanceof Error ? e.message : String(e);
    } finally {
      this.busy = false;
    }
  }

  private async toggleDiscarded(): Promise<void> {
    const c: NovelChapter | null = this.chapter();
    if (c === null || this.busy) return;
    this.busy = true;
    try {
      await getNovelCreation().setChapterDiscarded(this.projectId, c.id, !c.discarded);
      this.notice = c.discarded ? '已恢复本章' : '已废弃本章';
      await this.reload();
    } catch (e) {
      if (this.pageAlive) this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }

  private confirmDelete(): void {
    AlertDialog.show({
      title: '删除本章？',
      message: '删除不可恢复；如果只是想让它不参与剧情，请用「废弃本章」。',
      primaryButton: { value: '取消', action: (): void => {} },
      secondaryButton: {
        value: '删除',
        fontColor: ERROR,
        action: (): void => { this.deleteChapter(); },
      },
    });
  }

  private async deleteChapter(): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    try {
      await getNovelCreation().deleteChapter(this.projectId, this.chapterId);
      router.back();
    } catch (e) {
      if (this.pageAlive) this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }

  private async restoreVersion(versionId: string): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    try {
      await getNovelCreation().restoreChapterVersion(this.projectId, versionId);
      this.versionPreview = null;
      this.versionsOpen = false;
      this.notice = '已恢复该版本为当前正文';
      this.evidenceQuote = '';
      await this.reload();
    } catch (e) {
      if (this.pageAlive) this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }

  // ⋯菜单行(含阻塞原因;iOS 禁用项标题附理由)
  @Builder
  private MenuRow(label: string, reason: string, danger: boolean, isLast: boolean,
    action: () => void): void {
    Column() {
      Row() {
        Text(reason.length > 0 ? `${label}（${reason}）` : label)
          .fontSize(15).fontFamily(FONT_SANS_MEDIUM)
          .fontColor(reason.length > 0 ? INK4 : (danger ? ERROR : INK))
      }
      .width('100%').constraintSize({ minHeight: 48 })
      .padding({ left: 18, right: 18 })
      .enabled(reason.length === 0 && !this.busy)
      .onClick((): void => {
        this.menuOpen = false;
        action();
      })
      if (!isLast) {
        Row() {
          Row().width('100%').height(1).backgroundColor(LINE)
        }.width('100%').padding({ left: 18, right: 18 })
      }
    }.width('100%')
  }

  @Builder
  private MenuSheet(): void {
    Column() {
      Blank().onClick((): void => { if (!this.busy) this.menuOpen = false; })
      Column() {
        this.MenuRow('版本历史', '', false, false, (): void => {
          this.errorMsg = ''; this.versionPreview = null; this.versionsOpen = true;
        })
        this.MenuRow('编辑本章', '', false, false, (): void => { this.openEdit(); })
        this.MenuRow('查找和替换', '', false, false, (): void => { this.openEdit(true); })
        this.MenuRow('整章润色', this.busy ? '任务进行中' : '', false, false,
          (): void => { this.openPolish(); })
        this.MenuRow('按要求修订本章', this.busy ? '任务进行中' : '', false, false,
          (): void => { this.openRevision('revise'); })
        this.MenuRow('整章重新生成', this.busy ? '任务进行中' : '', false, false,
          (): void => { this.openRevision('regenerate'); })
        this.MenuRow(this.chapter()?.discarded ? '恢复本章' : '废弃本章', '', false, false,
          (): void => { this.toggleDiscarded(); })
        this.MenuRow('删除本章', '', true, true, (): void => { this.confirmDelete(); })
      }
      .width('100%')
      .backgroundColor(SURFACE)
      .borderRadius({ topLeft: 20, topRight: 20 })
      .padding({ top: 10, bottom: 16 + Math.max(this.navBarHeight, 24) })
      .onClick((): void => {})
      .transition(TransitionEffect.translate({ y: 160 })
        .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
    }
    .width('100%').height('100%').backgroundColor(OVERLAY)
    .justifyContent(FlexAlign.End)
    .transition(TransitionEffect.OPACITY.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
  }

  @Builder
  private VersionPreviewSheet(version: NovelChapterVersion): void {
    NovelChapterVersionPreview({
      title: version.title, content: version.content,
      metadata: `${this.versionKindLabel(version.kind)} · ${this.versionTime(version.createdAt)} · ${charCountOf(version.content)} 字`,
      busy: this.busy, errorMessage: this.errorMsg, bottomPadding: Math.max(this.navBarHeight, 24),
      forkName: this.versionForkName, forkAvailable: this.versionCheckpoint !== null,
      forkDetail: this.versionForkDetail, busyLabel: this.versionForking ? '创建分支中…' : '恢复中…',
      onForkName: (name: string): void => { this.versionForkName = name; },
      onFork: (): void => { this.confirmVersionFork(); },
      onBack: (): void => {
        if (!this.busy) { this.versionPreview = null; this.errorMsg = ''; }
      },
      onClose: (): void => { this.closeVersions(); },
      onRestore: (): void => { this.confirmRestoreVersion(); },
    })
  }

  @Builder
  private VersionsSheet(): void {
    Column() {
      Blank().onClick((): void => { this.closeVersions(); })
      Column() {
        Row() {
          Text('版本历史').fontSize(17).fontWeight(FontWeight.Bold).fontFamily(FONT_SANS_MEDIUM)
            .fontColor(INK).layoutWeight(1)
          Text('关闭').fontSize(13).fontColor(this.busy ? INK4 : INK3)
            .width(44).height(44).textAlign(TextAlign.Center).enabled(!this.busy)
            .onClick((): void => { this.closeVersions(); })
        }.width('100%').padding({ left: 18, right: 18, top: 8, bottom: 8 })
        Scroll() {
          Column() {
            if (this.chapterVersions().length === 0) {
              Text('暂无历史版本').fontSize(13).fontColor(INK3)
                .width('100%').padding({ left: 18, right: 18, top: 12, bottom: 20 })
            }
            ForEach(this.chapterVersions(), (v: NovelChapterVersion, idx: number): void => {
              Row() {
                Column({ space: 2 }) {
                  Text(v.title.length > 0 ? v.title : '未命名章节').fontSize(14).fontColor(INK)
                    .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
                  Text(this.versionKindLabel(v.kind)).fontSize(14).fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
                  Text(this.versionTime(v.createdAt)).fontSize(11).fontColor(INK3)
                }.alignItems(HorizontalAlign.Start).layoutWeight(1)
                Text('预览').fontSize(13).fontFamily(FONT_SANS_MEDIUM)
                  .fontColor(this.busy ? INK4 : ACCENT)
                  .constraintSize({ minWidth: 44, minHeight: 44 }).textAlign(TextAlign.End)
                  .enabled(!this.busy)
                  .onClick((): void => { this.previewVersion(v); })
              }
              .width('100%').padding({ left: 18, right: 18, top: 10, bottom: 10 })
              if (idx < this.chapterVersions().length - 1) {
                Row() {
                  Row().width('100%').height(1).backgroundColor(LINE)
                }.width('100%').padding({ left: 18, right: 18 })
              }
            }, (v: NovelChapterVersion): string => v.id)
          }.width('100%')
        }
        .width('100%').layoutWeight(1).align(Alignment.TopStart).scrollBar(BarState.Auto)
      }
      .width('100%').height('60%')
      .backgroundColor(SURFACE).borderRadius({ topLeft: 20, topRight: 20 })
      .padding({ bottom: Math.max(this.navBarHeight, 24) })
      .onClick((): void => {})
      .transition(TransitionEffect.translate({ y: 160 })
        .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
    }
    .width('100%').height('100%').backgroundColor(OVERLAY)
    .justifyContent(FlexAlign.End)
    .transition(TransitionEffect.OPACITY.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
  }

  @Builder
  private FindReplacePanel(): void {
    Column() {
      TextInput({ text: this.findQuery, placeholder: '查找文字（区分大小写，按原文匹配）', controller: this.findQueryController })
        .fontSize(13).fontColor(INK).placeholderColor(INK4).backgroundColor(SURFACE2).height(44)
        .enabled(!this.busy).onChange((value: string): void => {
          this.findQuery = value; this.findMatchIndex = 0; this.replaceNotice = '';
        })
      TextInput({ text: this.replaceText, placeholder: '替换为（留空则删除匹配文字）', controller: this.replaceTextController })
        .fontSize(13).fontColor(INK).placeholderColor(INK4).backgroundColor(SURFACE2).height(44)
        .margin({ top: 6 }).enabled(!this.busy)
        .onChange((value: string): void => { this.replaceText = value; })
      Row() {
        Text(this.findSummary()).fontSize(11).fontColor(INK3).layoutWeight(1)
        Text('上一处').fontSize(12).fontColor(ACCENT).height(44).margin({ right: 14 })
          .enabled(!this.busy && this.findMatches().length > 0)
          .onClick((): void => { this.moveFindMatch(-1); })
        Text('下一处').fontSize(12).fontColor(ACCENT).height(44)
          .enabled(!this.busy && this.findMatches().length > 0)
          .onClick((): void => { this.moveFindMatch(1); })
      }.width('100%')
      if (this.findMatches().length > 0) {
        Text(this.findPreview()).fontSize(12).fontColor(INK).lineHeight(18).maxLines(2).width('100%')
        Row() {
          Text('替换当前').fontSize(12).fontColor(ACCENT).height(44).layoutWeight(1)
            .enabled(!this.busy).onClick((): void => { this.replaceMatches(false); })
          Text('替换本章全部').fontSize(12).fontColor(ACCENT).height(44)
            .enabled(!this.busy).onClick((): void => { this.replaceMatches(true); })
        }.width('100%')
      }
      if (this.replaceNotice.length > 0) {
        Text(this.replaceNotice).fontSize(11).fontColor(INK3).lineHeight(16).width('100%')
      }
    }.width('100%').padding({ bottom: 10 })
  }

  @Builder
  private EditorBody(): void {
        if (this.findOpen) {
          this.FindReplacePanel()
        }
        TextInput({ text: this.editTitle, placeholder: '章节标题', controller: this.editTitleController })
          .height(44).fontSize(14).fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(10).padding({ left: 14, right: 14 })
          .enabled(!this.busy)
          .onChange((v: string): void => { this.editTitle = v; })
        if (this.findOpen) {
        TextArea({ text: this.editContent, placeholder: '章节正文', controller: this.editContentController })
          .width('100%').height(150).fontSize(14).fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(12).padding(12).margin({ top: 12 })
          .enabled(!this.busy)
          .onChange((v: string): void => { this.editContent = v; })
        } else {
        TextArea({ text: this.editContent, placeholder: '章节正文', controller: this.editContentController })
          .width('100%').layoutWeight(1).fontSize(14).fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(12).padding(12).margin({ top: 12 })
          .enabled(!this.busy)
          .onChange((v: string): void => { this.editContent = v; })
        }
        Text(`${charCountOf(this.editContent)} 字`).fontSize(11).fontColor(INK3)
          .width('100%').margin({ top: 6 })
        if (this.errorMsg.length > 0) {
          Scroll() {
            Text(this.errorMsg).fontSize(12).fontColor(ERROR).width('100%').wordBreak(WordBreak.BREAK_ALL)
          }
          .width('100%').constraintSize({ maxHeight: 54 }).align(Alignment.TopStart).margin({ top: 8 })
        }
  }

  @Builder
  private EditSheet(): void {
    Column() {
      Blank().onClick((): void => { this.confirmDiscardEdit(); })
      Column() {
        Row() {
          Text('编辑本章').fontSize(17).fontWeight(FontWeight.Bold).fontFamily(FONT_SANS_MEDIUM)
            .fontColor(INK).layoutWeight(1)
          Text(this.findOpen ? '收起查找' : '查找和替换').fontSize(12).fontColor(ACCENT).height(44)
            .onClick((): void => { if (!this.busy) this.findOpen = !this.findOpen; })
        }.width('100%').margin({ bottom: 8 })
        if (this.findOpen) {
          Scroll() {
            Column() { this.EditorBody(); }.width('100%').alignItems(HorizontalAlign.Start)
          }.width('100%').layoutWeight(1).align(Alignment.TopStart)
        } else {
          Column() { this.EditorBody(); }.width('100%').layoutWeight(1)
        }
        Row() {
          Text('取消').fontSize(14).fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
            .layoutWeight(1).textAlign(TextAlign.Center).constraintSize({ minHeight: 44 })
            .enabled(!this.busy)
            .onClick((): void => { this.confirmDiscardEdit(); })
          Text(this.busy ? '保存中…' : '保存').fontSize(14).fontWeight(FontWeight.Medium)
            .fontFamily(FONT_SANS_MEDIUM).fontColor(this.busy ? INK4 : ACCENT)
            .layoutWeight(1).textAlign(TextAlign.Center).constraintSize({ minHeight: 44 })
            .enabled(!this.busy)
            .onClick((): void => { this.saveEdit(); })
        }.width('100%').height(44).margin({ top: 8 })
      }
      .width('100%').height('85%')
      .padding({ left: 18, right: 18, top: 16, bottom: 14 + Math.max(this.navBarHeight, 24) })
      .backgroundColor(SURFACE).borderRadius({ topLeft: 20, topRight: 20 })
      .onClick((): void => {})
      .transition(TransitionEffect.translate({ y: 160 })
        .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
    }
    .width('100%').height('100%').backgroundColor(OVERLAY)
    .justifyContent(FlexAlign.End)
    .transition(TransitionEffect.OPACITY.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
  }

  // 底部上一章/下一章胶囊(iOS 阅读器底部导航)
  @Builder
  private PrevNextBar(): void {
    Row() {
      Row() {
        Icon({ name: 'chevron_left', iconSize: 15, color: this.chapterIndex() > 0 ? INK : INK4 })
        Text('上一章').fontSize(13).fontFamily(FONT_SANS_MEDIUM)
          .fontColor(this.chapterIndex() > 0 ? INK : INK4).margin({ left: 4 })
      }
      .alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 44 })
      .padding({ left: 16, right: 16 })
      .backgroundColor(SURFACE).border({ width: 0.5, color: LINE }).borderRadius(22)
      .opacity(this.chapterIndex() > 0 ? 1 : 0.45)
      .enabled(!this.busy && this.chapterIndex() > 0)
      .onClick((): void => { this.jumpTo(this.chapterIndex() - 1); })
      Blank()
      Row() {
        Text('下一章').fontSize(13).fontFamily(FONT_SANS_MEDIUM)
          .fontColor(this.hasNext() ? INK : INK4).margin({ right: 4 })
        Icon({ name: 'chevron_right', iconSize: 15, color: this.hasNext() ? INK : INK4 })
      }
      .alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 44 })
      .padding({ left: 16, right: 16 })
      .backgroundColor(SURFACE).border({ width: 0.5, color: LINE }).borderRadius(22)
      .opacity(this.hasNext() ? 1 : 0.45)
      .enabled(!this.busy && this.hasNext())
      .onClick((): void => { this.jumpTo(this.chapterIndex() + 1); })
    }
    .width('100%')
    .padding({ left: 20, right: 20, top: 8, bottom: 10 + Math.max(this.navBarHeight, 24) })
    .backgroundColor(BG)
  }

  private hasNext(): boolean {
    const p: NovelProject | null = this.project;
    const idx: number = this.chapterIndex();
    return p !== null && idx >= 0 && idx < p.chapters.length - 1;
  }

  @Builder
  private NovelReaderTitleBar(): void {
    Row({ space: 12 }) {
      Row() {
        Icon({ name: 'back', iconSize: 18, color: INK })
      }.width(44).height(44).borderRadius(22).backgroundColor(SURFACE)
        .border({ width: 0.5, color: LINE }).justifyContent(FlexAlign.Center)
        .onClick((): void => { if (!this.onBackPress()) router.back(); })
        .accessibilityText('返回')
      Column({ space: 2 }) {
        Text(this.chapterIndex() >= 0
          ? `第 ${this.chapterOrdinal()} 章 · ${charCountOf(this.chapter()?.content ?? '')} 字` : '')
          .fontSize(12).fontColor(INK3).fontFamily(FONT_SANS_MEDIUM).maxLines(1)
        Text((this.chapter()?.title ?? '').length > 0 ? this.chapter()?.title ?? '' : '未命名章节')
          .fontSize(17).fontWeight(600).fontColor(INK).fontFamily(FONT_SANS_SEMIBOLD)
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
      }.layoutWeight(1).alignItems(HorizontalAlign.Start)
      Row() {
        Text('⋯').fontSize(24).fontWeight(600).fontColor(INK3)
      }.width(44).height(44).borderRadius(22).backgroundColor(SURFACE)
        .border({ width: 0.5, color: LINE }).justifyContent(FlexAlign.Center)
        .enabled(!this.busy).onClick((): void => { if (!this.busy) this.menuOpen = true; })
        .accessibilityText('章节操作')
    }.width('100%').padding({ left: 16, right: 16, top: this.statusBarHeight + 10, bottom: 10 })
      .backgroundColor(BG)
  }

  build(): void {
    Stack() {
      // 主题纪元重挂载包括正文与浮层，确保全量重读颜色令牌。
      ForEach([this.themeEpoch], (_epoch: number): void => {
        Stack() {
          Column() {
            if (getProductKind() === 'novel') {
              this.NovelReaderTitleBar()
            } else {
              CenteredTitleBar({
                title: (this.chapter()?.title ?? '').length > 0 ? this.chapter()?.title ?? '' : '未命名章节',
                subtitle: this.chapterIndex() >= 0
                  ? `第 ${this.chapterOrdinal()} 章 · ${charCountOf(this.chapter()?.content ?? '')} 字` : '',
                actionIcon: 'more',
                onAction: (): void => { if (!this.busy) this.menuOpen = true; },
                onBack: (): void => { if (!this.onBackPress()) router.back(); },
              })
            }
            if (this.errorMsg.length > 0 && !this.editOpen && this.versionPreview === null) {
              Text(this.errorMsg).fontSize(12).fontColor(ERROR).lineHeight(18)
                .width('100%').padding({ left: 20, right: 20, bottom: 8 })
            }
            if (this.evidenceQuote.length > 0) {
              Row() {
                Text(this.evidenceFocused ? `证据附近正文 · 第 ${this.evidenceStart + 1}–${this.evidenceEnd} 字符` : '正在阅读全文')
                  .fontSize(12).fontColor(INK3).layoutWeight(1)
                Text(this.evidenceFocused ? '阅读全文' : '返回证据').fontSize(13).fontColor(ACCENT)
                  .constraintSize({ minWidth: 72, minHeight: 44 }).textAlign(TextAlign.End)
                  .onClick((): void => { this.toggleEvidenceFocus(); })
              }.width('100%').padding({ left: 20, right: 20, bottom: 8 })
            }
            if (this.notice.length > 0) {
              Text(this.notice).fontSize(12).fontColor(INK3).lineHeight(18)
                .width('100%').padding({ left: 20, right: 20, bottom: 8 })
            }
            if (!this.loaded) {
              Column() {
                LoadingProgress().width(36).height(36).color(ACCENT)
              }.width('100%').layoutWeight(1).justifyContent(FlexAlign.Center)
            } else if (this.chapter() === null) {
              Column() {
                Text('章节不存在或已删除。').fontSize(15).fontColor(INK3)
              }.width('100%').layoutWeight(1).justifyContent(FlexAlign.Center)
            } else {
              if (this.chapter()?.discarded === true) {
                Text('已废弃 · 不参与剧情连贯与导出').fontSize(12).fontColor(WARNING_FG)
                  .width('100%').padding({ left: 20, right: 20, bottom: 8 })
              }
              Scroll(this.readerScroller) {
                Column() {
                  // 章节切换重挂载(与主题纪元同款):chapterId 变 → 内容销毁重建,
                  // 进场透明+轻位移,换章不生硬跳变
                  ForEach([`${this.chapterId}:${this.evidenceFocused}`], (id: string): void => {
                    Column() {
                      MarkdownText({
                        content: this.readerContent(),
                        fontColor: INK, readingMode: true, serifForReading: false,
                        enableLatexRendering: false,
                        codeBlockAutoCollapse: true,
                      })
                    }
                    .width('100%')
                    .alignItems(HorizontalAlign.Start)
                    .transition(TransitionEffect.OPACITY
                      .combine(TransitionEffect.translate({ y: 8 }))
                      .animation({ duration: MOTION_ENTER, curve: Curve.EaseOut }))
                  }, (id: string): string => id)
                }
                .width('100%').padding({ left: 20, right: 20, top: 8, bottom: 24 })
                .alignItems(HorizontalAlign.Start)
              }
              .width('100%').layoutWeight(1).align(Alignment.TopStart).scrollBar(BarState.Auto)
              this.PrevNextBar()
            }
          }
          .width('100%').height('100%').backgroundColor(BG)
          .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])

          if (this.menuOpen) {
            this.MenuSheet()
          }
          if (this.versionsOpen) {
            if (this.versionPreview === null) {
              this.VersionsSheet()
            } else {
              this.VersionPreviewSheet(this.versionPreview)
            }
          }
          if (this.editOpen) {
            this.EditSheet()
          }
          if (this.revisionOpen) {
            NovelRevisionBriefSheet({
              draftKey: `${this.revisionToken}`, closeRequest: this.revisionCloseRequest,
              sheetTitle: this.revisionKind === 'revise' ? '按要求修订本章' : '整章重新生成',
              detail: this.revisionDetail, placeholder: this.revisionKind === 'revise'
                ? '例如：保留情节，减少解释，让人物对话更自然' : '可填写新的情节或叙事要求，也可留空重新设计',
              confirmLabel: '生成替换候选', allowEmpty: this.revisionKind !== 'revise',
              busy: this.busy, errorMessage: this.revisionError, bottomPadding: Math.max(this.navBarHeight, 12),
              onConfirm: (brief: string): void => { this.submitRevision(brief); },
              onCancel: (): void => { if (!this.busy) { this.revisionOpen = false; this.revisionToken++; } },
            })
          }
          if (this.polishOpen) {
            NovelPolishCreateSheet({
              visible: true, singleChapter: true, chapters: this.polishChapters(),
              modelReady: this.polishModelReady, modelDisabledReason: this.polishModelError,
              errorMessage: this.polishStartError, busy: this.busy, bottomPadding: Math.max(this.navBarHeight, 12),
              onStart: (_start: number, _end: number, options: PolishContextOptions, _ordinals: number[]): void => { this.startSinglePolish(options); },
              onClose: (): void => { if (!this.busy) this.polishOpen = false; },
            })
          }
        }
        .width('100%').height('100%')
      }, (_epoch: number): string => `${_epoch}`)
    }.width('100%').height('100%')
  }
}
