import type { AbortSignalLike } from '@amber/deepread-domain';
import type { Conversation, JevApprovalPolicy, JsonObject, JsonValue, KeyValueStore,
  UIMessagePartTool, JevSettings, JevQuestion, JevEvaluateResult } from '@amber/chat-domain';
import { initializeJevApprovalTriage } from './JevApprovalTriage.ets';
import { initializeJevWebDecision } from './JevWebDecision.ets';
import type { JevWebChoiceObservation } from './JevWebDecision.ets';
import type { JevTriageObservation } from './JevApprovalTriage.ets';

// Saved permission facts are sufficient for local triage; never reconstruct tools through DI.
export const savedJevApprovalPolicy = (part: UIMessagePartTool): JevApprovalPolicy | null => {
  const trace: JsonValue | undefined = part.metadata?.['permission_trace'];
  if (trace === null || trace === undefined || typeof trace !== 'object' || Array.isArray(trace)) return null;
  const policy: JsonValue | undefined = (trace as JsonObject)['policy'];
  if (policy === null || policy === undefined || typeof policy !== 'object' || Array.isArray(policy)) return null;
  const fields: JsonObject = policy as JsonObject;
  if (typeof fields['category'] !== 'string' || typeof fields['risk'] !== 'string'
    || typeof fields['mutates'] !== 'boolean') return null;
  return { readonly: fields['mutates'] === true ? 'no' : 'unknown', reversible: 'unknown',
    category: fields['category'] as string, risk: fields['risk'] as string, mutates: fields['mutates'] as boolean };
};

export interface JevTriageHostDeps {
  kv: () => KeyValueStore;
  loadConversation: (id: string) => Promise<Conversation | null>;
  mutateConversation: (id: string, update: (latest: Conversation) => Conversation | null | Promise<Conversation | null>) => Promise<Conversation | null>;
  loadSettings: () => Promise<JevSettings>;
  evaluate: (state: JsonObject, questions: Record<string, JevQuestion>, signal?: AbortSignalLike) => Promise<JevEvaluateResult>;
}
interface ShadowLog { observations: JevTriageObservation[]; }
interface WebShadowLog { observations: JevWebChoiceObservation[]; }
export const initializeJevTriageHost = (deps: JevTriageHostDeps): void => {
  let shadowTail: Promise<void> = Promise.resolve();
  initializeJevApprovalTriage({
    loadConversation: deps.loadConversation, mutateConversation: deps.mutateConversation,
    loadSettings: deps.loadSettings, evaluate: deps.evaluate, policy: savedJevApprovalPolicy,
    recordShadow: (observation: JevTriageObservation): Promise<void> => {
      const write: Promise<void> = shadowTail.then(async (): Promise<void> => {
        const kv: KeyValueStore = deps.kv();
        const raw: string | null = await kv.get('jev_shadow_approval');
        const previous: ShadowLog = raw === null ? { observations: [] } : JSON.parse(raw) as ShadowLog;
        const observations: JevTriageObservation[] = previous.observations.slice(-19);
        observations.push(observation);
        await kv.put('jev_shadow_approval', JSON.stringify({ observations } as ShadowLog));
      });
      shadowTail = write.catch((): void => {});
      return write;
    },
  });
  let webShadowTail: Promise<void> = Promise.resolve();
  initializeJevWebDecision({ loadSettings: deps.loadSettings, evaluate: deps.evaluate,
    recordShadow: (observation: JevWebChoiceObservation): Promise<void> => {
      const write: Promise<void> = webShadowTail.then(async (): Promise<void> => {
        const kv: KeyValueStore = deps.kv();
        const raw: string | null = await kv.get('jev_shadow_web');
        const previous: WebShadowLog = raw === null ? { observations: [] } : JSON.parse(raw) as WebShadowLog;
        const observations: JevWebChoiceObservation[] = previous.observations.slice(-19);
        observations.push(observation);
        await kv.put('jev_shadow_web', JSON.stringify({ observations } as WebShadowLog));
      });
      webShadowTail = write.catch((): void => {});
      return write;
    },
  });
};
