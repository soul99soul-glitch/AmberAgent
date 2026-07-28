// builtin_queue_tools — conversation_queue_* 工具对(D-059)
//
// Android 基准: ChatService.kt:2457-2544(createConversationQueueTools)
//   - 数据源 = getOrCreateSession(conversationId).pendingUserMessages
//     (会话级排队 user 消息,D-048/D-051/D-052 已移植)
//   - 输出 = 单 Text part,JSON 字符串(键序逐字)
// 裁剪/偏差登记:
//   - Android cancel/clear 经 ChatService 挂 pendingStore.persist + 审计;
//     本移植 deps.cancelOne/clearAll 由调用方(entry ChatPage)承载
//     持久化与审计,工具层只做语义转发
//   - findToolName/hasPendingOrUnexecutedTools/toolOutputPreview(:2547-2570)
//     属 ChatService 私有助手,非本工具组范围

import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessagePart } from './message.ts';
import type { PendingUserMessage } from './pending_queue.ts';
import { pendingPreviewText } from './pending_queue.ts';
import type { AgentTool } from './tool.ts';
import { makeAgentTool, makeInputSchemaObj } from './tool.ts';

// 调用面(Android 闭包内直接读写 session.pendingUserMessages;
//   鸿蒙依赖注入 provider/mutator — ChatPage 持久化+审计)
export interface ConversationQueueToolsDeps {
  pendingProvider: () => Promise<PendingUserMessage[]>;
  // ChatService.cancelPendingUserMessage(:498,未命中 no-op)
  cancelOne: (messageId: string) => Promise<void>;
  // ChatService.clearPendingUserMessages(:505)
  clearAll: () => Promise<void>;
}

// ===== 输入解析(jsonPrimitive contentOrNull/toBooleanStrictOrNull 语义) =====

const asObject = (input: JsonValue): JsonObject => {
  if (typeof input !== 'object' || input === null || Array.isArray(input)) return {};
  return input as JsonObject;
};

const inputString = (input: JsonValue, key: string): string => {
  const v: JsonValue | undefined = asObject(input)[key];
  return typeof v === 'string' ? v : '';
};

// toBooleanStrictOrNull == true:仅 JSON boolean true 生效,字符串 'true' 不算
const inputStrictTrue = (input: JsonValue, key: string): boolean => {
  const v: JsonValue | undefined = asObject(input)[key];
  return v === true;
};

export const createConversationQueueTools = (
  deps: ConversationQueueToolsDeps,
): AgentTool[] => [
  // conversation_queue_status(:2458-2489)
  makeAgentTool({
    name: 'conversation_queue_status',
    description: 'Read queued user messages for the current conversation. This is read-only and never exposes messages from other conversations.',
    parameters: () => makeInputSchemaObj({}),
    execute: async (_input: JsonValue): Promise<UIMessagePart[]> => {
      const queued: PendingUserMessage[] = await deps.pendingProvider();
      const messages: JsonObject[] = queued.map(
        (m: PendingUserMessage, index: number): JsonObject => ({
          index,
          id: m.id,
          // Android message.mode.name.lowercase()
          mode: m.mode.toLowerCase(),
          answer: m.answer,
          created_at_ms: m.createdAtMs,
          preview: pendingPreviewText(m),
        }));
      const payload: JsonObject = {
        status: 'ok',
        count: queued.length,
        messages,
      };
      return [{ type: 'text', text: JSON.stringify(payload), metadata: null }];
    },
  }),
  // conversation_queue_cancel(:2490-2539)
  makeAgentTool({
    name: 'conversation_queue_cancel',
    description: 'Cancel one queued user message by id, or clear the current conversation queue. Requires approval because it changes user-entered pending messages.',
    needsApproval: true,
    parameters: () => makeInputSchemaObj({
      message_id: {
        type: 'string',
        description: 'Queued message id to cancel. Omit when clear_all=true.',
      },
      clear_all: {
        type: 'boolean',
        description: 'Clear every queued message in the current conversation.',
      },
    }),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const clearAll: boolean = inputStrictTrue(input, 'clear_all');
      const messageId: string = inputString(input, 'message_id');
      const before: PendingUserMessage[] = await deps.pendingProvider();
      let changed: boolean;
      if (clearAll) {
        changed = before.length > 0;
        await deps.clearAll();
      } else {
        if (messageId.trim().length === 0) {
          throw new Error('message_id is required unless clear_all=true');
        }
        changed = before.some(
          (m: PendingUserMessage): boolean => m.id === messageId);
        // Android 无条件派发 cancelPendingUserMessage(未命中 no-op)
        await deps.cancelOne(messageId);
      }
      const after: PendingUserMessage[] = await deps.pendingProvider();
      const payload: JsonObject = {
        status: changed ? 'cancelled' : 'not_found',
        remaining: after.length,
      };
      return [{ type: 'text', text: JSON.stringify(payload), metadata: null }];
    },
  }),
];
