// OpenAiCompatibleAiClient — AiClient 契约的真实实现
// OpenAI Chat Completions 请求、SSE tool-call 累积和本地工具循环均在本文件完成。

import type {
  AiClient, GenerateTextParams, HttpClient, Storage, HttpRequest, HttpResponse,
  UIMessage, UIMessagePart, UIMessagePartText, UIMessagePartTool, AbortSignalLike, Tool,
  OpenAiStreamDelta, DecodeChunk, SseEvent,
} from '@amber/deepread-domain';
import { makeUIMessage, SseAssembler, parseOpenAiStreamEvent } from '@amber/deepread-domain';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { util } from '@kit.ArkTS';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.AiClient';
const DEFAULT_MAX_STEPS: number = 8;

export interface AiConfig {
  readonly baseUrl: string;
  readonly apiKey: string;
}

interface OpenAiTextContent {
  type: 'text';
  text: string;
}

interface OpenAiImageUrl {
  url: string;
}

interface OpenAiImageContent {
  type: 'image_url';
  image_url: OpenAiImageUrl;
}

type OpenAiContent = OpenAiTextContent | OpenAiImageContent;

interface OpenAiToolFunction {
  name: string;
  arguments: string;
}

interface OpenAiToolCall {
  id: string;
  type: 'function';
  function: OpenAiToolFunction;
}

interface OpenAiMessage {
  role: 'system' | 'user' | 'assistant' | 'tool';
  content: string | OpenAiContent[];
  tool_calls?: OpenAiToolCall[];
  tool_call_id?: string;
}

interface OpenAiFunction {
  name: string;
  description: string;
  parameters: Record<string, object>;
}

interface OpenAiRequestTool {
  type: 'function';
  function: OpenAiFunction;
}

interface OpenAiRequest {
  model: string;
  messages: OpenAiMessage[];
  tools?: OpenAiRequestTool[];
  stream: boolean;
}

interface OpenAiResponseMessage {
  role?: string;
  content?: string | null;
  tool_calls?: OpenAiToolCall[];
}

interface OpenAiResponseChoice {
  message?: OpenAiResponseMessage;
}

interface OpenAiResponse {
  choices?: OpenAiResponseChoice[];
}

interface StreamToolCallDelta {
  index: number;
  id?: string;
  name?: string;
  args?: string;
}

interface ExecutableTool extends Tool {
  execute: (input: string) => Promise<UIMessagePartText[]>;
  allowsAutoApproval?: boolean;
  isHighRisk?: boolean;
}

interface AccumulatedToolCall {
  index: number;
  id: string;
  name: string;
  arguments: string;
}

interface ParsedAssistant {
  text: string;
  reasoning: string;
  toolCalls: AccumulatedToolCall[];
}

const buildChatCompletionsUrl = (baseUrl: string): string => {
  const base: string = baseUrl.endsWith('/') ? baseUrl.slice(0, baseUrl.length - 1) : baseUrl;
  if (/\/v\d+$/.test(base)) return `${base}/chat/completions`;
  return `${base}/v1/chat/completions`;
};

const arktsDecode: DecodeChunk = (chunk: ArrayBuffer): string => {
  const decoder: util.TextDecoder = util.TextDecoder.create('utf-8');
  return decoder.decodeToString(new Uint8Array(chunk));
};

const isAborted = (signal?: AbortSignalLike): boolean => signal !== undefined && signal.aborted;

const textFromParts = (parts: UIMessagePart[]): string => {
  let text: string = '';
  for (const part of parts) {
    if (part.type === 'text') text += part.text;
    if (part.type === 'reasoning') text += part.reasoning;
  }
  return text;
};

const contentFromParts = (parts: UIMessagePart[]): string | OpenAiContent[] => {
  const content: OpenAiContent[] = [];
  for (const part of parts) {
    if (part.type === 'text') {
      const textContent: OpenAiTextContent = { type: 'text', text: part.text };
      content.push(textContent);
    } else if (part.type === 'image') {
      const imageUrl: OpenAiImageUrl = { url: part.url };
      const imageContent: OpenAiImageContent = { type: 'image_url', image_url: imageUrl };
      content.push(imageContent);
    } else if (part.type === 'reasoning') {
      const reasoningContent: OpenAiTextContent = { type: 'text', text: part.reasoning };
      content.push(reasoningContent);
    }
  }
  if (content.length === 0) return '';
  const first: OpenAiContent = content[0];
  if (content.length === 1 && first.type === 'text') return first.text;
  return content;
};

const toolOutputText = (part: UIMessagePartTool): string => textFromParts(part.output);

const toolPartsFrom = (parts: UIMessagePart[]): UIMessagePartTool[] => {
  const result: UIMessagePartTool[] = [];
  for (const part of parts) {
    if (part.type === 'tool') result.push(part);
  }
  return result;
};

const messagesToOpenAi = (messages: UIMessage[]): OpenAiMessage[] => {
  const result: OpenAiMessage[] = [];
  const explicitToolResultIds: Set<string> = new Set<string>();
  for (const message of messages) {
    if (message.role !== 'tool') continue;
    for (const part of toolPartsFrom(message.parts)) explicitToolResultIds.add(part.toolCallId);
  }
  for (const message of messages) {
    const toolParts: UIMessagePartTool[] = toolPartsFrom(message.parts);
    if (message.role === 'tool') {
      for (const part of toolParts) {
        result.push({ role: 'tool', content: toolOutputText(part), tool_call_id: part.toolCallId });
      }
      if (toolParts.length === 0) result.push({ role: 'tool', content: textFromParts(message.parts) });
      continue;
    }

    const openAiMessage: OpenAiMessage = {
      role: message.role,
      content: contentFromParts(message.parts),
    };
    if (message.role === 'assistant' && toolParts.length > 0) {
      const calls: OpenAiToolCall[] = [];
      for (const part of toolParts) {
        const fn: OpenAiToolFunction = { name: part.toolName, arguments: part.input };
        const call: OpenAiToolCall = { id: part.toolCallId, type: 'function', function: fn };
        calls.push(call);
      }
      openAiMessage.tool_calls = calls;
    }
    result.push(openAiMessage);
    if (message.role === 'assistant') {
      for (const part of toolParts) {
        if (part.output.length > 0 && !explicitToolResultIds.has(part.toolCallId)) {
          result.push({ role: 'tool', content: toolOutputText(part), tool_call_id: part.toolCallId });
        }
      }
    }
  }
  return result;
};

const toRequestTools = (tools: Tool[] | undefined): OpenAiRequestTool[] | undefined => {
  if (tools === undefined || tools.length === 0) return undefined;
  const result: OpenAiRequestTool[] = [];
  for (const tool of tools) {
    const fn: OpenAiFunction = {
      name: tool.name,
      description: tool.description,
      parameters: tool.schema as Record<string, object>,
    };
    const requestTool: OpenAiRequestTool = { type: 'function', function: fn };
    result.push(requestTool);
  }
  return result;
};

const accumulateToolCall = (calls: AccumulatedToolCall[], delta: StreamToolCallDelta): void => {
  let current: AccumulatedToolCall | undefined = calls.find(call => call.index === delta.index);
  if (current === undefined) {
    current = { index: delta.index, id: delta.id ?? '', name: delta.name ?? '', arguments: delta.args ?? '' };
    calls.push(current);
    return;
  }
  if (delta.id !== undefined && delta.id.length > 0) current.id = delta.id;
  if (delta.name !== undefined) current.name += delta.name;
  if (delta.args !== undefined) current.arguments += delta.args;
};

const assistantFromParsed = (parsed: ParsedAssistant): UIMessage => {
  const parts: UIMessagePart[] = [];
  if (parsed.text.length > 0) parts.push({ type: 'text', text: parsed.text, metadata: null });
  if (parsed.reasoning.length > 0) {
    const now: string = new Date().toISOString();
    parts.push({
      type: 'reasoning', reasoning: parsed.reasoning,
      createdAt: now, finishedAt: now, metadata: null,
    });
  }
  for (const call of parsed.toolCalls) {
    parts.push({
      type: 'tool', toolCallId: call.id, toolName: call.name, input: call.arguments,
      output: [], approvalState: { type: 'auto' }, metadata: { stream_tool_index: call.index },
    });
  }
  return makeUIMessage('assistant', parts);
};

class OpenAiCompatibleAiClient implements AiClient {
  private http: HttpClient;
  private storage: Storage;
  private readonly config: AiConfig | null;

  constructor(http: HttpClient, storage: Storage, config?: AiConfig) {
    this.http = http;
    this.storage = storage;
    this.config = config === undefined ? null : { baseUrl: config.baseUrl, apiKey: config.apiKey };
  }

  private async getConfig(): Promise<AiConfig> {
    if (this.config !== null) return this.config;
    const baseUrl: string = await this.storage.get<string>('ai_base_url', 'https://api.deepseek.com');
    const apiKey: string = await this.storage.get<string>('ai_api_key', '');
    return { baseUrl: baseUrl, apiKey: apiKey };
  }

  private async callOnce(
    params: GenerateTextParams, messages: UIMessage[], signal?: AbortSignalLike,
  ): Promise<UIMessage> {
    if (isAborted(signal)) throw this.abortError();
    const config: AiConfig = await this.getConfig();
    const request: OpenAiRequest = {
      model: params.model,
      messages: messagesToOpenAi(messages),
      // 真机实证(4/30):fetchStream 在 NetStack 层静默挂死(无 dataReceive/dataEnd/无超时触发);
      // 非流式同端点正常 → 深读工具循环统一走非流式,流式通道待平台修复后再启
      stream: false,
    };
    const requestTools: OpenAiRequestTool[] | undefined = toRequestTools(params.tools);
    if (requestTools !== undefined) request.tools = requestTools;
    const req: HttpRequest = {
      url: buildChatCompletionsUrl(config.baseUrl),
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${config.apiKey}` },
      body: JSON.stringify(request),
    };
    if (request.stream) return this.callStream(req, signal);
    const response: HttpResponse = await this.http.fetch(req, signal === undefined ? undefined : { signal: signal });
    this.assertResponse(response);
    const data: OpenAiResponse = JSON.parse(response.body) as OpenAiResponse;
    return this.responseAssistant(data);
  }

  private async callStream(req: HttpRequest, signal?: AbortSignalLike): Promise<UIMessage> {
    const parsed: ParsedAssistant = { text: '', reasoning: '', toolCalls: [] };
    const assembler: SseAssembler = new SseAssembler(arktsDecode);
    const consume = (event: SseEvent): void => {
      const delta: OpenAiStreamDelta = parseOpenAiStreamEvent(event);
      parsed.text += delta.deltaText;
      parsed.reasoning += delta.reasoningText;
      for (const rawCall of delta.toolCalls) {
        const call: StreamToolCallDelta = { index: rawCall.index };
        if (rawCall.id !== undefined) call.id = rawCall.id;
        if (rawCall.name !== undefined) call.name = rawCall.name;
        if (rawCall.args !== undefined) call.args = rawCall.args;
        accumulateToolCall(parsed.toolCalls, call);
      }
    };
    const response: HttpResponse = await this.http.fetchStream(req, {
      onChunk: (chunk: ArrayBuffer, end: boolean): void => {
        for (const event of assembler.feed(chunk)) consume(event);
        if (end) for (const event of assembler.flush()) consume(event);
      },
      signal: signal,
    });
    this.assertResponse(response);
    return assistantFromParsed(parsed);
  }

  private responseAssistant(data: OpenAiResponse): UIMessage {
    const choice: OpenAiResponseChoice | undefined = data.choices !== undefined && data.choices.length > 0
      ? data.choices[0] : undefined;
    const message = choice?.message;
    const parsed: ParsedAssistant = { text: message?.content ?? '', reasoning: '', toolCalls: [] };
    if (message?.tool_calls !== undefined) {
      for (let i = 0; i < message.tool_calls.length; i++) {
        const call: OpenAiToolCall = message.tool_calls[i];
        parsed.toolCalls.push({ index: i, id: call.id, name: call.function.name, arguments: call.function.arguments });
      }
    }
    return assistantFromParsed(parsed);
  }

  private assertResponse(response: HttpResponse): void {
    if (response.status < 200 || response.status >= 300) {
      const detail: string = response.body.substring(0, 200);
      hilog.error(0x0001, 'DeepRead.Ai', 'HTTP %{public}d: %{public}s', response.status, detail);
      throw new Error(`AiClient ${response.status}: ${detail}`);
    }
  }

  private abortError(): Error {
    const error: Error = new Error('AiClient aborted');
    error.name = 'AbortError';
    return error;
  }

  private shouldExecute(tool: ExecutableTool, params: GenerateTextParams): boolean {
    if (params.autoApprovedToolNames !== undefined && params.autoApprovedToolNames.includes(tool.name)) return true;
    if (tool.isHighRisk === true) return params.autoApproveHighRiskTools === true;
    if (params.autoApproveTools === undefined) return true;
    return params.autoApproveTools;
  }

  private async executeTools(
    assistant: UIMessage, params: GenerateTextParams, signal?: AbortSignalLike,
  ): Promise<UIMessage[]> {
    const tools: ExecutableTool[] = params.tools === undefined ? [] : params.tools as ExecutableTool[];
    const definitions: Map<string, ExecutableTool> = new Map();
    for (const tool of tools) definitions.set(tool.name, tool);
    const resultMessages: UIMessage[] = [];
    const toolParts: UIMessagePartTool[] = toolPartsFrom(assistant.parts);
    for (const part of toolParts) {
      if (isAborted(signal)) throw this.abortError();
      const tool: ExecutableTool | undefined = definitions.get(part.toolName);
      let output: UIMessagePartText[] = [];
      if (tool === undefined) {
        const missingText: UIMessagePartText = {
          type: 'text', text: JSON.stringify({ status: 'failed', message: 'tool definition missing' }),
          metadata: null,
        };
        output = [missingText];
      } else if (!this.shouldExecute(tool, params)) {
        part.approvalState = { type: 'pending' };
        continue;
      } else {
        try {
          output = await tool.execute(part.input);
          if (isAborted(signal)) throw this.abortError();
        } catch (error) {
          if (isAborted(signal)) throw this.abortError();
          const message: string = error instanceof Error ? error.message : String(error);
          const failedText: UIMessagePartText = {
            type: 'text', text: JSON.stringify({ status: 'failed', message: message }), metadata: null,
          };
          output = [failedText];
        }
      }
      part.output = output;
      resultMessages.push(makeUIMessage('tool', [{
        type: 'tool', toolCallId: part.toolCallId, toolName: part.toolName, input: part.input,
        output: output, approvalState: part.approvalState, metadata: part.metadata,
      }]));
    }
    return resultMessages;
  }

  async generateText(params: GenerateTextParams): Promise<UIMessage[]> {
    const config: AiConfig = await this.getConfig();
    if (config.apiKey.length === 0) {
      hilog.info(DOMAIN, TAG, 'no API key, returning empty (fallback)');
      return [];
    }
    let messages: UIMessage[] = [...params.messages];
    const maxSteps: number = Math.max(1, Math.floor(params.maxSteps ?? DEFAULT_MAX_STEPS));
    for (let step: number = 0; step < maxSteps; step++) {
      if (isAborted(params.signal)) throw this.abortError();
      const assistant: UIMessage = await this.callOnce(params, messages, params.signal);
      messages.push(assistant);
      const toolParts: UIMessagePartTool[] = toolPartsFrom(assistant.parts);
      if (toolParts.length === 0) return messages;
      const toolMessages: UIMessage[] = await this.executeTools(assistant, params, params.signal);
      for (const toolMessage of toolMessages) messages.push(toolMessage);
      if (params.autoApproveTools === false && params.autoApprovedToolNames === undefined) return messages;
    }
    return messages;
  }
}

export const createOpenAiCompatibleAiClient = (http: HttpClient, storage: Storage, config?: AiConfig): AiClient => {
  return new OpenAiCompatibleAiClient(http, storage, config);
};
