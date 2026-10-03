import { DEFAULT_AUTO_MODEL_ID } from '@amber/chat-domain';
import type { TaskModelReference } from '@amber/chat-domain';
import {
  TASK_DEEPREAD_MODEL_KEY, readTaskModelReference, saveTaskModelPair, saveTaskModelId,
  loadDeepReadModelOptions, resolveChatProviderChoice, resolveConfiguredTaskModel,
} from '../di/AppContainer.ets';
import type { ChatProviderChoice, DeepReadModelOption } from '../di/AppContainer.ets';

export interface DeepReadModelSelectionState {
  reference: TaskModelReference;
  options: DeepReadModelOption[];
  label: string;
  detail: string;
  available: boolean;
}

const choiceName = (choice: ChatProviderChoice): string =>
  `${choice.provider.name} / ${choice.model.displayName || choice.model.modelId}`;

export const loadDeepReadModelSelection = async (): Promise<DeepReadModelSelectionState> => {
  const reference: TaskModelReference = await readTaskModelReference(TASK_DEEPREAD_MODEL_KEY);
  const options: DeepReadModelOption[] = await loadDeepReadModelOptions();
  if (reference.kind === 'auto') {
    const choice: ChatProviderChoice | null = await resolveChatProviderChoice();
    return {
      reference, options, label: '跟随当前模型', available: choice !== null,
      detail: choice === null ? '暂无可用模型，请先配置模型服务商。'
        : `当前：${choiceName(choice)}。每轮任务开始时读取最新选择。`,
    };
  }
  const choice: ChatProviderChoice | null = await resolveConfiguredTaskModel(reference, 'chat');
  const selected: DeepReadModelOption | undefined = options.find((option: DeepReadModelOption): boolean =>
    option.modelId === (reference.kind === 'fixed' ? reference.pair.modelId : reference.modelId)
      && (reference.kind !== 'fixed' || option.providerId === reference.pair.providerId));
  const name: string = selected !== undefined ? `${selected.providerLabel} / ${selected.label}`
    : choice !== null ? choiceName(choice) : '所选模型';
  return {
    reference, options, label: choice === null ? `${name}（不可用）` : name,
    detail: choice === null ? '固定模型当前不可用，请修复服务商配置或重新选择。'
      : `固定使用此服务商与模型；当前请求服务商：${choice.provider.name}。`,
    available: choice !== null,
  };
};

export const saveDeepReadModelSelection = async (
  option: DeepReadModelOption | null,
): Promise<TaskModelReference> => {
  if (option === null) {
    await saveTaskModelId(TASK_DEEPREAD_MODEL_KEY, DEFAULT_AUTO_MODEL_ID);
    return { kind: 'auto' };
  }
  if (!option.available) throw new Error('此模型当前不可用，请先检查服务商配置');
  const reference: TaskModelReference = {
    kind: 'fixed', pair: { providerId: option.providerId, modelId: option.modelId },
  };
  await saveTaskModelPair(TASK_DEEPREAD_MODEL_KEY, reference.pair);
  return reference;
};
