import {
  buildJevApprovalSubject, buildJevApprovalBatch, completeJevApprovalFacts, listJevPendingApprovals,
  readJevApprovalTriage, resolveJevPurposeMode, updateCurrentMessages, currentMessages,
} from '@amber/chat-domain';
import type {
  Conversation, UIMessagePartTool, JevSettings, JevQuestion, JsonObject, JevEvaluateResult,
  JevApprovalPolicy, JevPendingApproval, JevApprovalTriage, ToolApprovalPartLocator,
} from '@amber/chat-domain';
import type { AbortSignalLike, AbortControllerLike } from '@amber/deepread-domain';
import { sha256HexUtf8 } from './EntryLocalToolPorts.ets';
import { createEntryAbortController } from './EntryAbortController.ets';

export interface JevTriageObservation {
  purpose: 'approval_triage'; subjectHash: string; model: string; facts: JevApprovalTriage; status: string;
}
export interface JevApprovalTriageDeps {
  loadConversation: (id: string) => Promise<Conversation | null>;
  mutateConversation: (id: string, update: (latest: Conversation) => Conversation | null | Promise<Conversation | null>) => Promise<Conversation | null>;
  loadSettings: () => Promise<JevSettings>;
  evaluate: (state: JsonObject, questions: Record<string, JevQuestion>, signal?: AbortSignalLike) => Promise<JevEvaluateResult>;
  policy: (action: UIMessagePartTool) => JevApprovalPolicy | null;
  recordShadow: (observation: JevTriageObservation) => Promise<void>;
}
const pendingAt = (conversation: Conversation, toolCallId: string, locator: ToolApprovalPartLocator): JevPendingApproval | null => {
  for (const pending of listJevPendingApprovals(conversation)) {
    if (pending.locator.messageId === locator.messageId && pending.locator.partIndex === locator.partIndex
      && pending.locator.toolCallId === toolCallId) return pending;
  }
  return null;
};
const pendingHash = (pending: JevPendingApproval): string => {
  const subject = buildJevApprovalSubject(pending.locator, pending.parent, pending.userMessageId);
  return subject === null ? '' : sha256HexUtf8(subject.bindingJSON);
};
export const currentJevApprovalSubjectHash = (
  conversation: Conversation, toolCallId: string, locator: ToolApprovalPartLocator,
): string => {
  const pending: JevPendingApproval | null = pendingAt(conversation, toolCallId, locator);
  return pending === null ? '' : pendingHash(pending);
};

export class JevApprovalTriageRuntime {
  private active: Map<string, AbortControllerLike> = new Map();
  private deps: JevApprovalTriageDeps;
  constructor(deps: JevApprovalTriageDeps) { this.deps = deps; }

  cancel(conversationId: string): void {
    const controller: AbortControllerLike | undefined = this.active.get(conversationId);
    this.active.delete(conversationId); controller?.abort();
  }
  cancelAll(): void { for (const id of this.active.keys()) this.cancel(id); }
  async run(conversationId: string, external?: AbortSignalLike): Promise<void> {
    this.cancel(conversationId);
    const controller: AbortControllerLike = createEntryAbortController();
    const relay = (): void => controller.abort();
    if (external?.aborted === true) controller.abort();
    else external?.addEventListener?.('abort', relay);
    this.active.set(conversationId, controller);
    const alive = (): boolean => this.active.get(conversationId) === controller && !controller.signal.aborted;
    try {
      const settings: JevSettings = await this.deps.loadSettings();
      const mode = resolveJevPurposeMode(settings, 'approval_triage');
      if (!alive() || mode === 'off') return;
      const conversation: Conversation | null = await this.deps.loadConversation(conversationId);
      if (!alive() || conversation === null) return;
      for (const pending of listJevPendingApprovals(conversation)) {
        if (!alive()) return;
        const subject = buildJevApprovalSubject(pending.locator, pending.parent, pending.userMessageId);
        if (subject === null) continue;
        const subjectHash: string = pendingHash(pending);
        if (mode === 'active' && readJevApprovalTriage(pending.parent, subjectHash) !== null) continue;
        const policy: JevApprovalPolicy | null = subject.action === null ? null : this.deps.policy(subject.action);
        const batch = buildJevApprovalBatch(settings, subject, subjectHash, policy, pending.userText);
        let facts: JevApprovalTriage = batch.facts; let status: string = 'local'; let model: string = settings.model;
        let shadow: boolean = mode === 'shadow';
        if (Object.keys(batch.questions).length > 0) {
          const dispatchSettings: JevSettings = await this.deps.loadSettings();
          if (!alive() || JSON.stringify(dispatchSettings) !== JSON.stringify(settings)) return;
          const result: JevEvaluateResult = await this.deps.evaluate(batch.state, batch.questions, controller.signal);
          shadow = shadow || result.shadow;
          status = result.ok ? 'ok' : result.reason;
          if (result.ok && result.evaluation !== null) {
            facts = completeJevApprovalFacts(batch, result.evaluation); model = result.evaluation.model || settings.model;
          } else if (mode === 'active') continue;
        }
        const currentSettings: JevSettings = await this.deps.loadSettings();
        if (!alive() || JSON.stringify(currentSettings) !== JSON.stringify(settings)) return;
        // Reload and then recheck again in the host's canonical write queue. No captured conversation is saved.
        const latest: Conversation | null = await this.deps.loadConversation(conversationId);
        if (!alive() || latest === null) return;
        const current = pendingAt(latest, pending.locator.toolCallId, pending.locator);
        if (current === null || pendingHash(current) !== subjectHash) continue;
        if (shadow) {
          const observation: JevTriageObservation = { purpose: 'approval_triage', subjectHash, model: model.substring(0, 80), facts, status };
          await this.deps.recordShadow(observation);
          continue;
        }
        await this.deps.mutateConversation(conversationId, async (canonical: Conversation): Promise<Conversation | null> => {
          const queuedSettings: JevSettings = await this.deps.loadSettings();
          if (!alive() || resolveJevPurposeMode(queuedSettings, 'approval_triage') !== 'active'
            || JSON.stringify(queuedSettings) !== JSON.stringify(settings)) return null;
          const target = pendingAt(canonical, pending.locator.toolCallId, pending.locator);
          if (target === null || pendingHash(target) !== subjectHash) return null;
          const targetSubject = buildJevApprovalSubject(target.locator, target.parent, target.userMessageId);
          const latestPolicy = targetSubject?.action === null || targetSubject?.action === undefined
            ? null : this.deps.policy(targetSubject.action);
          if (JSON.stringify(latestPolicy) !== JSON.stringify(policy)) return null;
          const metadata: JsonObject = {};
          if (target.parent.metadata !== null) for (const key of Object.keys(target.parent.metadata)) metadata[key] = target.parent.metadata[key];
          metadata['jev_approval_v1'] = { subjectHash: facts.subjectHash, readonly: facts.readonly,
            reversible: facts.reversible, goalAligned: facts.goalAligned };
          const parts = currentMessages(canonical).map((message) => {
            if (message.id !== target.locator.messageId) return message;
            const updatedParts = message.parts.slice();
            const parent = target.parent;
            const updated: UIMessagePartTool = { type: 'tool', toolCallId: parent.toolCallId,
              toolName: parent.toolName, input: parent.input, output: parent.output,
              approvalState: parent.approvalState, metadata };
            updatedParts[target.locator.partIndex] = updated;
            return { id: message.id, role: message.role, parts: updatedParts, annotations: message.annotations,
              modelId: message.modelId, createdAt: message.createdAt, usage: message.usage,
              finishedAt: message.finishedAt, translation: message.translation };
          });
          return updateCurrentMessages(canonical, parts);
        });
      }
    } finally {
      external?.removeEventListener?.('abort', relay);
      if (this.active.get(conversationId) === controller) this.active.delete(conversationId);
    }
  }
}
let runtime: JevApprovalTriageRuntime | null = null;
export const initializeJevApprovalTriage = (deps: JevApprovalTriageDeps): void => {
  runtime?.cancelAll(); runtime = new JevApprovalTriageRuntime(deps);
};
// Root calls only after the saved-pending barrier. Triage failure leaves the human approval unchanged.
export const scheduleJevApprovalTriage = (conversationId: string, signal?: AbortSignalLike): void => {
  runtime?.run(conversationId, signal).catch((): void => {});
};
export const cancelJevApprovalTriage = (conversationId: string): void => runtime?.cancel(conversationId);
