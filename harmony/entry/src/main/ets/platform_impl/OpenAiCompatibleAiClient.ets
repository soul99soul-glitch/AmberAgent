// OpenAiCompatibleAiClient — AiClient 契约的真实实现
// OpenAI 兼容 HTTP + SSE(复用 sse_assembler 解析流式 chunk)
// 对照设计 §4.8(AiClient 接口承载 GenerationHandler 核心行为)+ §8.5
//
// 当前:实现 generateText(非流式 + 流式)。
// TODO(toolchain/ArkTS 验证): 流式 SSE 的 RCP chunk 回调接 sse_assembler 需在 API 24 验证。
// TODO(phase6 完整): budgetPrompt / retryClassifier / tool exposure / accumulator 33ms flush
//   — 这些已在 deepread 纯逻辑层实现(budget_prompt.ts / retry_classifier.ts / append_chunk.ts),
//   此处组装进 generateText 的 agent loop。MVP 先做单轮 generateText(无 tool loop)。

import type { AiClient, HttpClient, Storage, HttpRequest, HttpResponse } from '@amber/deepread-domain';
import type { UIMessage } from '@amber/deepread-domain';
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

  const generateText = async (params: {
    model: string;
    messages: UIMessage[];
    tools?: unknown[];
    maxSteps?: number;
    stream?: boolean;
  }): Promise<UIMessage[]> => {
    const { baseUrl, apiKey } = await getConfig();
    const body = JSON.stringify({
      model: params.model,
      messages: toOpenAiMessages(params.messages),
      stream: false,  // MVP 非流式;流式见下方 TODO
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
    // 追加 assistant message 到原 messages
    return [...params.messages, makeAssistantMessage([{ kind: 'text', text: content }])];

    // TODO(phase6 完整 agent loop):
    //   1. 流式 SSE:stream=true → 解析 data: chunk → append_chunk 合并 → 33ms flush onMessagesUpdate
    //   2. tool loop:检测 tool_calls → executeToolsAndMerge(maxSteps 内循环)
    //   3. budgetPrompt 注入(stepIndex >= 阈值时)
    //   4. retryClassifier(408/429/5xx 重试,4xx 不重试)
    //   这些纯逻辑已在 deepread 实现,此处在 API 24 验证后组装。
  };

  return { generateText } as AiClient;
};
