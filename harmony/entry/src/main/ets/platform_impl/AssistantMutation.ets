// AssistantMutation — assistants KV 的串行 read-modify-write(RMW)助手
//
// 根因(F35):`assistants` 键此前无共享锁。ChatAssistantExtensionsPage /
// ChatAssistantLocalToolsPage / ChatAssistantMcpPage 的 toggle 均以页面里
// 尚未更新的 `this.assistant` 快照算 next,await load 后整份 put —— 两次快速
// switch 的 load→save 交错会互相覆盖(丢失更新)。ChatPage 的 enabledSkills
// 切换与 reasoning/模型绑定同理。
//
// 修复:复用 AtomicJsonFile 的单例 `sharedKvMutex`,以 ASSISTANTS_KEY 为键把
// 「load 最新 → 在锁内 transform 目标助手 → 覆盖写回该条」整体串行化。锁内读的
// 一定是最新列表,绝不基于旧 snapshot 写回整表。
//
// 语义:锁即事务,mutator 抛错则不落盘并向上抛;未找到目标助手 → 返回 null
// (调用方可判定),不新建、不整表覆盖。
import type { Assistant, KeyValueStore } from '@amber/chat-domain';
import { ASSISTANTS_KEY, loadAssistants, saveAssistants } from '@amber/chat-domain';
import { sharedKvMutex } from './AtomicJsonFile.ets';
import { getChatKvStore } from '../di/AppContainer.ets';

// 指定 store 变体:便于单测/探针注入内存 KV。
// 在锁内读最新 assistants,对 id 命中的助手应用 mutator 后原位置回写。
// 返回落库后的最新 Assistant;目标不存在返回 null。
export const mutateAssistantInStore = (
  kv: KeyValueStore,
  assistantId: string,
  mutator: (current: Assistant) => Assistant,
): Promise<Assistant | null> =>
  sharedKvMutex.withLock(ASSISTANTS_KEY, async (): Promise<Assistant | null> => {
    const current: Assistant[] = (await loadAssistants(kv)) ?? [];
    const index: number = current.findIndex(
      (a: Assistant): boolean => a.id === assistantId);
    if (index < 0) return null;
    const next: Assistant = mutator(current[index]);
    const list: Assistant[] = current.slice();
    list[index] = next;
    await saveAssistants(kv, list);
    return next;
  });

// 生产入口:assistants 键的串行 mutation(锁内 load 最新再 transform 字段)。
// ChatAssistant* 页与 ChatPage 技能 toggle / 模型绑定统一走这一个入口,
// 保证所有写者共用同一把锁,不会互相覆盖。
export const mutateAssistant = (
  assistantId: string,
  mutator: (current: Assistant) => Assistant,
): Promise<Assistant | null> => mutateAssistantInStore(getChatKvStore(), assistantId, mutator);
