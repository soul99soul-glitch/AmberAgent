import test from 'node:test';
import assert from 'node:assert/strict';
import { makeJevSettings, makeJevNewPurposeSettings, saveJevSettings, loadJevSettings } from '../main/ets/chat/jev_models.ts';
import { resolveJevPurposeMode, jevPurposeConsentUnchanged } from '../main/ets/chat/jev_approval.ts';
import { createMemoryKeyValueStore } from '../main/ets/chat/kv_store.ts';

test('four new purposes default off with independent output consent and survive persistence', async () => {
  const settings = makeJevSettings({ mode: 'active' });
  for (const field of ['toolContextSelection', 'contextRetention', 'autoApproval', 'completionCheck'] as const) {
    assert.equal(settings[field].mode, 'off');
    assert.equal(settings[field].allowToolOutput, false);
  }
  settings.toolContextSelection = makeJevNewPurposeSettings({ mode: 'active', allowToolOutput: true });
  const kv = createMemoryKeyValueStore(); await saveJevSettings(kv, settings);
  assert.deepEqual(await loadJevSettings(kv), settings);
  assert.equal(resolveJevPurposeMode(settings, 'tool_context_selection'), 'active');
  assert.equal(resolveJevPurposeMode(settings, 'context_selection'), 'active');
  assert.equal(resolveJevPurposeMode({ ...settings, mode: 'shadow' }, 'tool_context_selection'), 'shadow');
  assert.equal(resolveJevPurposeMode({ ...settings, mode: 'off' }, 'tool_context_selection'), 'off');
});

test('late configuration or consent changes invalidate the selected purpose only', () => {
  const settings = makeJevSettings({ mode: 'active', model: 'model', apiKey: 'key',
    completionCheck: makeJevNewPurposeSettings({ mode: 'active', allowTaskText: true, allowToolMetadata: true }) });
  assert.equal(jevPurposeConsentUnchanged(settings, { ...settings }, 'completion_check'), true);
  assert.equal(jevPurposeConsentUnchanged(settings, { ...settings, mode: 'shadow' }, 'completion_check'), false);
  assert.equal(jevPurposeConsentUnchanged(settings, { ...settings, apiKey: 'different' }, 'completion_check'), false);
  assert.equal(jevPurposeConsentUnchanged(settings, { ...settings, completionCheck: { ...settings.completionCheck, allowTaskText: false } }, 'completion_check'), false);
  assert.equal(jevPurposeConsentUnchanged(settings, { ...settings, autoApproval: makeJevNewPurposeSettings({ mode: 'active' }) }, 'completion_check'), true);
});
