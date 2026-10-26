import { hilog } from '@kit.PerformanceAnalysisKit';
import type { Conversation, UIMessage, UIMessagePart, UIMessagePartTool, ToolApprovalPartLocator,
  ToolApprovalVerdict, SandboxActivityUiState, SubAgentRun, SubAgentManager } from '@amber/chat-domain';
import { currentMessages, deriveSandboxActivities, mergeSandboxTimeline, idleSandboxActivity,
  withStepProgress, isActiveOperation } from '@amber/chat-domain';
import { getWebMountSession } from '../WebMountSession.ets';
import type { WebMountHookBinding } from '../WebMountSession.ets';
import type { ConversationRunSnapshot, ConversationVerdictCommand } from '../ConversationRunService.ets';
import { currentJevApprovalSubjectHash } from '../JevApprovalTriage.ets';

export interface ChatRunDetailsBindings {
  loadSubAgentManager: () => Promise<SubAgentManager>;
  readSetting: (key: string) => Promise<string | null>;
  runSnapshot: (conversationId: string) => ConversationRunSnapshot | null;
  currentConversation: () => Conversation | null;
  currentStreamingTail: () => UIMessage[];
  currentRegenTail: () => UIMessage | null;
  sending: () => boolean;
  error: (message: string) => void;
  stop: () => void;
  submitVerdict: (command: ConversationVerdictCommand) => boolean;
}

// Retained by the page across view/theme remounts. Only start/show create callbacks.
@Observed
export class ChatRunDetailsSession {
  liveActivity: SandboxActivityUiState | null = null;
  jsSandboxNeeded: boolean = false;
  snapshotVersion: number = 0;
  retryStatus: string = '';
  selectedSandboxIndex: number = -1;
  sandboxSheetOpen: boolean = false;
  subAgentSheetRunId: string = '';
  subAgentSheetOpen: boolean = false;
  subAgentLiveText: string = '';
  subAgentSheetTools: UIMessagePartTool[] = [];
  private unsubSubAgentLive: (() => void) | null = null;
  private subAgentSheetSeq: number = 0;
  councilSheetOpen: boolean = false;
  councilSheetRunId: string = '';
  councilSheetTools: UIMessagePartTool[] = [];
  toolPreviewSheetOpen: boolean = false;
  toolPreviewPart: UIMessagePartTool | null = null;
  toolPreviewLocator: ToolApprovalPartLocator | null = null;
  denyDialogToolCallId: string = '';
  denyDialogOpen: boolean = false;
  private denyDialogLocator: ToolApprovalPartLocator | undefined = undefined;
  denyReasonText: string = '';
  webmountHostVisible: boolean = false;
  webmountSheetOpen: boolean = false;
  private webmountHookBinding: WebMountHookBinding | null = null;
  // Existing settings snapshots are retained; no active reader currently consumes them.
  private webmountGlobalEnabled: boolean = false;
  private webmountEvalEnabled: boolean = false;
  private operationPreviewMode: string = 'always';
  private bindings: ChatRunDetailsBindings | null = null;
  private pageAlive: boolean = false;
  private pageVisible: boolean = false;

  start(bindings: ChatRunDetailsBindings): void {
    this.bindings = bindings;
    this.pageAlive = true;
    this.pageVisible = true;
    this.bindWebmountHooks();
    this.loadWebMountGlobalToggles();
  }

  show(): void {
    if (!this.pageAlive) return;
    this.pageVisible = true;
    this.bindWebmountHooks();
    this.loadWebMountGlobalToggles();
  }

  hide(): void {
    this.pageVisible = false;
    this.closeSubAgentSheet();
    this.closeCouncilSheet();
    this.webmountHookBinding?.unbind();
    this.webmountHookBinding = null;
  }

  dispose(): void {
    this.hide();
    this.pageAlive = false;
    this.bindings = null;
  }

  bindWebmountHooks(): void {
    this.webmountHookBinding?.unbind();
    this.webmountHookBinding = null;
    if (!this.pageAlive || !this.pageVisible) return;
    this.webmountHookBinding = getWebMountSession().bindHooks({
      onMountNeeded: (): void => {
        if (this.pageAlive && this.pageVisible && !this.webmountSheetOpen) this.webmountHostVisible = true;
      },
    });
  }

  applyRunSnapshot(snapshot: ConversationRunSnapshot): void {
    this.liveActivity = snapshot.liveActivity;
    this.jsSandboxNeeded = snapshot.jsSandboxNeeded;
    this.retryStatus = snapshot.retryStatus;
    this.snapshotVersion++;
  }

  setOperationPreviewMode(mode: string): void {
    this.operationPreviewMode = mode;
    this.snapshotVersion++;
  }

  sending(): boolean { return this.bindings?.sending() ?? false; }
  requestStop(): void { this.bindings?.stop(); }

  private currentConversation(): Conversation | null {
    return this.bindings?.currentConversation() ?? null;
  }

  openCouncilSheet(runId: string, tools: UIMessagePartTool[]): void {
    this.councilSheetRunId = runId;
    this.councilSheetTools = tools;
    this.councilSheetOpen = true;
  }

  closeCouncilSheet(): void { this.councilSheetOpen = false; }
  closeSandboxSheet(): void { this.sandboxSheetOpen = false; }
  finishSubAgentSheet(): void {
    this.closeSubAgentSheet();
    this.subAgentSheetRunId = '';
    this.subAgentLiveText = '';
  }
  finishToolPreview(): void {
    this.closeToolPreview();
    this.toolPreviewLocator = null;
  }
  webmountSheetDidDisappear(): void {
    this.webmountSheetOpen = false;
    if (!this.webmountHostVisible) this.webmountHostVisible = true;
  }
  loadWebMountGlobalToggles(): void {
    if (this.bindings === null) return;
    this.bindings.readSetting('webmount_global_enabled').then((raw: string | null): void => {
      if (this.pageAlive) this.webmountGlobalEnabled = raw === 'true';
    }).catch((): void => {});
    this.bindings.readSetting('webmount_eval_enabled').then((raw: string | null): void => {
      if (this.pageAlive) this.webmountEvalEnabled = raw === 'true';
    }).catch((): void => {});
  }

  webmountHostOf(url: string): string {
    const m: RegExpMatchArray | null = url.match(/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\/([^/?#]+)/);
    return m !== null ? m[1] : url;
  }

  openWebmountSheet(): void {
    this.webmountHostVisible = false;
    this.webmountSheetOpen = true;
  }

  closeWebmountSheet(): void {
    this.webmountSheetOpen = false;
    this.webmountHostVisible = true;
  }

  toggleWebmountLease(currentLease: string): void {
    getWebMountSession().setLease(currentLease === 'hand' ? 'bot' : 'hand');
  }

  async openSubAgentSheet(runId: string, tools: UIMessagePartTool[]): Promise<void> {
    if (!this.pageAlive || !this.pageVisible || this.bindings === null) return;
    const seq: number = ++this.subAgentSheetSeq;
    if (this.unsubSubAgentLive !== null) {
      this.unsubSubAgentLive();
      this.unsubSubAgentLive = null;
    }
    this.subAgentSheetRunId = runId;
    this.subAgentSheetTools = tools;
    this.subAgentLiveText = '';
    try {
      const manager = await this.bindings.loadSubAgentManager();
      if (!this.pageAlive || !this.pageVisible || seq !== this.subAgentSheetSeq) return;
      // 订阅实时文本
      this.unsubSubAgentLive = manager.subscribeLiveText(runId, (text: string): void => {
        if (!this.pageAlive || !this.pageVisible || seq !== this.subAgentSheetSeq) return;
        this.subAgentLiveText = text;
      });
      // 若已结束,从 snapshot 取最终文本
      const snapshot: SubAgentRun | null = manager.snapshot(runId);
      if (snapshot !== null && snapshot.displayText.length > 0) {
        this.subAgentLiveText = snapshot.displayText;
      }
    } catch (e) {
      if (!this.pageAlive || !this.pageVisible || seq !== this.subAgentSheetSeq) return;
      const msg: string = e instanceof Error ? e.message : String(e);
      hilog.warn(0x0001, 'Chat.Page', 'openSubAgentSheet failed: %{public}s', msg);
    }
    this.subAgentSheetOpen = true;
  }

  closeSubAgentSheet(): void {
    this.subAgentSheetSeq++;
    if (this.unsubSubAgentLive !== null) {
      this.unsubSubAgentLive();
      this.unsubSubAgentLive = null;
    }
    this.subAgentSheetOpen = false;
  }

  jevSubjectHash(part: UIMessagePartTool, locator?: ToolApprovalPartLocator): string {
    const viewed: Conversation | null = this.currentConversation();
    if (viewed === null || locator === undefined) return '';
    const snapshot: ConversationRunSnapshot | null = this.bindings?.runSnapshot(viewed.id) ?? null;
    if (snapshot?.conversationDeleted === true) return '';
    const canonical: Conversation = snapshot?.conversation ?? viewed;
    return currentJevApprovalSubjectHash(canonical, part.toolCallId, locator);
  }

  currentToolPreviewPart(): UIMessagePartTool {
    const part: UIMessagePartTool = this.toolPreviewPart as UIMessagePartTool;
    const locator: ToolApprovalPartLocator | null = this.toolPreviewLocator;
    const viewed: Conversation | null = this.currentConversation();
    if (locator === null || viewed === null) return part;
    const snapshot: ConversationRunSnapshot | null = this.bindings?.runSnapshot(viewed.id) ?? null;
    if (snapshot?.conversationDeleted === true) return part;
    const canonical: Conversation = snapshot?.conversation ?? viewed;
    const message: UIMessage | undefined = currentMessages(canonical).find((item: UIMessage): boolean => item.id === locator.messageId);
    const candidate: UIMessagePart | undefined = message?.parts[locator.partIndex];
    return candidate?.type === 'tool' && (candidate as UIMessagePartTool).toolCallId === part.toolCallId
      ? candidate as UIMessagePartTool : part;
  }

  openToolPreview(part: UIMessagePartTool, locator?: ToolApprovalPartLocator): void {
    this.toolPreviewLocator = locator ?? null;
    this.toolPreviewPart = part;
    this.toolPreviewSheetOpen = true;
  }

  closeToolPreview(): void {
    this.toolPreviewSheetOpen = false;
  }

  sandboxTimeline(): SandboxActivityUiState[] {
    const viewed: Conversation | null = this.currentConversation();
    if (viewed === null) return [];
    const snapshot: ConversationRunSnapshot | null = this.bindings?.runSnapshot(viewed.id) ?? null;
    if (snapshot?.conversationDeleted === true) return [];
    const conv: Conversation = snapshot?.conversation ?? viewed;
    const messageActivities: SandboxActivityUiState[] = deriveSandboxActivities(
      conv, this.sending(), this.retryStatus.length > 0 ? this.retryStatus : null);
    // scopedLiveSandboxActivity(:508-511):conversationId 匹配,或 null 且
    //   消息时间线含该 toolCallId
    let scoped: SandboxActivityUiState | null = null;
    const live: SandboxActivityUiState | null = this.liveActivity;
    if (live !== null) {
      const inMessages: boolean = messageActivities.some(
        (a: SandboxActivityUiState): boolean => a.toolCallId === live.toolCallId);
      if (live.conversationId === conv.id || (live.conversationId === null && inMessages)) {
        scoped = withStepProgress(live, conv);
      }
    }
    const raw: SandboxActivityUiState[] = mergeSandboxTimeline(messageActivities, scoped);
    // :516-522 — ALWAYS 空 → idle 兜底;AUTO 过滤活动态;HIDDEN 空
    const mode: string = this.operationPreviewMode;
    if (mode === 'always') {
      return raw.length > 0 ? raw : [idleSandboxActivity(conv)];
    }
    if (mode === 'auto') return raw.filter(isActiveOperation);
    return [];
  }

  currentSandboxActivity(timeline: SandboxActivityUiState[]): SandboxActivityUiState | null {
    const latest: number = timeline.length - 1;
    if (latest < 0) return null;
    if (this.selectedSandboxIndex >= 0 && this.selectedSandboxIndex <= latest) {
      return timeline[this.selectedSandboxIndex];
    }
    return timeline[latest];
  }

  sandboxStatusZh(status: string): string {
    if (status === 'running') return '执行中';
    if (status === 'waiting_for_permission') return '待授权';
    if (status === 'succeeded') return '成功';
    if (status === 'failed') return '失败';
    return '已取消';
  }

  sandboxStepText(activity: SandboxActivityUiState): string {
    if (activity.toolName === 'agent_idle') return '待命';
    if (activity.stepIndex !== null && activity.stepTotal !== null) {
      return `${activity.stepIndex}/${activity.stepTotal}`;
    }
    return this.sandboxStatusZh(activity.status);
  }

  handleToolApproval(toolCallId: string, approved: boolean, reason: string = '',
    locator?: ToolApprovalPartLocator): void {
    const verdict: ToolApprovalVerdict = approved
      ? { kind: 'approved' }
      : { kind: 'denied', reason };
    this.applyToolVerdict(toolCallId, verdict, locator);
  }

  openDenyDialog(toolCallId: string, locator?: ToolApprovalPartLocator): void {
    this.denyReasonText = '';
    this.denyDialogToolCallId = toolCallId;
    this.denyDialogLocator = locator;
    this.denyDialogOpen = true;
  }

  dismissDenyDialog(): void {
    this.denyDialogOpen = false;
    this.denyDialogToolCallId = '';
    this.denyDialogLocator = undefined;
    // 保留退出帧的输入内容；下次 openDenyDialog 再清空。
  }

  confirmDenyDialog(): void {
    const id: string = this.denyDialogToolCallId;
    const reason: string = this.denyReasonText;
    const locator: ToolApprovalPartLocator | undefined = this.denyDialogLocator;
    this.dismissDenyDialog();
    if (id.length > 0 || locator !== undefined) {
      this.handleToolApproval(id, false, reason, locator);
    }
  }

  handleToolAnswer(toolCallId: string, answer: string, locator?: ToolApprovalPartLocator): void {
    this.applyToolVerdict(toolCallId, { kind: 'answered', answer }, locator);
  }

  applyToolVerdict(toolCallId: string, verdict: ToolApprovalVerdict,
    locator?: ToolApprovalPartLocator): void {
    if (!this.pageAlive || this.currentConversation() === null || this.sending()) return;
    const command: ConversationVerdictCommand = { kind: 'verdict', toolCallId: toolCallId, verdict: verdict, locator: locator };
    this.bindings?.submitVerdict(command);
  }

  currentSandboxIndexOf(timeline: SandboxActivityUiState[]): number {
    const latest: number = timeline.length - 1;
    if (latest < 0) return -1;
    if (this.selectedSandboxIndex >= 0 && this.selectedSandboxIndex <= latest) {
      return this.selectedSandboxIndex;
    }
    return latest;
  }
}
