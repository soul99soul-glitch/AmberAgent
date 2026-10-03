// jev_models — Jev 判断服务模型层(Android core/jev JevModels.kt 对齐)
//
// 模式:off(关)/ shadow(影子:评估只记录不影响行为)/ active(主动:结果影响选择)。
// 方言:TYPESAFE(typesafe 原生 /v1/systemone)/ VERCEL(AI Gateway /evaluation-model,
//   模型走 ai-model-id 头)。题型(本批接线):noul(是非概率)+ choice(有限单选);
//   答案经严格校验(缺题/类型不符/未知候选/非有限数值一律抛,不伪造 confidence)。

export type JevMode = 'off' | 'shadow' | 'active';
export type JevApiMode = 'typesafe' | 'vercel';
export type JevNewPurpose = 'approval_triage' | 'web_automation' | 'tool_context_selection'
  | 'context_retention' | 'auto_approval' | 'completion_check';
export type JevPurpose = JevNewPurpose | 'tool_discovery' | 'memory_recall' | 'context_selection' | 'model_routing';
export interface JevNewPurposeSettings {
  mode: JevMode;
  allowToolMetadata: boolean;
  allowTaskText: boolean;
  allowPageContent: boolean;
  allowToolOutput: boolean;
}
export const makeJevNewPurposeSettings = (opts: Partial<JevNewPurposeSettings> = {}): JevNewPurposeSettings => ({
  mode: opts.mode === 'active' || opts.mode === 'shadow' ? opts.mode : 'off',
  allowToolMetadata: opts.allowToolMetadata === true,
  allowTaskText: opts.allowTaskText === true,
  allowPageContent: opts.allowPageContent === true,
  allowToolOutput: opts.allowToolOutput === true,
});

export const JEV_TYPESAFE_ENDPOINT: string = 'https://api.typesafe.ai/v1/systemone';
export const JEV_VERCEL_DEFAULT_BASE: string = 'https://ai-gateway.vercel.sh/v4/ai';

export interface JevSettings {
  mode: JevMode;
  apiMode: JevApiMode;
  apiKey: string;
  baseUrl: string;   // VERCEL 网关 base;TYPESAFE 恒用 JEV_TYPESAFE_ENDPOINT
  model: string;
  approvalTriage: JevNewPurposeSettings;
  webAutomation: JevNewPurposeSettings;
  toolContextSelection: JevNewPurposeSettings;
  contextRetention: JevNewPurposeSettings;
  autoApproval: JevNewPurposeSettings;
  completionCheck: JevNewPurposeSettings;
}

export interface JevSettingsInit {
  mode?: JevMode; apiMode?: JevApiMode; apiKey?: string; baseUrl?: string; model?: string;
  approvalTriage?: Partial<JevNewPurposeSettings>; webAutomation?: Partial<JevNewPurposeSettings>;
  toolContextSelection?: Partial<JevNewPurposeSettings>; contextRetention?: Partial<JevNewPurposeSettings>;
  autoApproval?: Partial<JevNewPurposeSettings>; completionCheck?: Partial<JevNewPurposeSettings>;
}

export const makeJevSettings = (opts: JevSettingsInit = {}): JevSettings => ({
  mode: opts.mode ?? 'off',
  apiMode: opts.apiMode ?? 'typesafe',
  apiKey: opts.apiKey ?? '',
  baseUrl: opts.baseUrl ?? JEV_VERCEL_DEFAULT_BASE,
  model: opts.model ?? '',
  approvalTriage: makeJevNewPurposeSettings(opts.approvalTriage),
  webAutomation: makeJevNewPurposeSettings(opts.webAutomation),
  toolContextSelection: makeJevNewPurposeSettings(opts.toolContextSelection),
  contextRetention: makeJevNewPurposeSettings(opts.contextRetention),
  autoApproval: makeJevNewPurposeSettings(opts.autoApproval),
  completionCheck: makeJevNewPurposeSettings(opts.completionCheck),
});

// ===== 题 =====

export interface JevNoulQuestion {
  kind: 'noul';
  instructions: string;
  trueCriteria: string;
  falseCriteria: string;
}

export interface JevChoiceQuestion {
  kind: 'choice';
  instructions: string;
  // 选项 id → 说明(可为空串)
  options: Record<string, string>;
}

export type JevQuestion = JevNoulQuestion | JevChoiceQuestion;

// ===== 答 =====

export interface JevNoulAnswer {
  kind: 'noul';
  probability: number;
}

export interface JevChoiceAnswer {
  kind: 'choice';
  selected: string;
}

export type JevAnswer = JevNoulAnswer | JevChoiceAnswer;

export interface JevUsage {
  inputTokens: number;
  outputTokens: number;
}

export interface JevEvaluation {
  answers: Record<string, JevAnswer>;
  usage: JevUsage | null;
  model: string;
}

export interface JevCallOk {
  status: 'ok';
  evaluation: JevEvaluation;
}

export interface JevCallSkipped {
  status: 'skipped';
  reason: string;
}

export interface JevCallFailed {
  status: 'failed';
  error: string;
}

export type JevCallOutcome = JevCallOk | JevCallSkipped | JevCallFailed;
export interface JevEvaluateResult {
  ok: boolean;
  reason: string;
  evaluation: JevEvaluation | null;
  shadow: boolean;
}

// ===== KV 设置('jev_settings' JSON) =====

import type { KeyValueStore } from './kv_store.ts';

const JEV_SETTINGS_KEY: string = 'jev_settings';

export const loadJevSettings = async (kv: KeyValueStore): Promise<JevSettings> => {
  const raw: string | null = await kv.get(JEV_SETTINGS_KEY);
  if (raw === null) return makeJevSettings();
  try {
    const parsed = JSON.parse(raw) as Partial<JevSettings>;
    return makeJevSettings({
      mode: parsed.mode === 'shadow' || parsed.mode === 'active' ? parsed.mode : 'off',
      apiMode: parsed.apiMode === 'vercel' ? 'vercel' : 'typesafe',
      apiKey: typeof parsed.apiKey === 'string' ? parsed.apiKey : '',
      baseUrl: typeof parsed.baseUrl === 'string' && parsed.baseUrl.length > 0
        ? parsed.baseUrl : JEV_VERCEL_DEFAULT_BASE,
      model: typeof parsed.model === 'string' ? parsed.model : '',
      approvalTriage: parsed.approvalTriage !== null && typeof parsed.approvalTriage === 'object'
        ? parsed.approvalTriage : undefined,
      webAutomation: parsed.webAutomation !== null && typeof parsed.webAutomation === 'object'
        ? parsed.webAutomation : undefined,
      toolContextSelection: parsed.toolContextSelection !== null && typeof parsed.toolContextSelection === 'object'
        ? parsed.toolContextSelection : undefined,
      contextRetention: parsed.contextRetention !== null && typeof parsed.contextRetention === 'object'
        ? parsed.contextRetention : undefined,
      autoApproval: parsed.autoApproval !== null && typeof parsed.autoApproval === 'object'
        ? parsed.autoApproval : undefined,
      completionCheck: parsed.completionCheck !== null && typeof parsed.completionCheck === 'object'
        ? parsed.completionCheck : undefined,

    });
  } catch (_e) {
    return makeJevSettings();
  }
};

export const saveJevSettings = async (kv: KeyValueStore, settings: JevSettings): Promise<void> => {
  await kv.put(JEV_SETTINGS_KEY, JSON.stringify(settings));
};
