// Task slots store the provider container ID and model configuration UUID together.
import type { JsonObject, JsonValue } from './json.ts';
import type { ModelType, ProviderModel, ProviderSetting } from './provider_settings.ts';
import { DEFAULT_AUTO_MODEL_ID } from './task_model.ts';

export interface TaskModelPair {
  providerId: string;
  modelId: string;
}

export interface TaskModelAutoReference { kind: 'auto'; }
export interface TaskModelLegacyReference { kind: 'legacy'; modelId: string; }
export interface TaskModelFixedReference { kind: 'fixed'; pair: TaskModelPair; }
export type TaskModelReference = TaskModelAutoReference | TaskModelLegacyReference | TaskModelFixedReference;

export interface TaskProviderModel {
  provider: ProviderSetting;
  model: ProviderModel;
}

export const taskModelReferenceFromId = (
  modelId: string, providerId: string | null = null,
): TaskModelReference => {
  if (modelId.length === 0 || modelId === DEFAULT_AUTO_MODEL_ID) return { kind: 'auto' };
  if (providerId === null) return { kind: 'legacy', modelId };
  return { kind: 'fixed', pair: { providerId, modelId } };
};

export const decodeTaskModelReference = (raw: string | null): TaskModelReference => {
  if (raw === null || raw.length === 0 || raw === DEFAULT_AUTO_MODEL_ID) return { kind: 'auto' };
  const text = raw.trim();
  if (!text.startsWith('{') && !text.startsWith('[')) return { kind: 'legacy', modelId: raw };
  let value: JsonValue;
  try { value = JSON.parse(text) as JsonValue; }
  catch (_) { throw new Error('任务模型配置无效'); }
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('任务模型配置无效');
  }
  const pair: JsonObject = value;
  const providerId = pair['providerId'];
  const modelId = pair['modelId'];
  if (typeof providerId !== 'string' || providerId.trim().length === 0 ||
    typeof modelId !== 'string' || modelId.trim().length === 0) {
    throw new Error('任务模型配置无效');
  }
  return { kind: 'fixed', pair: { providerId, modelId } };
};

export const encodeTaskModelReference = (ref: TaskModelReference): string => {
  if (ref.kind === 'auto') return DEFAULT_AUTO_MODEL_ID;
  if (ref.kind === 'legacy') return ref.modelId;
  if (ref.pair.providerId.trim().length === 0 || ref.pair.modelId.trim().length === 0) {
    throw new Error('任务模型配置无效');
  }
  return JSON.stringify(ref.pair);
};

export const taskModelReferenceId = (ref: TaskModelReference): string => {
  if (ref.kind === 'auto') return DEFAULT_AUTO_MODEL_ID;
  return ref.kind === 'legacy' ? ref.modelId : ref.pair.modelId;
};

// Resolve raw configuration only. Authentication and providerOverwrite remain in Entry's factory.
export const findTaskProviderModel = (
  providers: ProviderSetting[], ref: TaskModelReference, modelType: ModelType | null = null,
): TaskProviderModel | null => {
  if (ref.kind === 'auto') return null;
  const modelId = taskModelReferenceId(ref);
  for (const provider of providers) {
    if (ref.kind === 'fixed' && provider.id !== ref.pair.providerId) continue;
    const model = provider.models.find((candidate: ProviderModel): boolean => candidate.id === modelId);
    if (model !== undefined) {
      return modelType === null || model.type === modelType ? { provider, model } : null;
    }
    if (ref.kind === 'fixed') return null;
  }
  return null;
};
