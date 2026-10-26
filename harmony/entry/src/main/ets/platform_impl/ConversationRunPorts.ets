import type { AbortSignalLike } from '@amber/deepread-domain';
import type {
  AgentTool, Assistant, AutoApprovalReview, ChatStreamProvider, ChatTurnDeps, CompactStore,
  Conversation, ConversationRepository, JevCompletionFacts, KeyValueStore, RecipeLoopAdapter,
  TerminalApprovalGuard, ToolLoopOptions, UIMessagePart,
} from '@amber/chat-domain';
import type { DetachedConversationContext, PreparedConversationSeed } from './ConversationRunService.ets';
import type { ManualCompactionOutcome } from './ContextCompactionSupport.ets';
import type { RunToolCatalogSnapshot } from './RunToolCatalog.ets';

export interface ConversationRunStorage {
  repository: ConversationRepository;
  kv: KeyValueStore;
  compactStore: CompactStore;
  loadAssistants: () => Promise<Assistant[]>;
}

// The service owns admission, all session state and both serialized write tails.
// These capabilities perform external work without reading the composition root.
export interface ConversationRunExecution {
  prepareSeed: (conversation: Conversation) => Promise<PreparedConversationSeed>;
  buildTurnDeps: (context: DetachedConversationContext) => ChatTurnDeps;
  buildToolLoop: (context: DetachedConversationContext) => Promise<ToolLoopOptions>;
  completeAuxiliary: (context: DetachedConversationContext, finalized: Conversation, manualToolCallId: string) => void;
  compactConversation: (conversation: Conversation) => Promise<ManualCompactionOutcome>;
  evaluateCompletion: (conversation: Conversation, signal: AbortSignalLike) => Promise<JevCompletionFacts | null>;
  scheduleApprovalTriage: (conversationId: string, signal?: AbortSignalLike) => void;
  cancelApprovalTriage: (conversationId: string) => void;
  materializeDocumentParts: (parts: UIMessagePart[]) => Promise<UIMessagePart[]>;
  materializeConversationDocuments: (conversation: Conversation) => Promise<Conversation>;
}

export interface PreparedConversationTools {
  profiledTools: AgentTool[];
  terminalGuard: TerminalApprovalGuard | null;
  goalAdapter?: RecipeLoopAdapter;
}

// Platform capability construction stays in composition. The live catalog and
// run-scoped refresh remain together in ConversationRunTools.
export interface ConversationRunToolPorts {
  prepareTools: (context: DetachedConversationContext, compressProvider: ChatStreamProvider) => Promise<PreparedConversationTools>;
  createSkillTools: (context: DetachedConversationContext) => Promise<AgentTool[]>;
  createCatalog: (context: DetachedConversationContext, primitives: AgentTool[]) => Promise<RunToolCatalogSnapshot>;
  loadSubAgentTools: (context: DetachedConversationContext) => Promise<AgentTool[]>;
  warnIfToolAbilityMissing: (context: DetachedConversationContext) => void;
  createAutoApprovalReview: (context: DetachedConversationContext) => AutoApprovalReview;
  compressProvider: (seed: PreparedConversationSeed) => ChatStreamProvider;
}
