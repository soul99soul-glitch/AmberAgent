// builtin_memory_tools — MemoryTools.kt 全文移植(D-085c)
//
// Android 基准: app/.../core/ai/tools/MemoryTools.kt(全文 344 行)
//   - memory_list(:42-85):type 默认 all,require 集合校验;payload 键序
//     type/count/memories;条目 toJson(:333-343)键序 id/type/scope/kind/
//     content/expiresAt(仅非 null)/confidence/pinned/archived
//   - memory_write(:86-156):needsApproval=true + allowsAutoApproval=false;
//     type 默认 long_term;AssistantMemory serializer payload
//   - memory_delete(:157-182):needsApproval=true + allowsAutoApproval=false;
//     payload {success:true, id}
//   - memory_tool(:183-331):描述逐字(trimIndent;Today is ${toLocalString(true)}
//     → deps.todayText 快照);action create/edit/delete;未知 action error
// 偏差登记:
//   - AssistantMemory serializer 形状 = JsonInstant(encodeDefaults=true,
//     explicitNulls 默认 true)→ 全字段恒在,expiresAt null → null 字面量;
//     confidence Float 序列化:整数值 → 'N.0'(JS 数字无 int/float 之分,
//     经 confidenceToJsonText 还原),其余 String(v)
//   - Kotlin require/error → throw new Error(消息逐字)
//   - expiresAt 解析:contentOrNull?.toLongOrNull() — JSON 数字 123 → '123' → 123;
//     '123.5'/非数字 → null(见 inputLongOrNull)
//   - onCreation 的 source 拼接('${content}\nSource: ${source}')在 Android 由
//     ChatService 接线层完成(ChatService.kt:2365-2369)→ 属 deps 实现侧,不在本模块

import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessagePart } from './message.ts';
import type { MemoryKind, MemoryScope } from './memory_models.ts';
import { memoryKindFromWireName, memoryScopeFromWireName } from './memory_models.ts';
import type { AgentTool } from './tool.ts';
import { makeAgentTool, makeInputSchemaObj } from './tool.ts';

// ===== AssistantMemory(core/model/Assistant.kt:107-117) =====

export interface AssistantMemory {
  id: number;
  content: string;
  scope: MemoryScope;
  kind: MemoryKind;
  expiresAt: number | null;
  confidence: number;
  pinned: boolean;
  archived: boolean;
}

export const makeAssistantMemory = (
  id: number, content: string, scope: MemoryScope, kind: MemoryKind,
  expiresAt: number | null = null, confidence: number = 1,
  pinned: boolean = false, archived: boolean = false,
): AssistantMemory => ({ id, content, scope, kind, expiresAt, confidence, pinned, archived });

// ===== MemoryToolWriteRequest(MemoryTools.kt:24-33) =====

export interface MemoryToolWriteRequest {
  scope: MemoryScope;
  kind: MemoryKind;
  content: string;
  source: string | null;
  sourceConversationId: string | null;
  sourceMessageIds: string[];
  expiresAt: number | null;
  confidence: number;
}

export interface MemoryToolDeps {
  // 'Today is ${LocalDate.now().toLocalString(true)}' 的日期文本(建工具时快照,
  //   本地化 MEDIUM 日期,entry 侧 Intl.DateTimeFormat dateStyle:'medium')
  todayText: string;
  onList: (scope: string) => Promise<AssistantMemory[]>;
  onCreation: (request: MemoryToolWriteRequest) => Promise<AssistantMemory>;
  onUpdate: (id: number, content: string) => Promise<AssistantMemory>;
  onDelete: (id: number) => Promise<void>;
}

// ===== 输入解析(jsonPrimitive contentOrNull/intOrNull/floatOrNull 语义) =====

const asObject = (input: JsonValue): JsonObject => {
  if (typeof input !== 'object' || input === null || Array.isArray(input)) return {};
  return input as JsonObject;
};

// contentOrNull:缺失/非 primitive → null;数字/布尔 → 其文本
const inputContentOrNull = (input: JsonValue, key: string): string | null => {
  const v: JsonValue | undefined = asObject(input)[key];
  if (typeof v === 'string') return v;
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  return null;
};

// contentOrNull?.toLongOrNull():整数文本 → number,否则 null
const inputLongOrNull = (input: JsonValue, key: string): number | null => {
  const text: string | null = inputContentOrNull(input, key);
  if (text === null) return null;
  if (!/^-?\d+$/.test(text)) return null;
  const n: number = Number.parseInt(text, 10);
  return Number.isSafeInteger(n) ? n : null;
};

// intOrNull:仅 JSON 数字生效(kotlinx intOrNull 对字符串 primitive 亦解析 —
//   content.toIntOrNull();此处对齐:数字或整数字符串)
const inputIntOrNull = (input: JsonValue, key: string): number | null => {
  const v: JsonValue | undefined = asObject(input)[key];
  if (typeof v === 'number') return Number.isInteger(v) ? v : null;
  if (typeof v === 'string' && /^-?\d+$/.test(v)) {
    const n: number = Number.parseInt(v, 10);
    return Number.isSafeInteger(n) ? n : null;
  }
  return null;
};

// floatOrNull ?: 1f
const inputFloatOr = (input: JsonValue, key: string, fallback: number): number => {
  const v: JsonValue | undefined = asObject(input)[key];
  if (typeof v === 'number') return v;
  if (typeof v === 'string') {
    const n: number = Number.parseFloat(v);
    return Number.isFinite(n) ? n : fallback;
  }
  return fallback;
};

// jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
const inputStringArray = (input: JsonValue, key: string): string[] => {
  const v: JsonValue | undefined = asObject(input)[key];
  if (!Array.isArray(v)) return [];
  const out: string[] = [];
  for (const item of v) {
    if (typeof item === 'string') out.push(item);
    else if (typeof item === 'number' || typeof item === 'boolean') out.push(String(item));
  }
  return out;
};

// ===== payload 形状 =====

// Kotlin Float 序列化文本:整数值 → 'N.0',其余 String(v)
const floatJsonText = (v: number): string => Number.isInteger(v) ? `${v}.0` : String(v);

// JsonInstant.encodeToJsonElement(AssistantMemory.serializer(), m):
//   encodeDefaults=true + explicitNulls=true → 全字段恒在,声明序
const assistantMemoryToSerializerJson = (m: AssistantMemory): JsonObject => ({
  id: m.id,
  content: m.content,
  scope: m.scope,
  kind: m.kind,
  expiresAt: m.expiresAt,
  confidence: m.confidence,
  pinned: m.pinned,
  archived: m.archived,
});

// 序列化 payload 文本:JS number 无 int/float 之分,JSON.stringify(1) → '1'
//   而 Kotlin Float 1f → '1.0';对 confidence 做文本级还原(其他字段类型无歧义)
const serializerPayloadText = (m: AssistantMemory): string => {
  const raw: string = JSON.stringify(assistantMemoryToSerializerJson(m));
  return raw.replace(`"confidence":${m.confidence}`, `"confidence":${floatJsonText(m.confidence)}`);
};

// AssistantMemory.toJson(scope)(MemoryTools.kt:333-343)— expiresAt 仅非 null 时放
const assistantMemoryToListJson = (m: AssistantMemory, scope: string): JsonObject => {
  const out: JsonObject = {
    id: m.id,
    type: m.scope.length > 0 ? m.scope : scope,
    scope: m.scope,
    kind: m.kind,
    content: m.content,
  };
  if (m.expiresAt !== null) out['expiresAt'] = m.expiresAt;
  out['confidence'] = m.confidence;
  out['pinned'] = m.pinned;
  out['archived'] = m.archived;
  return out;
};

const textPart = (text: string): UIMessagePart[] => [{ type: 'text', text, metadata: null }];

const MEMORY_KIND_WIRE_NAMES: string[] = ['user', 'feedback', 'project', 'reference', 'routine', 'note'];

// ===== createMemoryTools(buildMemoryTools :35-331) =====

export const createMemoryTools = (deps: MemoryToolDeps): AgentTool[] => [
  // memory_list(:42-85)
  makeAgentTool({
    name: 'memory_list',
    description: 'List AmberAgent memory entries by type: core, short_term, long_term, or all.',
    parameters: () => makeInputSchemaObj({
      type: {
        type: 'string',
        enum: ['core', 'short_term', 'long_term', 'all'],
        description: 'Memory type to list. Defaults to all.',
      },
    }),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const type: string = inputContentOrNull(input, 'type') ?? 'all';
      if (['core', 'short_term', 'long_term', 'all'].indexOf(type) < 0) {
        throw new Error('type must be core, short_term, long_term, or all');
      }
      const entries: Array<[string, AssistantMemory]> = [];
      if (type === 'all') {
        for (const scope of ['core', 'short_term', 'long_term']) {
          const list: AssistantMemory[] = await deps.onList(scope);
          for (const m of list) entries.push([scope, m]);
        }
      } else {
        const list: AssistantMemory[] = await deps.onList(type);
        for (const m of list) entries.push([type, m]);
      }
      const payload: JsonObject = {
        type,
        count: entries.length,
        memories: entries.map((e: [string, AssistantMemory]): JsonObject =>
          assistantMemoryToListJson(e[1], e[0])),
      };
      return textPart(JSON.stringify(payload));
    },
  }),
  // memory_write(:86-156)
  makeAgentTool({
    name: 'memory_write',
    description: 'Create a new AmberAgent memory entry. Core and long-term memory should be stable and important; short-term memory is for current project/task continuity.',
    parameters: () => makeInputSchemaObj(
      {
        type: {
          type: 'string',
          enum: ['core', 'short_term', 'long_term'],
          description: 'Memory type. Defaults to long_term.',
        },
        content: { type: 'string', description: 'Memory content.' },
        source: { type: 'string', description: 'Optional source note.' },
        kind: {
          type: 'string',
          enum: MEMORY_KIND_WIRE_NAMES,
          description: 'Structured memory kind. Defaults to note.',
        },
        expiresAt: { type: 'integer', description: 'Optional expiration time in epoch milliseconds.' },
      },
      ['content'],
    ),
    needsApproval: true,
    allowsAutoApproval: false,
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const type: string = inputContentOrNull(input, 'type') ?? 'long_term';
      if (['core', 'short_term', 'long_term'].indexOf(type) < 0) {
        throw new Error('type must be core, short_term, or long_term');
      }
      const content: string | null = inputContentOrNull(input, 'content');
      if (content === null) throw new Error('content is required');
      const source: string | null = inputContentOrNull(input, 'source');
      const kindText: string | null = inputContentOrNull(input, 'kind');
      const kind: MemoryKind = kindText !== null ? memoryKindFromWireName(kindText) : 'note';
      const expiresAt: number | null = inputLongOrNull(input, 'expiresAt');
      const memory: AssistantMemory = await deps.onCreation({
        scope: memoryScopeFromWireName(type),
        kind,
        content,
        source,
        sourceConversationId: null,
        sourceMessageIds: [],
        expiresAt,
        confidence: 1,
      });
      return textPart(serializerPayloadText(memory));
    },
  }),
  // memory_delete(:157-182)
  makeAgentTool({
    name: 'memory_delete',
    description: 'Delete an AmberAgent memory entry by id. This is high risk and always requires explicit approval.',
    parameters: () => makeInputSchemaObj(
      {
        id: { type: 'integer', description: 'Memory id to delete.' },
      },
      ['id'],
    ),
    needsApproval: true,
    allowsAutoApproval: false,
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const id: number | null = inputIntOrNull(input, 'id');
      if (id === null) throw new Error('id is required');
      await deps.onDelete(id);
      const payload: JsonObject = { success: true, id };
      return textPart(JSON.stringify(payload));
    },
  }),
  // memory_tool(:183-331)
  makeAgentTool({
    name: 'memory_tool',
    description: 'The memory tool stores layered information across AmberAgent conversations.\n' +
      'Use `action` to control the operation: `create` (add), `edit` (update), `delete` (remove).\n' +
      'Use `scope` for create:\n' +
      '- `core`: durable identity, behavior rules, or explicit facts the user wants injected everywhere.\n' +
      '- `short_term`: concise summaries of the active project or recent conversations.\n' +
      '- `long_term`: stable preferences, recurring interests, plans, and factual context.\n' +
      'Use `kind` for create: `user`, `feedback`, `project`, `reference`, `routine`, or `note`.\n' +
      '- No relevant record: `create` + `content`\n' +
      '- Existing relevant record: `edit` + `id` + `content`\n' +
      '- Outdated/irrelevant record: `delete` + `id`\n' +
      'Memories will automatically appear in later conversations when the corresponding memory module is enabled.\n' +
      'Do not store sensitive information (e.g., ethnicity, religion, sexual orientation, political views, sex life, criminal records).\n' +
      'You may store: preferred name, preferences, plans, work-related notes, chat style preferences, first chat time, etc.\n' +
      'Do not show memory content directly in the conversation unless the user explicitly asks.\n' +
      `Today is ${deps.todayText}.\n` +
      'Similar memories should be merged; prefer updating existing records.\n' +
      '\n' +
      'Examples:\n' +
      '{"action":"create","scope":"long_term","content":"User prefers brief replies and is more active on weekends."}\n' +
      '{"action":"create","scope":"short_term","content":"Current thread is about building AmberAgent Android agent features."}\n' +
      '{"action":"edit","id":12,"content":"User’s preferred name updated to “A-Xing”, prefers Chinese replies."}\n' +
      '{"action":"delete","id":7}',
    parameters: () => makeInputSchemaObj(
      {
        action: {
          type: 'string',
          enum: ['create', 'edit', 'delete'],
          description: 'Operation to perform: create, edit, or delete',
        },
        id: { type: 'integer', description: 'The id of the memory record (required for edit/delete)' },
        scope: {
          type: 'string',
          enum: ['core', 'short_term', 'long_term'],
          description: 'The memory scope for create. Defaults to long_term.',
        },
        kind: {
          type: 'string',
          enum: MEMORY_KIND_WIRE_NAMES,
          description: 'The memory kind for create. Defaults to note.',
        },
        content: { type: 'string', description: 'The content of the memory record (required for create/edit)' },
        sourceConversationId: { type: 'string', description: 'Optional source conversation id.' },
        sourceMessageIds: {
          type: 'array',
          description: 'Optional source message ids.',
          items: { type: 'string' },
        },
        expiresAt: { type: 'integer', description: 'Optional expiration time in epoch milliseconds.' },
        confidence: { type: 'number', description: 'Confidence from 0 to 1. Defaults to 1.' },
      },
      ['action'],
    ),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const action: string | null = inputContentOrNull(input, 'action');
      if (action === null) throw new Error('action is required');
      if (action === 'create') {
        const content: string | null = inputContentOrNull(input, 'content');
        if (content === null) throw new Error('content is required');
        const scope: string = inputContentOrNull(input, 'scope') ?? 'long_term';
        if (['core', 'short_term', 'long_term'].indexOf(scope) < 0) {
          throw new Error('scope must be one of [core, short_term, long_term]');
        }
        const kindText: string | null = inputContentOrNull(input, 'kind');
        const kind: MemoryKind = kindText !== null ? memoryKindFromWireName(kindText) : 'note';
        const sourceConversationId: string | null = inputContentOrNull(input, 'sourceConversationId');
        const sourceMessageIds: string[] = inputStringArray(input, 'sourceMessageIds');
        const expiresAt: number | null = inputLongOrNull(input, 'expiresAt');
        const confidence: number = inputFloatOr(input, 'confidence', 1);
        const memory: AssistantMemory = await deps.onCreation({
          scope: memoryScopeFromWireName(scope),
          kind,
          content,
          source: null,
          sourceConversationId,
          sourceMessageIds,
          expiresAt,
          confidence,
        });
        return textPart(serializerPayloadText(memory));
      }
      if (action === 'edit') {
        const id: number | null = inputIntOrNull(input, 'id');
        if (id === null) throw new Error('id is required');
        const content: string | null = inputContentOrNull(input, 'content');
        if (content === null) throw new Error('content is required');
        const memory: AssistantMemory = await deps.onUpdate(id, content);
        return textPart(serializerPayloadText(memory));
      }
      if (action === 'delete') {
        const id: number | null = inputIntOrNull(input, 'id');
        if (id === null) throw new Error('id is required');
        await deps.onDelete(id);
        const payload: JsonObject = { success: true, id };
        return textPart(JSON.stringify(payload));
      }
      throw new Error(`unknown action: ${action}, must be one of [create, edit, delete]`);
    },
  }),
];
