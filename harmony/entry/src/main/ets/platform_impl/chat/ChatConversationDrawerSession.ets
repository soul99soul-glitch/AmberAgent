import type { Conversation, ConversationSummary, MessageSearchHit, ConversationRepository } from '@amber/chat-domain';
import { patchConversation } from '@amber/chat-domain';
import type { ConversationRunService } from '../ConversationRunService.ets';
import { beginConversationRestore } from '../ConversationRunGuard.ets';
import { ERROR } from '../../design/tokens.ets';

export interface ChatConversationDrawerDependencies {
  repository: ConversationRepository;
  runService: ConversationRunService;
  currentConversationId: () => string;
  closeModelMenu: () => void;
  suspendIME: () => void;
  resumeIME: () => void;
  onNavigate: (target: string, conversationId: string) => void;
  maybeAutoTitle: (conversation: Conversation, force: boolean) => Promise<void>;
  cleanupConversation: (conversationId: string) => Promise<void>;
  cleanupDeletedConversation: (conversation: Conversation) => Promise<void>;
  onError: (message: string) => void;
}

@Observed
export class ChatConversationDrawerSession {
  drawerOpen: boolean = false;
  drawerConversations: ConversationSummary[] = [];
  drawerMessageHits: MessageSearchHit[] = [];
  drawerSearchText: string = '';
  private drawerSearchSeq: number = 0;
  private dependencies: ChatConversationDrawerDependencies | null = null;
  private alive: boolean = false;
  private visible: boolean = false;

  start(dependencies: ChatConversationDrawerDependencies): void {
    this.dependencies = dependencies;
    this.alive = true;
    this.visible = true;
  }
  show(): void { if (this.alive) this.visible = true; }
  hide(): void { this.visible = false; this.drawerSearchSeq++; }
  dispose(): void { this.alive = false; this.visible = false; this.drawerSearchSeq++; }

  refreshDrawerConversations(text: string = this.drawerSearchText): void {
    const deps = this.dependencies;
    if (deps === null) return;
    this.drawerSearchText = text;
    const sequence: number = ++this.drawerSearchSeq;
    const query: string = text.trim();
    const request: Promise<ConversationSummary[]> = query.length > 0 ? deps.repository.search(query) : deps.repository.list();
    request.then((items: ConversationSummary[]): void => {
      if (this.alive && sequence === this.drawerSearchSeq) this.drawerConversations = items;
    }).catch((error: Error): void => {
      if (this.alive && sequence === this.drawerSearchSeq) deps.onError(error.message);
    });
    if (query.length > 0) {
      deps.repository.searchMessages(query, 8).then((hits: MessageSearchHit[]): void => {
        if (this.alive && sequence === this.drawerSearchSeq) this.drawerMessageHits = hits;
      }).catch((_error: Error): void => {
        if (this.alive && sequence === this.drawerSearchSeq) this.drawerMessageHits = [];
      });
    } else { this.drawerMessageHits = []; }
  }

  openDrawer(): void {
    if (this.dependencies === null) return;
    this.dependencies.closeModelMenu();
    // The page callback detaches its one listener, zeros the inset, then stops the input session.
    this.dependencies.suspendIME();
    this.refreshDrawerConversations('');
    this.drawerOpen = true;
  }
  closeDrawer(): void {
    this.drawerOpen = false;
    this.drawerSearchSeq++;
    if (this.visible) this.dependencies?.resumeIME();
  }
  openDrawerConversation(id: string): void {
    this.closeDrawer();
    if (this.dependencies?.currentConversationId() === id) return;
    this.dependencies?.onNavigate('conversation', id);
  }
  newChat(): void { this.dependencies?.onNavigate('newChat', ''); }
  openDrawerBoard(): void { this.drawerOpen = false; this.dependencies?.onNavigate('board', ''); }
  openDrawerApps(): void { this.drawerOpen = false; this.dependencies?.onNavigate('apps', ''); }
  openDrawerSettings(): void { this.drawerOpen = false; this.dependencies?.onNavigate('settings', ''); }
  editDrawerNickname(_name: string): void { this.drawerOpen = false; this.dependencies?.onNavigate('profile', ''); }

  deleteDrawerConversation(item: ConversationSummary): void {
    const deps = this.dependencies;
    if (deps === null) return;
    const deletingCurrent: boolean = deps.currentConversationId() === item.id;
    if (deps.runService.isBusy(item.id)) { deps.onError('生成中无法删除当前会话'); return; }
    AlertDialog.show({
      title: '删除会话',
      message: `确定删除“${item.title.length > 0 ? item.title : '未命名对话'}”吗？此操作不可恢复。`,
      primaryButton: { value: '取消', action: (): void => {} },
      secondaryButton: { value: '删除', fontColor: ERROR, action: (): void => {
        if (deps.runService.isBusy(item.id)) { deps.onError('生成中无法删除该会话'); return; }
        const commit = async (): Promise<void> => {
          const release: () => void = beginConversationRestore([item.id]);
          try {
            const full: Conversation | null = await deps.repository.getById(item.id);
            if (full === null) return;
            await deps.repository.delete(item.id);
            deps.runService.forgetDeletedConversation(item.id);
            await deps.cleanupConversation(item.id);
            await deps.cleanupDeletedConversation(full);
          } finally { release(); }
        };
        commit().then((): void => {
          if (deletingCurrent) {
            this.drawerOpen = false;
            deps.onNavigate('deletedCurrent', '');
          } else { this.refreshDrawerConversations(); }
        }).catch((error: Error): void => { deps.onError(error.message); });
      } },
    });
  }
  pinDrawerConversation(item: ConversationSummary): void {
    const deps = this.dependencies;
    if (deps === null) return;
    deps.runService.mutateStoredConversation(item.id,
      (latest: Conversation): Conversation => patchConversation(latest, { isPinned: !latest.isPinned }))
      .then((): void => { this.refreshDrawerConversations(); })
      .catch((error: Error): void => { deps.onError(error.message); });
  }
  regenerateDrawerTitle(item: ConversationSummary): void {
    const deps = this.dependencies;
    if (deps === null) return;
    deps.repository.getById(item.id).then((conversation: Conversation | null): Promise<void> => {
      return conversation === null ? Promise.resolve() : deps.maybeAutoTitle(conversation, true);
    }).then((): void => { this.refreshDrawerConversations(); })
      .catch((error: Error): void => { deps.onError(error.message); });
  }
}
