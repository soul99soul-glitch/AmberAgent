// timeline_follow 纯逻辑测试(node:test,零 SDK 依赖)
import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  transitionOnUserScroll, transitionOnStreamUpdate, transitionOnGenerateEnd,
  transitionOnJumpToBottom, shouldScrollOnStreamUpdate, shouldScrollOnGenerateEnd,
  shouldScrollOnAppend, isCloseEnoughToBottom, resolveInitialRowIndex,
  FOLLOW_BOTTOM_TOLERANCE_VP,
} from '../main/ets/chat/timeline_follow.ts';
import type { FollowMode } from '../main/ets/chat/timeline_follow.ts';

// ===== transitionOnUserScroll:用户滚动 → 暂停/恢复 =====

test('用户滚动:following + 离底 → paused(生成中不抢回)', () => {
  assert.equal(transitionOnUserScroll('following', false, true), 'paused');
  assert.equal(transitionOnUserScroll('following', false, false), 'paused');
});

test('用户滚动:paused + 离底 → 保持 paused', () => {
  assert.equal(transitionOnUserScroll('paused', false, true), 'paused');
});

test('用户滚动:idle + 离底 + 未生成 → 维持 idle', () => {
  assert.equal(transitionOnUserScroll('idle', false, false), 'idle');
});

test('用户滚动:idle + 离底 + 生成中 → paused(生成开始前用户已在上方)', () => {
  assert.equal(transitionOnUserScroll('idle', false, true), 'paused');
});

test('用户滚动:滚回末端 + 生成中 → following(恢复跟随)', () => {
  assert.equal(transitionOnUserScroll('paused', true, true), 'following');
  assert.equal(transitionOnUserScroll('following', true, true), 'following');
});

test('用户滚动:滚回末端 + 未生成 → idle(结束跟随)', () => {
  assert.equal(transitionOnUserScroll('paused', true, false), 'idle');
  assert.equal(transitionOnUserScroll('following', true, false), 'idle');
  assert.equal(transitionOnUserScroll('idle', true, false), 'idle');
});

// ===== transitionOnStreamUpdate:chunk 到达 → 跟随/暂停 =====

test('流式更新:following → 保持 following', () => {
  assert.equal(transitionOnStreamUpdate('following', false), 'following');
});

test('流式更新:paused → 恒保持 paused(不抢回)', () => {
  assert.equal(transitionOnStreamUpdate('paused', true), 'paused');
  assert.equal(transitionOnStreamUpdate('paused', false), 'paused');
});

test('流式更新:idle + 贴底 → following(首次 chunk 开始跟随)', () => {
  assert.equal(transitionOnStreamUpdate('idle', true), 'following');
});

test('流式更新:idle + 离底 → paused(用户在上方,不自动贴底)', () => {
  assert.equal(transitionOnStreamUpdate('idle', false), 'paused');
});

// ===== transitionOnGenerateEnd:完成 → 清退跟随 =====

test('生成结束:following → idle(本轮跟随收尾)', () => {
  assert.equal(transitionOnGenerateEnd('following'), 'idle');
});

test('生成结束:paused → 保持 paused(完成回调不抢回)', () => {
  assert.equal(transitionOnGenerateEnd('paused'), 'paused');
});

test('生成结束:idle → 保持 idle', () => {
  assert.equal(transitionOnGenerateEnd('idle'), 'idle');
});

// ===== transitionOnJumpToBottom:跳底按钮 =====

test('跳底:生成中 → following', () => {
  assert.equal(transitionOnJumpToBottom(true), 'following');
});

test('跳底:未生成 → idle', () => {
  assert.equal(transitionOnJumpToBottom(false), 'idle');
});

// ===== shouldScrollOnStreamUpdate:生成中是否可滚动 =====

test('生成中:paused 永不滚动(核心防抢回)', () => {
  assert.equal(shouldScrollOnStreamUpdate('paused', true), false);
  assert.equal(shouldScrollOnStreamUpdate('paused', false), false);
});

test('生成中:following 恒滚动(自动贴底)', () => {
  assert.equal(shouldScrollOnStreamUpdate('following', true), true);
  assert.equal(shouldScrollOnStreamUpdate('following', false), true);
});

test('生成中:idle 仅在贴底时滚动', () => {
  assert.equal(shouldScrollOnStreamUpdate('idle', true), true);
  assert.equal(shouldScrollOnStreamUpdate('idle', false), false);
});

// ===== shouldScrollOnGenerateEnd:结束时是否可滚动 =====

test('结束:following 可滚动收尾', () => {
  assert.equal(shouldScrollOnGenerateEnd('following', false), true);
});

test('结束:paused 不可滚动(完成回调不抢)', () => {
  assert.equal(shouldScrollOnGenerateEnd('paused', true), false);
  assert.equal(shouldScrollOnGenerateEnd('paused', false), false);
});

test('结束:idle 仅贴底时滚动', () => {
  assert.equal(shouldScrollOnGenerateEnd('idle', true), true);
  assert.equal(shouldScrollOnGenerateEnd('idle', false), false);
});

// ===== shouldScrollOnAppend:发送/追加后是否可滚动 =====

test('追加:paused 不抢用户', () => {
  assert.equal(shouldScrollOnAppend('paused', false), false);
});

test('追加:following 恒滚动', () => {
  assert.equal(shouldScrollOnAppend('following', false), true);
});

test('追加:idle 仅贴底时滚动(展示刚发送的消息)', () => {
  assert.equal(shouldScrollOnAppend('idle', true), true);
  assert.equal(shouldScrollOnAppend('idle', false), false);
});

// ===== isCloseEnoughToBottom:贴底近似 =====

test('贴底近似:内容不足一屏 → 天然贴底', () => {
  assert.equal(isCloseEnoughToBottom(0, 0), true);
  assert.equal(isCloseEnoughToBottom(10, -1), true);
});

test('贴底近似:距底在容差内 → 贴底', () => {
  assert.equal(isCloseEnoughToBottom(1000, 1080, 120), true);
  assert.equal(isCloseEnoughToBottom(960, 1080, 120), true);
});

test('贴底近似:超出容差 → 离底', () => {
  assert.equal(isCloseEnoughToBottom(900, 1080, 120), false);
});

test('贴底近似:默认容差 FOLLOW_BOTTOM_TOLERANCE_VP 生效', () => {
  assert.equal(FOLLOW_BOTTOM_TOLERANCE_VP, 24);
  assert.equal(isCloseEnoughToBottom(596, 620), true);
  assert.equal(isCloseEnoughToBottom(595, 620), false);
});

// ===== resolveInitialRowIndex:首屏定位解析 =====

test('初始定位:数字 index 命中', () => {
  assert.equal(resolveInitialRowIndex('2', '', ['a', 'b', 'c']), 2);
});

test('初始定位:数字 index 越界 → 收敛到底部行', () => {
  assert.equal(resolveInitialRowIndex('99', '', ['a', 'b', 'c']), 2);
});

test('初始定位:空 rows + 数字 index → -1 底部', () => {
  assert.equal(resolveInitialRowIndex('0', '', []), -1);
});

test('初始定位:负数/非数字 index 忽略 → 回落 nodeId', () => {
  assert.equal(resolveInitialRowIndex('-1', 'b', ['a', 'b', 'c']), 1);
  assert.equal(resolveInitialRowIndex('abc', 'b', ['a', 'b', 'c']), 1);
});

test('初始定位:nodeId 命中', () => {
  assert.equal(resolveInitialRowIndex('', 'c', ['a', 'b', 'c']), 2);
});

test('初始定位:nodeId 未命中 → 底部(-1)', () => {
  assert.equal(resolveInitialRowIndex('', 'zz', ['a', 'b', 'c']), -1);
});

test('初始定位:无任何目标参数 → 底部(-1)', () => {
  assert.equal(resolveInitialRowIndex('', '', ['a', 'b', 'c']), -1);
});

test('初始定位:非法数字 + nodeId 未命中 → 底部', () => {
  assert.equal(resolveInitialRowIndex('1.5', 'zz', ['a', 'b', 'c']), -1);
});

// ===== 组合场景:完整生成生命周期 =====

test('组合:贴底生成全程跟随,结束回 idle', () => {
  let mode: FollowMode = 'idle';
  // 用户在底部,发送 → 首 chunk
  mode = transitionOnStreamUpdate(mode, true); // idle+贴底 → following
  assert.equal(mode, 'following');
  assert.equal(shouldScrollOnStreamUpdate(mode, false), true); // 持续贴底
  // 更多 chunk
  mode = transitionOnStreamUpdate(mode, false);
  assert.equal(mode, 'following');
  // 完成 → 滚动收尾 + 回 idle
  assert.equal(shouldScrollOnGenerateEnd(mode, false), true);
  mode = transitionOnGenerateEnd(mode);
  assert.equal(mode, 'idle');
});

test('组合:用户上滑暂停后 chunk 与完成均不抢回', () => {
  let mode: FollowMode = 'following';
  // 用户上滑
  mode = transitionOnUserScroll(mode, false, true);
  assert.equal(mode, 'paused');
  // 后续 chunk
  assert.equal(shouldScrollOnStreamUpdate(mode, true), false);
  assert.equal(transitionOnStreamUpdate(mode, true), 'paused');
  // 完成
  assert.equal(shouldScrollOnGenerateEnd(mode, false), false);
  assert.equal(transitionOnGenerateEnd(mode), 'paused');
  // 用户滚回末端恢复
  mode = transitionOnUserScroll(mode, true, true);
  assert.equal(mode, 'following');
});

test('组合:暂停中点击跳底 → 立即恢复跟随', () => {
  let mode: FollowMode = 'paused';
  mode = transitionOnJumpToBottom(true);
  assert.equal(mode, 'following');
});
