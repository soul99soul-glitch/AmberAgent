// jev_models — Jev 判断服务模型层(Android core/jev JevModels.kt 对齐)
//
// 模式:off(关)/ shadow(影子:评估只记录不影响行为)/ active(主动:结果影响选择)。
// 方言:TYPESAFE(typesafe 原生 /v1/systemone)/ VERCEL(AI Gateway /evaluation-model,
//   模型走 ai-model-id 头)。题型(本批接线):noul(是非概率)+ choice(有限单选);
//   答案经严格校验(缺题/类型不符/未知候选/非有限数值一律抛,不伪造 confidence)。

export type JevMode = 'off' | 'shadow' | 'active';
// 契约方言：TYPESAFE = /systemone（noul/choice），VERCEL = decision-model。
export type JevApiMode = 'typesafe' | 'vercel';
export type JevProvider = 'typesafe' | 'vercel' | 'perplexity' | 'liquid';
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

export interface JevProviderStyle {
  provider: JevProvider;
  label: string;
  contract: JevApiMode;
  endpoint: string;
  defaultModel: string;
  keyHint: string;
}

// 供应商差异只在此登记：endpoint、默认模型、Key 提示、能力位。
export const JEV_PROVIDERS: Record<JevProvider, JevProviderStyle> = {
  typesafe: {
    provider: 'typesafe', label: 'TypeSafe', contract: 'typesafe',
    endpoint: JEV_TYPESAFE_ENDPOINT, defaultModel: 'jev-1.14.0',
    keyHint: 'TypeSafe API Key',
  },
  vercel: {
    provider: 'vercel', label: 'Vercel Gateway', contract: 'vercel',
    endpoint: JEV_VERCEL_DEFAULT_BASE, defaultModel: 'typesafe-ai/jev-1.14.0',
    keyHint: 'Vercel AI Gateway Key',
  },
  perplexity: {
    provider: 'perplexity', label: 'Perplexity', contract: 'typesafe',
    endpoint: 'https://api.perplexity.ai/v1/decisions', defaultModel: 'pplx-decider-v1.2',
    keyHint: 'Perplexity API Key',
  },
  liquid: {
    provider: 'liquid', label: 'Liquid', contract: 'typesafe',
    endpoint: 'https://api.liquid.ai/decisions/v1/systemone', defaultModel: 'd1',
    keyHint: 'Liquid API Key',
  },
};

export const JEV_PROVIDER_ORDER: JevProvider[] = ['typesafe', 'vercel', 'perplexity', 'liquid'];

export const jevProviderStyle = (provider: JevProvider): JevProviderStyle => JEV_PROVIDERS[provider];

export const jevEndpointOf = (settings: JevSettings): string => {
  const style: JevProviderStyle = JEV_PROVIDERS[settings.provider];
  if (style.provider !== 'vercel') return style.endpoint;
  return settings.baseUrl.length > 0 ? settings.baseUrl : JEV_VERCEL_DEFAULT_BASE;
};

// 实际 POST 地址：Vercel 的 endpoint 是网关 base，需要追加 decision-model 路径。
export const jevRequestUrlOf = (settings: JevSettings): string => {
  const endpoint: string = jevEndpointOf(settings);
  return settings.apiMode === 'vercel' ? `${endpoint.replace(/\/+$/, '')}/evaluation-model` : endpoint;
};

export interface JevSettings {
  mode: JevMode;
  provider: JevProvider;
  apiMode: JevApiMode;   // provider 的契约派生值，保留给旧请求路径
  apiKey: string;
  baseUrl: string;   // VERCEL 网关 base;其他供应商恒用 registry endpoint
  model: string;
  modelProvider: JevProvider;   // model 属于哪家；切供应商时不把旧模型 ID 发错家
  approvalTriage: JevNewPurposeSettings;
  webAutomation: JevNewPurposeSettings;
  toolContextSelection: JevNewPurposeSettings;
  contextRetention: JevNewPurposeSettings;
  autoApproval: JevNewPurposeSettings;
  completionCheck: JevNewPurposeSettings;
}

export interface JevSettingsInit {
  mode?: JevMode; provider?: JevProvider; apiMode?: JevApiMode;
  apiKey?: string; baseUrl?: string; model?: string; modelProvider?: JevProvider;
  approvalTriage?: Partial<JevNewPurposeSettings>; webAutomation?: Partial<JevNewPurposeSettings>;
  toolContextSelection?: Partial<JevNewPurposeSettings>; contextRetention?: Partial<JevNewPurposeSettings>;
  autoApproval?: Partial<JevNewPurposeSettings>; completionCheck?: Partial<JevNewPurposeSettings>;
}

export const makeJevSettings = (opts: JevSettingsInit = {}): JevSettings => {
  const provider: JevProvider = opts.provider ?? (opts.apiMode === 'vercel' ? 'vercel' : 'typesafe');
  const style: JevProviderStyle = JEV_PROVIDERS[provider];
  return {
    mode: opts.mode ?? 'off',
    provider,
    apiMode: style.contract,
    apiKey: opts.apiKey ?? '',
    baseUrl: opts.baseUrl ?? JEV_VERCEL_DEFAULT_BASE,
    model: opts.model ?? '',
    modelProvider: opts.modelProvider ?? provider,
    approvalTriage: makeJevNewPurposeSettings(opts.approvalTriage),
    webAutomation: makeJevNewPurposeSettings(opts.webAutomation),
    toolContextSelection: makeJevNewPurposeSettings(opts.toolContextSelection),
    contextRetention: makeJevNewPurposeSettings(opts.contextRetention),
    autoApproval: makeJevNewPurposeSettings(opts.autoApproval),
    completionCheck: makeJevNewPurposeSettings(opts.completionCheck),
  };
};

// 切换供应商：清空旧 Key；模型只有属于新供应商时才保留，否则回到默认模型。
export const switchJevProvider = (settings: JevSettings, provider: JevProvider): JevSettings => {
  if (provider === settings.provider) return settings;
  const style: JevProviderStyle = JEV_PROVIDERS[provider];
  const keepModel: boolean = settings.modelProvider === provider && settings.model.length > 0;
  return makeJevSettings({
    mode: settings.mode,
    provider,
    modelProvider: provider,
    apiKey: '',
    baseUrl: settings.baseUrl,
    model: keepModel ? settings.model : style.defaultModel,
    approvalTriage: settings.approvalTriage,
    webAutomation: settings.webAutomation,
    toolContextSelection: settings.toolContextSelection,
    contextRetention: settings.contextRetention,
    autoApproval: settings.autoApproval,
    completionCheck: settings.completionCheck,
  });
};

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
    const legacyProvider: JevProvider = parsed.apiMode === 'vercel' ? 'vercel' : 'typesafe';
    const provider: JevProvider = typeof parsed.provider === 'string' &&
      JEV_PROVIDER_ORDER.indexOf(parsed.provider as JevProvider) >= 0
      ? parsed.provider as JevProvider : legacyProvider;
    const modelProvider: JevProvider = typeof parsed.modelProvider === 'string' &&
      JEV_PROVIDER_ORDER.indexOf(parsed.modelProvider as JevProvider) >= 0
      ? parsed.modelProvider as JevProvider : provider;
    const storedModel: string = typeof parsed.model === 'string' ? parsed.model : '';
    // 旧记录/手改记录若模型归属与供应商不一致，不把旧模型 ID 发到新家。
    const model: string = modelProvider === provider && storedModel.length > 0
      ? storedModel : JEV_PROVIDERS[provider].defaultModel;
    return makeJevSettings({
      mode: parsed.mode === 'shadow' || parsed.mode === 'active' ? parsed.mode : 'off',
      provider,
      modelProvider,
      apiKey: typeof parsed.apiKey === 'string' ? parsed.apiKey : '',
      baseUrl: typeof parsed.baseUrl === 'string' && parsed.baseUrl.length > 0
        ? parsed.baseUrl : JEV_VERCEL_DEFAULT_BASE,
      model,
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
  } catch {
    return makeJevSettings();
  }
};

export const saveJevSettings = async (kv: KeyValueStore, settings: JevSettings): Promise<void> => {
  await kv.put(JEV_SETTINGS_KEY, JSON.stringify(settings));
};
