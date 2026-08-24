// UIMessage + UIMessagePart — HarmonyOS port of Android ai/ui/Message.kt
// 设计:用 `kind` discriminant(ArkTS-friendly string literal union)
// 替代 Kotlin sealed class + when 穷尽检查
//
// 严格子集:只保留 Deep Read 需要的 part 类型(Text / Image / Reasoning / Tool)
// Video/Audio/Document/MiniApp 等 Android 有但 Deep Read 不用的部分省略

export type MessageRole = 'user' | 'assistant' | 'system' | 'tool';

export interface UIMessagePartText {
  kind: 'text';
  text: string;
}

export interface UIMessagePartImage {
  kind: 'image';
  url: string;
}

export interface UIMessagePartReasoning {
  kind: 'reasoning';
  reasoning: string;
  createdAt: number;    // epoch ms
  finishedAt: number | null;
}

// 注意:Tool part 的 output 字段是"已执行的结果"(对应 Android output: List<UIMessagePart>)
// 在 ArkTS 用 readonly 数组避免 mutation
export type ToolApprovalState = 'auto' | 'pending' | 'approved' | 'denied' | 'answered';

export interface UIMessagePartTool {
  kind: 'tool';
  toolCallId: string;
  toolName: string;
  input: string;  // JSON 参数字符串
  output: UIMessagePartText[];  // 工具执行结果,通常 [Text(jsonString)]
  approvalState: ToolApprovalState;
  // 流式归并用:OpenAI tool_calls 数组下标(id 缺席/迟到时按它匹配后续分片);
  // 不参与持久化/请求组装,执行后无意义
  streamIndex?: number;
}

export type UIMessagePart =
  | UIMessagePartText
  | UIMessagePartImage
  | UIMessagePartReasoning
  | UIMessagePartTool;

export interface UIMessage {
  id: string;
  role: MessageRole;
  parts: UIMessagePart[];
  createdAt: number;
  finishedAt: number | null;
  modelId: string | null;
}

// ===== 工厂函数(ArkTS 友好,无 class) =====

export const makeUIMessage = (
  role: MessageRole,
  parts: UIMessagePart[] = [],
  opts: Partial<UIMessage> = {},
): UIMessage => ({
  id: opts.id ?? randomId(),
  role,
  parts,
  createdAt: opts.createdAt ?? Date.now(),
  finishedAt: opts.finishedAt ?? null,
  modelId: opts.modelId ?? null,
});

export const makeUserMessage = (text: string): UIMessage =>
  makeUIMessage('user', [{ kind: 'text', text }]);

export const makeAssistantMessage = (parts: UIMessagePart[] = []): UIMessage =>
  makeUIMessage('assistant', parts);

export const makeSystemMessage = (text: string): UIMessage =>
  makeUIMessage('system', [{ kind: 'text', text }]);

// ===== 辅助查询 =====

// 提取消息中所有 Tool part(对应 Android getTools())
export const getTools = (msg: UIMessage): UIMessagePartTool[] =>
  msg.parts.filter((p): p is UIMessagePartTool => p.kind === 'tool');

// 是否已执行(output 非空)
export const isToolExecuted = (t: UIMessagePartTool): boolean => t.output.length > 0;

// 是否可恢复执行(未执行 + 审批态可继续)
export const canResumeToolExecution = (approval: ToolApprovalState): boolean =>
  approval === 'approved' || approval === 'denied' || approval === 'answered';

export const canToolResumeExecution = (t: UIMessagePartTool): boolean =>
  !isToolExecuted(t) && canResumeToolExecution(t.approvalState);

// 待执行判定(执行器视角):未执行且不在等用户审批。
// 'auto'(流式新工具)也要进入执行队列,由 resolveDecision + ApprovalMatrix 决定 allow/ask/deny;
// 'pending' 表示已标记等待用户,不重复进入决策。
export const isToolAwaitingExecution = (t: UIMessagePartTool): boolean =>
  !isToolExecuted(t) && t.approvalState !== 'pending';

// 拿最后一条 assistant 的纯文本(用于解析 plan、fallback 等)
export const latestAssistantText = (messages: UIMessage[]): string => {
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    if (m.role === 'assistant') {
      return m.parts
        .filter((p): p is UIMessagePartText => p.kind === 'text')
        .map(p => p.text)
        .join('');
    }
  }
  return '';
};

// 简易 id 生成(测试用;鸿蒙运行时可用 @ohos.util generateRandomUUID)
const randomId = (): string => {
  // 不用 crypto.randomUUID(测试要兼容)
  return `msg_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 10)}`;
};
