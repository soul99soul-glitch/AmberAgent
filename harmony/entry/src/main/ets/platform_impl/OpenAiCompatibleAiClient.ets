// OpenAiCompatibleAiClient — AiClient 契约的真实实现
// OpenAI 兼容 HTTP + agent loop 组装(复用 deepread runAgentLoop + 纯逻辑组件)
// 对照设计 §4.8(AiClient 接口承载 GenerationHandler 核心行为)+ §8.5
//
// agent loop 已在 deepread/agent/agent_loop.ts 实现并测过(10 tests)。
// 此处:把单次 HTTP/SSE 模型调用封装成 callModel,注入 runAgentLoop。
// 复用:runAgentLoop + executeToolsAndMerge + budget_prompt + retry_classifier + append_chunk + sse_assembler
//
// TODO(toolchain/ArkTS 验证): RCP 流式 chunk 回调接 sse_assembler 需 API 24 验证。
//   非流式 callModel 已可工作;流式(§4.8.4 accumulator 33ms flush)见下方 TODO。

import type {
  AiClient, HttpClient, Storage, HttpRequest, HttpResponse,
  ToolDefinition, ApprovalMatrix,
} from '@amber/deepread-domain';
import { runAgentLoop } from '@amber/deepread-domain';
import type { AgentLoopDeps } from '@amber/deepread-domain';
import type { UIMessage, UIMessagePart } from '@amber/deepread-domain';
import { makeAssistantMessage, makeUserMessage } from '@amber/deepread-domain';

interface OpenAiMessage {
  role: string;
  content: string;
}

const toOpenAiMessages = (messages: UIMessage[]): OpenAiMessage[] => {
  return messages.map((m): OpenAiMessage => {
    const text = m.parts
      .filter((p): p is { kind: 'text'; text: string } => p.kind === 'text')
      .map(p => p.text)
      .join('');
    const role = m.role === 'assistant' ? 'assistant' : m.role === 'user' ? 'user' : 'system';
    return { role, content: text };
  });
};

export const createOpenAiCompatibleAiClient = (
  http: HttpClient,
  storage: Storage,
): AiClient => {
  const getConfig = async (): Promise<{ baseUrl: string; apiKey: string }> => {
    const baseUrl = (await storage.getString('ai_base_url')) ?? 'https://api.deepseek.com';
    const apiKey = (await storage.getString('ai_api_key')) ?? '';
    return { baseUrl, apiKey };
  };

  // 单次模型调用(非流式 MVP;流式见 TODO)
  const callModelOnce = async (
    messages: UIMessage[],
    _exposedTools: ToolDefinition[],
    budgetPrompt: string,
    model: string,
  ): Promise<UIMessage[]> => {
    const { baseUrl, apiKey } = await getConfig();
    // budget 提醒作为最后一条 user message 注入(§4.8.1)
    const withBudget: UIMessage[] = budgetPrompt.length > 0
      ? [...messages, makeUserMessage(budgetPrompt)]
      : messages;
    const body = JSON.stringify({
      model,
      messages: toOpenAiMessages(withBudget),
      stream: false,
      // TODO(toolchain): tools 字段序列化 exposedTools 的 schema(§4.8.2 eager 暴露)
    });

    const req: HttpRequest = {
      url: `${baseUrl}/v1/chat/completions`,
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${apiKey}`,
      },
      body,
    };
    const resp: HttpResponse = await http.fetch(req);
    if (resp.status !== 200) {
      throw new Error(`AiClient ${resp.status}: ${resp.body.slice(0, 200)}`);
    }
    const data = JSON.parse(resp.body) as { choices?: Array<{ message?: { content?: string } }> };
    const content = data.choices?.[0]?.message?.content ?? '';
    return [...messages, makeAssistantMessage([{ kind: 'text', text: content }])];
  };

  // generateText:组装 agent loop
  // tools:runManager 传入的 SectionWriterTools;approval:autoApprove writer tools
  const generateText = async (params: {
    model: string;
    messages: UIMessage[];
    tools?: Map<string, ToolDefinition>;
    approval?: ApprovalMatrix;
    maxSteps?: number;
    stream?: boolean;
  }): Promise<UIMessage[]> => {
    const tools = params.tools ?? new Map<string, ToolDefinition>();
    const approval: ApprovalMatrix = params.approval ?? {
      autoApproveTools: true,
      autoApproveHighRiskTools: false,
      autoApprovedToolNames: new Set(Array.from(tools.keys())),
    };
    const maxSteps = params.maxSteps ?? 32;

    // TODO(streaming): params.stream === true 时,callModel 改用 RCP 流式:
    //   SSE chunk → sse_assembler 解析 → append_chunk 合并 → 33ms flush onMessagesUpdate。
    //   当前用非流式 callModelOnce(结果等价,只是无流式 UI)。
    const loopDeps: AgentLoopDeps = {
      callModel: (msgs, exposed, budgetPrompt) => callModelOnce(msgs, exposed, budgetPrompt, params.model),
      tools,
      approval,
      maxSteps,
    };
    const result = await runAgentLoop(params.messages, loopDeps);
    return result.messages;

    // TODO(retry): callModelOnce 内部应用 retry_classifier(408/429/5xx 重试,4xx 不重试)。
    //   纯逻辑已在 deepread/retry_classifier.ts;此处包装一层重试循环。
  };

  return { generateText } as AiClient;
};
