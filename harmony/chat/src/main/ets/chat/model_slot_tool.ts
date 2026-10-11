// model_slot_tool — settings_set_model_slot(iOS IOSProviderConfigToolService.setModelSlot 对齐)
//
// 让 agent 把「已配置」的模型指到固定角色槽。纯逻辑(解析/校验/结果 JSON)在此,
// 持久化经 ModelSlotPort(entry 实现:ModelSlotPort.ets)。
//
// 语义:
//   - 仅固定槽:chat / assistant_chat / title / suggestion / compress / ocr / image_generation
//     (provider 名不是槽)。
//   - model_id(UUID,= provider_list 里 models[].id)精确匹配优先;否则 model_ref 对
//     displayName / 线上 modelId 做不区分大小写子串匹配,必须唯一。若恰有一个精确等值
//     (displayName 或 modelId)命中,以其为准(避免 "gpt-4o" 被 "gpt-4o-mini" 歧义)。
//   - 候选 = 启用且认证可用的 provider 下的模型;类型与槽不符 → 失败(不写入)。
//     chat 类槽要求 type=chat;image_generation 要求 type=image 且请求 provider 为 openai
//     (与 ChatTaskModelPickerPage / resolveImageGenChoice 同口径);ocr 另要求支持图片输入。
//   - 失败一律 status:'error' + code,不写入;歧义时附 candidates(≤8)。

import type { JsonObject, JsonValue } from './json.ts';
import type { ProviderModel, ProviderSetting } from './provider_settings.ts';
import { hasUsableAuth } from './provider_settings.ts';
import { makeAgentTool, makeInputSchemaObj } from './tool.ts';
import type { AgentTool } from './tool.ts';
import type { UIMessagePart } from './message.ts';

export const MODEL_SLOTS: string[] = [
  'chat', 'assistant_chat', 'title', 'ocr', 'compress', 'suggestion', 'image_generation',
];

export interface ModelSlotPort {
  // 读取最新 provider 配置(未配置 → [])
  loadProviders(): Promise<ProviderSetting[]>;
  // 写入已校验的目标。providerId = provider 容器 id;model = 容器内模型(model.id 为 UUID)。
  // assistant_chat 的目标助手由 port 构造时绑定。
  writeSlot(slot: string, providerId: string, model: ProviderModel): Promise<void>;
}

export interface ModelSlotToolDeps {
  port: ModelSlotPort;
}

export interface Candidate {
  provider: ProviderSetting;
  model: ProviderModel;
}

const isObj = (v: JsonValue | undefined): v is JsonObject =>
  typeof v === 'object' && v !== null && !Array.isArray(v);

const trimmedString = (obj: JsonObject, key: string): string => {
  const v: JsonValue | undefined = obj[key];
  return typeof v === 'string' ? v.trim() : '';
};

const textResult = (value: JsonObject): UIMessagePart[] =>
  [{ type: 'text', text: JSON.stringify(value), metadata: null }];

const candidateJson = (c: Candidate): JsonObject => ({
  model_id: c.model.id,
  model_wire_id: c.model.modelId,
  display_name: label(c.model),
  type: c.model.type,
  provider_id: c.provider.id,
  provider_name: c.provider.name,
});

const failure = (code: string, message: string, candidates?: Candidate[]): UIMessagePart[] => {
  const out: JsonObject = { tool: 'settings_set_model_slot', status: 'error', code, message };
  if (candidates !== undefined) out['candidates'] = candidates.map(candidateJson);
  return textResult(out);
};

const label = (model: ProviderModel): string =>
  model.displayName.trim().length > 0 ? model.displayName.trim() : model.modelId;

// 槽对模型的要求;不满足返回原因,满足返回 null
export const slotTypeMismatch = (slot: string, c: Candidate): string | null => {
  const effective: ProviderSetting = c.model.providerOverwrite ?? c.provider;
  if (slot === 'image_generation') {
    if (c.model.type !== 'image') return `slot image_generation needs an image model, but this model is type ${c.model.type}`;
    if (effective.type !== 'openai') return 'image_generation currently supports OpenAI-compatible providers only';
    return null;
  }
  if (c.model.type !== 'chat') return `slot ${slot} needs a chat model, but this model is type ${c.model.type}`;
  if (slot === 'ocr' && !c.model.inputModalities.includes('image')) {
    return 'slot ocr needs a chat model with image input; this model does not accept images';
  }
  return null;
};

const usableCandidates = (providers: ProviderSetting[]): Candidate[] => {
  const out: Candidate[] = [];
  for (const provider of providers) {
    for (const model of provider.models) {
      if (model.id.trim().length === 0) continue; // 损坏模型不可绑定
      const effective: ProviderSetting = model.providerOverwrite ?? provider;
      if (!hasUsableAuth(provider) || !hasUsableAuth(effective)) continue;
      out.push({ provider, model });
    }
  }
  return out;
};

export interface ModelSlotResolution {
  kind: 'ok' | 'error';
  candidate?: Candidate;
  code?: string;
  message?: string;
  candidates?: Candidate[];
}

export const resolveModelSlotTarget = (
  providers: ProviderSetting[], slot: string, modelId: string, modelRef: string,
): ModelSlotResolution => {
  if (!MODEL_SLOTS.includes(slot)) {
    return { kind: 'error', code: 'unknown_slot',
      message: `Unsupported slot "${slot}". Allowed: ${MODEL_SLOTS.join(', ')}. Provider names are not slots.` };
  }
  const all: Candidate[] = usableCandidates(providers);
  let hits: Candidate[];
  if (modelId.length > 0) {
    const q: string = modelId.toLowerCase();
    hits = all.filter((c: Candidate): boolean => c.model.id.toLowerCase() === q);
    if (hits.length === 0) {
      return { kind: 'error', code: 'model_not_found',
        message: 'model_id did not match a configured model on an enabled provider with credentials. Use the models[].id from provider_list.' };
    }
    if (hits.length > 1) {
      return { kind: 'error', code: 'ambiguous',
        message: 'model_id is shared by several providers; fix the duplicated model in provider settings first.',
        candidates: hits.slice(0, 8) };
    }
  } else if (modelRef.length > 0) {
    const q: string = modelRef.toLowerCase();
    hits = all.filter((c: Candidate): boolean =>
      c.model.modelId.toLowerCase().includes(q) || label(c.model).toLowerCase().includes(q)
      || c.model.displayName.toLowerCase().includes(q) || c.model.id.toLowerCase() === q);
    if (hits.length === 0) {
      return { kind: 'error', code: 'model_not_found',
        message: 'model_ref did not match any configured model on an enabled provider with credentials.' };
    }
    // 优先保留与槽类型相容的命中("gpt" 同时命中 chat 与 image 模型时不应歧义)
    const compatible: Candidate[] = hits.filter((c: Candidate): boolean => slotTypeMismatch(slot, c) === null);
    if (compatible.length === 0) {
      return { kind: 'error', code: 'type_mismatch',
        message: slotTypeMismatch(slot, hits[0]) ?? `No matching model fits slot ${slot}.`,
        candidates: hits.slice(0, 8) };
    }
    hits = compatible;
    if (hits.length > 1) {
      const exact: Candidate[] = hits.filter((c: Candidate): boolean =>
        c.model.modelId.toLowerCase() === q || c.model.displayName.trim().toLowerCase() === q);
      if (exact.length === 1) hits = exact;
    }
    if (hits.length > 1) {
      return { kind: 'error', code: 'ambiguous',
        message: 'model_ref matches multiple models; retry with model_id from candidates.',
        candidates: hits.slice(0, 8) };
    }
  } else {
    return { kind: 'error', code: 'missing_model', message: 'Provide model_id or model_ref.' };
  }
  const candidate: Candidate = hits[0];
  const mismatch: string | null = slotTypeMismatch(slot, candidate);
  if (mismatch !== null) return { kind: 'error', code: 'type_mismatch', message: mismatch };
  return { kind: 'ok', candidate };
};

const CHAT_ROLE_SLOTS: string[] = ['chat', 'assistant_chat'];

export const createModelSlotTool = (deps: ModelSlotToolDeps): AgentTool => makeAgentTool({
  name: 'settings_set_model_slot',
  description: 'Set a fixed model role slot to an already-configured model by UUID or fuzzy model_ref. ' +
    `Allowed slots only: ${MODEL_SLOTS.join(', ')}. Provider names are not slots. ` +
    'Fails if the reference is ambiguous or the model type does not match the slot. ' +
    'Use provider_list to find model ids (models[].id) first.',
  parameters: () => makeInputSchemaObj({
    slot: { type: 'string', enum: MODEL_SLOTS,
      description: 'Fixed role slot. chat = the chat model used by this conversation (like the top model menu: updates the current assistant\'s binding if it has one, otherwise the global default); assistant_chat = bind the current assistant to this model.' },
    model_id: { type: 'string', description: 'Configured model UUID (models[].id from provider_list).' },
    model_ref: { type: 'string', description: 'Model display name or wire model id substring; must uniquely match one configured model.' },
  }, ['slot']),
  execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
    const obj: JsonObject = isObj(input) ? input : {};
    const slot: string = trimmedString(obj, 'slot').toLowerCase();
    if (slot.length === 0) return failure('missing_slot', 'slot is required.');
    try {
      const providers: ProviderSetting[] = await deps.port.loadProviders();
      const resolved: ModelSlotResolution = resolveModelSlotTarget(
        providers, slot, trimmedString(obj, 'model_id'), trimmedString(obj, 'model_ref'));
      if (resolved.kind === 'error' || resolved.candidate === undefined) {
        return failure(resolved.code ?? 'error', resolved.message ?? 'Unable to resolve model', resolved.candidates);
      }
      const provider: ProviderSetting = resolved.candidate.provider;
      const model: ProviderModel = resolved.candidate.model;
      await deps.port.writeSlot(slot, provider.id, model);
      const out: JsonObject = {
        tool: 'settings_set_model_slot', status: 'ok', slot,
        model_id: model.id, model_wire_id: model.modelId, display_name: label(model),
        provider_id: provider.id, provider_name: provider.name,
        effective: 'next_turn',
        message: 'Saved. The current reply keeps its model; the new model is used from the next turn '
          + '(slot chat also updates the current assistant\'s bound model when it has one).',
      };
      if (CHAT_ROLE_SLOTS.includes(slot) && !model.abilities.includes('tool')) {
        out['warning'] = 'This model is not marked as supporting tool calls; from the next turn the agent '
          + 'will have no tools (including this one) until the user switches models in settings.';
      }
      return textResult(out);
    } catch (error) {
      return failure('write_failed', error instanceof Error ? error.message : 'Unable to save model slot');
    }
  },
});
