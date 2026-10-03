import type { AbortSignalLike } from '@amber/deepread-domain';
import type { Assistant, CustomHeader } from './assistant.ts';
import { makeUserMessage, makeUIMessage } from './message.ts';
import type {
  MessageChunk, UIMessage, UIMessagePart,
  UIMessagePartReasoning, UIMessagePartText, UIMessagePartTool,
} from './message.ts';
import { toText } from './message.ts';
import type { ProviderModel } from './provider_settings.ts';
import type { ChatStreamProvider, ChatTurnDeps } from './chat_turn.ts';
import { createMemoryConversationStore } from './chat_turn.ts';
import { makeConversation, currentMessages, toMessageNode } from './conversation.ts';
import type { ToolLoopOptions } from './tool_loop.ts';
import { runAgenticToolLoop } from './tool_loop.ts';
import type { ChatToolDefinition, TextGenerationParams } from './provider_model.ts';
import { makeTextGenerationParams } from './provider_model.ts';
import {
  defaultReasoningLevelForModel, mergeCustomParams, resolveSessionDefaults, toChatModel,
} from './context_assembly.ts';
import type { GenerationRetrySetting } from './generation_retry.ts';
import type { SubAgentDefinition } from './agent_prompt_config.ts';
import type { SubAgentTaskSpec, SubAgentResult } from './subagent_models.ts';
import { makeSubAgentResult } from './subagent_models.ts';
import { SubAgentReportCapture } from './subagent_report_tool.ts';
import { SUBAGENT_REPORT_TOOL_NAME } from './subagent_report_tool.ts';
import type { AgentTool } from './tool.ts';
import { makeAssistant } from './assistant.ts';

export interface SubAgentAssistantResolution {
  assistant: Assistant;
  generationRetry: GenerationRetrySetting;
  autoApproveTools: boolean;
  autoApproveHighRiskTools: boolean;
}

export interface SubAgentGenerationRequest {
  model: ProviderModel;
  assistant: Assistant;
  generationRetry: GenerationRetrySetting;
  messages: UIMessage[];
  tools: AgentTool[];
  maxSteps: number;
  autoApproveTools: boolean;
  autoApproveHighRiskTools: boolean;
  onUpdate: (messages: UIMessage[]) => void;
  signal?: AbortSignalLike;
}

export interface SubAgentGenerationPort {
  // D5-1:taskHint = 角色名/任务提示(Jev 模型路由上下文;无 override 时才消费)
  resolveModel(modelSettingId: string | null, taskHint?: string): Promise<ProviderModel | null>;
  resolveAssistant(): Promise<SubAgentAssistantResolution | null>;
  run(request: SubAgentGenerationRequest): Promise<UIMessage[]>;
}

export interface ChatStreamSubAgentGenerationPortDeps {
  resolveModel: (modelSettingId: string | null, taskHint?: string) => Promise<ProviderModel | null>;
  resolveAssistant: () => Promise<SubAgentAssistantResolution | null>;
  makeProvider: (
    model: ProviderModel, params: TextGenerationParams, headers: CustomHeader[],
  ) => ChatStreamProvider;
  configureRecipeLoop?: (loop: ToolLoopOptions, request: SubAgentGenerationRequest) => Promise<void>;
}

const unreachableSubAgentProvider: ChatStreamProvider = {
  streamText(
    _messages: UIMessage[], _onChunk: (chunk: MessageChunk) => void,
  ): Promise<void> {
    return Promise.reject(new Error('Subagent step provider was not selected'));
  },
};

export class ChatStreamSubAgentGenerationPort implements SubAgentGenerationPort {
  private readonly deps: ChatStreamSubAgentGenerationPortDeps;

  constructor(deps: ChatStreamSubAgentGenerationPortDeps) {
    this.deps = deps;
  }

  resolveModel(modelSettingId: string | null, taskHint?: string): Promise<ProviderModel | null> {
    return this.deps.resolveModel(modelSettingId, taskHint);
  }

  resolveAssistant(): Promise<SubAgentAssistantResolution | null> {
    return this.deps.resolveAssistant();
  }

  async run(request: SubAgentGenerationRequest): Promise<UIMessage[]> {
    const store = createMemoryConversationStore();
    const seed = makeConversation(
      'subagent', request.messages.map((message: UIMessage) => toMessageNode(message)),
    );
    const custom = mergeCustomParams(request.assistant, request.model);
    const sessionDefaults = resolveSessionDefaults(
      request.assistant,
      null,
      defaultReasoningLevelForModel(request.model),
    );
    const customHeaders: CustomHeader[] = custom.customHeaders;
    const providerFor = (definitions: ChatToolDefinition[]): ChatStreamProvider =>
      this.deps.makeProvider(request.model, makeTextGenerationParams({
        model: toChatModel(request.model),
        temperature: custom.temperature,
        topP: custom.topP,
        maxTokens: sessionDefaults.maxTokens,
        tools: definitions,
        reasoningLevel: sessionDefaults.reasoningLevel,
        customBody: custom.customBodies,
      }), customHeaders);
    const deps: ChatTurnDeps = {
      assistant: request.assistant,
      inputTransformers: [],
      outputTransformers: [],
      provider: unreachableSubAgentProvider,
      store,
      agentSoul: '',
      abortSignal: request.signal,
      retrySetting: request.generationRetry,
      onUpdate: request.onUpdate,
    };
    const loop: ToolLoopOptions = {
      tools: request.tools,
      maxSteps: request.maxSteps,
      autoApproveTools: request.autoApproveTools,
      autoApproveHighRiskTools: request.autoApproveHighRiskTools,
      invocationContext: 'subagent',
      toolRetrySetting: request.generationRetry,
      speculativeEnabled: false,
      toolPromptModel: toChatModel(request.model),
      makeProviderForStep: (definitions: ChatToolDefinition[]): ChatStreamProvider =>
        providerFor(definitions),
    };
    if (this.deps.configureRecipeLoop !== undefined) await this.deps.configureRecipeLoop(loop, request);
    const result = await runAgenticToolLoop(seed, deps, loop);
    return currentMessages(result);
  }
}

export interface SubAgentRunner {
  run(
    definition: SubAgentDefinition,
    task: SubAgentTaskSpec,
    tools: AgentTool[],
    liveText: (text: string) => void,
    liveParts: (parts: UIMessagePart[]) => void,
    signal?: AbortSignalLike,
  ): Promise<SubAgentResult>;
}

const REPORT_RETRY_STEPS: number = 2;
const ERROR_MAX_CHARS: number = 300;

const errorMessage = (error: unknown): string => {
  if (error instanceof Error) {
    const message: string = error.message;
    return (message.length > 0 ? message : error.name).slice(0, ERROR_MAX_CHARS);
  }
  return String(error).slice(0, ERROR_MAX_CHARS);
};

const abortError = (): Error => {
  const error: Error = new Error('Subagent generation aborted');
  error.name = 'AbortError';
  return error;
};

const signalIsAborted = (signal: AbortSignalLike | undefined): boolean =>
  signal !== undefined && signal.aborted;

const renderAssistantForLive = (message: UIMessage): string => {
  let reasoning: string = '';
  let text: string = '';
  for (const part of message.parts) {
    if (part.type === 'reasoning') {
      const reasoningPart: UIMessagePartReasoning = part as UIMessagePartReasoning;
      reasoning += `${reasoning.length > 0 ? '\n' : ''}${reasoningPart.reasoning}`;
    }
    if (part.type === 'text') {
      const textPart: UIMessagePartText = part as UIMessagePartText;
      text += `${text.length > 0 ? '\n' : ''}${textPart.text}`;
    }
  }
  reasoning = reasoning.trim();
  text = text.trim();
  if (reasoning.length === 0 && text.length === 0) return '';
  let rendered: string = '';
  if (reasoning.length > 0) {
    rendered += `> 💭 ${reasoning.replace(/\n/g, '\n> ')}`;
    if (text.length > 0) rendered += '\n\n';
  }
  return rendered + text;
};

const renderTranscriptForDisplay = (messages: UIMessage[]): string => {
  const seen: string[] = [];
  for (const message of messages) {
    if (message.role !== 'assistant') continue;
    const text: string = toText(message).trim();
    if (text.length > 0 && !seen.includes(text)) seen.push(text);
  }
  return seen.join('\n\n');
};

const allParts = (messages: UIMessage[]): UIMessagePart[] => {
  const parts: UIMessagePart[] = [];
  for (const message of messages) {
    for (const part of message.parts) parts.push(part);
  }
  return parts;
};

const isolatedAssistant = (
  parent: Assistant, definition: SubAgentDefinition,
): Assistant => {
  const result: Assistant = makeAssistant({
    id: parent.id,
    chatModelId: parent.chatModelId,
    imageGenerationModelId: parent.imageGenerationModelId,
    name: definition.name,
    avatar: parent.avatar,
    useAssistantAvatar: parent.useAssistantAvatar,
    tags: parent.tags,
    systemPrompt: definition.systemPrompt,
    temperature: definition.temperature !== null ? definition.temperature : parent.temperature,
    topP: parent.topP,
    contextMessageSize: 0,
    streamOutput: true,
    enableMemory: false,
    useGlobalMemory: false,
    enableRecentChatsReference: false,
    messageTemplate: '{{ message }}',
    presetMessages: [],
    quickMessageIds: [],
    regexes: [],
    reasoningLevel: definition.reasoningLevel !== null
      ? definition.reasoningLevel : parent.reasoningLevel,
    maxTokens: parent.maxTokens,
    customHeaders: parent.customHeaders,
    customBodies: parent.customBodies,
    mcpServers: [],
    localTools: [],
    toolProfile: parent.toolProfile,
    background: parent.background,
    backgroundOpacity: parent.backgroundOpacity,
    enabledSkills: [],
    enableTimeReminder: false,
    rememberedReasoningLevelsByModelId: parent.rememberedReasoningLevelsByModelId,
  });
  return result;
};

const historyGrantPrompt = (task: SubAgentTaskSpec): string => {
  if (task.sessionGrantId.length === 0 && task.sourceSessionIds.length === 0 &&
    task.historyQuery.length === 0) return '';
  const sourceIds: string = task.sourceSessionIds.length > 0
    ? task.sourceSessionIds.join(', ') : '(none)';
  const query: string = task.historyQuery.length > 0 ? task.historyQuery : '(none)';
  const grant: string = task.sessionGrantId.length > 0 ? task.sessionGrantId : '(none)';
  return `Historical session scope:\n- session_grant_id: ${grant}\n- source_session_ids: ${sourceIds}\n- history_query: ${query}\n- shard: ${task.shardIndex + 1}/${Math.max(task.shardCount, 1)}\n\nWhen using session_read or session_expand, include the session_grant_id in the tool input.\nKeep source_message_ids in your evidence whenever the tool returns them.`;
};

const buildTaskPrompt = (
  definition: SubAgentDefinition, task: SubAgentTaskSpec,
): string => {
  const context: string = task.context.length > 0 ? task.context : '(none)';
  const history: string = historyGrantPrompt(task);
  let prompt: string = `You are running as subagent \`${definition.id}\`.\n\nObjective:\n${task.objective}\n\nOutput format:\n${task.outputFormat}\n\nTools and sources guidance:\n${task.toolsAndSources}\n\nBoundaries:\n${task.boundaries}\n\nContext from parent:\n${context}\n\n`;
  if (history.length > 0) prompt += `${history}\n\n`;
  prompt += `Subagent reporting:\n- Keep writing normal Markdown for the human live panel; do not print JSON or machine-only wrappers.\n- Before you finish, call \`${SUBAGENT_REPORT_TOOL_NAME}\` once with the compact result the supervisor should consume: summary, findings, evidence, risks, recommended_next_steps, and confidence.\n- \`${SUBAGENT_REPORT_TOOL_NAME}\` is an internal injected tool for this subagent run. It may not appear in tools_list or tool catalog output; use it anyway when you are done.\n- The report tool is not the human-facing answer. It is the structured channel back to the main agent.\n\nReturn only the useful result for the supervisor. Do not ask the user follow-up questions.`;
  return prompt;
};

const isRecoverableReportArgumentFailure = (
  error: unknown, definition: SubAgentDefinition,
): boolean => {
  if (definition.toolAllowlist.length !== 0) return false;
  const lower: string = errorMessage(error).toLowerCase();
  return lower.includes('invalid function arguments json string') && lower.includes('tool_call');
};

const pendingToolName = (messages: UIMessage[]): string | null => {
  if (messages.length === 0) return null;
  const finalMessage: UIMessage = messages[messages.length - 1];
  for (const part of finalMessage.parts) {
    if (part.type === 'tool') {
      const toolPart: UIMessagePartTool = part as UIMessagePartTool;
      if (toolPart.approvalState.type === 'pending') return toolPart.toolName;
    }
  }
  return null;
};

const approvalRequiredResult = (toolName: string): SubAgentResult => makeSubAgentResult({
  status: 'approval_required',
  summary: `Subagent requested approval for ${toolName}.`,
  risks: ['Subagent cannot self-approve sensitive tools.'],
  recommendedNextSteps: [
    'Main agent should decide whether to ask the user for approval in the parent conversation.',
  ],
});

export class GenerationSubAgentRunner implements SubAgentRunner {
  private readonly generation: SubAgentGenerationPort;

  constructor(generation: SubAgentGenerationPort) {
    this.generation = generation;
  }

  async run(
    definition: SubAgentDefinition,
    task: SubAgentTaskSpec,
    tools: AgentTool[],
    liveText: (text: string) => void,
    liveParts: (parts: UIMessagePart[]) => void,
    signal?: AbortSignalLike,
  ): Promise<SubAgentResult> {
    let model: ProviderModel | null = null;
    if (definition.modelId !== null) model = await this.generation.resolveModel(definition.modelId);
    if (model === null) model = await this.generation.resolveModel(null, definition.name);
    if (model === null) throw new Error('Current chat model is not configured');
    const assistantResolution: SubAgentAssistantResolution | null =
      await this.generation.resolveAssistant();
    if (assistantResolution === null) throw new Error('Current assistant is not configured');

    const assistant: Assistant = isolatedAssistant(assistantResolution.assistant, definition);
    const reportCapture: SubAgentReportCapture = new SubAgentReportCapture();
    const childTools: AgentTool[] = tools.slice();
    childTools.push(reportCapture.tool());
    const initialMessages: UIMessage[] = [makeUserMessage(buildTaskPrompt(definition, task))];
    let latest: UIMessage[] = initialMessages;
    let firstGenerationError: unknown = null;
    let originalDisplay: string = '';
    let currentLiveText: string = '';
    let hasFirstGenerationUpdate: boolean = false;

    const update = (messages: UIMessage[], updateLiveText: boolean): void => {
      latest = messages;
      liveParts(allParts(messages));
      if (!updateLiveText) return;
      let assistantMessage: UIMessage | undefined;
      for (let index: number = messages.length - 1; index >= 0; index--) {
        if (messages[index].role === 'assistant') {
          assistantMessage = messages[index];
          break;
        }
      }
      const display: string = assistantMessage === undefined ? '' : renderAssistantForLive(assistantMessage);
      currentLiveText = display;
      liveText(display);
      hasFirstGenerationUpdate = true;
    };
    const firstUpdate = (messages: UIMessage[]): void => update(messages, true);

    const firstRequest: SubAgentGenerationRequest = {
      model,
      assistant,
      generationRetry: assistantResolution.generationRetry,
      messages: initialMessages,
      tools: childTools,
      maxSteps: definition.maxTurns,
      autoApproveTools: assistantResolution.autoApproveTools,
      autoApproveHighRiskTools: assistantResolution.autoApproveHighRiskTools,
      onUpdate: firstUpdate,
      signal,
    };

    try {
      latest = await this.generation.run(firstRequest);
      if (!hasFirstGenerationUpdate) update(latest, true);
      if (signalIsAborted(signal)) throw abortError();
    } catch (error) {
      if (error instanceof Error && error.name === 'AbortError') throw error;
      if (signalIsAborted(signal)) throw error;
      firstGenerationError = error;
    }

    originalDisplay = renderTranscriptForDisplay(latest);
    if (originalDisplay.length === 0) originalDisplay = currentLiveText;
    let displayForOutput: string = originalDisplay.slice(0, definition.outputBudgetChars);
    if (displayForOutput.length > 0) liveText(displayForOutput);
    if (firstGenerationError !== null && (displayForOutput.trim().length === 0 ||
      !isRecoverableReportArgumentFailure(firstGenerationError, definition))) {
      return makeSubAgentResult({ status: 'failed', error: errorMessage(firstGenerationError) });
    }

    const pending: string | null = pendingToolName(latest);
    if (pending !== null) {
      return approvalRequiredResult(pending);
    }

    let reportRetryError: unknown = null;
    if (!reportCapture.hasReport && firstGenerationError === null) {
      const reminder: UIMessage = makeUserMessage(
        'Internal supervisor reminder: call `subagent_report` now with the compact structured result.\n' +
        'The report tool is injected directly into this subagent run and may not appear in tools_list/catalog output.\n' +
        'Do not repeat the full visible answer; keep any final text short.',
      );
      const retryMessages: UIMessage[] = latest.slice();
      retryMessages.push(reminder);
      const retryRequest: SubAgentGenerationRequest = {
        model,
        assistant,
        generationRetry: assistantResolution.generationRetry,
        messages: retryMessages,
        tools: childTools,
        maxSteps: REPORT_RETRY_STEPS,
        autoApproveTools: assistantResolution.autoApproveTools,
        autoApproveHighRiskTools: assistantResolution.autoApproveHighRiskTools,
        onUpdate: (messages: UIMessage[]): void => update(messages, false),
        signal,
      };
      try {
        latest = await this.generation.run(retryRequest);
        if (signalIsAborted(signal)) throw abortError();
        if (latest.length > 0) liveParts(allParts(latest));
      } catch (error) {
        if (error instanceof Error && error.name === 'AbortError') throw error;
        if (signalIsAborted(signal)) throw error;
        reportRetryError = error;
      }
    }

    if (signalIsAborted(signal)) throw abortError();
    liveParts(allParts(latest));
    if (displayForOutput.trim().length === 0) {
      displayForOutput = renderTranscriptForDisplay(latest).slice(0, definition.outputBudgetChars);
    }
    if (displayForOutput.length > 0) liveText(displayForOutput);
    const retryPending: string | null = pendingToolName(latest);
    if (retryPending !== null) return approvalRequiredResult(retryPending);
    const result: SubAgentResult = reportCapture.resultOrFallback(displayForOutput);
    const reportError: unknown = firstGenerationError ?? reportRetryError;
    if (!reportCapture.hasReport && reportError !== null && displayForOutput.length > 0) {
      const risks: string[] = result.risks.slice();
      risks.push(`Structured subagent report failed; summary was derived from visible text. ${errorMessage(reportError)}`);
      return makeSubAgentResult({
        status: result.status,
        summary: result.summary,
        findings: result.findings,
        evidence: result.evidence,
        risks,
        confidence: result.confidence,
        recommendedNextSteps: result.recommendedNextSteps,
        error: result.error,
      });
    }
    return result;
  }
}
