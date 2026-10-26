import { promptAction } from '@kit.ArkUI';
import { inputMethod } from '@kit.IMEKit';
import { vibrator } from '@kit.SensorServiceKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type { Conversation, MessageNode, UIMessage, UIMessagePart, ConversationRepository, CompactStore, ConversationRecapService, DeepReadOpenEvent, PendingUserMessage, DisplaySetting, JevCompletionNotice, ConversationRecap, ConversationRecapNode, RecapState, MessageChunk, OpenAIChatApi, ProviderSetting, ConversationTimelineLoadState } from '@amber/chat-domain';
import { currentMessages, runAutoTitle, makeUserMessage, makeTextGenerationParams, toChatModel, toText, patchConversation, selectMessageBranch, makeDisplaySetting, projectRecap, recapEligible, recapIsStale, nodeCurrentMessage, type KernelPhase } from '@amber/chat-domain';
import type { ChatProviderChoice, TaskPrompts } from '../../di/ChatRuntimeContracts.ets';
import type { ConversationRunService, ConversationRunSnapshot, ConversationRunHandle, ConversationRunCommand, ConversationRegenerateCommand, ConversationResumeCommand } from '../ConversationRunService.ets';
import { beginConversationRun, conversationRestoreVersion } from '../ConversationRunGuard.ets';
import { StreamingFrameRate } from '../../components/StreamingFrameRate.ets';
import { chatCompactionErrorText, invalidateChatConversationCompacts } from './ChatCompactionSupport.ets';
const HILOG_DOMAIN: number = 0x0001;
const HILOG_TAG: string = 'DeepRead.ChatPage';
export interface ChatPresentationPorts {
  repository: ConversationRepository;
  runService: ConversationRunService;
  recapService: ConversationRecapService;
  compactStore: CompactStore;
  bindBrowserPage: (id: string) => () => void;
  bindBrowserHooks: () => void;
  refreshFavorites: (id: string) => void;
  refreshModel: () => void;
  refreshOcr: () => void;
  refreshPendingRows: () => void;
  publishRunDetails: (snapshot: ConversationRunSnapshot) => void;
  publishModelContext: (snapshot: ConversationRunSnapshot) => void;
  reconcileSmartUi: (conversation: Conversation) => void;
  clearSmartUi: () => void;
  openDeepRead: (event: DeepReadOpenEvent) => void;
  timelineState: () => ConversationTimelineLoadState;
  clearTimeline: () => void;
  syncTimeline: () => void;
  projectConversation: (previous: Conversation | null, next: Conversation) => void;
  resetTimeline: (conversation: Conversation) => void;
  followStreamingUpdate: () => void;
  handleGenerateEnd: () => void;
  settleScroll: () => void;
  isGenerationActive: () => boolean;
  closeModelMenu: () => void;
  locateRecap: (conversation: Conversation, nodeId: string, index: number) => void;
  loadTaskPrompts: () => Promise<TaskPrompts>;
  resolveTitleChoice: () => Promise<ChatProviderChoice | null>;
  prepareProviderApi: (provider: ProviderSetting, headers: Record<string, string>) => Promise<OpenAIChatApi>;
}

@Observed
export class ChatConversationPresentation {
  loadedRestoreVersion: number = 0;
  conversationLoaded: boolean = false;
  conversationArrivedTick: number = 0;
  restoreReloadFailed: boolean = false;
  conversation: Conversation | null = null;
  runConversationId: string = '';
  currentRunId: string = '';
  unsubscribeRun: (() => void) | null = null;
  unsubscribeRunNavigation: (() => void) | null = null;
  unbindConversationBrowser: (() => void) | null = null;
  displayedRunConversation: Conversation | null = null;
  lastSnapshotTail: UIMessage[] = [];
  lastSnapshotRegenTail: UIMessage | null = null;
  unsavedConversationSeed: Conversation | null = null;
  runningConversationIds: string[] = [];
  unsubscribeRunning: (() => void) | null = null;
  sending: boolean = false;
  streamingTail: UIMessage[] = [];
  regenTail: UIMessage | null = null;
  regenNodeId: string = '';
  suggestions: string[] = [];
  errorMsg: string = '';
  pendingQueue: PendingUserMessage[] = [];
  completionNotice: JevCompletionNotice | null = null;
  title: string = '新对话';
  editingTitle: boolean = false;
  titleInput: string = '';
  displaySettings: DisplaySetting = makeDisplaySetting();
  userNickname: string = '';
  userAvatar: string = '';
  autoCloseThinking: boolean = true;
  enableGenerationNotification: boolean = false;
  recapOpen: boolean = false;
  recapSourceLoading: boolean = false;
  recapSource: Conversation | null = null;
  recapState: RecapState = { recap: null, loading: false, error: '' };
  recapOpenSeq: number = 0;
  unsubscribeRecap: (() => void) | null = null;
  kernelPhase: KernelPhase = 'idle';
  homePaneActiveId: string = '';
  private ports: ChatPresentationPorts | null = null;
  private get deps(): ChatPresentationPorts { return this.ports as ChatPresentationPorts; }
  private pageAlive: boolean = false;
  private pageVisible: boolean = false;
  private appBackgrounded: boolean = false;
  private streamingFrameRate: StreamingFrameRate = new StreamingFrameRate();
  start(ports: ChatPresentationPorts): void { this.ports = ports; this.pageAlive = true; this.pageVisible = true; }
  show(): void { this.pageVisible = true; this.syncStreamingFrameRate(); }
  hide(): void { this.pageVisible = false; this.closeRecap(); this.detachConversationRun(); this.syncStreamingFrameRate(); }
  dispose(): void { this.hide(); this.pageAlive = false; if (this.runConversationId.length > 0) this.deps.runService.discardNewConversation(this.runConversationId); }
  setAppBackgrounded(backgrounded: boolean): void { this.appBackgrounded = backgrounded; this.syncStreamingFrameRate(); }
  syncStreamingFrameRate(): void { this.streamingFrameRate.update(this.pageVisible && !this.appBackgrounded && this.sending); }

  serviceOwnsConversation(id: string): boolean {
    const snapshot: ConversationRunSnapshot | null = this.deps.runService.snapshot(id);
    return snapshot !== null && (snapshot.sending || snapshot.conversation !== null);
  }

  detachConversationRun(): void {
    this.unsubscribeRecap?.(); this.unsubscribeRecap = null;
    this.unsubscribeRun?.(); this.unsubscribeRun = null;
    this.unsubscribeRunning?.(); this.unsubscribeRunning = null;
    this.unsubscribeRunNavigation?.(); this.unsubscribeRunNavigation = null;
    this.unbindConversationBrowser?.(); this.unbindConversationBrowser = null;
  }

  attachConversationRun(id: string, reloadIdle: boolean = false): void {
    this.deps.refreshFavorites(id);
    this.detachConversationRun();
    this.runConversationId = id;
    this.homePaneActiveId = id;
    this.unsubscribeRecap = this.deps.recapService.subscribe(id, (state: RecapState): void => {
      if (this.pageAlive && this.pageVisible && this.runConversationId === id) this.recapState = state;
    });
    this.unsubscribeRunning = this.deps.runService.subscribeRunning((ids: string[]): void => {
      if (this.pageAlive && this.pageVisible) this.runningConversationIds = ids;
    });
    let first: boolean = true;
    this.unsubscribeRun = this.deps.runService.subscribe(id, (snapshot: ConversationRunSnapshot): void => {
      if (!this.pageAlive || !this.pageVisible || this.runConversationId !== id) return;
      this.applyConversationRunSnapshot(snapshot, !first);
      first = false;
    });
    if (!this.pageVisible) return;
    this.unsubscribeRunNavigation = this.deps.runService.subscribeNavigation(id, (event: DeepReadOpenEvent): void => {
      if (this.pageAlive && this.pageVisible && this.runConversationId === id) this.deps.openDeepRead(event);
    });
    this.unbindConversationBrowser = this.deps.bindBrowserPage(id);
    this.deps.bindBrowserHooks();
    if (reloadIdle && !this.serviceOwnsConversation(id)) {
      this.deps.repository.getById(id).then((saved: Conversation | null): void => {
        if (!this.pageAlive || !this.pageVisible || this.runConversationId !== id || this.serviceOwnsConversation(id)) return;
        if (saved === null) {
          if (this.unsavedConversationSeed === null) {
            this.errorMsg = '会话不存在或已删除'; this.conversationLoaded = false;
            this.conversation = null; this.deps.clearTimeline(); this.suggestions = [];
          }
          return;
        }
        const previous: Conversation | null = this.displayedRunConversation ?? this.conversation;
        this.unsavedConversationSeed = null;
        this.conversation = saved; this.displayedRunConversation = saved; this.conversationLoaded = true;
        this.deps.projectConversation(previous, saved);
        this.title = saved.title || '对话'; this.suggestions = saved.chatSuggestions;
      }).catch((error: Error): void => {
        if (this.pageAlive && this.pageVisible && this.runConversationId === id) this.errorMsg = error.message;
      });
    }
  }

  streamStructureChanged(previous: UIMessage | null, next: UIMessage | null): boolean {
    if (previous === next) return false;
    if (previous === null || next === null || previous.id !== next.id ||
      previous.parts.length !== next.parts.length) return true;
    for (let index: number = 0; index < next.parts.length; index++) {
      const oldPart: UIMessagePart = previous.parts[index];
      const nextPart: UIMessagePart = next.parts[index];
      if (oldPart.type !== nextPart.type || (nextPart.type !== 'text' && oldPart !== nextPart)) return true;
    }
    return false;
  }

  applyConversationRunSnapshot(snapshot: ConversationRunSnapshot, settle: boolean): void {
    const wasSending: boolean = this.sending;
    const oldRunId: string = this.currentRunId;
    const tailStructureChanged: boolean = this.lastSnapshotTail.length !== snapshot.streamingTail.length ||
      this.lastSnapshotTail.some((message: UIMessage, index: number): boolean =>
        this.streamStructureChanged(message, snapshot.streamingTail[index])) ||
      this.streamStructureChanged(this.lastSnapshotRegenTail, snapshot.regenTail);
    this.lastSnapshotTail = snapshot.streamingTail;
    this.lastSnapshotRegenTail = snapshot.regenTail;
    let timelineChanged: boolean = false;
    this.currentRunId = snapshot.runId;
    this.completionNotice = snapshot.completionNotice;
    if (this.sending && !snapshot.sending) {
      // 一轮结束:agent 可能已用 settings_set_model_slot 改了对话/视觉模型,刷新页头与图片守卫快照
      this.deps.refreshModel();
      this.deps.refreshOcr();
    }
    this.sending = snapshot.sending;
    this.syncStreamingFrameRate();
    this.streamingTail = snapshot.streamingTail;
    this.regenTail = snapshot.regenTail;
    this.regenNodeId = snapshot.regenNodeId;
    this.errorMsg = snapshot.errorMsg;
    this.deps.publishRunDetails(snapshot);
    this.pendingQueue = snapshot.pendingQueue;
    this.deps.refreshPendingRows();
    this.kernelPhase = snapshot.phase;
    if (snapshot.conversationDeleted) {
      this.conversation = null; this.displayedRunConversation = null; this.unsavedConversationSeed = null;
      this.deps.clearTimeline(); this.suggestions = []; this.conversationLoaded = false;
      this.errorMsg = '会话已删除';
      this.deps.syncTimeline();
      return;
    }
    const full: Conversation | null = snapshot.conversation;
    if (full !== null) {
      this.unsavedConversationSeed = null;
      if (this.conversation !== full) this.conversation = full;
      this.deps.reconcileSmartUi(full);
      this.conversationLoaded = true;
      const nextTitle: string = full.title || '对话';
      if (this.title !== nextTitle) this.title = nextTitle;
      const nextSuggestions: string[] = full.chatSuggestions;
      const suggestionsChanged: boolean = this.suggestions.length !== nextSuggestions.length ||
        this.suggestions.some((value: string, index: number): boolean => value !== nextSuggestions[index]);
      if (suggestionsChanged) this.suggestions = nextSuggestions;
      const visible: Conversation = snapshot.sending ? (snapshot.visualConversation ?? full) : full;
      if (!this.deps.timelineState().initialized || this.displayedRunConversation !== visible) {
        timelineChanged = true;
        this.deps.projectConversation(this.displayedRunConversation, visible);
      }
      this.displayedRunConversation = visible;
    }
    this.deps.publishModelContext(snapshot);
    this.deps.syncTimeline();
    // Text growth has onTextGrow; reasoning/tool updates have no such callback and follow here.
    if (snapshot.sending && (timelineChanged || tailStructureChanged)) this.deps.followStreamingUpdate();
    if (settle && wasSending && !snapshot.sending && oldRunId === snapshot.runId) {
      this.deps.handleGenerateEnd();
      if (snapshot.phase === 'completed') this.fireGenerationHaptic();
      this.deps.settleScroll();
    }
  }

  submitConversationRun(command: ConversationRunCommand): boolean {
    if (!this.pageAlive || this.conversation === null) return false;
    const id: string = this.conversation.id;
    if (conversationRestoreVersion(id) !== this.loadedRestoreVersion) {
      this.deps.clearSmartUi();
      this.refreshRestoredConversation().catch((error: Error): void => { if (this.pageAlive) this.errorMsg = error.message; });
      this.errorMsg = '会话已恢复，正在刷新，请稍后重试';
      return false;
    }
    if (this.unsubscribeRun === null || this.runConversationId !== id) this.attachConversationRun(id);
    try {
      const handle: ConversationRunHandle = this.deps.runService.start(id, command);
      handle.done.catch((error: Error): void => {
        if (this.pageAlive && this.pageVisible && this.runConversationId === id && this.currentRunId === handle.runId)
          this.errorMsg = chatCompactionErrorText(error);
      });
      return true;
    } catch (error) {
      this.errorMsg = error instanceof Error ? error.message : String(error);
      return false;
    }
  }

  requestStop(): void {
    if (this.runConversationId.length > 0 && this.currentRunId.length > 0) {
      this.deps.runService.cancel(this.runConversationId, this.currentRunId);
    }
  }

  ensureFullConversationLoaded(convId: string): Promise<Conversation | null> {
    const st: ConversationTimelineLoadState = this.deps.timelineState();
    if (st.initialized && st.fullyLoaded && this.conversation !== null
      && this.conversation.id === convId) {
      return Promise.resolve(this.conversation);
    }
    return this.deps.repository.getById(convId).then((full: Conversation | null): Conversation | null => {
      if (!this.pageAlive) return null;
      const active: ConversationRunSnapshot | null = this.deps.runService.snapshot(convId);
      if (active !== null && (active.sending || active.conversation !== null)) return active.conversation;
      this.conversation = full;
      if (full !== null) this.deps.reconcileSmartUi(full);
      return full;
    });
  }

  async refreshRestoredConversation(): Promise<void> {
    const current: Conversation | null = this.conversation;
    if (current === null) return;
    const version: number = conversationRestoreVersion(current.id);
    if (version === this.loadedRestoreVersion) return;
    this.deps.clearSmartUi();
    this.conversationLoaded = false;
    this.restoreReloadFailed = false;
    let restored: Conversation | null;
    try {
      restored = await this.deps.repository.getById(current.id);
    } catch (e) {
      this.restoreReloadFailed = true;
      throw e instanceof Error ? e : new Error(String(e));
    }
    if (!this.pageAlive || this.conversation?.id !== current.id) return;
    if (restored === null) {
      this.restoreReloadFailed = true;
      throw new Error('恢复后的会话无法读取');
    }
    this.conversation = restored;
    this.deps.resetTimeline(restored);
    this.suggestions = restored.chatSuggestions;
    this.title = restored.title || '对话';
    this.loadedRestoreVersion = version;
    this.conversationLoaded = true;
    this.deps.refreshModel();
  }

  async ensureFullConversationForMutation(): Promise<void> {
    await this.refreshRestoredConversation();
    const st: ConversationTimelineLoadState = this.deps.timelineState();
    if (st.initialized && st.fullyLoaded) return Promise.resolve();
    if (this.conversation === null) return Promise.resolve();
    const convId: string = this.conversation.id;
    const full: Promise<Conversation | null> = this.ensureFullConversationLoaded(convId);
    return full.then((loaded: Conversation | null): void => {
      if (loaded === null) return;
      // 生成与持久化需要全量数据，但展示窗口保持当前范围；历史仍由 onReachStart 展开。
    });
  }

  sameConversationTail(a: Conversation, b: Conversation): boolean {
    return JSON.stringify(currentMessages(a)) === JSON.stringify(currentMessages(b));
  }

  async maybeAutoTitle(conv: Conversation, force: boolean = false): Promise<void> {
    if (!force && conv.title.trim().length > 0) return;
    let releaseRun: () => void;
    try { releaseRun = beginConversationRun(conv.id); } catch { return; }
    try {
    // D-094:settings.titlePrompt(ChatPrefs.kt:81)→ runAutoTitle deps.template
    //   (auto_title.ts:60;调用时新取 = settingsFlow.value 语义)
    const prompts: TaskPrompts = await this.deps.loadTaskPrompts();
    const snapshot: Conversation = conv;
    await runAutoTitle(snapshot, {
      // D-087:title 任务模型(AiAuxiliaryGenerator.kt:41)—
      //   resolveTaskChatModel(titleModelId) ?: return(无模型静默跳过);
      //   params = model + reasoning OFF + model.customHeaders/customBodies(:55-59,
      //   不带 chat 温度/topP/maxTokens)
      generateText: async (prompt: string): Promise<string> => {
        const choice: ChatProviderChoice | null = await this.deps.resolveTitleChoice();
        if (choice === null) throw new Error('no title model');
        const headers: Record<string, string> = {};
        for (const h of choice.model.customHeaders) {
          const name: string = h.name.trim();
          if (name.length === 0) continue;
          headers[name] = h.value.trim();
        }
        const api: OpenAIChatApi = await this.deps.prepareProviderApi(choice.provider, headers);
        const chunk: MessageChunk = await api.generateText(
          [makeUserMessage(prompt)],
          makeTextGenerationParams({
            model: toChatModel(choice.model),
            reasoningLevel: 'off',
            customBody: choice.model.customBodies,
          }),
        );
        const msg = chunk.choices.length > 0 ? chunk.choices[0].message : null;
        return msg !== null ? toText(msg) : '';
      },
      // Auxiliary generation must not persist its stale snapshot directly.
      store: { save: (_ignored: Conversation): Promise<void> => Promise.resolve() },
      locale: '简体中文',
      template: prompts.titlePrompt,
    }, force)
      .then(async (titled: Conversation): Promise<void> => {
        const merged: Conversation | null = await this.deps.runService.mutateStoredConversation(titled.id,
          (latest: Conversation): Conversation | null => latest.title === snapshot.title
            && this.sameConversationTail(snapshot, latest) ? patchConversation(latest, { title: titled.title }) : null);
        if (merged === null) return;
        if (this.pageAlive && this.conversation !== null && this.conversation.id === merged.id) {
          this.conversation = merged;
          this.title = merged.title.length > 0 ? merged.title : this.title;
        }
      })
      .catch((e: Error): void => {
        if (this.pageAlive) this.errorMsg = `自动命名失败:${e.message}`;
      });
    } finally {
      releaseRun();
    }
  }

  startEditTitle(): void {
    if (this.conversation === null) return;
    this.titleInput = this.conversation.title;
    this.editingTitle = true;
  }

  saveTitle(): void {
    if (!this.pageAlive || this.conversation === null) return;
    const id: string = this.conversation.id;
    const title: string = this.titleInput.trim();
    this.editingTitle = false;
    this.deps.runService.mutateStoredConversation(id, (latest: Conversation): Conversation =>
      patchConversation(latest, { title: title })).catch((error: Error): void => {
        if (this.pageAlive && this.conversation?.id === id) this.errorMsg = error.message;
      });
  }

  fireGenerationHaptic(): void {
    if (!this.displaySettings.enableMessageGenerationHapticEffect) return;
    if (!canIUse('SystemCapability.Sensors.MiscDevice')) return;
    vibrator.isSupportEffect('haptic.notice.success')
      .then((supported: boolean): void => {
        if (supported) {
          vibrator.startVibration(
            { type: 'preset', effectId: 'haptic.notice.success', count: 1 },
            { usage: 'notification' },
          ).catch((): void => {});
        } else {
          vibrator.startVibration(
            { type: 'time', duration: 60 },
            { usage: 'notification' },
          ).catch((): void => {});
        }
      })
      .catch((): void => {});
  }

  switchBranch(nodeId: string, idx: number): void {
    if (this.conversation === null || this.sending) return;
    const id: string = this.conversation.id;
    this.deps.runService.mutateStoredConversation(id, (latest: Conversation): Conversation | null => {
      if (this.deps.runService.isBusy(id)) throw new Error('该会话仍在运行，请稍后重试');
      return selectMessageBranch(latest, nodeId, idx);
    }).then((updated: Conversation | null): Promise<void> => updated === null ? Promise.resolve()
      : invalidateChatConversationCompacts(this.deps.compactStore, id, 'message_branch_changed')).catch((error: Error): void => {
        if (this.pageAlive && this.conversation?.id === id) this.errorMsg = error.message;
      });
  }

  regenerate(nodeId: string): void {
    if (!this.pageAlive || this.conversation === null || this.sending) return;
    const command: ConversationRegenerateCommand = { kind: 'regenerate', nodeId: nodeId };
    this.submitConversationRun(command);
  }

  resumeToolLoop(conv: Conversation): void {
    if (!this.pageAlive || this.sending) return;
    const command: ConversationResumeCommand = { kind: 'resume', manualToolCallId: '' };
    this.submitConversationRun(command);
  }

  closeRecap(): void {
    this.recapOpen = false;
    this.recapSourceLoading = false;
    this.recapOpenSeq++;
  }

  async openRecap(force: boolean = false): Promise<void> {
    const id: string = this.conversation?.id ?? '';
    if (id.length === 0) return;
    if (this.deps.isGenerationActive()) { promptAction.showToast({ message: '回复完成后可查看回顾' }); return; }
    this.deps.closeModelMenu();
    const seq: number = ++this.recapOpenSeq;
    // The window's keyboard avoidance can otherwise move the anchored panel off screen.
    try { await inputMethod.getController().stopInputSession(); } catch (_error) {}
    if (!this.pageAlive || !this.pageVisible || this.recapOpenSeq !== seq || this.conversation?.id !== id) return;
    this.recapOpen = true;
    this.recapSourceLoading = true;
    try {
      // Read the full branch: the timeline may currently display only its tail window.
      const full: Conversation | null = await this.deps.repository.getById(id);
      if (!this.pageAlive || !this.pageVisible || this.recapOpenSeq !== seq || this.conversation?.id !== id) return;
      if (full === null || !recapEligible(full)) {
        this.closeRecap(); promptAction.showToast({ message: '再聊几轮就能回顾（至少 3 条用户消息）' }); return;
      }
      this.recapSource = full;
      this.recapSourceLoading = false;
      await this.deps.recapService.request(id, force);
    } catch (error) {
      if (this.pageAlive && this.pageVisible && this.recapOpenSeq === seq) {
        this.recapSourceLoading = false;
        const state: RecapState = this.recapState;
        this.recapState = { recap: state.recap, loading: false, error: (error as Error).message };
      }
    }
  }

  displayedRecap(): ConversationRecap | null {
    const recap: ConversationRecap | null = this.recapState.recap;
    const source: Conversation | null = this.recapSource;
    return recap === null || source === null ? null : projectRecap(recap, source);
  }

  recapNeedsRefresh(): boolean {
    return this.recapState.recap !== null && this.recapSource !== null
      && recapIsStale(this.recapState.recap, this.recapSource);
  }

  locateRecapNode(node: ConversationRecapNode): void {
    const full: Conversation | null = this.recapSource;
    if (full === null || node.messageId === null) return;
    const index: number = full.messageNodes.findIndex((item: MessageNode): boolean =>
      nodeCurrentMessage(item).id === node.messageId);
    if (index < 0) return;
    this.closeRecap();
    this.deps.locateRecap(full, full.messageNodes[index].id, index);
  }
}
