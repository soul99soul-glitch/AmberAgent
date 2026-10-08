// builtin_memory_tools + memory_write 测试(D-085c)
// 锚点:MemoryTools.kt 全文 / MemoryRepository.kt 写路径
import assert from 'node:assert/strict';
import test from 'node:test';
import type { JsonObject, JsonValue } from '../main/ets/chat/json.ts';
import type { UIMessagePart, UIMessagePartText } from '../main/ets/chat/message.ts';
import type { MemoryRecord } from '../main/ets/chat/memory_models.ts';
import { makeMemoryRecord } from '../main/ets/chat/memory_models.ts';
import type { AssistantMemory, MemoryToolDeps, MemoryToolWriteRequest } from '../main/ets/chat/builtin_memory_tools.ts';
import { createMemoryTools, makeAssistantMemory } from '../main/ets/chat/builtin_memory_tools.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import {
  appendMemoryRecord, deleteMemoryRecord, memoriesOfAssistant, memoryBucketForScope, memoryKindForBucketAdd,
  memoryRecordToAssistantMemory, memoryScopeForBucket, updateMemoryRecordContent,
} from '../main/ets/chat/memory_write.ts';

const NOW = new Date(2026, 6, 28, 12).getTime();

const record = (over: Partial<MemoryRecord>): MemoryRecord => makeMemoryRecord({
  id: over.id ?? 1,
  content: over.content ?? '内容',
  scope: over.scope ?? 'long_term',
  kind: over.kind ?? 'note',
  assistantId: over.assistantId ?? '__long_term__',
  sourceConversationId: over.sourceConversationId ?? null,
  sourceMessageIds: over.sourceMessageIds ?? [],
  supersedesIds: over.supersedesIds ?? [],
  expiresAt: over.expiresAt ?? null,
  confidence: over.confidence ?? 1,
  pinned: over.pinned ?? false,
  archived: over.archived ?? false,
  createdAt: over.createdAt ?? NOW,
  updatedAt: over.updatedAt ?? NOW,
  lastUsedAt: over.lastUsedAt ?? null,
});

const toolByName = (tools: AgentTool[], name: string): AgentTool => {
  const t: AgentTool | undefined = tools.find((x: AgentTool): boolean => x.name === name);
  assert.ok(t !== undefined, `tool ${name} 存在`);
  return t;
};

const execText = async (tool: AgentTool, input: JsonValue): Promise<string> => {
  const parts: UIMessagePart[] = await tool.execute(input);
  assert.equal(parts.length, 1);
  assert.equal(parts[0].type, 'text');
  return (parts[0] as UIMessagePartText).text;
};

const sampleMemory = (over: Partial<AssistantMemory> = {}): AssistantMemory =>
  makeAssistantMemory(
    over.id ?? 3, over.content ?? '偏好简短', over.scope ?? 'long_term',
    over.kind ?? 'user', over.expiresAt ?? null, over.confidence ?? 1,
    over.pinned ?? false, over.archived ?? false);

const depsWith = (over: Partial<MemoryToolDeps> = {}): MemoryToolDeps => ({
  todayText: over.todayText ?? '2026年7月28日',
  onList: over.onList ?? ((_scope: string): Promise<AssistantMemory[]> => Promise.resolve([])),
  onCreation: over.onCreation ?? ((req: MemoryToolWriteRequest): Promise<AssistantMemory> =>
    Promise.resolve(sampleMemory({ content: req.content, scope: req.scope, kind: req.kind }))),
  onUpdate: over.onUpdate ?? ((id: number, content: string): Promise<AssistantMemory> =>
    Promise.resolve(sampleMemory({ id, content }))),
  onDelete: over.onDelete ?? ((_id: number): Promise<void> => Promise.resolve()),
});

// ===== memory_list =====

test('memory_list: type 缺省 → all,三桶平铺,条目带调用侧 scope', async () => {
  const calls: string[] = [];
  const tools: AgentTool[] = createMemoryTools(depsWith({
    onList: (scope: string): Promise<AssistantMemory[]> => {
      calls.push(scope);
      return Promise.resolve([sampleMemory({ id: calls.length, scope: 'long_term' })]);
    },
  }));
  const text: string = await execText(toolByName(tools, 'memory_list'), {});
  assert.deepEqual(calls, ['core', 'short_term', 'long_term']);
  const payload = JSON.parse(text) as JsonObject;
  assert.equal(payload['type'], 'all');
  assert.equal(payload['count'], 3);
  const memories = payload['memories'] as JsonObject[];
  // toJson(:333-343):type 用 memory.scope(非空);scope 字段 = 调用桶
  assert.equal(memories[0]['type'], 'long_term');
  assert.equal(memories[0]['scope'], 'long_term');
  // expiresAt null → 条目省略(toJson 与 serializer 形状差异点)
  assert.ok(!('expiresAt' in memories[0]));
  // 键序逐字:id/type/scope/kind/content/confidence/pinned/archived
  assert.deepEqual(Object.keys(memories[0]),
    ['id', 'type', 'scope', 'kind', 'content', 'confidence', 'pinned', 'archived']);
});

test('memory_list: 指定 type + 非法 type 抛错(消息逐字)', async () => {
  const tools: AgentTool[] = createMemoryTools(depsWith({
    onList: (_s: string): Promise<AssistantMemory[]> =>
      Promise.resolve([sampleMemory({ expiresAt: 123 })]),
  }));
  const tool: AgentTool = toolByName(tools, 'memory_list');
  const text: string = await execText(tool, { type: 'core' });
  const payload = JSON.parse(text) as JsonObject;
  assert.equal(payload['type'], 'core');
  const memories = payload['memories'] as JsonObject[];
  assert.equal(memories[0]['expiresAt'], 123); // 非 null → 放进
  await assert.rejects(
    (): Promise<string> => execText(tool, { type: 'bogus' }),
    /type must be core, short_term, long_term, or all/);
});

// ===== memory_write =====

test('memory_write: 默认 type=long_term/kind=note + needsApproval/禁自动批准', async () => {
  let seen: MemoryToolWriteRequest | null = null;
  const tools: AgentTool[] = createMemoryTools(depsWith({
    onCreation: (req: MemoryToolWriteRequest): Promise<AssistantMemory> => {
      seen = req;
      return Promise.resolve(sampleMemory({ scope: req.scope, kind: req.kind, content: req.content }));
    },
  }));
  const tool: AgentTool = toolByName(tools, 'memory_write');
  assert.equal(tool.needsApproval, true);
  assert.equal(tool.allowsAutoApproval, false);
  await execText(tool, { content: '用户喜欢茶' });
  assert.equal(seen!.scope, 'long_term');
  assert.equal(seen!.kind, 'note');
  assert.equal(seen!.content, '用户喜欢茶');
  assert.equal(seen!.source, null);
  assert.equal(seen!.expiresAt, null);
});

test('memory_write: 显式 type/kind/expiresAt/source 透传 + serializer payload 形状', async () => {
  let seen: MemoryToolWriteRequest | null = null;
  const tools: AgentTool[] = createMemoryTools(depsWith({
    onCreation: (req: MemoryToolWriteRequest): Promise<AssistantMemory> => {
      seen = req;
      return Promise.resolve(sampleMemory({
        content: req.content, scope: req.scope, kind: req.kind, expiresAt: req.expiresAt,
      }));
    },
  }));
  const tool: AgentTool = toolByName(tools, 'memory_write');
  const text: string = await execText(tool, {
    type: 'core', kind: 'feedback', content: '规则X', source: '对话', expiresAt: 999,
  });
  assert.equal(seen!.source, '对话'); // source 透传(拼接在 deps 实现侧)
  // serializer 形状(JsonInstant encodeDefaults+explicitNulls):全字段恒在,声明序
  assert.equal(text, '{"id":3,"content":"规则X","scope":"core","kind":"feedback",'
    + '"expiresAt":999,"confidence":1.0,"pinned":false,"archived":false}');
});

test('memory_write: content 缺失/非法 type 抛错;expiresAt 非整数字符串 → null', async () => {
  let seen: MemoryToolWriteRequest | null = null;
  const tools: AgentTool[] = createMemoryTools(depsWith({
    onCreation: (req: MemoryToolWriteRequest): Promise<AssistantMemory> => {
      seen = req;
      return Promise.resolve(sampleMemory());
    },
  }));
  const tool: AgentTool = toolByName(tools, 'memory_write');
  await assert.rejects((): Promise<string> => execText(tool, {}), /content is required/);
  await assert.rejects(
    (): Promise<string> => execText(tool, { type: 'all', content: 'x' }),
    /type must be core, short_term, or long_term/);
  await execText(tool, { content: 'x', expiresAt: '123.5' });
  assert.equal(seen!.expiresAt, null); // toLongOrNull('123.5') → null
  await execText(tool, { content: 'x', expiresAt: '456' });
  assert.equal(seen!.expiresAt, 456); // 字符串整数可解析(contentOrNull 语义)
});

// ===== memory_delete =====

test('memory_delete: id 必填 + payload {success:true,id} + 审批门', async () => {
  const deleted: number[] = [];
  const tools: AgentTool[] = createMemoryTools(depsWith({
    onDelete: (id: number): Promise<void> => {
      deleted.push(id);
      return Promise.resolve();
    },
  }));
  const tool: AgentTool = toolByName(tools, 'memory_delete');
  assert.equal(tool.needsApproval, true);
  assert.equal(tool.allowsAutoApproval, false);
  const text: string = await execText(tool, { id: 7 });
  assert.equal(text, '{"success":true,"id":7}');
  assert.deepEqual(deleted, [7]);
  await assert.rejects((): Promise<string> => execText(tool, {}), /id is required/);
});

// ===== memory_tool =====

test('memory_tool: 描述逐字(todayText 注入 + trimIndent 形状) + 默认审批标志', () => {
  const tools: AgentTool[] = createMemoryTools(depsWith({ todayText: 'Jul 28, 2026' }));
  const tool: AgentTool = toolByName(tools, 'memory_tool');
  assert.equal(tool.needsApproval, false); // Android 未显式声明 → Tool.kt 默认
  assert.equal(tool.allowsAutoApproval, true);
  const d: string = tool.description;
  assert.ok(d.startsWith('The memory tool stores layered information'));
  assert.ok(d.indexOf('Today is Jul 28, 2026.\n') >= 0);
  assert.ok(d.endsWith('{"action":"delete","id":7}'));
  assert.ok(d.indexOf('\n\nExamples:\n') >= 0);
  // 用户偏好名示例的弯引号逐字
  assert.ok(d.indexOf('User’s preferred name updated to “A-Xing”') >= 0);
});

test('memory_tool create: 默认 scope/kind/confidence + 全参透传', async () => {
  let seen: MemoryToolWriteRequest | null = null;
  const tools: AgentTool[] = createMemoryTools(depsWith({
    onCreation: (req: MemoryToolWriteRequest): Promise<AssistantMemory> => {
      seen = req;
      return Promise.resolve(sampleMemory({ confidence: req.confidence }));
    },
  }));
  const tool: AgentTool = toolByName(tools, 'memory_tool');
  await execText(tool, { action: 'create', content: 'A' });
  assert.equal(seen!.scope, 'long_term');
  assert.equal(seen!.kind, 'note');
  assert.equal(seen!.confidence, 1);
  assert.deepEqual(seen!.sourceMessageIds, []);
  const text: string = await execText(tool, {
    action: 'create', content: 'B', scope: 'short_term', kind: 'project',
    sourceConversationId: 'conv-1', sourceMessageIds: ['m1', 'm2', 3],
    expiresAt: 100, confidence: 0.5,
  });
  assert.equal(seen!.scope, 'short_term');
  assert.equal(seen!.kind, 'project');
  assert.equal(seen!.sourceConversationId, 'conv-1');
  assert.deepEqual(seen!.sourceMessageIds, ['m1', 'm2', '3']); // contentOrNull:数字 → 文本
  assert.equal(seen!.expiresAt, 100);
  assert.equal(seen!.confidence, 0.5);
  assert.ok(text.indexOf('"confidence":0.5') >= 0);
});

test('memory_tool edit/delete/未知 action + 缺参错误(消息逐字)', async () => {
  const calls: string[] = [];
  const tools: AgentTool[] = createMemoryTools(depsWith({
    onUpdate: (id: number, content: string): Promise<AssistantMemory> => {
      calls.push(`update:${id}:${content}`);
      return Promise.resolve(sampleMemory({ id, content }));
    },
    onDelete: (id: number): Promise<void> => {
      calls.push(`delete:${id}`);
      return Promise.resolve();
    },
  }));
  const tool: AgentTool = toolByName(tools, 'memory_tool');
  const editText: string = await execText(tool, { action: 'edit', id: 12, content: '新内容' });
  assert.equal(editText, '{"id":12,"content":"新内容","scope":"long_term","kind":"user",'
    + '"expiresAt":null,"confidence":1.0,"pinned":false,"archived":false}');
  await execText(tool, { action: 'delete', id: 5 });
  assert.deepEqual(calls, ['update:12:新内容', 'delete:5']);
  await assert.rejects(
    (): Promise<string> => execText(tool, {}), /action is required/);
  await assert.rejects(
    (): Promise<string> => execText(tool, { action: 'create' }), /content is required/);
  await assert.rejects(
    (): Promise<string> => execText(tool, { action: 'create', content: 'x', scope: 'all' }),
    /scope must be one of \[core, short_term, long_term\]/);
  await assert.rejects(
    (): Promise<string> => execText(tool, { action: 'edit', content: 'x' }), /id is required/);
  await assert.rejects(
    (): Promise<string> => execText(tool, { action: 'wipe' }),
    /unknown action: wipe, must be one of \[create, edit, delete\]/);
});

// ===== 写路径纯函数 =====

test('bucketForScope/scopeForBucket/kindForBucketAdd(else→long_term 逐字)', () => {
  assert.equal(memoryBucketForScope('core'), '__global__');
  assert.equal(memoryBucketForScope('short_term'), '__short_term__');
  assert.equal(memoryBucketForScope('long_term'), '__long_term__');
  assert.equal(memoryScopeForBucket('__global__'), 'core');
  assert.equal(memoryScopeForBucket('__short_term__'), 'short_term');
  assert.equal(memoryScopeForBucket('__long_term__'), 'long_term');
  assert.equal(memoryScopeForBucket('assistant-xyz'), 'long_term'); // else 分支
  assert.equal(memoryKindForBucketAdd('__short_term__'), 'project');
  assert.equal(memoryKindForBucketAdd('__global__'), 'note');
});

test('appendMemoryRecord: id 自增/默认桶/confidence 钳/supersedes distinct/插入序', () => {
  const r1 = appendMemoryRecord([], {
    scope: 'long_term', kind: 'note', content: '首条',
  }, NOW);
  assert.equal(r1.record.id, 1); // 空库 → 1
  assert.equal(r1.record.assistantId, '__long_term__'); // 默认 bucketForScope
  assert.equal(r1.record.createdAt, NOW);
  assert.equal(r1.record.updatedAt, NOW);
  assert.equal(r1.record.archived, false);
  const r2 = appendMemoryRecord(r1.records, {
    scope: 'core', kind: 'user', content: '次条', confidence: 1.7,
    supersedesIds: [1, 1, 3], pinned: true,
  }, NOW + 1);
  assert.equal(r2.record.id, 2); // max+1
  assert.equal(r2.record.confidence, 1); // coerce 0-1 上界
  assert.deepEqual(r2.record.supersedesIds, [1, 3]); // distinct 保序
  assert.equal(r2.records.length, 2);
  assert.equal(r2.records[0].id, 1); // 插入序
  const r3 = appendMemoryRecord(r2.records, {
    scope: 'short_term', kind: 'project', content: '负置信', confidence: -0.5,
  }, NOW + 2);
  assert.equal(r3.record.confidence, 0); // coerce 下界
});

test('updateMemoryRecordContent: 命中 copy/未命中抛错(逐字)/delete 未命中 no-op', () => {
  const base: MemoryRecord[] = [record({ id: 1 }), record({ id: 2 })];
  const out = updateMemoryRecordContent(base, 2, '改写', NOW + 9);
  assert.equal(out.record.content, '改写');
  assert.equal(out.record.updatedAt, NOW + 9);
  assert.equal(out.records[0].content, '内容'); // 其他不动
  assert.equal(base[1].content, '内容'); // 输入不突变
  assert.throws(
    (): void => { updateMemoryRecordContent(base, 99, 'x', NOW); },
    /Memory record #99 not found/);
  assert.deepEqual(deleteMemoryRecord(base, 99).length, 2); // SQL DELETE 0 行语义
  assert.deepEqual(deleteMemoryRecord(base, 1).map((r: MemoryRecord): number => r.id), [2]);
});

test('memoriesOfAssistant/toAssistantMemory: 桶过滤 + 插入序 + 字段映射', () => {
  const base: MemoryRecord[] = [
    record({ id: 5, assistantId: '__global__', pinned: true }),
    record({ id: 2, assistantId: '__long_term__' }),
    record({ id: 9, assistantId: '__global__' }),
  ];
  const out: AssistantMemory[] = memoriesOfAssistant(base, '__global__');
  assert.deepEqual(out.map((m: AssistantMemory): number => m.id), [5, 9]); // 插入序
  assert.equal(out[0].pinned, true);
  const mapped: AssistantMemory = memoryRecordToAssistantMemory(base[1]);
  assert.deepEqual(mapped, {
    id: 2, content: '内容', scope: 'long_term', kind: 'note',
    expiresAt: null, confidence: 1, pinned: false, archived: false,
  });
});

// ===== 工具 schema 形状 =====
