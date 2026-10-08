// timeline_load 纯逻辑测试(node:test,零 SDK 依赖)
// 覆盖:窗口边界、fullyLoaded 判定、跨页定位、防重入、去重合并
import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  createInitialTimelineLoadState,
  applyTailWindow,
  beginLoadOlder,
  applyOlderPage,
  canLoadOlder,
  mergeOlderNodes,
} from '../main/ets/chat/timeline_load.ts';
import type {
  ConversationTimelineLoadState,
} from '../main/ets/chat/timeline_load.ts';

interface Row { nodeId: string }

const row = (nodeId: string): Row => ({ nodeId });

// ===== applyTailWindow:尾窗到达 =====

test('尾窗:覆盖中间段 → initialized,未 fullyLoaded', () => {
  const s: ConversationTimelineLoadState = createInitialTimelineLoadState();
  const loaded: ConversationTimelineLoadState = applyTailWindow(s, 50, 50, 120);
  assert.equal(loaded.initialized, true);
  assert.equal(loaded.loadingOlder, false);
  assert.equal(loaded.fullyLoaded, false);
  assert.equal(loaded.oldestLoadedIndex, 50);
  assert.equal(loaded.loadedNodeCount, 50);
  assert.equal(canLoadOlder(loaded), true);
});

test('尾窗:覆盖到最旧(全部装入)→ fullyLoaded', () => {
  const s: ConversationTimelineLoadState = createInitialTimelineLoadState();
  const loaded: ConversationTimelineLoadState = applyTailWindow(s, 0, 30, 30);
  assert.equal(loaded.initialized, true);
  assert.equal(loaded.fullyLoaded, true);
  assert.equal(loaded.oldestLoadedIndex, 0);
  assert.equal(loaded.loadedNodeCount, 30);
  assert.equal(canLoadOlder(loaded), false);
});

test('尾窗:空会话 → initialized + fullyLoaded', () => {
  const s: ConversationTimelineLoadState = createInitialTimelineLoadState();
  const loaded: ConversationTimelineLoadState = applyTailWindow(s, 0, 0, 0);
  assert.equal(loaded.initialized, true);
  assert.equal(loaded.fullyLoaded, true);
  assert.equal(loaded.loadedNodeCount, 0);
});

test('beginLoadOlder:加载中重复触发 → 原状态不变(防重入)', () => {
  const s: ConversationTimelineLoadState = applyTailWindow(
    createInitialTimelineLoadState(), 50, 50, 120);
  const loading: ConversationTimelineLoadState = beginLoadOlder(s);
  const again: ConversationTimelineLoadState = beginLoadOlder(loading);
  assert.equal(again, loading);
  assert.equal(again.loadingOlder, true);
});

// ===== applyOlderPage:更早一页到达 =====

test('applyOlderPage:一页到达最旧(索引 0)→ fullyLoaded,累计 loadedNodeCount', () => {
  // 尾窗 [70,120);更早页 [0,70) → 覆盖整段
  const s: ConversationTimelineLoadState = applyTailWindow(
    createInitialTimelineLoadState(), 70, 50, 120);
  const older: ConversationTimelineLoadState = applyOlderPage(s, 0, 70);
  assert.equal(older.initialized, true);
  assert.equal(older.loadingOlder, false);
  assert.equal(older.fullyLoaded, true);
  assert.equal(older.oldestLoadedIndex, 0);
  assert.equal(older.loadedNodeCount, 120);
  assert.equal(canLoadOlder(older), false);
});

test('applyOlderPage:未到最旧 → 仍可继续加载', () => {
  // 尾窗 [70,120);更早页 [20,70) → 未覆盖最旧
  const s: ConversationTimelineLoadState = applyTailWindow(
    createInitialTimelineLoadState(), 70, 50, 120);
  const older: ConversationTimelineLoadState = applyOlderPage(s, 20, 50);
  assert.equal(older.fullyLoaded, false);
  assert.equal(older.oldestLoadedIndex, 20);
  assert.equal(older.loadedNodeCount, 100);
  assert.equal(canLoadOlder(older), true);
});

test('applyOlderPage:已全量后到达一页 → 维持 fullyLoaded,累计仍正确', () => {
  const s: ConversationTimelineLoadState = applyTailWindow(
    createInitialTimelineLoadState(), 0, 10, 10);
  const older: ConversationTimelineLoadState = applyOlderPage(s, 0, 5);
  assert.equal(older.fullyLoaded, true);
  assert.equal(older.oldestLoadedIndex, 0);
});

// ===== mergeOlderNodes:去重合并 =====

test('mergeOlderNodes:更早行前插,顺序保持', () => {
  const existing: Row[] = [row('n50'), row('n51')];
  const older: Row[] = [row('n30'), row('n31')];
  const merged: Row[] = mergeOlderNodes(existing, older);
  assert.deepEqual(merged.map((r: Row): string => r.nodeId), ['n30', 'n31', 'n50', 'n51']);
});

test('mergeOlderNodes:重叠行去重(offset 边界安全)', () => {
  const existing: Row[] = [row('n30'), row('n31')];
  const older: Row[] = [row('n20'), row('n30'), row('n31')];
  const merged: Row[] = mergeOlderNodes(existing, older);
  assert.deepEqual(merged.map((r: Row): string => r.nodeId), ['n20', 'n30', 'n31']);
  // 前插数 = 合并后长度差(锚点补偿用)
  assert.equal(merged.length - existing.length, 1);
});
