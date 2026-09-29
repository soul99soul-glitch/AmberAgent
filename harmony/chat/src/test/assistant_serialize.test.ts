// Assistant 全量模型 kotlinx JSON 线格式规格测试
//
// Android 基准:
//   core/model/.../Assistant.kt(30 字段,@Serializable,默认值逐字)
//   core/model/.../Avatar.kt(sealed: Dummy/Emoji/Image,无 @SerialName → FQN 鉴别名)
//   core/model/.../LocalToolOption.kt(13 variant,@SerialName snake_case)
//   core/model/.../MainAgentToolProfile.kt(6 值,小写 serial)
//   ai/util/Json.kt(ignoreUnknownKeys/encodeDefaults/explicitNulls=false/isLenient/allowTrailingComma)
//   存储形态:Settings DataStore 内 assistants: List<Assistant> JSON(非 Room)
//
// 决定(D-016):
//   - messageTemplate 默认 '{{ message }}'(Android 原值),transformer 总是套用(恒等)
//   - Float 字段(temperature/topP/backgroundOpacity)线格式保留 .0(kotlinx Float)
//   - 领域 ReasoningLevel 小写 union ↔ 线格式大写枚举名
//   - 领域 AssistantAffectScope 'user'|'assistant' ↔ 线格式 'USER'|'ASSISTANT'

import { test } from 'node:test';
import assert from 'node:assert/strict';

import { makeAssistant, makeAssistantRegex } from '../main/ets/chat/assistant.ts';
import { serializeAssistant, parseAssistant, serializeAssistantList, parseAssistantList } from '../main/ets/chat/assistant_serialize.ts';
import { createMemoryKeyValueStore, saveAssistants, loadAssistants } from '../main/ets/chat/kv_store.ts';
import { makeUserMessage } from '../main/ets/chat/message.ts';

const FIXED_ID = '0950e2dc-9bd5-4801-afa3-aa887aa36b4e';

test('golden: 默认 Assistant 全字段线格式(键序=声明序,defaults 编码,null 省略,Float 带 .0)', () => {
  const a = makeAssistant({ id: FIXED_ID });
  const json = serializeAssistant(a);
  const expected = '{"id":"0950e2dc-9bd5-4801-afa3-aa887aa36b4e",'
    + '"name":"",'
    + '"avatar":{"type":"app.amber.core.model.Avatar.Dummy"},'
    + '"useAssistantAvatar":false,'
    + '"tags":[],'
    + '"systemPrompt":"",'
    + '"contextMessageSize":0,'
    + '"streamOutput":true,'
    + '"enableMemory":false,'
    + '"useGlobalMemory":false,'
    + '"enableRecentChatsReference":false,'
    + '"messageTemplate":"{{ message }}",'
    + '"presetMessages":[],'
    + '"quickMessageIds":[],'
    + '"regexes":[],'
    + '"reasoningLevel":"AUTO",'
    + '"customHeaders":[],'
    + '"customBodies":[],'
    + '"mcpServers":[],'
    + '"localTools":[{"type":"time_info"}],'
    + '"toolProfile":"full",'
    + '"backgroundOpacity":1.0,'
    + '"enabledSkills":[],'
    + '"enableTimeReminder":false,'
    + '"rememberedReasoningLevelsByModelId":{}'
    + '}';
  assert.equal(json, expected);
});

test('golden: 全字段填充(空值字段出现/Float 整数 → 1.0/枚举大写/嵌套结构)', () => {
  const a = makeAssistant({
    id: FIXED_ID,
    chatModelId: 'm-1',
    imageGenerationModelId: 'g-1',
    name: '助手',
    avatar: { type: 'emoji', content: '🤖' },
    useAssistantAvatar: true,
    tags: ['t1', 't2'],
    systemPrompt: '你是助手',
    temperature: 1.0,
    topP: 0.5,
    contextMessageSize: 20,
    streamOutput: false,
    enableMemory: true,
    useGlobalMemory: true,
    enableRecentChatsReference: true,
    messageTemplate: '[{{role}}] {{message}}',
    presetMessages: [makeUserMessage('预设')],
    quickMessageIds: ['q1'],
    regexes: [makeAssistantRegex({
      id: 'r1', name: '规则', findRegex: 'a', replaceString: 'b',
      affectingScope: ['user'], visualOnly: true,
    })],
    reasoningLevel: 'high',
    maxTokens: 4096,
    customHeaders: [{ name: 'X-K', value: 'v' }],
    customBodies: [{ key: 'k', value: { n: 1 } }],
    mcpServers: ['s1'],
    localTools: ['time_info', 'webmount_eval', 'javascript_engine'],
    toolProfile: 'coding',
    background: 'file://bg.png',
    backgroundOpacity: 0.8,
    enabledSkills: ['skill-a'],
    enableTimeReminder: true,
    rememberedReasoningLevelsByModelId: { 'm-1': 'max', 'm-2': 'off' },
  });
  const json = serializeAssistant(a);
  const parsed: unknown = JSON.parse(json);
  assert.deepEqual(parseAssistant(json), a, 'round-trip');
  const obj = parsed as Record<string, unknown>;
  // 线格式关键点
  assert.equal(obj['chatModelId'], 'm-1');
  assert.equal(obj['temperature'], 1.0);
  assert.ok(json.includes('"temperature":1.0'), 'Float 1 → 1.0');
  assert.ok(json.includes('"topP":0.5'));
  assert.ok(json.includes('"backgroundOpacity":0.8'));
  assert.equal(obj['reasoningLevel'], 'HIGH');
  assert.deepEqual(obj['rememberedReasoningLevelsByModelId'], { 'm-1': 'MAX', 'm-2': 'OFF' });
  const avatar = obj['avatar'] as Record<string, unknown>;
  assert.equal(avatar['type'], 'app.amber.core.model.Avatar.Emoji');
  assert.equal(avatar['content'], '🤖');
  const regexes = obj['regexes'] as Array<Record<string, unknown>>;
  assert.deepEqual(regexes[0]['affectingScope'], ['USER']);
  assert.equal(regexes[0]['name'], '规则');
  assert.deepEqual(obj['localTools'], [
    { type: 'time_info' }, { type: 'webmount_eval' }, { type: 'javascript_engine' },
  ]);
  assert.equal(obj['toolProfile'], 'coding');
  const preset = obj['presetMessages'] as Array<Record<string, unknown>>;
  assert.equal(preset[0]['role'], 'user');
  // 键序:id 在最前,remembered… 在最后
  assert.ok(json.indexOf('"id"') < json.indexOf('"name"'));
  assert.ok(json.indexOf('"rememberedReasoningLevelsByModelId"') > json.indexOf('"enableTimeReminder"'));
});

test('avatar image variant 线格式', () => {
  const a = makeAssistant({ id: FIXED_ID, avatar: { type: 'image', url: 'file://a.png' } });
  const obj = JSON.parse(serializeAssistant(a)) as Record<string, unknown>;
  assert.deepEqual(obj['avatar'], { type: 'app.amber.core.model.Avatar.Image', url: 'file://a.png' });
});

test('parse: 未知键忽略 + 缺失字段取默认(ignoreUnknownKeys/encodeDefaults 双箭头)', () => {
  const minimal = '{"id":"x","name":"n","unknownField":123,"avatar":{"type":"app.amber.core.model.Avatar.Dummy"}}';
  const a = parseAssistant(minimal);
  assert.equal(a.id, 'x');
  assert.equal(a.name, 'n');
  assert.equal(a.messageTemplate, '{{ message }}');
  assert.equal(a.reasoningLevel, 'auto');
  assert.deepEqual(a.localTools, ['time_info']);
  assert.equal(a.toolProfile, 'full');
  assert.equal(a.backgroundOpacity, 1);
  assert.equal(a.chatModelId, null);
});

test('parse: 13 个 LocalToolOption variant 全表', () => {
  const names = [
    'javascript_engine', 'time_info', 'clipboard', 'tts', 'ask_user', 'workspace_files',
    'terminal', 'screen_automation', 'system_access', 'webview', 'icloud_drive',
    'webmount', 'webmount_eval',
  ];
  for (const n of names) {
    const json = `{"id":"x","localTools":[{"type":"${n}"}]}`;
    const a = parseAssistant(json);
    assert.deepEqual(a.localTools, [n], n);
  }
});

test('parse: ReasoningLevel 七档大写 → 小写 union', () => {
  const levels = ['OFF', 'AUTO', 'LOW', 'MEDIUM', 'HIGH', 'XHIGH', 'MAX'];
  const expected = ['off', 'auto', 'low', 'medium', 'high', 'xhigh', 'max'];
  levels.forEach((lv: string, i: number): void => {
    const a = parseAssistant(`{"id":"x","reasoningLevel":"${lv}"}`);
    assert.equal(a.reasoningLevel, expected[i]);
  });
});

test('parse: 缺 id → 抛错(kotlinx missing field)', () => {
  assert.throws(() => parseAssistant('{"name":"n"}'), /missing field 'id'/);
});

test('list 线格式 + KV Port 存取 round-trip', async () => {
  const store = createMemoryKeyValueStore();
  const list = [makeAssistant({ id: 'a1', name: 'A' }), makeAssistant({ id: 'a2', name: 'B' })];
  await saveAssistants(store, list);
  const loaded = await loadAssistants(store);
  assert.deepEqual(loaded, list);
  // blob 是 JSON 数组
  const raw = serializeAssistantList(list);
  assert.ok(raw.startsWith('['));
  assert.deepEqual(parseAssistantList(raw), list);
  // 空 store → null(对齐 DataStore 无键语义,由调用方落默认)
  const empty = await loadAssistants(createMemoryKeyValueStore());
  assert.equal(empty, null);
});
