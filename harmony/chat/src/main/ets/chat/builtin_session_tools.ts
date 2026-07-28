// builtin_session_tools — session_* 历史会话工具组(D-058)
//
// Android 基准: app/feature/tools/ConversationHistoryTools.kt 全文(391 行)
//   - session_list/session_search/session_read/session_expand 四件
//   - 数据源 = ConversationRepository(摘要列表 + 节点);grant 门禁 =
//     SessionAccessGrantStore(D-058 已移植)
// 偏差登记:
//   - session_search 的 FTS 索引(MessageSearchResult bm25 排序)未移植 →
//     全量子串扫描,hit 顺序 = 会话列表序(摘要 updateAt 降序)后消息序,
//     snippet = previewAround 窗口(240)而非 FTS 上下文片段
//   - countConversationNodes 专用计数查询未建 → getById 整载计数(N+1,
//     MVP 规模可接受)
//   - 节点分页(pageSize 24)→ 整载后切分(语义等价)
//   - updated_date = ISO_LOCAL_DATE_TIME(系统时区,无偏移后缀)

import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessage, UIMessagePart } from './message.ts';
import type { UIMessagePartText, UIMessagePartTool, UIMessagePartDocument, UIMessagePartReasoning } from './message.ts';
import { isToolExecuted } from './message.ts';
import type { Conversation, MessageNode } from './conversation.ts';
import { nodeCurrentMessage } from './conversation.ts';
import type { ConversationRepository, ConversationSummary } from './persistence.ts';
import type { AgentTool } from './tool.ts';
import { makeAgentTool, makeInputSchemaObj } from './tool.ts';
import type { SessionAccessGrantStore } from './session_grant_store.ts';
import type { GrantValidation } from './session_grant_store.ts';
import { previewAround } from './context_engine.ts';
import { toText } from './message.ts';

// ===== 调用面(ConversationHistoryTools 构造参) =====

export interface ConversationHistoryToolsDeps {
  repository: ConversationRepository;
  currentConversationProvider: () => Promise<Conversation>;
  // 缺省 = 空 store(任何 grant 恒 unknown — 与 Android 空 store 一致)
  grantStore?: SessionAccessGrantStore;
}

// ===== prop builders(:372-391) =====

const stringProp = (description: string): JsonObject => ({ type: 'string', description });
const intProp = (description: string): JsonObject => ({ type: 'integer', description });
const boolProp = (description: string): JsonObject => ({ type: 'boolean', description });
const arrayProp = (description: string): JsonObject => ({
  type: 'array', description, items: { type: 'string' },
});

// ===== 输入解析(jsonPrimitive contentOrNull/intOrNull/booleanOrNull 语义) =====

const asObject = (input: JsonValue): JsonObject => {
  if (typeof input !== 'object' || input === null || Array.isArray(input)) return {};
  return input as JsonObject;
};

const inputString = (input: JsonValue, key: string): string => {
  const v: JsonValue | undefined = asObject(input)[key];
  return typeof v === 'string' ? v : '';
};

const inputInt = (input: JsonValue, key: string): number | null => {
  const v: JsonValue | undefined = asObject(input)[key];
  return typeof v === 'number' ? v : null;
};

const inputBool = (input: JsonValue, key: string): boolean | null => {
  const v: JsonValue | undefined = asObject(input)[key];
  return typeof v === 'boolean' ? v : null;
};

// scope(:368-369):仅 'all' 生效,其余(含缺省)→ current_assistant
const inputScope = (input: JsonValue): string => {
  const v: string = inputString(input, 'scope');
  return v === 'all' ? 'all' : 'current_assistant';
};

// stringArray(:370-371):trim 后非空白
const inputStringArray = (input: JsonValue, key: string): string[] => {
  const v: JsonValue | undefined = asObject(input)[key];
  if (!Array.isArray(v)) return [];
  const out: string[] = [];
  for (const item of v) {
    if (typeof item !== 'string') continue;
    const t: string = item.trim();
    if (t.length > 0) out.push(t);
  }
  return out;
};

// ===== 输出件 =====

const errorPayload = (code: string, message: string): JsonObject => ({
  status: 'failed', code, error: message,
});

// ISO_LOCAL_DATE_TIME(系统时区;毫秒非零才带 .SSS)
const localIsoDateTime = (epochMs: number): string => {
  const d: Date = new Date(epochMs);
  const pad = (n: number, w: number = 2): string => String(n).padStart(w, '0');
  const ms: number = d.getMilliseconds();
  const fraction: string = ms !== 0 ? `.${pad(ms, 3)}` : '';
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
    + `T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}${fraction}`;
};

// formatMessage(:316-341 逐字)
const formatMessage = (message: UIMessage, includeTools: boolean): string => {
  const parts: string[] = [];
  for (const part of message.parts) {
    if (part.type === 'text') {
      parts.push((part as UIMessagePartText).text);
    } else if (part.type === 'tool') {
      const t: UIMessagePartTool = part as UIMessagePartTool;
      if (includeTools) {
        const outputText: string = t.output
          .filter((p: UIMessagePart): boolean => p.type === 'text')
          .map((p: UIMessagePart): string => (p as UIMessagePartText).text)
          .join('\n');
        parts.push(`[tool:${t.toolName} input=${t.input.slice(0, 800)} output_tail=${outputText.slice(-1200)}]`);
      } else {
        parts.push(`[tool:${t.toolName} executed=${isToolExecuted(t)}]`);
      }
    } else if (part.type === 'image') {
      parts.push('[image]');
    } else if (part.type === 'video') {
      parts.push('[video]');
    } else if (part.type === 'audio') {
      parts.push('[audio]');
    } else if (part.type === 'document') {
      parts.push(`[document:${(part as UIMessagePartDocument).fileName}]`);
    } else if (part.type === 'reasoning') {
      parts.push(`[reasoning:${(part as UIMessagePartReasoning).reasoning.slice(0, 300)}]`);
    }
    // 其余 part 类型 → null(Android mapNotNull 同语义)
  }
  return parts.join('\n').trim();
};

// buildTranscript(:296-314 逐字)
const buildTranscript = (messages: UIMessage[], includeTools: boolean, maxChars: number): string => {
  let builder: string = '';
  for (const message of messages) {
    const line: string = `[${message.role} ${message.id}] ${formatMessage(message, includeTools)}`.trim();
    if (line.length === 0) continue;
    if (builder.length + line.length + 2 > maxChars) {
      builder += '\n...[truncated]...';
      break;
    }
    builder += `${line}\n\n`;
  }
  return builder.trim().slice(0, maxChars);
};

type GrantCheck = { kind: 'allowed'; maxChars: number } | { kind: 'denied'; reason: string };

export const createConversationHistoryTools = (
  deps: ConversationHistoryToolsDeps,
): AgentTool[] => {
  const validateGrant = (grantId: string, sessionId: string, requestedChars: number): GrantCheck => {
    if (grantId.trim().length === 0) return { kind: 'allowed', maxChars: requestedChars };
    if (deps.grantStore === undefined) {
      return { kind: 'denied', reason: 'Unknown or expired session access grant.' };
    }
    const validation: GrantValidation = deps.grantStore.validate(grantId, sessionId, requestedChars);
    return validation.kind === 'allowed'
      ? { kind: 'allowed', maxChars: validation.allowedChars }
      : { kind: 'denied', reason: validation.reason };
  };

  // toSessionSummary(:345-354);message_nodes 计数 = getById 整载(偏差登记)
  const toSessionSummary = async (summary: ConversationSummary): Promise<JsonObject> => {
    const createMs: number = Date.parse(summary.createAt);
    const updateMs: number = Date.parse(summary.updateAt);
    const full: Conversation | null = await deps.repository.getById(summary.id);
    return {
      session_id: summary.id,
      assistant_id: summary.assistantId,
      title: summary.title,
      created_at: createMs,
      updated_at: updateMs,
      updated_date: localIsoDateTime(updateMs),
      message_nodes: full !== null ? full.messageNodes.length : 0,
      is_pinned: summary.isPinned,
    };
  };

  const loadConversationSummary = (sessionId: string): Promise<ConversationSummary | null> =>
    deps.repository.getSummaryById(sessionId);

  return [
    // session_list(:38-79)
    makeAgentTool({
      name: 'session_list',
      description: 'List historical AmberAgent sessions by metadata only. Use this before asking to read old session content.',
      parameters: () => makeInputSchemaObj({
        query: stringProp('Optional title keyword.'),
        scope: stringProp('current_assistant or all. Default current_assistant.'),
        limit: intProp('Maximum sessions, default 12, capped at 50.'),
      }),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
        const query: string = inputString(input, 'query');
        const scope: string = inputScope(input);
        const rawLimit: number | null = inputInt(input, 'limit');
        const limit: number = rawLimit !== null ? Math.min(Math.max(rawLimit, 1), 50) : 12;
        const current: Conversation = await deps.currentConversationProvider();
        const scopedAssistant: string | undefined =
          scope === 'all' ? undefined : current.assistantId;
        let sessions: ConversationSummary[];
        if (query.trim().length === 0) {
          sessions = await deps.repository.listRecent(limit, scopedAssistant);
        } else {
          const found: ConversationSummary[] =
            await deps.repository.search(query, scopedAssistant);
          sessions = found.slice(0, limit);
        }
        const summaries: JsonObject[] = [];
        for (const s of sessions) {
          summaries.push(await toSessionSummary(s));
        }
        const payload: JsonObject = { status: 'ok', scope, query, sessions: summaries };
        return [{ type: 'text', text: JSON.stringify(payload), metadata: null }];
      },
    }),
    // session_search(:81-128;FTS → 全量子串扫描,偏差登记见文件头)
    makeAgentTool({
      name: 'session_search',
      description: 'Search snippets across historical sessions. Returns short excerpts, not full transcripts.',
      parameters: () => makeInputSchemaObj(
        {
          query: stringProp('Keyword query to search in historical transcript text.'),
          scope: stringProp('current_assistant or all. Default current_assistant.'),
          session_ids: arrayProp('Optional list of session ids to restrict search.'),
          limit: intProp('Maximum hits, default 10, capped at 30.'),
        },
        ['query'],
      ),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
        const query: string = inputString(input, 'query');
        const scope: string = inputScope(input);
        const rawLimit: number | null = inputInt(input, 'limit');
        const limit: number = rawLimit !== null ? Math.min(Math.max(rawLimit, 1), 30) : 10;
        const sessionIds: Set<string> = new Set<string>(inputStringArray(input, 'session_ids'));
        const current: Conversation = await deps.currentConversationProvider();
        const scopedAssistant: string | undefined =
          scope === 'all' ? undefined : current.assistantId;
        const summaries: ConversationSummary[] = await deps.repository.list(scopedAssistant);
        const needle: string = query.toLowerCase();
        const hits: JsonObject[] = [];
        for (const s of summaries) {
          if (hits.length >= limit) break;
          if (sessionIds.size > 0 && !sessionIds.has(s.id)) continue;
          const full: Conversation | null = await deps.repository.getById(s.id);
          if (full === null) continue;
          const updateMs: number = Date.parse(s.updateAt);
          for (const node of full.messageNodes) {
            if (hits.length >= limit) break;
            for (const message of node.messages) {
              if (hits.length >= limit) break;
              const text: string = toText(message);
              if (query.trim().length === 0 || !text.toLowerCase().includes(needle)) continue;
              hits.push({
                session_id: s.id,
                title: s.title,
                message_id: message.id,
                node_id: node.id,
                updated_at: updateMs,
                snippet: previewAround(text, query).slice(0, 1200),
              });
            }
          }
        }
        const payload: JsonObject = { status: 'ok', scope, query, hits };
        return [{ type: 'text', text: JSON.stringify(payload), metadata: null }];
      },
    }),
    // session_read(:130-174)
    makeAgentTool({
      name: 'session_read',
      description: 'Read a bounded transcript from a specified historical session. Requires approval unless a valid session access grant is supplied.',
      needsApproval: true,
      allowsAutoApproval: true,
      parameters: () => makeInputSchemaObj(
        {
          session_id: stringProp('Historical session id returned by session_list or session_search.'),
          grant_id: stringProp('Optional SessionAccessGrant id for history subagents.'),
          max_chars: intProp('Maximum transcript chars, default 20000, capped at 60000.'),
          max_messages: intProp('Maximum messages, default 80, capped at 200.'),
          include_tools: boolProp('Include compact tool input/output previews. Default false.'),
        },
        ['session_id'],
      ),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
        const sessionId: string = inputString(input, 'session_id');
        const grantId: string = inputString(input, 'grant_id');
        const rawChars: number | null = inputInt(input, 'max_chars');
        const requestedChars: number =
          rawChars !== null ? Math.min(Math.max(rawChars, 1_000), 60_000) : 20_000;
        const rawMessages: number | null = inputInt(input, 'max_messages');
        const maxMessages: number =
          rawMessages !== null ? Math.min(Math.max(rawMessages, 1), 200) : 80;
        const includeTools: boolean = inputBool(input, 'include_tools') ?? false;
        const grantValidation: GrantCheck = validateGrant(grantId, sessionId, requestedChars);
        if (grantValidation.kind === 'denied') {
          return [{
            type: 'text',
            text: JSON.stringify(errorPayload('grant_denied', grantValidation.reason)),
            metadata: null,
          }];
        }
        const maxChars: number = grantValidation.maxChars;
        const summary: ConversationSummary | null = await loadConversationSummary(sessionId);
        if (summary === null) {
          return [{
            type: 'text',
            text: JSON.stringify(errorPayload('not_found', `Unknown session_id: ${sessionId}`)),
            metadata: null,
          }];
        }
        const full: Conversation | null = await deps.repository.getById(sessionId);
        // loadMessagesFromStart(:272-293):自起始铺平全部节点消息(含分支),
        //   上限 maxMessages(分页 → 整载切分,偏差登记)
        const messages: UIMessage[] = [];
        if (full !== null) {
          for (const node of full.messageNodes) {
            if (messages.length >= maxMessages) break;
            for (const message of node.messages) {
              if (messages.length >= maxMessages) break;
              messages.push(message);
            }
          }
        }
        const transcript: string = buildTranscript(messages, includeTools, maxChars);
        const sessionSummary: JsonObject = await toSessionSummary(summary);
        if (grantId.trim().length > 0 && deps.grantStore !== undefined) {
          deps.grantStore.recordUse(grantId, transcript.length);
        }
        const payload: JsonObject = {
          status: 'ok',
          session: sessionSummary,
          message_count: messages.length,
          max_chars: maxChars,
          truncated: transcript.length >= maxChars,
          transcript,
        };
        return [{ type: 'text', text: JSON.stringify(payload), metadata: null }];
      },
    }),
    // session_expand(:176-259)
    makeAgentTool({
      name: 'session_expand',
      description: 'Expand original messages around a message_id or node_id in a specified historical session. Requires approval unless a valid grant is supplied.',
      needsApproval: true,
      allowsAutoApproval: true,
      parameters: () => makeInputSchemaObj(
        {
          session_id: stringProp('Historical session id.'),
          source_id: stringProp('Message id or node id to expand.'),
          grant_id: stringProp('Optional SessionAccessGrant id for history subagents.'),
          radius: intProp('Neighboring message radius, default 2, capped at 8.'),
          max_chars: intProp('Maximum output chars, default 20000, capped at 60000.'),
          include_tools: boolProp('Include compact tool previews. Default false.'),
        },
        ['session_id', 'source_id'],
      ),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
        const sessionId: string = inputString(input, 'session_id');
        const sourceId: string = inputString(input, 'source_id');
        const grantId: string = inputString(input, 'grant_id');
        const rawRadius: number | null = inputInt(input, 'radius');
        const radius: number = rawRadius !== null ? Math.min(Math.max(rawRadius, 0), 8) : 2;
        const rawChars: number | null = inputInt(input, 'max_chars');
        const requestedChars: number =
          rawChars !== null ? Math.min(Math.max(rawChars, 1_000), 60_000) : 20_000;
        const includeTools: boolean = inputBool(input, 'include_tools') ?? false;
        const grantValidation: GrantCheck = validateGrant(grantId, sessionId, requestedChars);
        if (grantValidation.kind === 'denied') {
          return [{
            type: 'text',
            text: JSON.stringify(errorPayload('grant_denied', grantValidation.reason)),
            metadata: null,
          }];
        }
        const maxChars: number = grantValidation.maxChars;
        const summary: ConversationSummary | null = await loadConversationSummary(sessionId);
        if (summary === null) {
          return [{
            type: 'text',
            text: JSON.stringify(errorPayload('not_found', `Unknown session_id: ${sessionId}`)),
            metadata: null,
          }];
        }
        const full: Conversation | null = await deps.repository.getById(sessionId);
        const notFound = (): UIMessagePart[] => [{
          type: 'text',
          text: JSON.stringify(errorPayload('not_found', 'source_id not found in session.')),
          metadata: null,
        }];
        if (full === null) return notFound();
        // resolveSourceNodeId(:295-300):节点 id 直中 → 任意分支消息 id →
        //   currentMessage id(findNodeIdForMessage/ContainingMessage 同序)
        const nodes: MessageNode[] = full.messageNodes;
        let sourceNodeIndex: number = nodes.findIndex(
          (n: MessageNode): boolean => n.id === sourceId);
        if (sourceNodeIndex < 0) {
          sourceNodeIndex = nodes.findIndex((n: MessageNode): boolean =>
            n.messages.some((m: UIMessage): boolean => m.id === sourceId));
        }
        if (sourceNodeIndex < 0) {
          sourceNodeIndex = nodes.findIndex((n: MessageNode): boolean =>
            nodeCurrentMessage(n).id === sourceId);
        }
        if (sourceNodeIndex < 0) return notFound();
        const startNodeIndex: number = Math.max(sourceNodeIndex - radius, 0);
        const endNodeIndex: number = Math.min(sourceNodeIndex + radius, nodes.length - 1);
        // 铺平(节点区间 → 全局 nodeIndex + 节点内 messageIndex)
        interface IndexedHistoryMessage {
          nodeId: string;
          nodeIndex: number;
          messageIndex: number;
          message: UIMessage;
        }
        const indexed: IndexedHistoryMessage[] = [];
        for (let i = startNodeIndex; i <= endNodeIndex; i++) {
          const node: MessageNode = nodes[i];
          for (let mi = 0; mi < node.messages.length; mi++) {
            indexed.push({
              nodeId: node.id,
              nodeIndex: i,
              messageIndex: mi,
              message: node.messages[mi],
            });
          }
        }
        const matchIndex: number = indexed.findIndex(
          (it: IndexedHistoryMessage): boolean =>
            it.message.id === sourceId || it.nodeId === sourceId);
        if (matchIndex < 0) return notFound();
        const selected: IndexedHistoryMessage[] = indexed.slice(
          Math.max(matchIndex - radius, 0),
          Math.min(matchIndex + radius + 1, indexed.length),
        );
        const transcript: string = buildTranscript(
          selected.map((it: IndexedHistoryMessage): UIMessage => it.message),
          includeTools, maxChars);
        const sessionSummary: JsonObject = await toSessionSummary(summary);
        if (grantId.trim().length > 0 && deps.grantStore !== undefined) {
          deps.grantStore.recordUse(grantId, transcript.length);
        }
        const payload: JsonObject = {
          status: 'ok',
          session: sessionSummary,
          source_id: sourceId,
          messages: selected.map((it: IndexedHistoryMessage): JsonObject => ({
            node_id: it.nodeId,
            node_index: it.nodeIndex,
            message_index: it.messageIndex,
            message_id: it.message.id,
            role: it.message.role,
            text: formatMessage(it.message, includeTools).slice(0, 8000),
          })),
          truncated: transcript.length >= maxChars,
        };
        return [{ type: 'text', text: JSON.stringify(payload), metadata: null }];
      },
    }),
  ];
};
