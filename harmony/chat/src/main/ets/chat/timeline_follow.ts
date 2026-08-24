// timeline_follow — 消息流「跟随底部」纯逻辑(零 SDK 依赖,可 node:test)
//
// 三态跟随机:
//   idle      未在跟随(无生成,或生成尚未触碰过跟随)
//   following 生成中自动贴底(每 chunk 更新后滚到底)
//   paused    用户上滑离开底部,暂停跟随 — 后续 chunk / 完成回调均不抢回
//
// 恢复:用户再次滚动到末端(transitionOnUserScroll atEnd=true)
//   或点击「跳到底部」按钮(transitionOnJumpToBottom)。
//
// ArkUI 侧注意:Scroller.isAtEnd() 在流式追加后的同一帧可能滞后(内容高度
//   尚未重排),页面层需用「最近一次程序化贴底且用户未再离开」做近似,
//   见 ChatPage.ets atBottomApprox 注释。本模块仅收布尔 atEnd,不含 UI 依赖。

/** 跟随模式 */
export type FollowMode = 'idle' | 'following' | 'paused';

/** isCloseEnoughToBottom 的默认容差(vp):距底 24vp 内视为贴底 */
export const FOLLOW_BOTTOM_TOLERANCE_VP: number = 24;

/**
 * 用户主动滚动事件(ScrollState.Scroll / Fling)时的状态转移。
 * 程序化 scrollEdge 触发的是 Idle 而非 Scroll/Fling,因此本函数只会被
 * 真实用户滚动驱动(手指拖动、滚动条、滚轮),不会误暂停程序化贴底。
 *
 * @param atEnd 当前是否位于末端(页面层近似)
 * @param generating 是否正处于生成流程(sending/streamingTail 活跃)
 */
export function transitionOnUserScroll(
  mode: FollowMode,
  atEnd: boolean,
  generating: boolean,
): FollowMode {
  if (atEnd) {
    // 用户已滚回末端 → 恢复跟随(生成中)或回到未跟随
    return generating ? 'following' : 'idle';
  }
  // 离开底部:正在跟随或生成中 → 暂停;否则维持 idle
  if (mode === 'following') return 'paused';
  return generating ? 'paused' : 'idle';
}

/**
 * 流式 chunk 更新后的状态转移(idle 且贴底 → 开始跟随;idle 且离底 →
 * 生成中用户在上方 → 暂停;paused 恒保持,不抢回)。
 */
export function transitionOnStreamUpdate(mode: FollowMode, atEnd: boolean): FollowMode {
  if (mode === 'paused') return 'paused';
  if (mode === 'following') return 'following';
  return atEnd ? 'following' : 'paused';
}

/**
 * 生成完成后的状态转移:following → idle(本轮跟随结束);
 * paused 保持(用户仍在阅读上方内容);idle 保持。
 */
export function transitionOnGenerateEnd(mode: FollowMode): FollowMode {
  return mode === 'following' ? 'idle' : mode;
}

/**
 * 点击「跳到底部」或主动恢复跟随:生成中 → following,否则回 idle。
 */
export function transitionOnJumpToBottom(generating: boolean): FollowMode {
  return generating ? 'following' : 'idle';
}

/** 核心判定:当前模式是否允许滚动到底(统一规则) */
function shouldAutoScrollToBottom(mode: FollowMode, atEnd: boolean): boolean {
  if (mode === 'paused') return false; // 用户暂停,不抢回
  if (mode === 'following') return true; // 持续贴底
  return atEnd; // idle:仅在用户本来就贴底时跟随
}

/** 生成中(流式 chunk 更新后)是否可滚动到底 */
export function shouldScrollOnStreamUpdate(mode: FollowMode, atEnd: boolean): boolean {
  return shouldAutoScrollToBottom(mode, atEnd);
}

/** 生成结束时是否可滚动到底(跟随中收尾滚一次;暂停不抢) */
export function shouldScrollOnGenerateEnd(mode: FollowMode, atEnd: boolean): boolean {
  return shouldAutoScrollToBottom(mode, atEnd);
}

/** 内容追加(发送/排队入列后 rows 重建)是否可滚动到底 */
export function shouldScrollOnAppend(mode: FollowMode, atEnd: boolean): boolean {
  return shouldAutoScrollToBottom(mode, atEnd);
}

/**
 * 位置近似:offset 距 maxOffset 在容差内视为「贴底」。
 * 内容不足一屏(maxOffset<=0)天然贴底。maxOffset 由调用方提供
 * (页面层如无 contentSize,可退化为固定大值或直接用 isAtEnd)。
 */
export function isCloseEnoughToBottom(
  offset: number,
  maxOffset: number,
  toleranceVp: number = FOLLOW_BOTTOM_TOLERANCE_VP,
): boolean {
  if (maxOffset <= 0) return true;
  return offset >= maxOffset - toleranceVp;
}

/**
 * 解析首屏定位目标(router params 最小兼容):
 * - rawIndexParam 为合法非负整数 → 收敛到行索引(越界收敛到底部,-1)
 * - 否则 nodeIdParam 命中 rows 的 nodeId → 对应索引
 * - 均未命中/无目标 → -1(底部)
 *
 * @param rowNodeIds rows 的 nodeId 列表(顺序即渲染顺序)
 */
export function resolveInitialRowIndex(
  rawIndexParam: string,
  nodeIdParam: string,
  rowNodeIds: string[],
): number {
  if (rawIndexParam.length > 0) {
    const n: number = Number(rawIndexParam);
    if (Number.isInteger(n) && n >= 0) {
      if (rowNodeIds.length === 0) return -1;
      return n < rowNodeIds.length ? n : rowNodeIds.length - 1;
    }
  }
  if (nodeIdParam.length > 0) {
    for (let i = 0; i < rowNodeIds.length; i++) {
      if (rowNodeIds[i] === nodeIdParam) return i;
    }
    return -1;
  }
  return -1;
}
