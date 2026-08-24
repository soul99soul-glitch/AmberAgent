// timeline_load — 历史分页「窗口加载状态」纯逻辑(零 SDK 依赖,可 node:test)
//
// 对齐 Android ConversationTimelineLoadState + loadOlderTimelinePage
// (计划 §6.6 step 1 的状态四元组):
//   未初始化 → 加载中 → 已加载(尾窗)→ 已加载(历史页追加)→ fullyLoaded
//
// 本模块只收窗口数值(ConversationWindow 的映射参数),不含 RDB/UI 依赖:
//   - applyTailWindow:     会话打开后首个尾窗到达(initialized=true)
//   - beginLoadOlder:      用户上滑到顶触发历史加载(loadingOlder=true,防重入)
//   - applyOlderPage:      更早一页到达(合并 old/new 边界,可能 fullyLoaded)
//   - canLoadOlder:        加载门(initialized && !loadingOlder && !fullyLoaded)
//   - coversIndex:         目标 node 索引是否已被窗口覆盖(首屏跨界定位)
//   - nextOlderPageOffset: 下一页历史加载的绝对 offset(基于 oldestLoadedIndex)
//   - mergeOlderNodes:     去重合并更早行到现有序列(锚点补偿需知道前插数)

/** 历史窗口加载状态(Android ConversationTimelineLoadState 子集) */
export interface ConversationTimelineLoadState {
  /** 是否已装配首个窗口(会话打开后至少加载过一次) */
  initialized: boolean;
  /** 正在向上加载更早历史(防重入) */
  loadingOlder: boolean;
  /** 已加载到最旧节点,无更早历史可加载 */
  fullyLoaded: boolean;
  /** 已加载窗口中最旧节点的绝对 node 索引 */
  oldestLoadedIndex: number;
  /** 已加载 node 总数(窗口大小 / 展示用) */
  loadedNodeCount: number;
}

export const createInitialTimelineLoadState = (): ConversationTimelineLoadState => ({
  initialized: false,
  loadingOlder: false,
  fullyLoaded: false,
  oldestLoadedIndex: 0,
  loadedNodeCount: 0,
});

/**
 * 首个尾窗到达后的状态迁移。
 * fullyLoaded 判定 = 窗口已覆盖最旧节点(oldestLoadedIndex === 0),
 * 即整段会话(或空会话)都在窗口内。
 */
export function applyTailWindow(
  state: ConversationTimelineLoadState,
  oldestLoadedIndex: number,
  loadedCount: number,
  totalNodeCount: number,
): ConversationTimelineLoadState {
  return {
    initialized: true,
    loadingOlder: false,
    fullyLoaded: oldestLoadedIndex === 0,
    oldestLoadedIndex,
    loadedNodeCount: totalNodeCount === 0 ? 0 : loadedCount,
  };
}

/** 触发历史加载:非法/重复/已全量 → 原状态不变(防重入门) */
export function beginLoadOlder(state: ConversationTimelineLoadState): ConversationTimelineLoadState {
  if (!state.initialized || state.loadingOlder || state.fullyLoaded) return state;
  return { ...state, loadingOlder: true };
}

/** 更早一页到达后的状态迁移(loadedNodeCount 累加) */
export function applyOlderPage(
  state: ConversationTimelineLoadState,
  pageOldestLoadedIndex: number,
  pageLoadedCount: number,
): ConversationTimelineLoadState {
  return {
    initialized: true,
    loadingOlder: false,
    fullyLoaded: pageOldestLoadedIndex === 0,
    oldestLoadedIndex: pageOldestLoadedIndex,
    loadedNodeCount: state.loadedNodeCount + pageLoadedCount,
  };
}

/** 是否可以发起更早历史加载 */
export function canLoadOlder(state: ConversationTimelineLoadState): boolean {
  return state.initialized && !state.loadingOlder && !state.fullyLoaded;
}

/** 目标 node 索引是否已被已加载窗口覆盖(首屏跨页定位判定) */
export function coversIndex(state: ConversationTimelineLoadState, index: number): boolean {
  return state.initialized && index >= state.oldestLoadedIndex;
}

/** 下一页历史加载的绝对 offset(基于已加载窗口最旧索引,避免重复) */
export function nextOlderPageOffset(
  state: ConversationTimelineLoadState,
  pageSize: number,
): number {
  return Math.max(0, state.oldestLoadedIndex - Math.max(0, Math.floor(pageSize)));
}

/**
 * 去重合并更早行到现有序列(前插)。
 * 返回合并后序列;新行按顺序排前,重复 nodeId 跳过(offset 边界重叠安全)。
 */
export function mergeOlderNodes<T extends { nodeId: string }>(
  existing: T[],
  olderNodes: T[],
): T[] {
  const seen: Set<string> = new Set<string>();
  for (const row of existing) seen.add(row.nodeId);
  const merged: T[] = [];
  for (const row of olderNodes) {
    if (!seen.has(row.nodeId)) {
      merged.push(row);
      seen.add(row.nodeId);
    }
  }
  for (const row of existing) merged.push(row);
  return merged;
}
