// builtin_conversation_tools — conversation_* 工具组(D-058)
//
// Android 基准: app/feature/tools/ConversationContextTools.kt 全文 166 行
//   - 数据源 = ConversationContextEngine → Repository(compact 摘要 + 当前
//     会话节点扫描);status/search/expand/compact 四件
//   - 输出 = 单 Text part,JSON 字符串(键序逐字)
// 偏差登记:
//   - compact_lifecycle_status 恒 'idle'(生命周期 StateFlow 未移植,engine
//     层已登记);compress 任务模型未接 → 摘要生成复用聊天模型(D-055 同口径)

import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessage, UIMessagePart } from './message.ts';
import { toText } from './message.ts';
import type { Conversation } from './conversation.ts';
import type { AgentTool } from './tool.ts';
import { makeAgentTool, makeInputSchemaObj } from './tool.ts';
import type { CompactPolicy, CompactResult } from './context_compact.ts';
import type {
  CompactEngineDeps, CompactStore, ContextSearchResult,
} from './context_engine.ts';
import {
  compactConversation, conversationContextStatus,
  expandConversationContext, searchConversationContext,
} from './context_engine.ts';

// 调用面(ConversationContextTools 构造参:engine/conversation/settings/model)
export interface ConversationContextToolsDeps {
  // Android conversationProvider = getConversationFlow().value(最新会话)
  conversationProvider: () => Promise<Conversation>;
  compactStore: CompactStore;
  // settings.agentRuntime.contextCompaction.toCompactPolicy()
  policy: CompactPolicy;
  // settings.getCurrentChatModel()?.contextWindowTokens
  modelContextWindowTokens: number | null;
  // compactConversation 执行 deps(provider/store;摘要模型复用聊天模型)
  compactEngineDeps: CompactEngineDeps;
}

const inputString = (input: JsonValue, key: string): string => {
  if (typeof input !== 'object' || input === null || Array.isArray(input)) return '';
  const v: JsonValue | undefined = (input as JsonObject)[key];
  return typeof v === 'string' ? v : '';
};

const inputInt = (input: JsonValue, key: string): number | null => {
  if (typeof input !== 'object' || input === null || Array.isArray(input)) return null;
  const v: JsonValue | undefined = (input as JsonObject)[key];
  return typeof v === 'number' ? v : null;
};

export const createConversationContextTools = (
  deps: ConversationContextToolsDeps,
): AgentTool[] => [
  // conversation_context_status(:33-43)
  makeAgentTool({
    name: 'conversation_context_status',
    description: 'Inspect current conversation context pressure, compact summaries, and next automatic compression action.',
    parameters: () => makeInputSchemaObj({}),
    execute: async (_input: JsonValue): Promise<UIMessagePart[]> => {
      const conversation: Conversation = await deps.conversationProvider();
      const compacts = await deps.compactStore.getCompacts(conversation.id);
      const payload: JsonObject = conversationContextStatus(
        conversation, compacts, deps.policy, deps.modelContextWindowTokens);
      return [{ type: 'text', text: JSON.stringify(payload), metadata: null }];
    },
  }),
  // conversation_search(:45-86)
  makeAgentTool({
    name: 'conversation_search',
    description: 'Search this conversation\'s original transcript and compact summaries. Use before asking the user to repeat old details.',
    parameters: () => makeInputSchemaObj(
      {
        query: {
          type: 'string',
          description: 'Keyword to search in original transcript and compact summaries',
        },
        limit: { type: 'integer', description: 'Maximum results, default 8' },
      },
      ['query'],
    ),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const query: string = inputString(input, 'query');
      const limit: number = Math.min(Math.max(inputInt(input, 'limit') ?? 8, 1), 20);
      const conversation: Conversation = await deps.conversationProvider();
      const compacts = await deps.compactStore.getCompacts(conversation.id);
      const results: ContextSearchResult[] = searchConversationContext(
        conversation, compacts, query, limit);
      const payload: JsonObject = {
        status: 'ok',
        query,
        results: results.map((r: ContextSearchResult): JsonObject => {
          const item: JsonObject = { source: r.source, id: r.id };
          if (r.nodeIndex !== null) item['node_index'] = r.nodeIndex;
          item['preview'] = r.preview;
          return item;
        }),
      };
      return [{ type: 'text', text: JSON.stringify(payload), metadata: null }];
    },
  }),
  // conversation_expand(:88-128)
  makeAgentTool({
    name: 'conversation_expand',
    description: 'Expand a compact summary id or source message id back to original transcript snippets.',
    parameters: () => makeInputSchemaObj(
      {
        source_id: {
          type: 'string',
          description: 'Compact summary id or original message id',
        },
        radius: {
          type: 'integer',
          description: 'When expanding a message id, include this many neighboring messages, default 2',
        },
      },
      ['source_id'],
    ),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const sourceId: string = inputString(input, 'source_id');
      const radius: number = inputInt(input, 'radius') ?? 2;
      const conversation: Conversation = await deps.conversationProvider();
      const compacts = await deps.compactStore.getCompacts(conversation.id);
      const messages: UIMessage[] = expandConversationContext(
        conversation, compacts, sourceId, radius);
      const payload: JsonObject = {
        status: 'ok',
        source_id: sourceId,
        messages: messages.map((m: UIMessage): JsonObject => ({
          id: m.id,
          role: m.role,
          text: toText(m).slice(0, 12000),
        })),
      };
      return [{ type: 'text', text: JSON.stringify(payload), metadata: null }];
    },
  }),
  // conversation_compact(:130-166)
  makeAgentTool({
    name: 'conversation_compact',
    description: 'Manually compact older context into a structured, expandable summary without deleting original messages.',
    parameters: () => makeInputSchemaObj({
      additional_prompt: {
        type: 'string',
        description: 'Optional instructions about what to preserve',
      },
    }),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const conversation: Conversation = await deps.conversationProvider();
      const additionalPrompt: string = inputString(input, 'additional_prompt');
      const result: CompactResult = await compactConversation(
        conversation,
        { ...deps.policy, enabled: true },
        deps.modelContextWindowTokens,
        'manual_compact_tool',
        additionalPrompt,
        true,
        deps.compactEngineDeps,
      );
      const payload: JsonObject = { status: result.status };
      if (result.summaryId !== undefined) payload['summary_id'] = result.summaryId;
      payload['source_message_count'] = result.sourceMessageCount ?? 0;
      payload['estimated_tokens_before'] = result.estimatedTokensBefore ?? 0;
      payload['estimated_tokens_after'] = result.estimatedTokensAfter ?? 0;
      if (result.error !== undefined) payload['error'] = result.error;
      return [{ type: 'text', text: JSON.stringify(payload), metadata: null }];
    },
  }),
];
