// MemoryDreamOps — Memory 页 dream/候选操作 deps 组装(D-088)
// Android 锚点:
//   SettingAgentMemoryVM.kt:143-204(planDream/applyDreamPlan/dismissDreamPlan/
//     acceptCandidate/ignoreCandidate/ignoreLowConfidenceCandidates)
//   MemoryDreamPlanner.kt:74-100(planWithModel:generateText = model +
//     reasoningLevel=worker.daydreamReasoningLevel)/:102-120(resolveDaydreamModel
//     五分支链 → D-087 task_model.daydreamModelCandidates 已移植)
// 存储面 = MemoryStore.ets getMemoryDreamStoreOps/getMemoryDreamPlanStore;
// 模型面 = AppContainer 任务模型链(daydreamModelCandidates → resolveTaskModelChain)。
// 偏差(延续 D-087 登记):daydreamReasoningLevel 未移植 → generateText 最小参
//   model-only(Android 第二参缺省 ReasoningLevel 默认值差异,P1 随设置面);
//   resolveDaydreamModel 的 findProvider 失败支在 entry 不可达(choice 捆绑
//   provider+model,choice null ⇔ Android 任一 ?: return null)。
import { hilog } from '@kit.PerformanceAnalysisKit';
import type {
  MemoryCandidate, MemoryCandidateOpsDeps, MemoryDaydreamModelResolution,
  MemoryDreamApplyOpDeps, MemoryDreamPlanOpDeps, MemoryDreamPlannerDeps,
  MessageChunk, OpenAIChatApi, TaskModelWorkerGate,
} from '@amber/chat-domain';
import {
  daydreamModelCandidates, makeTextGenerationParams, makeUserMessage, toChatModel,
  toText,
} from '@amber/chat-domain';
import type { ChatProviderChoice, TaskModelIds } from '../di/AppContainer.ets';
import {
  buildProviderApi, currentChatModelUuid, getChatKvStore, loadTaskModelIds,
  resolveTaskModelChain,
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
  followCompressModel: w.followCompressModel,
  daydreamModelId: w.daydreamModelId,
  daydreamFollowCompressModel: w.daydreamFollowCompressModel,
});

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
      choicePromise = (async (): Promise<ChatProviderChoice | null> => {
        const ids: TaskModelIds = await loadTaskModelIds();
        const chatUuid: string = await currentChatModelUuid();
        return resolveTaskModelChain(
          daydreamModelCandidates(toTaskGate(worker), ids.compressModelId, chatUuid));
      })();
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
      const api: OpenAIChatApi = buildProviderApi(choice.provider, {});
      // TextGenerationParams(model, reasoningLevel=daydreamReasoningLevel)
      //   (Planner.kt:94-97)— daydreamReasoningLevel 未移植(P1),最小参 model-only
      const chunk: MessageChunk = await api.generateText(
        [makeUserMessage(prompt)],
        makeTextGenerationParams({ model: toChatModel(choice.model) }),
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
