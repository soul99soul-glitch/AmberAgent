import { router, promptAction } from '@kit.ArkUI';
import type { UIContext } from '@kit.ArkUI';
import { pasteboard } from '@kit.BasicServicesKit';
import { NativeTimelineFollow } from '../../components/NativeTimelineFollow.ets';
import { discussionArchiveAtMessage, discussionMessageVisible } from '../../components/NovelArchivePresentation.ets';
import { orphanSettingProposals, settingProposalsForMessage } from '../NovelSettingProposalProjection.ets';
import type { NovelCreation } from '@amber/deepread-domain';
import { NovelProject, NovelMessage, makeNovelMessage, NovelChapter, NovelInjectionOverrides, novelComposerRunKind, NovelChatMode, NovelGenerationGranularity, NovelRunKind, NovelRun, NovelActiveRun, NovelRunEvent, NovelOrdinaryRun, NovelOrdinaryRunView, latestAssistantText, ordinaryRunMessages, ordinaryRunHistoryCount, NovelSettingProposal, NovelDiscussionArchive, NovelWorkspaceStatus, StreamTransportState, UIMessage, makeUIMessage, toText, UIMessagePart, novelMessageUi, novelMessageText, NovelToolContinuationVerdict, eligibleNovelDiscussion, NovelDiscussionArchiveDraft, DurableGhostwriteJob, NovelGhostwriteReport, formatNovelQuickStartOverview } from '@amber/deepread-domain';
import { shouldScrollOnStreamUpdate, transitionOnGenerateEnd, transitionOnJumpToBottom, transitionOnStreamUpdate, transitionOnUserScroll } from '@amber/chat-domain';
import type { FollowMode, ToolApprovalPartLocator } from '@amber/chat-domain';
import { canRestoreFailedInput, NovelComposerDraftStore } from '../../novel/NovelComposerState';
import type { NovelComposeMode, NovelComposerDraft } from '../../novel/NovelComposerState';
interface ExportMenuResult {
  index: number;
}
interface WorkspaceReturnParams { novelContextOverrides?: string; }
interface NovelContextOverrideReturn {
  projectId: string;
  branchId: string;
  forceIncludeMaterialIds: string[];
  forceExcludeMaterialIds: string[];
  receiptToken: string;
}
export interface NovelDiscussionPorts {
  creation: NovelCreation;
  composerDraftStore: NovelComposerDraftStore;
  projectId: () => string;
  currentProject: () => NovelProject | null;
  currentStatus: () => NovelWorkspaceStatus | null;
  isBusy: () => boolean;
  setBusy: (value: boolean) => void;
  error: (value: string) => void;
  operationError: (action: string, error: Object) => void;
  hasBlockingNovelRun: () => boolean;
  jobLocksWorkspace: () => boolean;
  currentGhostwriteJob: () => DurableGhostwriteJob | null;
  currentGhostwriteReport: () => NovelGhostwriteReport | null;
  reload: () => Promise<void>;
  reloadToken: () => number;
  selectedTab: () => number;
  backgrounded: () => boolean;
  closeControl: () => void;
  uiContext: () => UIContext;
}
@Observed
export class NovelDiscussionSession {
  draft: string = '';  // 聊天
  composeMode: NovelComposeMode = 'whole_chapter';
  sendStarting: boolean = false;
  composerRestoring: boolean = false;
  failedInput: string = '';
  failedMessages: UIMessage[] = [];
  ordinaryRecovery: NovelOrdinaryRunView | null = null;
  activeMessages: UIMessage[] = [];
  activeTextDeltasLive: boolean = false;
  activeTransport: StreamTransportState = 'unavailable';
  activeFrameSeen: boolean = false;
  followMode: FollowMode = 'idle';
  atBottomApprox: boolean = true;
  jumpButtonVisible: boolean = false;
  generationStatus: string = '';
  activeRunId: string = '';
  activeRunCreatedAt: number = 0;
  activeHistoryCount: number = 0;
  archiveOpen: boolean = false;
  archiveSummary: string = '';
  archiveDecisions: string[] = [];
  archiveSourceCount: number = 0;
  archiveSourceRange: string = '';
  archiveDraftKey: string = '';
  archiveError: string = '';
  expandedArchiveIds: string[] = [];
  archiveDraft: NovelDiscussionArchiveDraft | null = null;
  pendingUserText: string = '';
  activeQuickStart: boolean = false;
  activeRunKind: NovelRunKind | null = null;
  completedPreview: UIMessage[] = [];
  failedQuickStart: boolean = false;
  currentUnsub: (() => void) | null = null;
  initialScrollArmed: boolean = false;
  novelScrollToken: number = 0;
  nativeFollow: NativeTimelineFollow | null = null;
  novelViewportHeight: number = 0;
  novelLastVisibleIndex: number = -1;
  novelUserScrolling: boolean = false;
  userTouchActive: boolean = false;
  recoveryAttemptedToolCalls: Set<string> = new Set();
  composerDraftOwner: string = '';
  composerRevision: number = 0;
  composerSaveToken: number = 0;
  failedInputMode: NovelComposeMode = 'whole_chapter';
  failedInputRunKind: NovelRunKind | null = null;
  pendingQuickStartSeed: string = '';
  contextOverrides: NovelInjectionOverrides | null = null;
  lastContextReceiptToken: string = '';
  private ports: NovelDiscussionPorts | null = null;
  private pageAlive: boolean = false;
  private studioPageVisible: boolean = false;
  chatScroller: Scroller = new Scroller();
  private get deps(): NovelDiscussionPorts { return this.ports as NovelDiscussionPorts; }
  private get projectId(): string { return this.deps.projectId(); }
  private get project(): NovelProject | null { return this.deps.currentProject(); }
  private get workspaceStatus(): NovelWorkspaceStatus | null { return this.deps.currentStatus(); }
  private get busy(): boolean { return this.deps.isBusy(); }
  private set busy(value: boolean) { this.deps.setBusy(value); }
  private set errorMsg(value: string) { this.deps.error(value); }
  private get composerDraftStore(): NovelComposerDraftStore { return this.deps.composerDraftStore; }
  private get reloadToken(): number { return this.deps.reloadToken(); }
  private get studioBackgrounded(): boolean { return this.deps.backgrounded(); }
  private get tab(): number { return this.deps.selectedTab(); }
  private get ghostwriteJob(): DurableGhostwriteJob | null { return this.deps.currentGhostwriteJob(); }
  private get ghostwriteReport(): NovelGhostwriteReport | null { return this.deps.currentGhostwriteReport(); }
  start(ports: NovelDiscussionPorts): void { this.ports = ports; this.pageAlive = true; this.studioPageVisible = true; }
  show(): void { this.studioPageVisible = true; }
  hide(): void { this.studioPageVisible = false; this.flushComposerDraft(); }
  dispose(): void {
    this.hide();
    this.pageAlive = false;
    this.novelScrollToken++;
    this.nativeFollow?.cancel();
    if (this.currentUnsub !== null) {
      const unsub: () => void = this.currentUnsub;
      this.currentUnsub = null;
      unsub();
    }
  }

  async archiveDiscussion(): Promise<void> {
    const project: NovelProject | null = this.project;
    if (project === null || this.busy || this.deps.hasBlockingNovelRun()) return;
    const messages: NovelMessage[] = eligibleNovelDiscussion(project);
    if (messages.length === 0) {
      promptAction.showToast({ message: '没有可归档的新增讨论内容', duration: 1800 });
      return;
    }
    this.busy = true;
    this.errorMsg = '';
    const branchId: string = this.workspaceStatus?.activeBranchId ?? '';
    try {
      const draft: NovelDiscussionArchiveDraft = await this.deps.creation.prepareDiscussionArchive(
        this.projectId, messages.map((message: NovelMessage): string => message.id));
      if (!this.pageAlive || (this.workspaceStatus?.activeBranchId ?? '') !== branchId) return;
      this.archiveDraft = draft;
      this.archiveSummary = draft.summary;
      this.archiveDecisions = draft.decisions.slice();
      this.archiveSourceCount = draft.sourceMessageIds.length;
      const archivedMessages: NovelMessage[] = messages.filter((message: NovelMessage): boolean =>
        draft.sourceMessageIds.includes(message.id));
      this.archiveSourceRange = `从「${archivedMessages[0].content.substring(0, 24)}」至「${archivedMessages[archivedMessages.length - 1].content.substring(0, 24)}」`;
      this.archiveDraftKey = `${draft.sourceDigest}-${Date.now()}`;
      this.archiveError = '';
      this.archiveOpen = true;
    } catch (e) {
      if (this.pageAlive) this.errorMsg = e instanceof Error ? e.message : String(e);
    } finally {
      this.busy = false;
    }
  }

  async confirmArchive(summary: string, selectedIndexes: number[]): Promise<void> {
    if (this.busy || this.archiveDraft === null) return;
    this.busy = true;
    this.archiveError = '';
    try {
      await this.deps.creation.confirmDiscussionArchive(this.projectId, this.archiveDraft, summary, selectedIndexes);
      this.archiveOpen = false;
      this.archiveDraft = null;
      await this.deps.reload();
      if (this.pageAlive) promptAction.showToast({ message: '讨论已归档，原文保留在创作记录中', duration: 1800 });
    } catch (e) {
      if (this.pageAlive) this.archiveError = e instanceof Error ? e.message : String(e);
    } finally {
      this.busy = false;
    }
  }

  cancelArchive(): void {
    if (this.busy) return;
    this.archiveOpen = false;
    this.archiveDraft = null;
  }

  maybeFireQuickStart(project: NovelProject, activeRun: NovelActiveRun | null): void {
    if (this.pendingQuickStartSeed.length === 0) return;
    if (project.messages.length > 0 || activeRun !== null) {
      this.pendingQuickStartSeed = '';
      return;
    }
    const seed: string = this.pendingQuickStartSeed;
    this.pendingQuickStartSeed = '';
    this.setComposerDraft(seed);
    this.send('quick_start');
  }

  scheduleRecoverableTool(project: NovelProject): void {
    for (let i: number = project.messages.length - 1; i >= 0; i--) {
      const parts: UIMessagePart[] = novelMessageUi(project.messages[i]).parts;
      for (let p: number = parts.length - 1; p >= 0; p--) {
        const part: UIMessagePart = parts[p];
        if (part.type !== 'tool' || part.output.length > 0 ||
          this.recoveryAttemptedToolCalls.has(part.toolCallId)) continue;
        let verdict: NovelToolContinuationVerdict | null = null;
        if (part.approvalState.type === 'approved') verdict = { kind: 'approved' };
        else if (part.approvalState.type === 'answered') {
          verdict = { kind: 'answered', answer: part.approvalState.answer };
        } else if (part.approvalState.type === 'denied') {
          verdict = { kind: 'denied', reason: part.approvalState.reason };
        }
        if (verdict === null) continue;
        this.recoveryAttemptedToolCalls.add(part.toolCallId);
        const toolCallId: string = part.toolCallId;
        setTimeout((): void => {
          if (this.pageAlive && !this.deps.hasBlockingNovelRun()) {
            this.continueNovelTool(toolCallId, verdict as NovelToolContinuationVerdict);
          }
        }, 0);
        return;
      }
    }
  }

  toggleDiscussionArchive(archiveId: string): void {
    if (!this.project?.discussionArchives.some((archive: NovelDiscussionArchive): boolean => archive.id === archiveId)) return;
    this.expandedArchiveIds = this.expandedArchiveIds.includes(archiveId)
      ? this.expandedArchiveIds.filter((id: string): boolean => id !== archiveId)
      : this.expandedArchiveIds.concat(archiveId);
  }

  ordinaryRecoveryRecord(): NovelOrdinaryRun | null {
    const record: NovelOrdinaryRun | null = this.ordinaryRecovery?.record ?? null;
    return record !== null && record.status !== 'completed' && record.status !== 'waiting_user' &&
      record.branchId === this.workspaceStatus?.activeBranchId && !this.ordinaryRecovery?.active ? record : null;
  }

  ordinaryPartialText(record: NovelOrdinaryRun): string {
    if (record.originalRequest.operation.kind === 'tool_continuation') {
      const current: UIMessage | undefined = ordinaryRunMessages(record).slice(ordinaryRunHistoryCount(record))
        .reverse().find((message: UIMessage): boolean => message.role === 'assistant');
      if (current === undefined) return '';
      const before: UIMessage | undefined = record.originalRequest.history.find((message: UIMessage): boolean => message.id === current.id);
      const text: string = latestAssistantText([current]);
      const oldText: string = before === undefined ? '' : latestAssistantText([before]);
      return text.startsWith(oldText) ? text.slice(oldText.length) : text;
    }
    const originalIds: string[] = record.originalRequest.history.map((message: UIMessage): string => message.id);
    return latestAssistantText(record.checkpointMessages.filter((message: UIMessage): boolean => !originalIds.includes(message.id)));
  }

  ordinaryRecoveryDetail(record: NovelOrdinaryRun): string {
    if (record.originalRequest.operation.kind === 'tool_continuation') {
      return `${this.workspaceStatus?.activeBranchName ?? '当前分支'} · 工具续接\n继续已保存的回答与工具结果，不重新发起原讨论。重试会替换本次工具续接的部分内容；需要保留可先复制。`;
    }
    const mode: string = record.runKind === 'regenerate' ? '章节重生成'
      : record.runKind === 'polish' ? '章节润色' : record.runKind === 'quick_start' ? '快速开始'
        : record.mode === 'discuss' ? '讨论' : record.granularity === 'whole_chapter' ? '整章创作' : '片段创作';
    const source: NovelChapter | undefined = this.project?.chapters.find((chapter: NovelChapter): boolean => chapter.id === record.candidate?.sourceChapterId);
    return `${this.workspaceStatus?.activeBranchName ?? '当前分支'} · ${mode}${source === undefined ? '' : ` · ${source.title}`}\n使用保存的原请求、模型与上下文。重试不会将此前失败的部分内容作为新的写作输入。`;
  }

  recoverOrdinaryRun(recordId: string, kind: 'retry' | 'resume'): void {
    const record: NovelOrdinaryRun | null = this.ordinaryRecoveryRecord();
    if (record === null || record.id !== recordId || this.busy || this.deps.hasBlockingNovelRun()) return;
    if (kind === 'retry' ? !this.ordinaryRecovery?.retryAllowed : !this.ordinaryRecovery?.resumeAllowed) return;
    const run: NovelRun = kind === 'retry'
      ? this.deps.creation.retryOrdinaryRun(this.projectId, recordId)
      : this.deps.creation.resumeOrdinaryRun(this.projectId, recordId);
    const historyCount: number = kind === 'resume' || record.originalRequest.operation.kind === 'tool_continuation'
      ? ordinaryRunHistoryCount(record) : this.project?.messages.length ?? 0;
    this.attachNovelRun(run, historyCount, '', Date.now(),
      kind === 'retry' ? '正在按原请求重试…' : '正在继续原任务…', record.runKind === 'quick_start',
      record.runKind ?? (record.mode === 'discuss' ? 'discussion' : 'prose_whole_chapter'));
  }

  copyOrdinaryPartial(recordId: string, text: string): void {
    if (this.ordinaryRecoveryRecord()?.id !== recordId || text.length === 0) return;
    try {
      pasteboard.getSystemPasteboard().setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, text));
      promptAction.showToast({ message: '已复制保存的部分内容', duration: 1400 });
    } catch (error) { this.deps.operationError('复制失败', error); }
  }

  assistantLabel(m: NovelMessage): string {
    if (m.interrupted) return '已停止（内容已保留）';
    if (m.mode === 'discuss') return '讨论';
    return m.granularity === 'continuation' ? '续写' : '整章';
  }

  messageTimestamp(epochMillis: number): string {
    return new Date(epochMillis).toISOString();
  }

  projectMessageUi(message: NovelMessage): UIMessage {
    const overview: string | null = this.project === null ? null : formatNovelQuickStartOverview(this.project, message);
    if (overview === null) return novelMessageUi(message);
    const original: UIMessage = novelMessageUi(message);
    return makeUIMessage(original.role, [{ type: 'text', text: overview, metadata: null }], {
      id: original.id, createdAt: original.createdAt, finishedAt: original.finishedAt, modelId: original.modelId,
    });
  }

  unsavedDisplayMessage(message: UIMessage): UIMessage {
    if (!this.failedQuickStart || message.role !== 'assistant') return message;
    return makeUIMessage('assistant', [{ type: 'text', text: '快速开始未能保存。可复制原始内容后重试。', metadata: null }], {
      id: message.id, createdAt: message.createdAt, finishedAt: message.finishedAt, modelId: message.modelId,
    });
  }

  pendingUserUi(): UIMessage {
    const createdAt: string = this.messageTimestamp(
      this.activeRunCreatedAt > 0 ? this.activeRunCreatedAt : Date.now());
    return makeUIMessage('user', [{ type: 'text', text: this.pendingUserText, metadata: null }], {
      id: `novel-user:${this.activeRunId}`,
      createdAt,
      finishedAt: createdAt,
      modelId: this.project?.modelId ?? null,
    });
  }

  activeStatusUi(): UIMessage {
    const createdAt: string = this.messageTimestamp(
      this.activeRunCreatedAt > 0 ? this.activeRunCreatedAt : Date.now());
    return makeUIMessage('assistant', [{ type: 'text', text: this.generationStatus, metadata: null }], {
      id: `novel-run:${this.activeRunId}`,
      createdAt,
      finishedAt: null,
      modelId: this.project?.modelId ?? null,
    });
  }

  scheduleNovelScrollToBottom(): void {
    const token: number = ++this.novelScrollToken;
    setTimeout((): void => {
      if (!this.pageAlive || token !== this.novelScrollToken || this.userTouchActive) return;
      this.atBottomApprox = true;
      this.jumpButtonVisible = false;
      this.nativeFollow?.cancel();
      this.chatScroller.scrollEdge(Edge.Bottom);
    }, 0);
  }

  novelTimelineItemCount(): number {
    let count: number = 0;
    const project: NovelProject | null = this.project;
    if (project !== null) {
      if (this.ordinaryRecoveryRecord() !== null && this.activeRunId.length === 0) count++;
      if (this.ghostwriteJob !== null) count++;
      if (project.messages.length === 0 && this.activeRunId.length === 0) count++;
      if (this.ghostwriteJob === null && this.ghostwriteReport !== null) count++;
      const messages: NovelMessage[] = this.novelTimelineMessages();
      const expandedArchives: string[] = this.expandedArchiveIds;
      for (const message of messages) {
        if (discussionArchiveAtMessage(project.discussionArchives, message) !== null) count++;
        if (discussionMessageVisible(project.discussionArchives, expandedArchives, message)) count++;
        count += this.settingProposalsAt(message).length;
      }
      count += orphanSettingProposals(project.settingProposals, project.messages).length;
    }
    if (this.pendingUserText.length > 0 && this.activeMessages.length === 0) count++;
    if (this.activeRunId.length === 0) count += this.failedMessages.length + this.completedPreview.length;
    else {
      const activeCount: number = this.activeRunTimelineMessages().length;
      if (this.activeQuickStart) count += 1 + activeCount;
      else if (activeCount === 0) count++;
    }
    return count;
  }

  measuredNovelBottomTarget(): number | null {
    const current: number = this.chatScroller.currentOffset().yOffset;
    if (this.chatScroller.isAtEnd()) return current;
    const lastIndex: number = this.novelTimelineItemCount() - 1;
    if (lastIndex < 0) return current;
    if (this.novelLastVisibleIndex < lastIndex || this.novelViewportHeight <= 0) return null;
    const rect: RectResult = this.chatScroller.getItemRect(lastIndex);
    return current + Math.max(0, rect.y + rect.height - this.novelViewportHeight);
  }

  requestNovelNativeFollow(): void {
    if (this.nativeFollow === null) {
      this.nativeFollow = new NativeTimelineFollow(this.deps.uiContext(), this.chatScroller,
        (): boolean => this.pageAlive && this.studioPageVisible && !this.studioBackgrounded && this.tab === 0 &&
          !this.userTouchActive && shouldScrollOnStreamUpdate(this.followMode, this.atBottomApprox),
        (): number | null => this.measuredNovelBottomTarget(), (): void => {});
    }
    this.nativeFollow.request();
  }

  beginNovelGenerationFollow(): void {
    this.novelUserScrolling = false;
    this.followMode = transitionOnJumpToBottom(true);
    this.atBottomApprox = true;
    this.jumpButtonVisible = false;
    this.scheduleNovelScrollToBottom();
  }

  followNovelStreamingUpdate(): void {
    this.followMode = transitionOnStreamUpdate(this.followMode, this.atBottomApprox);
    this.jumpButtonVisible = this.followMode === 'paused';
    if (shouldScrollOnStreamUpdate(this.followMode, this.atBottomApprox)) {
      this.requestNovelNativeFollow();
    }
  }

  finishNovelGenerationFollow(): void {
    const wasPaused: boolean = this.followMode === 'paused';
    this.followMode = transitionOnGenerateEnd(this.followMode);
    this.jumpButtonVisible = wasPaused;
    if (!wasPaused) this.requestNovelNativeFollow();
  }

  reconcileNovelUserScroll(): void {
    this.novelScrollToken++;
    this.nativeFollow?.cancel();
    let atEnd: boolean = false;
    try {
      atEnd = this.chatScroller.isAtEnd();
    } catch (_error) {
      atEnd = this.atBottomApprox;
    }
    this.atBottomApprox = atEnd;
    this.followMode = transitionOnUserScroll(
      this.followMode, atEnd, this.activeRunId.length > 0);
    this.jumpButtonVisible = !atEnd;
  }

  jumpNovelToBottom(): void {
    this.novelUserScrolling = false;
    this.followMode = transitionOnJumpToBottom(this.activeRunId.length > 0);
    this.scheduleNovelScrollToBottom();
  }

  setComposerDraft(text: string): void {
    if (text === this.draft) return;
    this.draft = text;
    this.composerRevision++;
    this.scheduleComposerSave();
  }

  scheduleComposerSave(): void {
    const token: number = ++this.composerSaveToken;
    setTimeout((): void => {
      if (token === this.composerSaveToken) this.flushComposerDraft();
    }, 400);
  }

  flushComposerDraft(): void {
    this.composerSaveToken++;
    if (this.composerDraftOwner.length === 0 || this.composerRestoring) return;
    const owner: string = this.composerDraftOwner;
    const snapshot: NovelComposerDraft = { text: this.draft, mode: this.composeMode };
    this.composerDraftStore.save(owner, snapshot).catch((error: Object): void => {
      const message: string = `创作草稿保存失败：${String(error)}`;
      if (this.pageAlive && this.composerDraftOwner === owner) this.errorMsg = message;
      promptAction.showToast({ message, duration: 2400 });
    });
  }

  async restoreComposerDraft(owner: string): Promise<void> {
    if (this.composerDraftOwner === owner) return;
    this.flushComposerDraft();
    this.composerDraftOwner = owner;
    this.composerRevision++;
    const revision: number = this.composerRevision;
    this.composerRestoring = true;
    this.draft = '';
    this.failedInput = '';
    this.failedInputRunKind = null;
    this.failedMessages = [];
    try {
      const snapshot: NovelComposerDraft = await this.composerDraftStore.load(owner);
      if (this.pageAlive && this.composerDraftOwner === owner && this.composerRevision === revision) {
        this.draft = snapshot.text;
        this.composeMode = snapshot.mode;
      }
    } catch (error) {
      if (this.pageAlive && this.composerDraftOwner === owner) {
        this.errorMsg = `创作草稿恢复失败：${String(error)}`;
      }
    } finally {
      if (this.composerDraftOwner === owner) this.composerRestoring = false;
    }
  }

  restoreFailedInput(): void {
    if (this.failedInputRunKind === 'quick_start') {
      this.retryQuickStartSeed(this.failedInput);
      return;
    }
    if (this.failedInput.length === 0 || this.deps.hasBlockingNovelRun()) return;
    if (this.draft.length > 0) {
      promptAction.showToast({ message: '请先保留或清空当前输入，再恢复失败的输入。', duration: 1800 });
      return;
    }
    this.composeMode = this.failedInputMode;
    this.setComposerDraft(this.failedInput);
    this.failedInput = '';
    this.flushComposerDraft();
  }

  quickStartSeedFor(message: NovelMessage): string {
    const messages: NovelMessage[] = this.project?.messages ?? [];
    const index: number = messages.findIndex((item: NovelMessage): boolean => item.id === message.id);
    for (let cursor: number = index - 1; cursor >= 0; cursor--) {
      if (messages[cursor].role === 'user') return novelMessageText(messages[cursor]);
    }
    return '';
  }

  canRetryQuickStart(message: NovelMessage): boolean {
    return message.role === 'assistant' && message.runKind === 'quick_start' &&
      !this.project?.settingProposals.some((proposal: NovelSettingProposal): boolean => proposal.sourceMessageId === message.id) &&
      this.quickStartSeedFor(message).trim().length > 0;
  }

  async retryQuickStartSeed(seed: string): Promise<void> {
    if (seed.trim().length === 0 || this.busy || this.deps.hasBlockingNovelRun()) return;
    if (this.draft.length > 0 && this.draft !== seed) {
      promptAction.showToast({ message: '请先保留或清空当前输入，再重试快速开始。', duration: 1800 });
      return;
    }
    this.composeMode = 'discuss';
    this.setComposerDraft(seed);
    this.flushComposerDraft();
    await this.send('quick_start');
  }

  async send(runKindOverride: NovelRunKind | null = null): Promise<void> {
    const originalInput: string = this.draft;
    const text: string = originalInput.trim();
    if (text.length === 0 || this.busy || this.deps.hasBlockingNovelRun() || this.project === null) return;
    const owner: string = this.composerDraftOwner;
    const composeMode: NovelComposeMode = this.composeMode;
    this.sendStarting = true;
    let clearedRevision: number = -1;
    try {
      await this.deps.creation.validateWritingModel(this.projectId);
      if (!this.pageAlive || this.busy || this.activeRunId.length > 0 || this.deps.jobLocksWorkspace() ||
        this.project === null || this.composerDraftOwner !== owner || this.draft !== originalInput) return;
      const mode: NovelChatMode = composeMode === 'discuss' ? 'discuss' : 'write';
      let gran: NovelGenerationGranularity | null = null;
      if (composeMode === 'continue') gran = 'continuation';
      else if (composeMode === 'whole_chapter') gran = 'whole_chapter';
      let runKind: NovelRunKind = novelComposerRunKind(mode, gran);
      if (runKindOverride !== null) runKind = runKindOverride;
      this.setComposerDraft('');
      clearedRevision = this.composerRevision;
      this.flushComposerDraft();
      const run: NovelRun = runKind === 'quick_start'
        ? await this.deps.creation.quickStart(this.projectId, text)
        : this.deps.creation.generate(this.projectId, text, mode, gran, runKind, null, this.contextOverrides ?? undefined);
      if (runKind !== 'quick_start') this.contextOverrides = null;
      if (!this.pageAlive || this.composerDraftOwner !== owner) return;
      this.failedInput = '';
      this.attachNovelRun(
        run, this.project.messages.length, text, Date.now(), '正在连接模型并组织正文…', runKind === 'quick_start', runKind);
    } catch (error) {
      if (this.pageAlive && this.composerDraftOwner === owner) {
        if (clearedRevision >= 0 && canRestoreFailedInput(
          owner, this.composerDraftOwner, clearedRevision, this.composerRevision, this.draft)) {
          this.setComposerDraft(originalInput);
          this.flushComposerDraft();
        }
        if (runKindOverride === 'quick_start') {
          this.failedInput = text;
          this.failedInputRunKind = 'quick_start';
        }
        this.errorMsg = `生成未能启动：${String(error)}`;
      }
    } finally {
      this.sendStarting = false;
    }
  }

  interrupt(): void {
    if (this.activeRunId.length > 0) this.deps.creation.interrupt(this.activeRunId);
  }

  continueNovelTool(toolCallId: string, verdict: NovelToolContinuationVerdict,
    locator?: ToolApprovalPartLocator): void {
    if (this.busy || this.deps.hasBlockingNovelRun()) return;
    // R08:NovelService.continueTool 目前按 id 定位(findTool 取首个匹配),
    //   空 id 会错误命中同 blank 的其它工具、重复 id 也无法区分。UI 侧先用 locator
    //   精确解析出真实 id;无法唯一确定时安全拒绝,绝不按 blank 等价批准/续跑。
    const resolvedId: string = this.resolveNovelToolId(toolCallId, locator);
    if (resolvedId.length === 0) {
      this.errorMsg = '无法唯一定位这次工具调用（缺少或重复的工具 ID），已拒绝本次操作。';
      return;
    }
    const run: NovelRun = this.deps.creation.continueTool(this.projectId, resolvedId, verdict);
    const historyCount: number = (this.project?.messages ?? []).findIndex((message: NovelMessage): boolean =>
      novelMessageUi(message).parts.some((part: UIMessagePart): boolean => part.type === 'tool' && part.toolCallId === resolvedId));
    const sourceMessage: NovelMessage | undefined = this.project?.messages[historyCount];
    this.attachNovelRun(
      run, historyCount, '', Date.now(), '正在继续同一轮任务…', false,
      sourceMessage?.runKind ?? (sourceMessage?.mode === 'discuss' ? 'discussion' : 'prose_whole_chapter'));
  }

  resolveNovelToolId(toolCallId: string, locator?: ToolApprovalPartLocator): string {
    const project: NovelProject | null = this.project;
    if (project === null) return '';
    if (locator !== undefined) {
      for (let i: number = 0; i < project.messages.length; i++) {
        const ui: UIMessage = novelMessageUi(project.messages[i]);
        if (ui.id !== locator.messageId) continue;
        const parts: UIMessagePart[] = ui.parts;
        if (locator.partIndex < 0 || locator.partIndex >= parts.length) return '';
        const target: UIMessagePart = parts[locator.partIndex];
        if (target.type !== 'tool') return '';
        return target.toolCallId.length > 0 ? target.toolCallId : '';
      }
      return '';
    }
    if (toolCallId.length === 0) return '';
    let found: string = '';
    let count: number = 0;
    for (let i: number = 0; i < project.messages.length; i++) {
      const parts: UIMessagePart[] = novelMessageUi(project.messages[i]).parts;
      for (let p: number = 0; p < parts.length; p++) {
        const part: UIMessagePart = parts[p];
        if (part.type !== 'tool' || part.toolCallId !== toolCallId) continue;
        found = part.toolCallId;
        count++;
      }
    }
    return count === 1 ? found : '';
  }

  clearNovelRunUi(): void {
    this.activeRunId = '';
    this.activeQuickStart = false;
    this.activeRunKind = null;
    this.activeRunCreatedAt = 0;
    this.pendingUserText = '';
    this.activeMessages = [];
    this.activeHistoryCount = 0;
    this.activeTextDeltasLive = false;
    this.activeTransport = 'unavailable';
    this.activeFrameSeen = false;
    this.generationStatus = '';
  }

  attachNovelRun(
    run: NovelRun, historyCount: number, pendingUserText: string,
    startedAt: number, statusText: string, quickStart: boolean = false, runKind: NovelRunKind | null = null,
  ): void {
    if (this.currentUnsub !== null) {
      const previous: () => void = this.currentUnsub;
      this.currentUnsub = null;
      previous();
    }
    this.activeRunId = run.id;
    this.activeQuickStart = quickStart;
    this.activeRunCreatedAt = startedAt;
    this.pendingUserText = pendingUserText;
    this.activeMessages = [];
    this.activeHistoryCount = historyCount;
    this.activeTextDeltasLive = false;
    this.activeTransport = 'unavailable';
    this.activeFrameSeen = false;
    this.generationStatus = statusText;
    this.errorMsg = '';
    this.failedMessages = [];
    this.failedQuickStart = false;
    this.completedPreview = [];
    const owner: string = this.composerDraftOwner;
    const inputMode: NovelComposeMode = this.composeMode;
    this.activeRunKind = runKind ?? (quickStart ? 'quick_start' :
      (inputMode === 'discuss' ? 'discussion' : 'prose_whole_chapter'));
    this.beginNovelGenerationFollow();
    let terminalSeen: boolean = false;
    const unsub: () => void = run.subscribe((event: NovelRunEvent): void => {
      if (this.activeRunId !== run.id) return;
      if (event.kind === 'status') {
        if (event.text.length > 0) this.generationStatus = event.text;
      } else if (event.kind === 'snapshot') {
        this.activeMessages = event.messages.slice(this.activeHistoryCount);
        this.activeTextDeltasLive = event.textDeltasLive;
        this.activeTransport = event.transport;
        this.activeFrameSeen = true;
        this.followNovelStreamingUpdate();
      } else if (event.kind === 'completed' || event.kind === 'waiting_user' ||
        event.kind === 'interrupted' || event.kind === 'failed') {
        terminalSeen = true;
        if (event.kind === 'failed' && event.unsavedMessages !== undefined) {
          this.failedMessages = event.unsavedMessages.slice();
          this.failedQuickStart = this.activeQuickStart;
        }
        if (event.kind === 'failed' && this.composerDraftOwner === owner && this.pendingUserText.length > 0) {
          this.failedInput = this.pendingUserText;
          this.failedInputMode = inputMode;
          this.failedInputRunKind = this.activeQuickStart ? 'quick_start' : null;
        }
        if (event.kind === 'failed') this.errorMsg = event.message;
        this.currentUnsub = null;
        void this.settleNovelRunUi(run.id);
      }
    });
    if (!terminalSeen && this.activeRunId === run.id) this.currentUnsub = unsub;
    else unsub();
  }

  async settleNovelRunUi(runId: string): Promise<void> {
    const reloadToken: number = this.reloadToken + 1;
    // Keep the live body until reload atomically publishes the saved transcript.
    await this.deps.reload();
    if (!this.pageAlive || reloadToken !== this.reloadToken ||
      (this.activeRunId.length > 0 && this.activeRunId !== runId)) return;
    if (this.activeRunId === runId) {
      // A read failure must neither hide the last body nor keep an ended run locked.
      if (this.failedMessages.length === 0) {
        this.completedPreview = this.activeRunTimelineMessages().map((message: UIMessage): UIMessage =>
          this.activeRunMessage(message));
      }
      this.clearNovelRunUi();
    }
    this.finishNovelGenerationFollow();
  }

  attachChapterRun(run: NovelRun, statusText: string): void {
    this.attachNovelRun(
      run, this.project?.messages.length ?? 0, '', Date.now(), statusText, false, 'regenerate');
  }

  async regenerateChapter(chapterId: string): Promise<void> {
    if (this.deps.hasBlockingNovelRun()) return;
    try {
      const creation = this.deps.creation;
      const run: NovelRun = await creation.regenerateChapter(this.projectId, chapterId);
      this.attachChapterRun(run, '重新生成中…');
    } catch (e) {
      this.errorMsg = String(e);
    }
  }

  activeRunMessage(seed: UIMessage): UIMessage {
    const current: UIMessage = this.activeMessages.find((message: UIMessage): boolean => message.id === seed.id) ?? seed;
    if (!this.activeQuickStart) return current;
    // Quick start generates structured JSON. Show its live thinking, then the saved overview.
    return makeUIMessage(current.role, current.parts.filter((part: UIMessagePart): boolean => part.type === 'reasoning'), {
      id: current.id, createdAt: current.createdAt, finishedAt: current.finishedAt, modelId: current.modelId,
    });
  }

  activeRunTimelineMessages(): UIMessage[] {
    if (!this.activeQuickStart) return this.activeMessages;
    return this.activeMessages.filter((message: UIMessage): boolean => message.role === 'assistant' &&
      message.parts.some((part: UIMessagePart): boolean => part.type === 'reasoning'));
  }

  novelTimelineMessages(project: NovelProject | null = this.project): NovelMessage[] {
    if (project === null) return [];
    if (this.activeRunId.length === 0) return project.messages;
    const messages: NovelMessage[] = project.messages.slice(0, this.activeHistoryCount);
    if (!this.activeQuickStart) {
      for (const message of this.activeRunTimelineMessages()) {
        if (message.role !== 'user' && message.role !== 'assistant') continue;
        messages.push(makeNovelMessage({ id: message.id, role: message.role,
          mode: this.activeRunKind === 'discussion' ? 'discuss' : 'write', uiMessage: message,
          createdAt: this.activeRunCreatedAt, runKind: this.activeRunKind }));
      }
    }
    return messages;
  }

  novelTimelineMessageLive(message: NovelMessage): boolean {
    return this.activeRunId.length > 0 && !this.activeQuickStart &&
      this.activeMessages.some((current: UIMessage): boolean => current.id === message.uiMessage.id);
  }

  savedTimelineMessage(seed: NovelMessage, project: NovelProject | null = this.project): NovelMessage {
    return project?.messages.find((message: NovelMessage): boolean => message.id === seed.id) ?? seed;
  }

  novelTimelineMessageUi(message: NovelMessage, project: NovelProject | null = this.project): UIMessage {
    return this.novelTimelineMessageLive(message) ? this.activeRunMessage(message.uiMessage) :
      this.projectMessageUi(this.savedTimelineMessage(message, project));
  }

  settingProposalsAt(message: NovelMessage, project: NovelProject | null = this.project): NovelSettingProposal[] {
    return project === null ? [] : settingProposalsForMessage(project.settingProposals, message);
  }

  copyUnsavedMessage(message: UIMessage): void {
    try {
      const board: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
      board.setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, toText(message)));
      promptAction.showToast({ message: '已复制未保存内容', duration: 1400 });
    } catch (error) {
      this.deps.operationError('复制失败', error);
    }
  }

  composeModeLabel(): string {
    if (this.composeMode === 'discuss') return '讨论';
    if (this.composeMode === 'continue') return '写一段';
    return '写整章';
  }

  showComposeModeMenu(): void {
    if (this.busy || this.deps.hasBlockingNovelRun()) return;
    promptAction.showActionMenu({
      title: '创作方式',
      buttons: [
        { text: '讨论', color: $r('sys.color.ohos_id_color_text_primary') },
        { text: '写一段', color: $r('sys.color.ohos_id_color_text_primary') },
        { text: '写整章', color: $r('sys.color.ohos_id_color_text_primary') },
        { text: '归档当前讨论', color: $r('sys.color.ohos_id_color_text_primary') },
      ],
    }).then((result: ExportMenuResult): void => {
      if (result.index === 0) this.composeMode = 'discuss';
      else if (result.index === 1) this.composeMode = 'continue';
      else if (result.index === 2) this.composeMode = 'whole_chapter';
      else if (result.index === 3) this.archiveDiscussion();
      if (result.index >= 0 && result.index <= 2) this.flushComposerDraft();
    }).catch((): void => {});
  }

  consumeContextOverrides(): void {
    const params: WorkspaceReturnParams = router.getParams() as WorkspaceReturnParams;
    const raw: string | undefined = params?.novelContextOverrides;
    if (raw === undefined) return;
    try {
      const result: NovelContextOverrideReturn = JSON.parse(raw) as NovelContextOverrideReturn;
      if (result.receiptToken === this.lastContextReceiptToken) return;
      this.lastContextReceiptToken = result.receiptToken;
      if (result.projectId !== this.projectId || result.branchId !== this.workspaceStatus?.activeBranchId) {
        this.contextOverrides = null;
        this.errorMsg = '上下文选择来自其他项目或分支，请重新预览';
        return;
      }
      this.contextOverrides = { branchId: result.branchId, forceIncludeMaterialIds: result.forceIncludeMaterialIds,
        forceExcludeMaterialIds: result.forceExcludeMaterialIds };
    } catch (error) { this.errorMsg = `无法读取本次上下文选择：${String(error)}`; }
  }

  openContextPreview(): void {
    if (this.busy || this.deps.hasBlockingNovelRun() || this.project === null) return;
    this.deps.closeControl();
    router.pushUrl({ url: 'pages/NovelContextPreviewPage', params: {
      projectId: this.projectId, userText: this.draft, mode: this.composeMode === 'discuss' ? 'discuss' : 'write',
      granularity: this.composeMode === 'continue' ? 'continuation' : this.composeMode === 'whole_chapter' ? 'whole_chapter' : '',
      forceIncludeMaterialIds: JSON.stringify(this.contextOverrides?.forceIncludeMaterialIds ?? []),
      forceExcludeMaterialIds: JSON.stringify(this.contextOverrides?.forceExcludeMaterialIds ?? []),
    } }).catch((error: Error): void => { if (this.pageAlive) this.errorMsg = String(error); });
  }

  composerPlaceholder(): string {
    if (this.composeMode === 'discuss') return '聊人物、情节或世界观…';
    if (this.composeMode === 'continue') return '描述接下来发生的场景…';
    return '描述本章要点，生成完整章节…';
  }
}
