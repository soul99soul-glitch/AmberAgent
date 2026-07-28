// builtin_ask_user_tool — ask_user HITL 工具(D-058)
//
// Android 基准: app/core/ai/tools/AskUserTool.kt 全文 86 行
//   - needsApproval=true;resolver hitl 门恒 ASK(PermissionDecisionResolver.kt:117
//     'ask_user always needs a human answer.')
//   - execute 为 stub(HITL 流接管:审批门置 Pending → 用户应答 → Answered
//     写回,D-057 tool_approval 闭环);正常管线永不触达
//   - 完整 questions 选项 UI(ChatMessageAskUserStep.kt)= P1 视觉;MVP 经
//     idle blocker 自由文本应答(D-057 已接)

import type { JsonValue } from './json.ts';
import type { UIMessagePart } from './message.ts';
import type { AgentTool } from './tool.ts';
import { makeAgentTool, makeInputSchemaObj } from './tool.ts';

export const createAskUserTool = (): AgentTool => makeAgentTool({
  name: 'ask_user',
  description: 'Ask the user one or more questions when you need clarification, additional information, or confirmation. Each question can optionally provide a list of suggested options for the user to choose from. The user may select an option or provide their own free-text answer for each question. The answers will be returned as a JSON object mapping question IDs to the user\'s responses.',
  parameters: () => makeInputSchemaObj(
    {
      questions: {
        type: 'array',
        description: 'List of questions to ask the user',
        items: {
          type: 'object',
          properties: {
            id: { type: 'string', description: 'Unique identifier for this question' },
            question: { type: 'string', description: 'The question text to display to the user' },
            options: {
              type: 'array',
              description: 'Optional list of suggested options for the user to choose from',
              items: { type: 'string' },
            },
            selection_type: {
              type: 'string',
              enum: ['text', 'single', 'multi'],
              description: 'Answer type: text (free text input, default), single (select exactly one option), multi (select one or more options)',
            },
          },
          required: ['id', 'question'],
        },
      },
    },
    ['questions'],
  ),
  needsApproval: true,
  execute: (_input: JsonValue): Promise<UIMessagePart[]> =>
    Promise.reject(new Error('ask_user tool should be handled by HITL flow')),
});
