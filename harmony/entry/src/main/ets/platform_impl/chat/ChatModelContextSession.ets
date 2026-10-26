import { hilog } from '@kit.PerformanceAnalysisKit';
import { promptAction } from '@kit.ArkUI';
import type {
  Conversation, MessageNode, UIMessage, ConversationCompact, TokenUsage, CompactStore, KeyValueStore,
  Assistant, ProviderSetting, TopModelMenuGroup, ReasoningLevelOption, ReasoningLevel,
} from '@amber/chat-domain';
import {
  currentMessages, estimateConversationInputTokens, nativeTokenizerForModel,
  formatContextTokens, formatNumberInt, buildTopModelMenuGroups, loadProviders,
  reasoningLevelForModel, withReasoningLevelForModel, defaultReasoningLevelForModel,
  reasoningLevelsForModel,
} from '@amber/chat-domain';
import type { ChatProviderChoice } from '../../di/ChatRuntimeContracts.ets';
import type { ConversationRunSnapshot } from '../ConversationRunService.ets';
import type { ManualCompactionOutcome } from '../ContextCompactionSupport.ets';
import { countNativeContextTokens } from '../NativeTokenCounter.ets';
import { ERROR, INK3, STATUS_AMBER, STATUS_BLUE } from '../../design/tokens.ets';

const HILOG_DOMAIN: number = 0x0001;
const HILOG_TAG: string = 'Chat.ModelContext';

export interface ChatModelContextSelection {
  providerId: string;
  modelId: string;
}

export interface ChatModelContextPorts {
  currentConversation: () => Conversation | null;
  error: (message: string) => void;
  kvStore: KeyValueStore;
  compactStore: CompactStore;
  getAssistants: () => Promise<Assistant[]>;
  resolveChoice: (assistantId?: string) => Promise<ChatProviderChoice | null>;
  saveChoice: (selection: ChatModelContextSelection, assistantId?: string) => Promise<void>;
  mutateAssistant: (assistantId: string, mutate: (assistant: Assistant) => Assistant) => Promise<Assistant | null>;
  compact: (conversationId: string) => Promise<ManualCompactionOutcome>;
}

function modelContextNotStarted(): never {
  throw new Error('ChatModelContextSession.start must be called before use');
}

@Observed
export class ChatModelContextSession {
  reasoningLevels: ReasoningLevelOption[] = [];
  currentReasoningLevel: string = '';
  private currentModelId: string = '';
  contextCompacts: ConversationCompact[] = [];
  contextWindowTokens: number | null = null;
  compactActiveCount: number = 0;
  nativeContextTokens: number | null = null;
  modelLabel: string = '';
  modelMenuOpen: boolean = false;
  modelMenuLoading: boolean = false;
  modelMenuGroups: TopModelMenuGroup[] = [];
  modelMenuCurrentProvider: string = '';
  modelMenuCurrentModel: string = '';
  compactSummaryOpen: boolean = false;
  compactSummary: string = '';
  manualCompacting: boolean = false;
  contextMeterOpen: boolean = false;
  // Explicit first-level projections keep menu metrics reactive when the canonical
  // conversation changes behind the narrow getter; no conversation copy is owned here.
  contextUsedTokens: number = 0;
  turnTotalText: string = '—';
  turnCacheText: string = '—';
  turnSpeedText: string = '—';
  private contextCompactsRaw: ConversationCompact[] = [];
  private contextEstimateConversation: Conversation | null = null;
  private contextEstimateCompacts: ConversationCompact[] | null = null;
  private contextEstimateTokens: number = 0;
  private contextTokenModelId: string = '';
  private contextTokenizerId: string | null = null;
  private contextChoiceSeq: number = 0;
  private nativeContextSeq: number = 0;
  private nativeContextNodes: MessageNode[] | null = null;
  private nativeContextCompacts: ConversationCompact[] | null = null;
  private nativeContextModelId: string = '';
  private nativeContextConversationId: string = '';
  private compactLoadSeq: number = 0;
  private reasoningPickerSeq: number = 0;
  private modelMenuSeq: number = 0;
  private alive: boolean = false;
  private visible: boolean = false;
  private currentConversation: () => Conversation | null = (): Conversation | null => null;
  private onError: (message: string) => void = (_message: string): void => {};
  private readProviders: () => Promise<ProviderSetting[] | null> = modelContextNotStarted;
  private readCompacts: (conversationId: string) => Promise<ConversationCompact[]> = modelContextNotStarted;
  private getAssistants: () => Promise<Assistant[]> = modelContextNotStarted;
  private resolveChoice: (assistantId?: string) => Promise<ChatProviderChoice | null> = modelContextNotStarted;
  private saveChoice: (selection: ChatModelContextSelection, assistantId?: string) => Promise<void> = modelContextNotStarted;
  private mutateAssistant: (assistantId: string, mutate: (assistant: Assistant) => Assistant) => Promise<Assistant | null> = modelContextNotStarted;
  private compact: (conversationId: string) => Promise<ManualCompactionOutcome> = modelContextNotStarted;

  // Called through the page's observed instance. No callbacks capture constructor this.
  start(ports: ChatModelContextPorts): void {
    this.currentConversation = ports.currentConversation;
    this.onError = ports.error;
    this.readProviders = (): Promise<ProviderSetting[] | null> => loadProviders(ports.kvStore);
    this.readCompacts = (conversationId: string): Promise<ConversationCompact[]> =>
      ports.compactStore.getCompacts(conversationId);
    this.getAssistants = ports.getAssistants;
    this.resolveChoice = ports.resolveChoice;
    this.saveChoice = ports.saveChoice;
    this.mutateAssistant = ports.mutateAssistant;
    this.compact = ports.compact;
    this.alive = true;
    this.visible = true;
  }

  currentAssistantId(): string {
    return this.currentConversation()?.assistantId ?? '';
  }

  show(): void {
    if (!this.alive) return;
    this.visible = true;
  }

  hide(): void {
    this.visible = false;
    this.closeModelMenu();
    this.compactSummaryOpen = false;
  }

  dispose(): void {
    this.alive = false;
    this.visible = false;
    this.closeModelMenu();
    this.compactSummaryOpen = false;
    this.contextChoiceSeq++;
    this.reasoningPickerSeq++;
    this.compactLoadSeq++;
    this.nativeContextSeq++;
    this.nativeContextNodes = null;
    this.nativeContextCompacts = null;
  }

  // RunService remains the sole run owner; the page forwards its existing snapshot.
  applyRunSnapshot(snapshot: ConversationRunSnapshot): void {
    this.compactActiveCount = snapshot.compactActiveCount;
    if (snapshot.contextWindowTokens !== null) this.contextWindowTokens = snapshot.contextWindowTokens;
    if (snapshot.modelLabel.length > 0) this.modelLabel = snapshot.modelLabel;
    if (snapshot.conversation !== null) this.setContextCompacts(snapshot.contextCompacts);
    else this.refreshNativeContextEstimate();
  }

  private setContextCompacts(compacts: ConversationCompact[]): void {
    // Run snapshots copy the array while retaining its compact records. Keep the
    // previous projection when only that wrapper changed, so text frames reuse counts.
    if (compacts.length !== this.contextCompactsRaw.length || compacts.some(
      (compact: ConversationCompact, index: number): boolean => compact !== this.contextCompactsRaw[index])) {
      this.contextCompactsRaw = compacts;
      this.contextCompacts = compacts;
    }
    this.refreshNativeContextEstimate();
  }

  private refreshUsageProjection(): void {
    this.turnTotalText = this.lastTurnTotalText();
    this.turnCacheText = this.lastTurnCacheText();
    this.turnSpeedText = this.lastTurnSpeedText();
  }

  showCompactSummary(messageId: string): void {
    const compact: ConversationCompact | undefined = this.contextCompacts.find((c: ConversationCompact): boolean =>
      c.status === 'completed' && c.sourceMessageIds[c.sourceMessageIds.length - 1] === messageId);
    if (compact !== undefined) {
      this.compactSummary = compact.summary;
      this.compactSummaryOpen = true;
    }
  }

  refreshContextCompacts(): void {
    const conv: Conversation | null = this.currentConversation();
    if (conv === null) {
      this.setContextCompacts([]);
      return;
    }
    // 序号 + 会话身份双守卫:快速切换会话时旧摘要不得写入新会话的足迹
    const seq: number = ++this.compactLoadSeq;
    const convId: string = conv.id;
    this.readCompacts(convId)
      .then((cs: ConversationCompact[]): void => {
        if (!this.alive || seq !== this.compactLoadSeq) return;
        if (this.currentConversation()?.id !== convId) return;
        this.setContextCompacts(cs);
      })
      .catch((): void => {});
  }

  setContextModel(choice: ChatProviderChoice | null): void {
    this.contextWindowTokens = choice !== null ? choice.model.contextWindowTokens : null;
    this.contextTokenModelId = choice !== null ? choice.model.modelId : '';
    this.contextTokenizerId = nativeTokenizerForModel(this.contextTokenModelId);
    this.refreshNativeContextEstimate();
  }

  refreshNativeContextEstimate(): void {
    if (!this.alive) return;
    this.refreshUsageProjection();
    const conv: Conversation | null = this.currentConversation();
    const nodes: MessageNode[] | null = conv !== null ? conv.messageNodes : null;
    const convId: string = conv !== null ? conv.id : '';
    if (this.nativeContextNodes === nodes && this.nativeContextCompacts === this.contextCompactsRaw
      && this.nativeContextModelId === this.contextTokenModelId
      && this.nativeContextConversationId === convId) return;
    this.nativeContextNodes = nodes;
    this.nativeContextCompacts = this.contextCompactsRaw;
    this.nativeContextModelId = this.contextTokenModelId;
    this.nativeContextConversationId = convId;
    const seq: number = ++this.nativeContextSeq;
    this.nativeContextTokens = null;
    this.contextUsedTokens = this.estimatedContextTokens();
    const tokenizerId: string | null = this.contextTokenizerId;
    if (conv === null || tokenizerId === null) return;
    const modelId: string = this.contextTokenModelId;
    countNativeContextTokens(currentMessages(conv), this.contextCompactsRaw, tokenizerId)
      .then((tokens: number): void => {
        const current: Conversation | null = this.currentConversation();
        if (!this.alive || seq !== this.nativeContextSeq || current === null
          || current.id !== convId || current.messageNodes !== nodes
          || this.contextTokenModelId !== modelId) return;
        this.nativeContextTokens = tokens;
        this.contextUsedTokens = tokens;
      }).catch((): void => {});
  }

  estimatedContextTokens(): number {
    const conv: Conversation | null = this.currentConversation();
    if (conv === null) return 0;
    if (this.nativeContextTokens !== null && this.nativeContextNodes === conv.messageNodes
      && this.nativeContextCompacts === this.contextCompactsRaw
      && this.nativeContextModelId === this.contextTokenModelId
      && this.nativeContextConversationId === conv.id) return this.nativeContextTokens;
    if (this.contextEstimateConversation !== conv
      || this.contextEstimateCompacts !== this.contextCompactsRaw) {
      this.contextEstimateConversation = conv;
      this.contextEstimateCompacts = this.contextCompactsRaw;
      this.contextEstimateTokens = estimateConversationInputTokens(
        currentMessages(conv), this.contextCompactsRaw);
    }
    return this.contextEstimateTokens;
  }

  compactBoundaryCount(messageId: string): number {
    for (const c of this.contextCompacts) {
      if (c.status !== 'completed' || c.sourceMessageIds.length === 0) continue;
      if (c.sourceMessageIds[c.sourceMessageIds.length - 1] === messageId) {
        return c.sourceMessageIds.length;
      }
    }
    return 0;
  }

  contextUsageRatio(): number {
    const conv: Conversation | null = this.currentConversation();
    const w: number | null = this.contextWindowTokens;
    if (w === null || w <= 0 || conv === null) return 0;
    const used: number = this.estimatedContextTokens();
    const r: number = used / w;
    return r < 0 ? 0 : (r > 1 ? 1 : r);
  }

  contextFilledBars(): number {
    return Math.ceil(this.contextUsageRatio() * 5);
  }

  contextUsageText(): string {
    const conv: Conversation | null = this.currentConversation();
    if (this.compactActiveCount > 0) return '正在压缩上下文';
    if (conv === null) return 'Context 0 / --';
    const used: number = this.estimatedContextTokens();
    const w: number | null = this.contextWindowTokens;
    return `Context ${formatContextTokens(used)} / ${w !== null ? formatNumberInt(w) : '--'}`;
  }

  contextRingColor(): string {
    if (this.compactActiveCount > 0) return STATUS_BLUE;
    const r: number = this.contextUsageRatio();
    if (r >= 0.9) return ERROR;
    if (r >= 0.7) return STATUS_AMBER;
    return INK3;
  }

  lastTurnUsage(): TokenUsage | null {
    const conv: Conversation | null = this.currentConversation();
    if (conv === null) return null;
    const nodes = conv.messageNodes;
    for (let i: number = nodes.length - 1; i >= 0; i--) {
      const msgs = nodes[i].messages;
      if (msgs.length === 0) continue;
      const idx: number = nodes[i].selectIndex >= 0 && nodes[i].selectIndex < msgs.length
        ? nodes[i].selectIndex : 0;
      const msg: UIMessage = msgs[idx];
      if (msg.role !== 'assistant' || msg.usage === null) continue;
      return msg.usage;
    }
    return null;
  }

  lastTurnElapsedMs(): number | null {
    const conv: Conversation | null = this.currentConversation();
    if (conv === null) return null;
    const nodes = conv.messageNodes;
    for (let i: number = nodes.length - 1; i >= 0; i--) {
      const msgs = nodes[i].messages;
      if (msgs.length === 0) continue;
      const idx: number = nodes[i].selectIndex >= 0 && nodes[i].selectIndex < msgs.length
        ? nodes[i].selectIndex : 0;
      const msg: UIMessage = msgs[idx];
      if (msg.role !== 'assistant') continue;
      if (msg.finishedAt === null) return null;
      const a: number = new Date(msg.createdAt).getTime();
      const b: number = new Date(msg.finishedAt).getTime();
      if (isNaN(a) || isNaN(b)) return null;
      return b - a > 0 ? b - a : null;
    }
    return null;
  }

  formatGrouped(n: number): string {
    return Math.round(n).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  }

  lastTurnTotalText(): string {
    const u: TokenUsage | null = this.lastTurnUsage();
    return u !== null ? `${this.formatGrouped(u.totalTokens)} tok` : '—';
  }

  lastTurnCacheText(): string {
    const u: TokenUsage | null = this.lastTurnUsage();
    if (u === null || u.promptTokens <= 0 || u.cachedTokens <= 0) return '—';
    const pct: number = Math.min(100, u.cachedTokens * 100.0 / u.promptTokens);
    return `${Math.floor(pct)}%`;
  }

  lastTurnSpeedText(): string {
    const u: TokenUsage | null = this.lastTurnUsage();
    const ms: number | null = this.lastTurnElapsedMs();
    if (u === null || ms === null || u.completionTokens <= 0) return '—';
    return `${Math.floor(u.completionTokens / (ms / 1000.0))} tok/s`;
  }

  closeModelMenu(): void {
    this.modelMenuSeq++;
    this.modelMenuLoading = false;
    animateTo({ duration: 220, curve: Curve.EaseOut }, (): void => {
      this.modelMenuOpen = false;
    });
  }

  refreshEffectiveModelState(): void {
    const seq: number = ++this.contextChoiceSeq;
    const conversationId: string = this.currentConversation()?.id ?? '';
    const assistantId: string | undefined = this.currentConversation()?.assistantId;
    this.resolveChoice(assistantId).then((choice: ChatProviderChoice | null): void => {
      if (!this.alive || seq !== this.contextChoiceSeq
        || (this.currentConversation()?.id ?? '') !== conversationId
        || this.currentConversation()?.assistantId !== assistantId) return;
      this.modelLabel = choice !== null ? `${choice.provider.name} / ${choice.model.modelId}` : '';
      this.setContextModel(choice);
      this.refreshReasoningPicker(choice);
    }).catch((e: Error): void => {
      if (this.alive && seq === this.contextChoiceSeq
        && (this.currentConversation()?.id ?? '') === conversationId
        && this.currentConversation()?.assistantId === assistantId) this.onError(e.message);
    });
  }

  // 未使用：模型标签现与 Context/Reasoning 一并由 refreshEffectiveModelState 更新。
  private async describeEffectiveModel(): Promise<string> {
    const choice: ChatProviderChoice | null =
      await this.resolveChoice(this.currentConversation()?.assistantId);
    return choice !== null ? `${choice.provider.name} / ${choice.model.modelId}` : '';
  }

  toggleModelMenu(): void {
    if (this.modelMenuOpen || this.modelMenuLoading) {
      this.closeModelMenu();
      return;
    }
    const seq: number = ++this.modelMenuSeq;
    this.modelMenuLoading = true;
    // 读取失败也必须复位 loading — 否则菜单从此点不开
    this.readProviders().then((providers: ProviderSetting[] | null): void => {
      if (!this.alive || !this.visible || seq !== this.modelMenuSeq) return;
      this.resolveChoice(this.currentConversation()?.assistantId).then((choice: ChatProviderChoice | null): void => {
        if (!this.alive || !this.visible || seq !== this.modelMenuSeq) return;
        const currentProvider: string = choice !== null ? choice.provider.id : '';
        const currentModel: string = choice !== null ? choice.model.modelId : '';
        this.modelMenuGroups = buildTopModelMenuGroups(
          providers, 'chat', currentProvider, currentModel,
        );
        this.modelMenuCurrentProvider = currentProvider;
        this.modelMenuCurrentModel = currentModel;
        this.modelMenuLoading = false;
        animateTo({ duration: 220, curve: Curve.EaseOut }, (): void => {
          this.modelMenuOpen = true;
        });
      }).catch((e: Error): void => {
        if (!this.alive || seq !== this.modelMenuSeq) return;
        this.modelMenuLoading = false;
        hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'model selection read failed: %{public}s', e.message);
      });
    }).catch((e: Error): void => {
      if (!this.alive || seq !== this.modelMenuSeq) return;
      this.modelMenuLoading = false;
      hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'providers read failed: %{public}s', e.message);
    });
  }

  selectModelFromMenu(providerId: string, modelId: string): void {
    this.saveChoice({ providerId, modelId }, this.currentConversation()?.assistantId).then((): void => {
      if (!this.alive) return;
      // contextWindowTokens + ReasoningPicker 段集也随之刷新(模型变了,档位段集 + 当前档位变)
      this.refreshEffectiveModelState();
      this.closeModelMenu();
    }).catch((e: Error): void => { if (this.alive) this.onError(e.message); });
  }

  refreshReasoningPicker(choice: ChatProviderChoice | null): void {
    // 入口统一失效在途请求(null 分支也要递增,防旧模型迟到回调复活档位)
    const seq: number = ++this.reasoningPickerSeq;
    if (choice === null) {
      this.reasoningLevels = [];
      this.currentReasoningLevel = '';
      this.currentModelId = '';
      return;
    }
    this.reasoningLevels = reasoningLevelsForModel(choice.model);
    this.currentModelId = choice.model.id;
    // 当前档位 = assistant 按模型记忆(无记忆 → 模型默认档位)
    const modelId: string = choice.model.id;
    // 模型序号守卫:快速切换模型时,旧模型的迟到结果不得覆盖新模型档位
    this.getAssistants().then((assistants: Assistant[]): void => {
      if (!this.alive || seq !== this.reasoningPickerSeq) return;
      if (this.currentModelId !== modelId) return;
      const assistantId: string = this.currentConversation()?.assistantId ?? '';
      let picked: Assistant | null = null;
      for (const a of assistants) {
        if (a.id === assistantId) { picked = a; break; }
      }
      if (picked === null) return;
      const a: Assistant = picked;
      this.currentReasoningLevel = reasoningLevelForModel(
        a, modelId, defaultReasoningLevelForModel(choice.model),
      );
    });
  }

  handleReasoningLevelChange(level: string): void {
    const conv: Conversation | null = this.currentConversation();
    if (conv === null) return;
    const assistantId: string = conv.assistantId;
    const modelId: string = this.currentModelId;
    const rl: ReasoningLevel = level as ReasoningLevel;
    this.mutateAssistant(assistantId, (a: Assistant): Assistant =>
      withReasoningLevelForModel(a, modelId, rl)).then((changed: Assistant | null): void => {
      if (changed !== null && this.alive && this.currentModelId === modelId) {
        this.currentReasoningLevel = level;
      }
    }).catch((e: Error): void => { if (this.alive) this.onError(e.message); });
  }

  compactCurrentConversation(): void {
    const conv: Conversation | null = this.currentConversation();
    if (conv === null || this.manualCompacting) return;
    const id: string = conv.id;
    this.manualCompacting = true;
    this.compact(id).then((outcome: ManualCompactionOutcome): void => {
      if (!this.alive || this.currentConversation()?.id !== id) return;
      this.setContextCompacts(outcome.compacts);
      this.refreshContextCompacts();
      if (this.visible) promptAction.showToast({ message: outcome.message, duration: 2000 });
      if (this.visible && outcome.summary.length > 0) {
        this.compactSummary = outcome.summary;
        this.compactSummaryOpen = true;
      }
    }).catch((error: Error): void => {
      if (this.alive && this.currentConversation()?.id === id) this.onError(error.message);
    }).finally((): void => {
      if (this.currentConversation()?.id === id) this.manualCompacting = false;
    });
  }
}
