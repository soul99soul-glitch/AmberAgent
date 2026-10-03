// DeepRead uses the existing Chat protocol clients and writer tool loop.
import type { AiClient, GenerateTextParams, Tool, UIMessage, UIMessagePart, AbortSignalLike } from '@amber/deepread-domain';
import type { OpenAIChatApi } from './openai_chat_api.ts';
import { asChatStreamProvider } from './openai_chat_api.ts';
import type { TextGenerationParams, ChatToolDefinition } from './provider_model.ts';
import { makeTextGenerationParams } from './provider_model.ts';
import type { AgentTool, InputSchemaObj } from './tool.ts';
import { makeAgentTool, makeInputSchemaObj } from './tool.ts';
import type { JsonObject, JsonValue } from './json.ts';
import { makeAssistant } from './assistant.ts';
import { makeConversation, currentMessages, toMessageNode } from './conversation.ts';
import { MessageStreamAccumulator } from './stream_accumulator.ts';
import type { ChatStreamProvider, ChatTurnDeps, ConversationStore } from './chat_turn.ts';
import type { Conversation } from './conversation.ts';
import { makeGenerationRetrySetting } from './generation_retry.ts';
import { runAgenticToolLoop } from './tool_loop.ts';

export interface ChatDeepReadAiClientOptions {
  api: OpenAIChatApi;
  params: TextGenerationParams;
  onRawSnapshot?: (messages: UIMessage[]) => void;
  onRequestStart?: (history: UIMessage[]) => void;
}

const abortedError = (): Error => {
  const error: Error = new Error('DeepRead generation aborted');
  error.name = 'AbortError';
  return error;
};
const isAborted = (signal: AbortSignalLike | undefined): boolean => signal?.aborted === true;

const writerTool = (tool: Tool): AgentTool => {
  const schema: JsonObject = tool.schema as JsonObject;
  const properties: JsonValue | undefined = schema['properties'];
  const required: JsonValue | undefined = schema['required'];
  const inputSchema: InputSchemaObj = makeInputSchemaObj(
    properties !== undefined ? properties as JsonObject : {},
    required !== undefined ? required as string[] : null,
  );
  return makeAgentTool({
    name: tool.name, description: tool.description,
    parameters: (): InputSchemaObj => inputSchema,
    needsApproval: tool.allowsAutoApproval === false || tool.isHighRisk === true,
    allowsAutoApproval: tool.allowsAutoApproval ?? true,
    mandatoryApproval: tool.isHighRisk ?? false,
    execute: (input: JsonValue): Promise<UIMessagePart[]> => {
      if (tool.execute === undefined) throw new Error(`深读工具未接线：${tool.name}`);
      return tool.execute(JSON.stringify(input));
    },
  });
};

export const createChatDeepReadAiClient = (options: ChatDeepReadAiClientOptions): AiClient => {
  // Custom JSON body values and model abilities also belong to this run's snapshot.
  const params: TextGenerationParams = JSON.parse(JSON.stringify(options.params)) as TextGenerationParams;
  // This task exposes only its local writer tools; Chat's configured built-in
  // search/image tools are unrelated to article planning or title translation.
  params.model.tools = [];
  const providerFor = (tools: ChatToolDefinition[]): ChatStreamProvider => asChatStreamProvider(
    options.api, (): TextGenerationParams => makeTextGenerationParams({ ...params, tools }),
  );
  const wrap = (provider: ChatStreamProvider): ChatStreamProvider => ({
    streamText: (messages, onChunk, opts): Promise<void> => {
      options.onRequestStart?.(messages);
      return provider.streamText(messages, onChunk, opts);
    },
  });
  return {
    async generateText(request: GenerateTextParams): Promise<UIMessage[]> {
      if (isAborted(request.signal)) throw abortedError();
      const tools: Tool[] = request.tools ?? [];
      if (tools.length === 0) {
        const accumulator: MessageStreamAccumulator = new MessageStreamAccumulator(request.messages);
        options.onRequestStart?.(request.messages);
        // OAuth endpoints may require SSE even for the planning call's stream:false.
        await options.api.streamText(request.messages, makeTextGenerationParams({ ...params, tools: [] }),
          (chunk): void => {
            accumulator.append(chunk);
            options.onRawSnapshot?.(accumulator.snapshot());
          }, { signal: request.signal });
        if (isAborted(request.signal)) throw abortedError();
        return accumulator.snapshot();
      }
      if (!params.model.abilities.includes('tool')) {
        throw new Error('深度阅读需要支持工具调用的模型，请在模型配置中开启工具能力或选择其他模型。');
      }
      // Chat's unattended mode deliberately bypasses ordinary approval flags.
      // DeepRead has no approval UI, so reject this incompatible tool contract.
      if (request.autoApproveTools === true && request.autoApproveHighRiskTools === true
        && tools.some((tool: Tool): boolean => tool.allowsAutoApproval === false)) {
        throw new Error('深读工具不允许自动批准，无法以无人值守方式执行。');
      }
      const agentTools: AgentTool[] = tools.map(writerTool);
      const seed: Conversation = makeConversation('deepread-transient', request.messages.map(toMessageNode));
      const store: ConversationStore = { save: (_conversation: Conversation): Promise<void> => Promise.resolve() };
      const deps: ChatTurnDeps = {
        assistant: makeAssistant({ systemPrompt: '', streamOutput: true }),
        provider: wrap(providerFor([])), wrapStreamProvider: wrap,
        inputTransformers: [], outputTransformers: [], store,
        contextMessageSize: 0, abortSignal: request.signal,
        retrySetting: makeGenerationRetrySetting({ enabled: false }),
        onRawFlushSnapshot: options.onRawSnapshot,
      };
      const result: Conversation = await runAgenticToolLoop(seed, deps, {
        tools: agentTools, maxSteps: request.maxSteps ?? 32,
        autoApproveTools: request.autoApproveTools ?? false,
        autoApproveHighRiskTools: request.autoApproveHighRiskTools ?? false,
        // Only these supplied local writer tools are trusted for this request.
        // Risk and explicit non-auto-approvable flags remain authoritative.
        autoApprovedToolNames: tools.filter((tool: Tool): boolean =>
          tool.allowsAutoApproval !== false && tool.isHighRisk !== true
          && (request.autoApproveTools === true || (request.autoApprovedToolNames ?? []).includes(tool.name)))
          .map((tool: Tool): string => tool.name),
        makeProviderForStep: providerFor,
      });
      // Chat intentionally checkpoints partial aborts; DeepRead must retain its abort outcome.
      if (isAborted(request.signal)) throw abortedError();
      return currentMessages(result);
    },
  };
};
