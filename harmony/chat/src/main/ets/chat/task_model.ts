// task_model — 任务模型解析链(D-087)
//
// Android 基准:
//   - DefaultProviders.kt:95 DEFAULT_AUTO_MODEL_ID = b7055fb4-39f9-4042-a88a-0d80ed76cf08
//   - PreferencesStore.kt:479-481 resolveTaskChatModel(modelId) =
//     findModelById(modelId) ?: getCurrentChatModel()
//   - MemoryExtractor.kt:185-195 resolveMemoryModel(worker 二支 + 外层 ?: chat)
//   - MemoryDreamPlanner.kt:102-120 resolveDaydreamModel(daydream 二支 + worker 二支
//     + else chat;外层 ?: compress ?: chat)
//   - MemoryModels.kt:138-141 worker 字段默认(modelId/daydreamModelId = AUTO,
//     followCompressModel/daydreamFollowCompressModel = true)
//   - AiAuxiliaryGenerator.kt:41/:87 title/suggestion = resolveTaskChatModel(titleModelId
//     /suggestionModelId) ?: return(单次解析,无链)
//   - ConversationContextEngine.kt:408-410 compress = model ?:
//     resolveTaskChatModel(compressModelId) ?: error
// 说明:本模块只含纯 id 选择链;id → Model 的解析(findModelById ?: 当前 chat 模型)
//   属平台侧(providers 存储),由 entry 实现。

import type { TaskModelReference } from './task_model_reference.ts';

export const DEFAULT_AUTO_MODEL_ID: string = 'b7055fb4-39f9-4042-a88a-0d80ed76cf08';

// MemoryWorkerSetting 任务模型字段子集(MemoryModels.kt:138-141)
export interface TaskModelWorkerGate {
  modelId: string;
  providerId?: string | null;
  followCompressModel: boolean;
  daydreamModelId: string;
  daydreamProviderId?: string | null;
  daydreamFollowCompressModel: boolean;
}

export const DEFAULT_TASK_MODEL_WORKER_GATE: TaskModelWorkerGate = Object.freeze({
  modelId: DEFAULT_AUTO_MODEL_ID,
  followCompressModel: true,
  daydreamModelId: DEFAULT_AUTO_MODEL_ID,
  daydreamFollowCompressModel: true,
});

// resolveMemoryModel when 链(MemoryExtractor.kt:186-193)— 选单个候选 id;
//   外层 `?: resolveTaskChatModel(chatModelId)` → 调用方在首选解析失败后
//   再以 chatModelId 解析一次(候选列表 = [pick, chatModelId])
export const pickMemoryWorkerModelId = (
  worker: TaskModelWorkerGate, compressModelId: string, chatModelId: string,
): string => {
  if (worker.modelId !== DEFAULT_AUTO_MODEL_ID) return worker.modelId;
  if (worker.followCompressModel) return compressModelId;
  return chatModelId;
};

// resolveMemoryModel 完整候选序(含外层 ?: 回退)
export const memoryWorkerModelCandidates = (
  worker: TaskModelWorkerGate, compressModelId: string, chatModelId: string,
): string[] => [pickMemoryWorkerModelId(worker, compressModelId, chatModelId), chatModelId];

// resolveDaydreamModel when 链(MemoryDreamPlanner.kt:104-117)— 五支;
//   外层 `?: compress ?: chat` → 候选列表 = [pick, compressModelId, chatModelId]
export const pickDaydreamModelId = (
  worker: TaskModelWorkerGate, compressModelId: string, chatModelId: string,
): string => {
  if (worker.daydreamModelId !== DEFAULT_AUTO_MODEL_ID) return worker.daydreamModelId;
  if (worker.daydreamFollowCompressModel) return compressModelId;
  if (worker.modelId !== DEFAULT_AUTO_MODEL_ID) return worker.modelId;
  if (worker.followCompressModel) return compressModelId;
  return chatModelId;
};

// resolveDaydreamModel 完整候选序(含外层两级 ?: 回退)
export const daydreamModelCandidates = (
  worker: TaskModelWorkerGate, compressModelId: string, chatModelId: string,
): string[] => [
  pickDaydreamModelId(worker, compressModelId, chatModelId),
  compressModelId,
  chatModelId,
];

const configuredWorkerReference = (modelId: string, providerId: string | null): TaskModelReference =>
  providerId === null ? { kind: 'legacy', modelId } : { kind: 'fixed', pair: { providerId, modelId } };

// Preserve the original fallback order without losing each candidate's provider identity.
export const memoryWorkerModelReferences = (
  worker: TaskModelWorkerGate, compressRef: TaskModelReference, chatRef: TaskModelReference,
): TaskModelReference[] => {
  let selected: TaskModelReference = chatRef;
  if (worker.modelId !== DEFAULT_AUTO_MODEL_ID) {
    selected = configuredWorkerReference(worker.modelId, worker.providerId ?? null);
  } else if (worker.followCompressModel) selected = compressRef;
  return [selected, chatRef];
};

export const daydreamModelReferences = (
  worker: TaskModelWorkerGate, compressRef: TaskModelReference, chatRef: TaskModelReference,
): TaskModelReference[] => {
  let selected: TaskModelReference = chatRef;
  if (worker.daydreamModelId !== DEFAULT_AUTO_MODEL_ID) {
    selected = configuredWorkerReference(worker.daydreamModelId, worker.daydreamProviderId ?? null);
  } else if (worker.daydreamFollowCompressModel) selected = compressRef;
  else if (worker.modelId !== DEFAULT_AUTO_MODEL_ID) {
    selected = configuredWorkerReference(worker.modelId, worker.providerId ?? null);
  } else if (worker.followCompressModel) selected = compressRef;
  return [selected, compressRef, chatRef];
};
