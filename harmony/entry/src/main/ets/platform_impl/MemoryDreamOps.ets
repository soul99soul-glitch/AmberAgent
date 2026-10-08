// MemoryDreamOps — Memory 页 dream/候选操作 deps 组装(D-088)
// Android 锚点:
//   SettingAgentMemoryVM.kt:143-204(planDream/applyDreamPlan/dismissDreamPlan/
//     acceptCandidate/ignoreCandidate/ignoreLowConfidenceCandidates)
//   MemoryDreamPlanner.kt:74-100(planWithModel:generateText = model +
//     reasoningLevel=worker.daydreamReasoningLevel)/:102-120(resolveDaydreamModel
//     五分支链 → D-087 task_model.daydreamModelReferences 已移植)
// 存储面 = MemoryStore.ets getMemoryDreamStoreOps/getMemoryDreamPlanStore;
// 模型面 = AppContainer 任务模型链(daydreamModelReferences → resolveTaskModelReferenceChain)。
// 偏差(延续 D-087 登记):resolveDaydreamModel 的 findProvider 失败支在 entry
//   不可达(choice 捆绑 provider+model,choice null ⇔ Android 任一 ?: return null)。
// D-103:daydreamReasoningLevel 已接线(快照解析默认 HIGH;Android 无设置 UI,
//   仅数据链)。
import { hilog } from '@kit.PerformanceAnalysisKit';
import type {
  MemoryCandidate, MemoryCandidateOpsDeps, MemoryDaydreamModelResolution,
  MemoryDreamApplyOpDeps, MemoryDreamPlanOpDeps, MemoryDreamPlannerDeps,
  MessageChunk, OpenAIChatApi, TaskModelWorkerGate,
} from '@amber/chat-domain';
import {
  daydreamModelReferences, makeTextGenerationParams, makeUserMessage, toChatModel,
  toText,
} from '@amber/chat-domain';
import type { ChatProviderChoice } from '../di/AppContainer.ets';
import {
  prepareProviderApi, currentChatModelReference, getChatKvStore, readTaskModelReference, TASK_COMPRESS_MODEL_KEY,
  resolveTaskModelReferenceChain,
} from '../di/AppContainer.ets';
import type { AgentRuntimeSnapshot, MemoryWorkerGateSnapshot } from './AgentRuntimePrefs.ets';
import { loadAgentRuntimeSnapshot } from './AgentRuntimePrefs.ets';
import type { MemoryDreamStoreOps } from './MemoryStore.ets';
import { getMemoryDreamPlanStore, getMemoryDreamStoreOps } from './MemoryStore.ets';

const DOMAIN: number = 0x0001;
const TAG: string = 'Chat.MemoryDreamOps';

// 快照 → task_model.TaskModelWorkerGate(D-087 链入参四字段)
const toTaskGate = (w: MemoryWorkerGateSnapshot): TaskModelWorkerGate => ({
  modelId: w.modelId,
  providerId: w.providerId,
  followCompressModel: w.followCompressModel,
  daydreamModelId: w.daydreamModelId,
  daydreamProviderId: w.daydreamProviderId,
  daydreamFollowCompressModel: w.daydreamFollowCompressModel,
});

export const resolveMemoryDaydreamChoice = async (worker: MemoryWorkerGateSnapshot): Promise<ChatProviderChoice | null> => {
  const compressReference = await readTaskModelReference(TASK_COMPRESS_MODEL_KEY);
  const chatRef = await currentChatModelReference();
  return resolveTaskModelReferenceChain(daydreamModelReferences(toTaskGate(worker), compressReference, chatRef));
};

// planDream 编排 deps(VM :143-168 + Planner.kt:74-100)
// daydream 模型解析在首次调用时求值并缓存(与 ChatPage extraction 闭包同策略,
//   D-087 登记:运行期改设置需重进页面生效)
export const loadMemoryDreamPlanOpDeps = async (): Promise<MemoryDreamPlanOpDeps> => {
  const snapshot: AgentRuntimeSnapshot = await loadAgentRuntimeSnapshot(getChatKvStore());
  const worker: MemoryWorkerGateSnapshot = snapshot.memoryWorker;
  const ops: MemoryDreamStoreOps = getMemoryDreamStoreOps();
  let choicePromise: Promise<ChatProviderChoice | null> | null = null;
  const resolveChoice = (): Promise<ChatProviderChoice | null> => {
    if (choicePromise === null) {
      choicePromise = resolveMemoryDaydreamChoice(worker);
    }
    return choicePromise;
  };
  const planner: MemoryDreamPlannerDeps = {
    worker: {
      enabled: worker.enabled,
      dreamMaintenanceEnabled: worker.dreamMaintenanceEnabled,
      dreamModelEnabled: worker.dreamModelEnabled,
    },
    getAllRecords: ops.getAllRecords,
    getPendingCandidates: ops.getPendingCandidates,
    // resolveDaydreamModel(Planner.kt:102-120)— 链解析空 → unavailable
    //   (Android model/provider 任一空 → null,鸿蒙合并为一支,D-085f 已钉)
    resolveDaydreamModel: async (): Promise<MemoryDaydreamModelResolution> => {
      const choice: ChatProviderChoice | null = await resolveChoice();
      return choice === null
        ? { kind: 'unavailable' }
        : { kind: 'ok', modelId: choice.model.modelId };
    },
    generateText: async (prompt: string): Promise<string> => {
      const choice: ChatProviderChoice | null = await resolveChoice();
      if (choice === null) throw new Error('no daydream model');
      const headers: Record<string, string> = {};
      for (const header of choice.model.customHeaders) {
        const name: string = header.name.trim();
        if (name.length > 0) headers[name] = header.value.trim();
      }
      const api: OpenAIChatApi = await prepareProviderApi(choice.provider, headers);
      // TextGenerationParams(model, reasoningLevel=daydreamReasoningLevel)
      //   (Planner.kt:94-97)— D-103 接线(MemoryModels.kt:142 默认 HIGH)
      const chunk: MessageChunk = await api.generateText(
        [makeUserMessage(prompt)],
        makeTextGenerationParams({
          model: toChatModel(choice.model),
          reasoningLevel: worker.daydreamReasoningLevel,
          customBody: choice.model.customBodies,
        }),
      );
      // :98 response.choices.firstOrNull()?.message?.toText().orEmpty()
      const msg = chunk.choices.length > 0 ? chunk.choices[0].message : null;
      return msg !== null ? toText(msg) : '';
    },
    addEvent: ops.addEvent,
  };
  return { planner, planStore: getMemoryDreamPlanStore() };
};

// applyDreamPlan/dismissDreamPlan 编排 deps(VM :171-204)
export const loadMemoryDreamApplyOpDeps = (): MemoryDreamApplyOpDeps => {
  const ops: MemoryDreamStoreOps = getMemoryDreamStoreOps();
  return {
    applier: {
      getAllRecords: ops.getAllRecords,
      getAllCandidates: ops.getAllCandidates,
      upsertRecord: ops.upsertRecord,
      addMemory: ops.addMemory,
      updateCandidate: ops.updateCandidate,
      addEvent: ops.addEvent,
    },
    planStore: getMemoryDreamPlanStore(),
  };
};

// 候选操作面 deps(VM :95-137;存储面与 dream 同源)
export const getMemoryCandidateOpsDeps = (): MemoryCandidateOpsDeps => {
  const ops: MemoryDreamStoreOps = getMemoryDreamStoreOps();
  return {
    getAllCandidates: ops.getAllCandidates,
    addMemory: ops.addMemory,
    updateCandidate: ops.updateCandidate,
    addEvent: ops.addEvent,
  };
};

// 页面读取面:待审候选列表(status='pending' DESC,VM candidates flow 同序)
export const loadPendingMemoryCandidates = (): Promise<MemoryCandidate[]> =>
  getMemoryDreamStoreOps().getPendingCandidates();

// 操作失败上报(VM onFailure → operationMessage;调用处按常量格式化)
export const logMemoryDreamOpFailure = (where: string, e: Error): void => {
  hilog.warn(DOMAIN, TAG, '%{public}s failed: %{public}s', where, e.message);
};
