// builtin_local_tools.test.ts — D-061 本地工具三件
// Android 基准: RunPlanUpdateTool.kt + ClipboardTool.kt + DeepReadOpenTool.kt
//   + DeepReadOpenRequest.kt(createDeepReadOpenEvent)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { JsonObject } from '../main/ets/chat/json.ts';
import type { UIMessagePart } from '../main/ets/chat/message.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import type { ClipboardPort, DeepReadOpenBus, DeepReadOpenEvent } from '../main/ets/chat/builtin_local_tools.ts';
import {
  createRunPlanUpdateTool, createClipboardTool, createDeepReadOpenTool,
  createDeepReadOpenEvent, DEEP_READ_TITLE_MAX_CHARS,
} from '../main/ets/chat/builtin_local_tools.ts';

const textOf = (parts: UIMessagePart[]): JsonObject =>
  JSON.parse((parts[0] as { text: string }).text) as JsonObject;

// 测试用 sha256:定长 hex 标记(形态断言用;entry 注入真实 SHA-256)
const fakeSha256Hex = (input: string): string =>
  `aa${input.length.toString(16).padStart(2, '0')}`.repeat(16).slice(0, 64);

// ===== run_plan_update(RunPlanUpdateTool.kt 全文 52 行) =====

test('run_plan_update:缺省回退 — status running / index 0 / steps []', async () => {
  const t: AgentTool = createRunPlanUpdateTool();
  const payload: JsonObject = textOf(await t.execute({}));
  assert.deepEqual(Object.keys(payload),
    ['status', 'current_step_index', 'steps', 'note']);
  assert.equal(payload['status'], 'running');
  assert.equal(payload['current_step_index'], 0);
  assert.deepEqual(payload['steps'], []);
  assert.equal(payload['note'],
    'Plan state was accepted by the tool layer. UI live rendering is stage1 and uses the normal tool timeline.');
});

test('run_plan_update:显式值原样回显(含非字符串 status 直通)', async () => {
  const t: AgentTool = createRunPlanUpdateTool();
  const payload: JsonObject = textOf(await t.execute({
    steps: ['一', '二'], current_step_index: 1, status: 'completed',
  }));
  assert.equal(payload['status'], 'completed');
  assert.equal(payload['current_step_index'], 1);
  assert.deepEqual(payload['steps'], ['一', '二']);
});

// ===== clipboard_tool(ClipboardTool.kt 全文) =====

const makeClipboard = (initial: string): {
  port: ClipboardPort;
  written: () => string;
} => {
  let store: string = initial;
  return {
    port: {
      readText: (): Promise<string> => Promise.resolve(store),
      writeText: (text: string): Promise<void> => {
        store = text;
        return Promise.resolve();
      },
    },
    written: (): string => store,
  };
};

test('clipboard_tool:read → {text};write → {success,text} + port 写入', async () => {
  const { port, written } = makeClipboard('clip-content');
  const t: AgentTool = createClipboardTool(port);
  const readPayload: JsonObject = textOf(await t.execute({ action: 'read' }));
  assert.deepEqual(readPayload, { text: 'clip-content' });
  const writePayload: JsonObject = textOf(
    await t.execute({ action: 'write', text: 'new-value' }));
  assert.deepEqual(writePayload, { success: true, text: 'new-value' });
  assert.equal(written(), 'new-value');
});

test('clipboard_tool:缺 action / 缺 text / 未知 action — error 文案逐字', async () => {
  const { port } = makeClipboard('');
  const t: AgentTool = createClipboardTool(port);
  await assert.rejects(
    (): Promise<UIMessagePart[]> => t.execute({}),
    (e: Error): boolean => e.message === 'action is required');
  await assert.rejects(
    (): Promise<UIMessagePart[]> => t.execute({ action: 'write' }),
    (e: Error): boolean => e.message === 'text is required');
  await assert.rejects(
    (): Promise<UIMessagePart[]> => t.execute({ action: 'erase' }),
    (e: Error): boolean =>
      e.message === 'unknown action: erase, must be one of [read, write]');
});

// ===== createDeepReadOpenEvent(DeepReadOpenRequest.kt:13-34 + 私有助手) =====

test('deep_read event:仅标题 → topicId=chat_deep_read_+sha24,无 source_url', () => {
  const ev: DeepReadOpenEvent = createDeepReadOpenEvent('  我的主题  ', null, false, fakeSha256Hex);
  assert.equal(ev.title, '我的主题');
  assert.equal(ev.sourceUrl, null);
  assert.equal(ev.forceRegenerate, false);
  assert.ok(ev.topicId.startsWith('chat_deep_read_'));
  assert.equal(ev.topicId.length, 'chat_deep_read_'.length + 24);
  // key = title.trim().lowercase()
  assert.equal(ev.topicId, `chat_deep_read_${fakeSha256Hex('我的主题').slice(0, 24)}`);
});

test('deep_read event:仅 URL → 标题派生 slug(host 兜底/深度阅读 兜底)', () => {
  const ev: DeepReadOpenEvent = createDeepReadOpenEvent(
    null, 'https://www.example.com/posts/my-first_post.md?x=1', false, fakeSha256Hex);
  // slug = 'my first post';key = url.trim().lowercase()
  assert.equal(ev.title, 'my first post');
  assert.equal(ev.sourceUrl, 'https://www.example.com/posts/my-first_post.md?x=1');
  assert.equal(ev.topicId,
    `chat_deep_read_${fakeSha256Hex('https://www.example.com/posts/my-first_post.md?x=1').slice(0, 24)}`);
  const hostOnly: DeepReadOpenEvent = createDeepReadOpenEvent(
    null, 'https://www.foo.com/', false, fakeSha256Hex);
  assert.equal(hostOnly.title, 'foo.com'); // host removePrefix www.
});

test('deep_read event:title == normalizedUrl → 视为未给标题,由 URL 派生', () => {
  const url: string = 'https://a.com/x-y';
  const ev: DeepReadOpenEvent = createDeepReadOpenEvent(url, url, false, fakeSha256Hex);
  assert.equal(ev.title, 'x y');
});

test('deep_read event:非法 URL / 缺 host / 双缺 — 文案逐字', () => {
  assert.throws(
    (): DeepReadOpenEvent => createDeepReadOpenEvent(null, 'ftp://x.com/a', false, fakeSha256Hex),
    (e: Error): boolean => e.message === 'source_url must be HTTP(S)');
  assert.throws(
    (): DeepReadOpenEvent => createDeepReadOpenEvent(null, 'not-a-url', false, fakeSha256Hex),
    (e: Error): boolean => e.message === 'source_url must be a valid HTTP(S) URL');
  assert.throws(
    (): DeepReadOpenEvent => createDeepReadOpenEvent('  ', null, false, fakeSha256Hex),
    (e: Error): boolean => e.message === 'topic_title or source_url is required');
});

test('deep_read event:标题 120 截断 + force_regenerate 透传', () => {
  assert.equal(DEEP_READ_TITLE_MAX_CHARS, 120);
  const long: string = '题'.repeat(200);
  const ev: DeepReadOpenEvent = createDeepReadOpenEvent(long, null, true, fakeSha256Hex);
  assert.equal(ev.title.length, 120);
  assert.equal(ev.forceRegenerate, true);
});

// ===== deep_read_open(DeepReadOpenTool.kt 全文) =====

const makeBus = (collectors: boolean): {
  bus: DeepReadOpenBus;
  emitted: () => DeepReadOpenEvent[];
} => {
  const events: DeepReadOpenEvent[] = [];
  return {
    bus: {
      hasCollectors: (): boolean => collectors,
      emit: (event: DeepReadOpenEvent): void => {
        events.push(event);
      },
    },
    emitted: (): DeepReadOpenEvent[] => events,
  };
};

test('deep_read_open:hasCollectors → opened 载荷键序逐字 + emit 收到事件', async () => {
  const { bus, emitted } = makeBus(true);
  const t: AgentTool = createDeepReadOpenTool({ bus, sha256Hex: fakeSha256Hex });
  assert.equal(t.allowsAutoApproval, true);
  const payload: JsonObject = textOf(await t.execute({
    topic_title: '主题', source_url: 'https://a.com/b', force_regenerate: true,
  }));
  assert.deepEqual(Object.keys(payload),
    ['status', 'topic_id', 'title', 'source_url', 'cache_ttl_hours',
      'force_regenerate', 'note']);
  assert.equal(payload['status'], 'opened');
  assert.equal(payload['title'], '主题');
  assert.equal(payload['source_url'], 'https://a.com/b');
  assert.equal(payload['cache_ttl_hours'], 24);
  assert.equal(payload['force_regenerate'], true);
  assert.equal(payload['note'],
    'Deep Read panel opened. The panel will stream segmented generation through the hidden-agent pipeline.');
  assert.equal(emitted().length, 1);
  assert.equal(emitted()[0].title, '主题');
});

test('deep_read_open:无 collector → not_opened + 不 emit + 无 source_url 键', async () => {
  const { bus, emitted } = makeBus(false);
  const t: AgentTool = createDeepReadOpenTool({ bus, sha256Hex: fakeSha256Hex });
  const payload: JsonObject = textOf(await t.execute({ topic_title: '主题' }));
  assert.equal(payload['status'], 'not_opened');
  assert.equal('source_url' in payload, false);
  assert.equal(payload['force_regenerate'], false);
  assert.equal(payload['note'],
    'Deep Read UI was not active, so the panel could not be opened.');
  assert.equal(emitted().length, 0);
});

test('deep_read_open:force_regenerate 字符串 true 视为 false(strict 语义)', async () => {
  const { bus } = makeBus(true);
  const t: AgentTool = createDeepReadOpenTool({ bus, sha256Hex: fakeSha256Hex });
  const payload: JsonObject = textOf(
    await t.execute({ topic_title: 't', force_regenerate: 'true' }));
  assert.equal(payload['force_regenerate'], false);
});
