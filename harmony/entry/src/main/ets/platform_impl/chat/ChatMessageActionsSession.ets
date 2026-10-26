import { promptAction } from '@kit.ArkUI';
import { pasteboard } from '@kit.BasicServicesKit';
import { systemShare } from '@kit.ShareKit';
import { uniformTypeDescriptor as utd } from '@kit.ArkData';
import type { common } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type { Conversation, ChatTimelineRow, UIMessage, MessageNode, ChatMessageArchiveSource,
  ConversationRepository, CompactStore } from '@amber/chat-domain';
import { messageCopyText, toText, nodeCurrentMessage, deleteMessage, forkConversation,
  copyValidCompactsToConversation, newId } from '@amber/chat-domain';
import type { ConversationRunService } from '../ConversationRunService.ets';
import { ERROR } from '../../design/tokens.ets';
import { invalidateChatConversationCompacts } from './ChatCompactionSupport.ets';

export interface ChatMessageActionsDependencies {
  repository: ConversationRepository;
  runService: ConversationRunService;
  compactStore: CompactStore;
  currentConversation: () => Conversation | null;
  resolveRow: (row: ChatTimelineRow) => ChatTimelineRow;
  isSending: () => boolean;
  ensureFullConversation: () => Promise<void>;
  listFavoriteNodeIds: (conversationId: string) => Promise<string[]>;
  setNodeFavorite: (conversationId: string, nodeId: string, selected: boolean) => Promise<void>;
  onEdit: (row: ChatTimelineRow) => void;
  onRegenerate: (nodeId: string) => void;
  onForkSaved: (conversationId: string) => void;
  onLocateArchive: (conversation: Conversation, nodeId: string, index: number) => void;
  onMessageCleanup: (conversationId: string, messageId: string) => void;
  onError: (message: string) => void;
  getShareContext: () => common.UIAbilityContext;
}

// Selection is a locator into the page's current rows; canonical history remains page/service owned.
@Observed
export class ChatMessageActionsSession {
  workspacePanelOpen: boolean = false;
  archiveInitialMessageId: string = '';
  exportSheetOpen: boolean = false;
  exportInitialMessageIds: string[] = [];
  favoriteNodeIds: string[] = [];
  copySheetOpen: boolean = false;
  copySheetMessage: UIMessage | null = null;
  actionsSheetOpen: boolean = false;
  actionsSheetRow: ChatTimelineRow | null = null;
  private favoriteWrites: Set<string> = new Set<string>();
  private dependencies: ChatMessageActionsDependencies | null = null;
  private alive: boolean = false;
  private visible: boolean = false;

  start(dependencies: ChatMessageActionsDependencies): void {
    this.dependencies = dependencies;
    this.alive = true;
    this.visible = true;
  }
  show(): void { if (this.alive) this.visible = true; }
  hide(): void { this.visible = false; this.workspacePanelOpen = false; }
  dispose(): void { this.hide(); this.alive = false; }

  refreshFavorites(conversationId: string): Promise<void> {
    const deps = this.dependencies;
    if (deps === null) return Promise.resolve();
    return deps.listFavoriteNodeIds(conversationId).then((ids: string[]): void => {
      if (this.alive && deps.currentConversation()?.id === conversationId) this.favoriteNodeIds = ids;
    }).catch((error: Error): void => {
      if (this.alive && this.visible) deps.onError(error.message);
    });
  }

  toggleFavorite(row: ChatTimelineRow): void {
    const deps = this.dependencies;
    if (deps === null) return;
    const conversation: Conversation | null = deps.currentConversation();
    if (conversation === null) return;
    const current: ChatTimelineRow = deps.resolveRow(row);
    const key: string = conversation.id + '/' + current.nodeId;
    if (this.favoriteWrites.has(key)) return;
    this.favoriteWrites.add(key);
    const selected: boolean = this.favoriteNodeIds.indexOf(current.nodeId) < 0;
    deps.setNodeFavorite(conversation.id, current.nodeId, selected).then(async (): Promise<void> => {
      await this.refreshFavorites(conversation.id);
      if (this.alive && this.visible && deps.currentConversation()?.id === conversation.id) {
        promptAction.showToast({ message: selected ? '已收藏' : '已取消收藏', duration: 1200 });
      }
    }).catch((error: Error): void => {
      if (this.alive && this.visible) deps.onError('收藏失败：' + error.message);
    }).finally((): void => { this.favoriteWrites.delete(key); });
  }

  openChatWorkspace(messageId: string = ''): void {
    this.archiveInitialMessageId = messageId;
    this.workspacePanelOpen = true;
  }

  async openArchiveSource(source: ChatMessageArchiveSource): Promise<void> {
    const deps = this.dependencies;
    if (deps === null) return;
    const full: Conversation | null = await deps.repository.getById(source.conversationId);
    if (!this.alive || !this.visible) return;
    const index: number = full === null ? -1 : full.messageNodes.findIndex((node: MessageNode): boolean => node.id === source.nodeId);
    if (full === null || index < 0) { deps.onError('来源消息已不存在'); return; }
    if (nodeCurrentMessage(full.messageNodes[index]).id !== source.messageId) {
      deps.onError('来源消息不在当前分支，请先切回对应分支'); return;
    }
    this.workspacePanelOpen = false;
    deps.onLocateArchive(full, source.nodeId, index);
  }

  openMessageExport(messageId: string = ''): void {
    this.exportInitialMessageIds = messageId.length > 0 ? [messageId] : [];
    this.exportSheetOpen = true;
  }
  openCopySheet(message: UIMessage): void {
    this.copySheetMessage = message;
    this.copySheetOpen = true;
  }
  closeCopySheet(): void { this.copySheetOpen = false; }
  openActionsSheet(row: ChatTimelineRow): void {
    if (this.dependencies === null || this.dependencies.isSending()) return;
    this.actionsSheetRow = this.dependencies.resolveRow(row);
    this.actionsSheetOpen = true;
  }
  closeActionsSheet(): void { this.actionsSheetOpen = false; }
  clearActionsSheet(): void { this.actionsSheetOpen = false; this.actionsSheetRow = null; }
  currentActionsRow(): ChatTimelineRow | null {
    return this.actionsSheetRow === null || this.dependencies === null ? null : this.dependencies.resolveRow(this.actionsSheetRow);
  }
  actionsSheetMessage(): UIMessage { return (this.currentActionsRow() as ChatTimelineRow).message; }
  actionsSheetIsUser(): boolean { return this.currentActionsRow()?.message.role === 'user'; }
  actionsSheetIsLastAssistant(): boolean { return this.currentActionsRow()?.isLastAssistant ?? false; }
  actionsSheetIsFavorite(): boolean {
    const row: ChatTimelineRow | null = this.currentActionsRow();
    return row !== null && this.favoriteNodeIds.indexOf(row.nodeId) >= 0;
  }
  copyActionsSheetMessage(): void {
    const row: ChatTimelineRow | null = this.currentActionsRow();
    if (row !== null) this.openCopySheet(row.message);
  }
  copyAssistantMessage(): void {
    const row: ChatTimelineRow | null = this.currentActionsRow();
    if (row === null) return;
    const board: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
    board.setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, messageCopyText(row.message, false)));
    promptAction.showToast({ message: '已复制', duration: 1200 });
  }
  editActionsSheetMessage(): void {
    const row: ChatTimelineRow | null = this.currentActionsRow();
    if (row !== null) this.dependencies?.onEdit(row);
  }
  regenerateActionsSheetMessage(): void {
    const row: ChatTimelineRow | null = this.currentActionsRow();
    if (row !== null) this.dependencies?.onRegenerate(row.nodeId);
  }
  forkActionsSheetMessage(): void {
    const row: ChatTimelineRow | null = this.currentActionsRow();
    if (row !== null) this.forkAt(row);
  }
  shareActionsSheetMessage(): void {
    const row: ChatTimelineRow | null = this.currentActionsRow();
    if (row !== null) this.shareMessage(row.message);
  }
  deleteActionsSheetMessage(): void {
    const row: ChatTimelineRow | null = this.currentActionsRow();
    if (row !== null) this.confirmDeleteMessage(row);
  }
  toggleActionsSheetFavorite(): void {
    const row: ChatTimelineRow | null = this.currentActionsRow();
    if (row !== null) this.toggleFavorite(row);
  }

  forkAt(row: ChatTimelineRow): void {
    const deps = this.dependencies;
    if (deps === null || deps.currentConversation() === null || deps.isSending()) return;
    const sourceId: string = (deps.currentConversation() as Conversation).id;
    deps.ensureFullConversation().then((): void => {
      const conversation: Conversation | null = deps.currentConversation();
      if (!this.alive || conversation === null || conversation.id !== sourceId || deps.isSending()) return;
      const fork: Conversation = forkConversation(conversation, deps.resolveRow(row).message.id);
      copyValidCompactsToConversation(deps.compactStore, sourceId, fork, (): string => newId(), (): number => Date.now())
        .catch((error: Error): void => {
          hilog.warn(0x0001, 'DeepRead.ChatPage', 'fork compact copy failed: %{public}s', error.message);
        });
      deps.repository.save(fork).then((): void => { deps.onForkSaved(fork.id); })
        .catch((error: Error): void => { deps.onError(error.message); });
    }).catch((error: Error): void => { deps.onError(error.message); });
  }

  confirmDeleteMessage(row: ChatTimelineRow): void {
    const deps = this.dependencies;
    if (deps === null || deps.currentConversation() === null || deps.isSending()) return;
    AlertDialog.show({
      title: '删除消息', message: '该消息将被删除,且不可恢复。',
      primaryButton: { value: '取消', action: (): void => {} },
      secondaryButton: { value: '删除', fontColor: ERROR, action: (): void => {
        const conversation: Conversation | null = deps.currentConversation();
        if (conversation === null) return;
        const id: string = conversation.id;
        const current: ChatTimelineRow = deps.resolveRow(row);
        deps.runService.mutateStoredConversation(id, (latest: Conversation): Conversation | null => {
          if (deps.runService.isBusy(id)) throw new Error('生成中无法删除消息');
          return deleteMessage(latest, current.message.id, false);
        }).then((updated: Conversation | null): Promise<void> => {
          if (updated === null) return Promise.resolve();
          deps.onMessageCleanup(id, current.message.id);
          return invalidateChatConversationCompacts(deps.compactStore, id, 'message_deleted');
        }).catch((error: Error): void => {
          if (this.alive && deps.currentConversation()?.id === id) deps.onError(error.message);
        });
      } },
    });
  }

  shareMessage(message: UIMessage): void {
    const text: string = toText(message).trim();
    if (text.length === 0) { promptAction.showToast({ message: '没有可分享的文本', duration: 1200 }); return; }
    try {
      const data: systemShare.SharedData = new systemShare.SharedData({ utd: utd.UniformDataType.PLAIN_TEXT, content: text });
      const controller: systemShare.ShareController = new systemShare.ShareController(data);
      if (this.dependencies === null) return;
      controller.show(this.dependencies.getShareContext(), { anchor: { windowOffset: { x: 660, y: 2200 } } })
        .catch((): void => { this.fallbackCopyShare(text); });
    } catch (_error) { this.fallbackCopyShare(text); }
  }
  private fallbackCopyShare(text: string): void {
    try {
      const board: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
      board.setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, text));
      promptAction.showToast({ message: '已复制，可粘贴分享', duration: 1400 });
    } catch (error) { this.dependencies?.onError(`分享准备失败: ${String(error)}`); }
  }
}
