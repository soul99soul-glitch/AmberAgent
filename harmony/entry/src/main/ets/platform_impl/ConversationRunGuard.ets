const activeRuns: Map<string, number> = new Map<string, number>();
const restoring: Set<string> = new Set<string>();
const restoredVersions: Map<string, number> = new Map<string, number>();

export const conversationRestoreVersion = (id: string): number => restoredVersions.get(id) ?? 0;

export const markConversationsRestored = (ids: string[]): void => {
  for (const id of ids) restoredVersions.set(id, conversationRestoreVersion(id) + 1);
};

export const beginConversationRun = (conversationId: string): (() => void) => {
  if (restoring.has(conversationId)) {
    throw new Error('该会话正在恢复，请稍后重试');
  }
  activeRuns.set(conversationId, (activeRuns.get(conversationId) ?? 0) + 1);
  let released: boolean = false;
  return (): void => {
    if (released) return;
    released = true;
    const remaining: number = (activeRuns.get(conversationId) ?? 1) - 1;
    if (remaining > 0) activeRuns.set(conversationId, remaining);
    else activeRuns.delete(conversationId);
  };
};

export const hasActiveConversationRuns = (conversationIds: string[]): boolean =>
  conversationIds.some((id: string): boolean => (activeRuns.get(id) ?? 0) > 0);

export const beginConversationRestore = (conversationIds: string[]): (() => void) => {
  for (const id of conversationIds) {
    if ((activeRuns.get(id) ?? 0) > 0 || restoring.has(id)) {
      throw new Error('目标会话仍在生成或恢复，请先停止任务再重试');
    }
  }
  for (const id of conversationIds) restoring.add(id);
  let released: boolean = false;
  return (): void => {
    if (released) return;
    released = true;
    for (const id of conversationIds) restoring.delete(id);
  };
};

// Precompaction may outlive the generation that launched it. Track its own
// lifetime so manual compaction and conversation restore cannot race its writes.
const activeCompactions: Map<string, number> = new Map<string, number>();

export const isConversationCompacting = (id: string): boolean => (activeCompactions.get(id) ?? 0) > 0;

export const beginConversationCompaction = (id: string): (() => void) => {
  const releaseRun: () => void = beginConversationRun(id);
  activeCompactions.set(id, (activeCompactions.get(id) ?? 0) + 1);
  let released: boolean = false;
  return (): void => {
    if (released) return;
    released = true;
    const remaining: number = (activeCompactions.get(id) ?? 1) - 1;
    if (remaining > 0) activeCompactions.set(id, remaining);
    else activeCompactions.delete(id);
    releaseRun();
  };
};
