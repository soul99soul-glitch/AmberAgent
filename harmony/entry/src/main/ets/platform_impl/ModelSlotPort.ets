// ModelSlotPort — settings_set_model_slot 的 entry 持久化端口
//
// 各槽的落点(与设置页 / 顶部模型菜单同源,不另造存储):
//   chat             → 全局选择键 chat_selected_provider_id / chat_selected_model_id
//                      (AppContainer.saveChatModelSelection;modelId 存线上 model.modelId)
//   assistant_chat   → 当前助手 chatModelId = model.id(UUID),经 mutateAssistant 在
//                      assistants 键共享锁内改写
//   title/suggestion/compress/image_generation
//                    → TASK_*_MODEL_KEY,{providerId, modelId: model.id} 固定对
//                      (AppContainer.saveTaskModelPair,同 ChatTaskModelPickerPage.pick)
//   ocr              → OCR_MODEL_KEY,同上
// 说明:「chat」槽与顶部模型菜单同语义(saveEffectiveChatChoice):当前助手已绑定
//   chatModelId 时改写该绑定(否则 resolveChatProviderChoice 仍优先旧绑定,切换不生效);
//   未绑定时写全局选择键。
import type { ProviderModel, ProviderSetting, TaskModelPair } from '@amber/chat-domain';
import { loadProviders, patchAssistant } from '@amber/chat-domain';
import type { Assistant, ModelSlotPort } from '@amber/chat-domain';
import {
  getChatKvStore, saveTaskModelPair,
  TASK_TITLE_MODEL_KEY, TASK_SUGGESTION_MODEL_KEY, TASK_COMPRESS_MODEL_KEY, TASK_IMAGE_MODEL_KEY,
} from '../di/AppContainer.ets';
import type { ChatModelSelection } from '../di/AppContainer.ets';
import { mutateAssistant } from './AssistantMutation.ets';
import { saveEffectiveChatChoice } from './ChatModelSelectionSupport.ets';
import { sharedKvMutex } from './AtomicJsonFile.ets';
import { OCR_MODEL_KEY } from './OcrSupport.ets';

const MODEL_SLOT_LOCK_KEY: string = 'model_slot_write';

const taskKeyForSlot = (slot: string): string => {
  if (slot === 'title') return TASK_TITLE_MODEL_KEY;
  if (slot === 'suggestion') return TASK_SUGGESTION_MODEL_KEY;
  if (slot === 'compress') return TASK_COMPRESS_MODEL_KEY;
  if (slot === 'image_generation') return TASK_IMAGE_MODEL_KEY;
  if (slot === 'ocr') return OCR_MODEL_KEY;
  return '';
};

class EntryModelSlotPort implements ModelSlotPort {
  private readonly assistantId: string;

  constructor(assistantId: string) {
    this.assistantId = assistantId;
  }

  async loadProviders(): Promise<ProviderSetting[]> {
    return (await loadProviders(getChatKvStore())) ?? [];
  }

  async writeSlot(slot: string, providerId: string, model: ProviderModel): Promise<void> {
    await sharedKvMutex.withLock(MODEL_SLOT_LOCK_KEY, async (): Promise<void> => {
      if (slot === 'chat') {
        const selection: ChatModelSelection = { providerId: providerId, modelId: model.modelId };
        await saveEffectiveChatChoice(selection, this.assistantId.length > 0 ? this.assistantId : undefined);
        return;
      }
      if (slot === 'assistant_chat') {
        if (this.assistantId.length === 0) throw new Error('当前没有可绑定的助手');
        const next: Assistant | null = await mutateAssistant(this.assistantId,
          (cur: Assistant): Assistant => patchAssistant(cur, { chatModelId: model.id }));
        if (next === null) throw new Error('助手不存在,模型绑定未保存');
        return;
      }
      const key: string = taskKeyForSlot(slot);
      if (key.length === 0) throw new Error('不支持的模型槽');
      const pair: TaskModelPair = { providerId: providerId, modelId: model.id };
      await saveTaskModelPair(key, pair);
    });
  }
}

export const createEntryModelSlotPort = (assistantId: string): ModelSlotPort =>
  new EntryModelSlotPort(assistantId);
