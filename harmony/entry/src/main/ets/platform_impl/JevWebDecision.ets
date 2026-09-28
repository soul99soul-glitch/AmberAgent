import { buildJevWebChoice, resolveJevPurposeMode } from '@amber/chat-domain';
import type { JevWebCandidate, JevWebObservation, JevWebDecision, JevSettings, JsonObject,
  JevQuestion, JevEvaluateResult } from '@amber/chat-domain';
import type { AbortSignalLike } from '@amber/deepread-domain';

export interface JevWebChoiceObservation { purpose: 'web_automation'; sessionId: string; revision: string; model: string; selected: string; status: string; }
export interface JevWebDecisionDeps {
  loadSettings: () => Promise<JevSettings>;
  evaluate: (state: JsonObject, questions: Record<string, JevQuestion>, signal?: AbortSignalLike) => Promise<JevEvaluateResult>;
  recordShadow: (observation: JevWebChoiceObservation) => Promise<void>;
}
let deps: JevWebDecisionDeps | null = null;
export const initializeJevWebDecision = (ports: JevWebDecisionDeps): void => { deps = ports; };
const handback = (reason: string, shadow: boolean = false): JevWebDecision =>
  ({ kind: 'handback', candidateId: null, reason, shadow });
// E12 owns the actual same-session/revision/lease/permission recheck and original primitive dispatcher.
export const chooseJevWebCandidate = async (
  observation: JevWebObservation, candidates: JevWebCandidate[], signal?: AbortSignalLike,
): Promise<JevWebDecision> => {
  const cancelled = (): boolean => signal?.aborted === true;
  const ports: JevWebDecisionDeps | null = deps;
  if (ports === null || cancelled()) return handback('unavailable_or_cancelled');
  const settings: JevSettings = await ports.loadSettings();
  const mode = resolveJevPurposeMode(settings, 'web_automation');
  if (mode === 'off') return handback('off');
  if (!settings.webAutomation.allowToolMetadata || !settings.webAutomation.allowPageContent) return handback('material_not_allowed', mode === 'shadow');
  try {
    const batch = buildJevWebChoice(observation, candidates);
    const sessionId: string = observation.sessionId; const revision: string = observation.revision;
    const result = await ports.evaluate(batch.state, batch.questions, signal);
    const latest = await ports.loadSettings();
    if (cancelled() || JSON.stringify(latest) !== JSON.stringify(settings)) return handback('cancelled_or_changed', mode === 'shadow');
    if (!result.ok || result.evaluation === null) return handback(result.reason, mode === 'shadow');
    const answer = result.evaluation.answers['next'];
    if (answer === undefined || answer.kind !== 'choice' || batch.questions['next'].kind !== 'choice'
      || batch.questions['next'].options[answer.selected] === undefined) return handback('invalid_choice', mode === 'shadow');
    if (mode === 'shadow' || result.shadow) {
      await ports.recordShadow({ purpose: 'web_automation', sessionId, revision,
        model: (result.evaluation.model || settings.model).substring(0, 80), selected: answer.selected, status: 'ok' });
      return handback('shadow', true);
    }
    return answer.selected === 'handback' ? handback('model_handback')
      : { kind: 'action', candidateId: answer.selected, reason: 'finite_choice', shadow: false };
  } catch (_error) { return handback(cancelled() ? 'cancelled' : 'evaluation_failed', mode === 'shadow'); }
};
