// display_setting 纯逻辑测试
import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  makeDisplaySetting, loadDisplaySetting, saveDisplaySetting, DISPLAY_SETTING_KEY,
} from '../main/ets/chat/display_setting.ts';
import { createMemoryKeyValueStore } from '../main/ets/chat/kv_store.ts';

test('round-trip: save → load', async () => {
  const store = createMemoryKeyValueStore();
  const original = makeDisplaySetting({ sendOnEnter: true, showAssistantBubble: true, fontSizeRatio: 1.25 });
  await saveDisplaySetting(store, original);
  const loaded = await loadDisplaySetting(store);
  assert.equal(loaded.sendOnEnter, true);
  assert.equal(loaded.showAssistantBubble, true);
  assert.equal(loaded.fontSizeRatio, 1.25);
});

test('load 空存储 → 默认值', async () => {
  const store = createMemoryKeyValueStore();
  const loaded = await loadDisplaySetting(store);
  assert.equal(loaded.sendOnEnter, false);
  assert.equal(loaded.showModelName, true);
});

test('load 损坏 JSON → 默认值', async () => {
  const store = createMemoryKeyValueStore();
  await store.put(DISPLAY_SETTING_KEY, '{broken');
  const loaded = await loadDisplaySetting(store);
  assert.equal(loaded.sendOnEnter, false);
});
