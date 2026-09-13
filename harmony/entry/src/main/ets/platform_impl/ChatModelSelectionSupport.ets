// ChatModelSelectionSupport — UI-F34「有效模型」唯一 resolver / 写入口
//
// 根因(UI-F34):运行时 resolveChatProviderChoice(assistant) 优先 assistant.chatModelId,
// 但顶部模型菜单(TopModelMenu)与 ChatModelPickerPage 只读写全局键
// chat_selected_provider_id/model_id → 绑定 chatModelId 的助手会话里,菜单的
// 「当前」标记、上下文窗口、推理档位都用全局模型显示,切换也只写全局,与发送实际
// 使用的助手绑定模型不一致。
//
// 本文件不新建第二套解析:resolveEffectiveChatChoice 直接委托既有
// AppContainer.resolveChatProviderChoice(assistant) —— 与 resolveChatRuntime 发送路径
// 消费的就是同一个 resolver,展示与请求天然同源。
//   - 传入 assistantId 且命中助手:按该助手解析(chatModelId 非空优先,未绑定/绑定
//     模型失效时 resolveChatProviderChoice 内部回落全局,与运行时一致)。
//   - 未传 assistantId / 助手不存在:全局选择。
//
// 写入口 saveEffectiveChatChoice 与读侧同源:
//   - 先校验所选 {providerId, modelId} 命中「enabled + auth 可用」provider 下 type=chat
//     且内部 UUID 非空的真实模型;不存在/损坏/不可用 → reject(不写坏数据,不新造 id)。
//   - 助手已绑定(chatModelId 非空)→ 经 mutateAssistant 在 assistants 键共享锁内把绑定
//     改成所选模型的内部 UUID(ProviderModel.id),而非人类可读 model.modelId 字符串。
//   - 显式传 assistantId 但助手不存在 / 锁内已被删除 → reject(不得降级写全局,
//     否则会污染其它未绑定会话)。
//   - 助手未绑定 / 未传 assistantId → 写全局选择键(既有语义,本计划明确接受)。
//   - clearEffectiveChatChoice → 清除助手绑定(回到跟随全局选择)。
import type { Assistant, ProviderModel } from '@amber/chat-domain';
import { hasUsableAuth, loadProviders, patchAssistant } from '@amber/chat-domain';
import {
  getChatAssistants, getChatKvStore, resolveChatProviderChoice, saveChatModelSelection,
} from '../di/AppContainer.ets';
import type { ChatModelSelection, ChatProviderChoice } from '../di/AppContainer.ets';
import { mutateAssistant } from './AssistantMutation.ets';

const findAssistant = async (assistantId: string): Promise<Assistant | null> => {
  if (assistantId.length === 0) return null;
  const assistants: Assistant[] = await getChatAssistants();
  for (const a of assistants) {
    if (a.id === assistantId) return a;
  }
  return null;
};

const assistantHasBinding = (assistant: Assistant | null): boolean =>
  assistant !== null && assistant.chatModelId !== null
    && assistant.chatModelId.trim().length > 0;

// 有效模型选择(读)。签名与 ChatPage 展示/发送门共用;不得在别处再造 resolver。
export const resolveEffectiveChatChoice = async (
  assistantId?: string,
): Promise<ChatProviderChoice | null> => {
  if (assistantId === undefined || assistantId.length === 0) {
    return resolveChatProviderChoice();
  }
  const assistant: Assistant | null = await findAssistant(assistantId);
  if (assistant === null) return resolveChatProviderChoice();
  return resolveChatProviderChoice(assistant);
};

// 助手是否处于「绑定模型」态(用于展示/提供「跟随全局选择」清除入口)。
export const hasEffectiveChatBinding = async (
  assistantId?: string,
): Promise<boolean> => {
  if (assistantId === undefined || assistantId.length === 0) return false;
  return assistantHasBinding(await findAssistant(assistantId));
};

// 所选 {providerId, modelId(人类可读)} 校验:provider 必须 enabled 且 auth 可用,
// 且存在 modelId 命中、type=chat 的真实模型。不存在/不可用 → null(保存方拒绝)。
const resolveSelectableModel = async (
  selection: ChatModelSelection,
): Promise<ProviderModel | null> => {
  const providers = await loadProviders(getChatKvStore());
  if (providers === null) return null;
  for (const p of providers) {
    if (p.id !== selection.providerId) continue;
    if (!hasUsableAuth(p)) return null;
    for (const m of p.models) {
      if (m.modelId !== selection.modelId || m.type !== 'chat') continue;
      // 内部 UUID 为空的损坏模型不可绑定:写入空串等于「无绑定」,且
      // resolveChatProviderChoice 无法反查 —— 直接拒绝,绝不新造 id
      // (新造 id 会让其它引用该助手绑定的路径全部失效)。
      if (m.id.trim().length === 0) return null;
      return m;
    }
    return null;
  }
  return null;
};

// 有效模型选择(写)。reject 语义:模型不存在/损坏/不可用,或显式指定但助手不存在
// → throw;调用方需 catch 并提示。
// 助手有绑定(chatModelId 非空)→ 锁内改成所选模型的内部 UUID id。
// 助手未绑定 → 按既有语义写全局选择键(本计划明确接受,非缺陷)。
export const saveEffectiveChatChoice = async (
  selection: ChatModelSelection, assistantId?: string,
): Promise<void> => {
  const model: ProviderModel | null = await resolveSelectableModel(selection);
  if (model === null) {
    throw new Error(`模型不存在或不可用:${selection.providerId}/${selection.modelId}`);
  }
  if (assistantId !== undefined && assistantId.length > 0) {
    const assistant: Assistant | null = await findAssistant(assistantId);
    if (assistant === null) {
      // 显式指定助手却不存在:不得降级写全局 —— 那会把「该会话的绑定意图」写到
      // 全局选择,污染其它未绑定会话(且无法回滚)。抛出清晰错误交由调用方提示。
      throw new Error(`助手不存在,模型绑定未保存:${assistantId}`);
    }
    if (assistantHasBinding(assistant)) {
      const next: Assistant | null = await mutateAssistant(assistantId,
        (cur: Assistant): Assistant => patchAssistant(cur, { chatModelId: model.id }));
      if (next === null) {
        // 锁内发现助手已被删除:同样不得 fallback 写全局,抛出清晰错误。
        throw new Error(`助手已删除,模型绑定未保存:${assistantId}`);
      }
      return;
    }
    // 助手未绑定:沿原有全局语义(chatModelId 为空 → 跟随全局选择)
  }
  await saveChatModelSelection(selection);
};

// 清除助手模型绑定 → 会话回到「跟随全局选择」。未传/无绑定为幂等空操作。
export const clearEffectiveChatChoice = async (assistantId?: string): Promise<void> => {
  if (assistantId === undefined || assistantId.length === 0) return;
  await mutateAssistant(assistantId,
    (cur: Assistant): Assistant => patchAssistant(cur, { chatModelId: null }));
};
