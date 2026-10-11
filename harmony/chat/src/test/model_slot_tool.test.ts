import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createModelSlotTool, MODEL_SLOTS } from '../main/ets/chat/model_slot_tool.ts';
import type { ModelSlotPort } from '../main/ets/chat/model_slot_tool.ts';
import { makeProviderModel, makeProviderSettingOpenAIVariant, makeProviderSettingClaude } from '../main/ets/chat/provider_settings.ts';
import type { ProviderModel, ProviderSetting } from '../main/ets/chat/provider_settings.ts';
import type { JsonObject } from '../main/ets/chat/json.ts';

interface Write { slot: string; providerId: string; model: ProviderModel }

const setup = (providers: ProviderSetting[]) => {
  const writes: Write[] = [];
  const port: ModelSlotPort = {
    loadProviders: async () => providers,
    writeSlot: async (slot, providerId, model) => { writes.push({ slot, providerId, model }); },
  };
  const tool = createModelSlotTool({ port });
  const call = async (input: JsonObject): Promise<JsonObject> => {
    const parts = await tool.execute(input);
    return JSON.parse((parts[0] as { text: string }).text);
  };
  return { writes, call, tool };
};

const openai = (name: string, models: ProviderModel[], enabled = true): ProviderSetting => {
  const p = makeProviderSettingOpenAIVariant();
  p.name = name; p.apiKey = 'k'; p.enabled = enabled; p.models = models;
  return p;
};
const chat = (modelId: string, displayName = ''): ProviderModel => makeProviderModel({ modelId, displayName, type: 'chat' });
const image = (modelId: string): ProviderModel => makeProviderModel({ modelId, type: 'image' });

test('unique model_ref match writes the slot and returns identifiers', async () => {
  const model = chat('deepseek-v4-pro', 'DeepSeek V4 Pro');
  const provider = openai('Gateway', [model, chat('qwen-max')]);
  const s = setup([provider]);
  const result = await s.call({ slot: 'title', model_ref: 'deepseek' });
  assert.equal(result.status, 'ok');
  assert.equal(result.model_id, model.id);
  assert.equal(result.provider_id, provider.id);
  assert.equal(s.writes.length, 1);
  assert.equal(s.writes[0].slot, 'title');
  assert.equal(s.writes[0].model.id, model.id);
});

test('model_id matches exactly (case-insensitive) and slot is normalized', async () => {
  const model = chat('a-model');
  const s = setup([openai('P', [model, chat('b-model')])]);
  const result = await s.call({ slot: ' Chat ', model_id: model.id.toUpperCase() });
  assert.equal(result.status, 'ok');
  assert.equal(s.writes[0].slot, 'chat');
});

test('ambiguous model_ref fails without writing and lists candidates', async () => {
  const s = setup([openai('A', [chat('gpt-4o-mini'), chat('gpt-4.1')]), openai('B', [chat('gpt-5')])]);
  const result = await s.call({ slot: 'chat', model_ref: 'gpt' });
  assert.equal(result.status, 'error');
  assert.equal(result.code, 'ambiguous');
  assert.equal((result.candidates as JsonObject[]).length, 3);
  assert.equal(s.writes.length, 0);
});

test('exact modelId wins over longer substring matches; duplicate exact stays ambiguous', async () => {
  const s = setup([openai('A', [chat('gpt-4o'), chat('gpt-4o-mini')])]);
  assert.equal((await s.call({ slot: 'chat', model_ref: 'gpt-4o' })).status, 'ok');
  assert.equal(s.writes[0].model.modelId, 'gpt-4o');
  const dup = setup([openai('A', [chat('gpt-4o')]), openai('B', [chat('gpt-4o')])]);
  assert.equal((await dup.call({ slot: 'chat', model_ref: 'gpt-4o' })).code, 'ambiguous');
});

test('type mismatch fails in both directions', async () => {
  const img = image('gpt-image-1');
  const s = setup([openai('A', [chat('gpt-4o'), img])]);
  const toChat = await s.call({ slot: 'chat', model_id: img.id });
  assert.equal(toChat.code, 'type_mismatch');
  const toImage = await s.call({ slot: 'image_generation', model_ref: 'gpt-4o' });
  assert.equal(toImage.code, 'type_mismatch');
  assert.equal(s.writes.length, 0);
  // sharing a prefix with an image model does not make a chat-slot ref ambiguous
  const ok = await s.call({ slot: 'image_generation', model_ref: 'gpt' });
  assert.equal(ok.status, 'ok');
  assert.equal(s.writes[0].model.id, img.id);
});

test('image_generation needs an OpenAI-compatible provider and ocr needs image input', async () => {
  const claude = makeProviderSettingClaude();
  claude.name = 'C'; claude.apiKey = 'k';
  const claudeImg = image('img-x');
  claude.models = [claudeImg];
  const blind = chat('text-only');
  const vision = makeProviderModel({ modelId: 'vision', type: 'chat', inputModalities: ['text', 'image'] });
  const s = setup([claude, openai('O', [blind, vision])]);
  assert.equal((await s.call({ slot: 'image_generation', model_id: claudeImg.id })).code, 'type_mismatch');
  assert.equal((await s.call({ slot: 'ocr', model_id: blind.id })).code, 'type_mismatch');
  assert.equal((await s.call({ slot: 'ocr', model_id: vision.id })).status, 'ok');
});

test('unknown slot and missing arguments fail', async () => {
  const s = setup([openai('A', [chat('m')])]);
  const unknown = await s.call({ slot: 'mimo', model_ref: 'm' });
  assert.equal(unknown.code, 'unknown_slot');
  assert.equal((await s.call({ model_ref: 'm' })).code, 'missing_slot');
  assert.equal((await s.call({ slot: 'chat' })).code, 'missing_model');
  assert.equal((await s.call({ slot: 'chat', model_ref: 'nope' })).code, 'model_not_found');
  assert.equal((await s.call({ slot: 'chat', model_id: 'not-a-uuid' })).code, 'model_not_found');
  assert.equal(s.writes.length, 0);
  assert.equal(MODEL_SLOTS.length, 7);
});

test('disabled or credential-less providers are not candidates; write errors are reported', async () => {
  const hidden = chat('hidden-model');
  const noKey = openai('B', [chat('nokey-model')]);
  noKey.apiKey = '';
  const s = setup([openai('A', [hidden], false), noKey]);
  assert.equal((await s.call({ slot: 'chat', model_ref: 'hidden' })).code, 'model_not_found');
  assert.equal((await s.call({ slot: 'chat', model_ref: 'nokey' })).code, 'model_not_found');
  const failing = createModelSlotTool({ port: {
    loadProviders: async () => [openai('A', [chat('m')])],
    writeSlot: async () => { throw new Error('助手不存在'); },
  } });
  const parts = await failing.execute({ slot: 'assistant_chat', model_ref: 'm' });
  const out = JSON.parse((parts[0] as { text: string }).text);
  assert.equal(out.status, 'error');
  assert.equal(out.code, 'write_failed');
});

test('tool declares no approval itself (tool_policy classifies it as mutating) and requires only slot', () => {
  const tool = setup([]).tool;
  assert.equal(tool.name, 'settings_set_model_slot');
  assert.equal(tool.needsApproval, false);
  assert.deepEqual(tool.parameters()?.required, ['slot']);
});
