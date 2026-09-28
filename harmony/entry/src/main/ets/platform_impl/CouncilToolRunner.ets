import type {
  HttpClient, ModelCouncilTextRunner, CouncilGenerateRequest, ModelCouncilTextResult,
  CouncilApprovalRequest, CouncilToolSnapshot, CouncilToolVerdict, CouncilSeatRunKey, AbortSignalLike,
} from '@amber/deepread-domain';
import type {
  Conversation, Assistant, ChatTurnDeps, ChatStreamProvider, ChatModel, ChatToolDefinition,
  ToolLoopOptions, ToolInvocationRequest, ToolApprovalVerdict, UIMessage, UIMessagePart, UIMessagePartTool,
  JevApprovalSubject, JsonObject, MessageNode,
} from '@amber/chat-domain';
import {
  makeChatModel, makeProviderSettingOpenAI, makeTextGenerationParams, createOpenAIChatApi, asChatStreamProvider,
  patchAssistant, patchConversation, currentMessages, updateCurrentMessages, toMessageNode, makeUserMessage, makeUIMessage,
  runAgenticToolLoop, runToolLoopContinuation, applyToolApprovalToConversation, listJevPendingApprovals,
  buildJevApprovalSubject, toText,
} from '@amber/chat-domain';
import type { CouncilSeatStore } from './CouncilSeatStore.ets';
import { councilSourcesFromMessages } from './CouncilToolSources.ets';
import { sha256HexUtf8 } from './EntryLocalToolPorts.ets';
import { createEntryAbortController } from './EntryAbortController.ets';

export interface CouncilSeatExecution {
  conversation: Conversation;
  assistant: Assistant;
  store: CouncilSeatStore;
  makeLoop: (makeProviderForStep: (definitions: ChatToolDefinition[]) => ChatStreamProvider,
    toolPromptModel: ChatModel, beforeEffect?: (request: ToolInvocationRequest) => Promise<void>) => Promise<ToolLoopOptions>;
}
export interface CouncilToolRunnerDeps {
  http: HttpClient;
  offRunner: ModelCouncilTextRunner;
  prepareSeat: (request: CouncilGenerateRequest) => Promise<CouncilSeatExecution>;
}

const checkSignal = (signal: AbortSignalLike): void => {
  if (!signal.aborted) return;
  const error: Error = new Error('Council seat cancelled'); error.name = 'AbortError'; throw error;
};
const pendingSnapshot = (key: CouncilSeatRunKey, conversation: Conversation): CouncilToolSnapshot => {
  const pending: CouncilApprovalRequest[] = [];
  for (const item of listJevPendingApprovals(conversation)) {
    const subject: JevApprovalSubject | null = buildJevApprovalSubject(item.locator, item.parent, item.userMessageId);
    if (subject === null) throw new Error('Council approval has no saved subject');
    pending.push({ key: key, conversationId: conversation.id, messageId: item.locator.messageId,
      partIndex: item.locator.partIndex, toolCallId: item.locator.toolCallId,
      subjectHash: sha256HexUtf8(subject.bindingJSON), part: item.parent });
  }
  const messages: UIMessage[] = currentMessages(conversation);
  return { key: key, conversationId: conversation.id, messages: messages,
    pending: pending, sources: councilSourcesFromMessages(messages) };
};
const finalText = (messages: UIMessage[]): string => {
  const last: UIMessage | undefined = messages[messages.length - 1];
  if (last === undefined || last.role !== 'assistant') return '';
  let afterTool: number = 0;
  last.parts.forEach((part: UIMessagePart, index: number): void => { if (part.type === 'tool') afterTool = index + 1; });
  return last.parts.slice(afterTool).filter((part: UIMessagePart): boolean => part.type === 'text')
    .map((part: UIMessagePart): string => part.type === 'text' ? part.text : '').join('\n').trim();
};
const answeredTool = (conversation: Conversation, approval: CouncilApprovalRequest): boolean => {
  const pending = listJevPendingApprovals(conversation).find((item): boolean => item.locator.messageId === approval.messageId
    && item.locator.partIndex === approval.partIndex && item.locator.toolCallId === approval.toolCallId);
  if (pending === undefined) return false;
  return buildJevApprovalSubject(pending.locator, pending.parent, pending.userMessageId)?.action?.toolName === 'ask_user';
};

class CouncilToolRunner implements ModelCouncilTextRunner {
  private deps: CouncilToolRunnerDeps;

  constructor(deps: CouncilToolRunnerDeps) { this.deps = deps; }

  async generate(req: CouncilGenerateRequest): Promise<ModelCouncilTextResult> {
    if (req.toolMode !== 'search' && req.toolMode !== 'full') return this.deps.offRunner.generate(req);
    if (req.key === undefined || req.model.apiKey.length === 0) throw new Error('Council seat tool configuration is incomplete');
    const key: CouncilSeatRunKey = req.key;
    // The manager's actual seat signal identity is also passed to every approval waiter.
    const signal: AbortSignalLike = req.signal ?? createEntryAbortController().signal;
    checkSignal(signal);
    const seat: CouncilSeatExecution = await this.deps.prepareSeat(req);
    checkSignal(signal);
    if (await seat.store.load() !== null) throw new Error('Council seat interrupted: saved execution cannot be replayed');
    const assistant: Assistant = patchAssistant(seat.assistant, { systemPrompt: req.systemPrompt,
      temperature: req.temperature, reasoningLevel: req.reasoningLevel ?? 'auto', streamOutput: true });
    const model: ChatModel = makeChatModel({ modelId: req.model.model, displayName: req.model.label,
      abilities: ['tool', 'reasoning'], inputModalities: ['text'], outputModalities: ['text'] });
    const setting = makeProviderSettingOpenAI({ id: req.model.id, baseUrl: req.model.baseUrl,
      chatCompletionsPath: '/v1/chat/completions', authMode: 'api_key' });
    const api = createOpenAIChatApi({ http: this.deps.http, setting: setting,
      headers: (): Record<string, string> => ({ 'Authorization': `Bearer ${req.model.apiKey}` }) });
    const makeProviderForStep = (definitions: ChatToolDefinition[]): ChatStreamProvider => asChatStreamProvider(api,
      () => makeTextGenerationParams({ model: model, tools: definitions,
        temperature: req.temperature, reasoningLevel: req.reasoningLevel ?? 'auto' }));
    const initialNodes: MessageNode[] = seat.conversation.messageNodes.slice();
    initialNodes.push(toMessageNode(makeUserMessage(req.userPrompt)));
    let conversation: Conversation = patchConversation(seat.conversation, { messageNodes: initialNodes });
    await seat.store.save(conversation);
    checkSignal(signal);

    let barrierTail: Promise<void> = Promise.resolve();
    const beforeEffect = (request: ToolInvocationRequest): Promise<void> => {
      // Only serialize canonical read/marker/write, never the primitive execution itself.
      const barrier: Promise<void> = barrierTail.then(async (): Promise<void> => {
        checkSignal(signal);
        const live: Conversation | null = await seat.store.load();
        if (live === null) throw new Error('Council seat checkpoint missing before effect');
        const messages: UIMessage[] = currentMessages(live);
        let match: UIMessagePartTool | null = null;
        let messageIndex: number = -1; let partIndex: number = -1;
        for (let i: number = 0; i < messages.length; i++) {
          for (let j: number = 0; j < messages[i].parts.length; j++) {
            const part: UIMessagePart = messages[i].parts[j];
            if (part.type !== 'tool') continue;
            for (const name of ['recipe_v1', 'plugin_v1']) {
              const state: JsonObject | undefined = part.metadata?.[name] as JsonObject | undefined;
              const pendingStep: JsonObject | null = state === undefined ? null
                : (state['pendingStep'] as JsonObject | undefined) ?? null;
              if (state !== undefined && state['phase'] === 'started' && pendingStep !== null
                && pendingStep['toolCallId'] === request.tool.toolCallId
                && pendingStep['input'] === request.tool.input && pendingStep['toolName'] === request.tool.toolName) {
                checkSignal(signal); return; // The package's same-store STARTED barrier already succeeded.
              }
            }
            if (part.toolCallId !== request.tool.toolCallId) continue;
            if (match !== null) throw new Error('Ambiguous Council tool effect');
            match = part; messageIndex = i; partIndex = j;
          }
        }
        if (match === null || match.input !== request.tool.input || match.toolName !== request.tool.toolName
          || match.output.length !== 0 || match.approvalState.type === 'pending' || match.approvalState.type === 'denied') {
          throw new Error('Council tool changed before effect');
        }
        const metadata: JsonObject = match.metadata === null ? {} : JSON.parse(JSON.stringify(match.metadata)) as JsonObject;
        metadata['council_started_v1'] = { toolCallId: match.toolCallId, toolName: match.toolName, input: match.input };
        const started: UIMessagePartTool = { type: 'tool', toolCallId: match.toolCallId,
          toolName: match.toolName, input: match.input, output: match.output,
          approvalState: match.approvalState, metadata: metadata };
        const parts: UIMessagePart[] = messages[messageIndex].parts.slice();
        parts[partIndex] = started;
        messages[messageIndex] = makeUIMessage(messages[messageIndex].role, parts, messages[messageIndex]);
        checkSignal(signal);
        await seat.store.save(updateCurrentMessages(live, messages));
        checkSignal(signal);
      });
      barrierTail = barrier.then((): void => {}, (): void => {});
      return barrier;
    };
    const loop: ToolLoopOptions = await seat.makeLoop(makeProviderForStep, model, beforeEffect);
    const deps: ChatTurnDeps = { assistant: assistant, provider: makeProviderForStep([]), store: seat.store,
      inputTransformers: [], outputTransformers: [], abortSignal: signal,
      onUpdate: (messages: UIMessage[]): void => {
        if (signal.aborted) return;
        const last: UIMessage | undefined = messages[messages.length - 1];
        if (last !== undefined && last.role === 'assistant') req.onChunk(toText(last).slice(0, req.outputBudgetChars));
      } };
    checkSignal(signal);
    conversation = await runAgenticToolLoop(conversation, deps, loop);
    while (true) {
      checkSignal(signal);
      const saved: Conversation | null = await seat.store.load();
      if (saved === null) throw new Error('Council seat checkpoint missing');
      conversation = saved;
      const snapshot: CouncilToolSnapshot = pendingSnapshot(key, saved);
      checkSignal(signal);
      req.onTools?.(snapshot);
      if (snapshot.pending.length === 0) break;
      if (req.requestApproval === undefined) throw new Error('Council seat approval host unavailable');
      for (const approval of snapshot.pending) {
        const verdict: CouncilToolVerdict = await req.requestApproval(approval, signal);
        checkSignal(signal);
        const latest: Conversation | null = await seat.store.load();
        if (latest === null) throw new Error('Council seat checkpoint missing');
        const current: CouncilToolSnapshot = pendingSnapshot(key, latest);
        const exact: CouncilApprovalRequest | undefined = current.pending.find((candidate: CouncilApprovalRequest): boolean =>
          candidate.messageId === approval.messageId && candidate.partIndex === approval.partIndex
          && candidate.toolCallId === approval.toolCallId && candidate.subjectHash === approval.subjectHash);
        if (exact === undefined) throw new Error('Council approval subject changed');
        const asks: boolean = answeredTool(latest, exact);
        if ((asks && verdict.kind === 'approved') || (verdict.kind === 'answered' && (!asks || verdict.answer === null))) {
          throw new Error('Council question requires its actual answer');
        }
        let decision: ToolApprovalVerdict;
        if (verdict.kind === 'answered') decision = { kind: 'answered', answer: verdict.answer as string };
        else if (verdict.kind === 'denied') decision = { kind: 'denied', reason: verdict.reason };
        else decision = { kind: 'approved' };
        const approved: Conversation = applyToolApprovalToConversation(latest, exact.toolCallId, decision,
          { messageId: exact.messageId, partIndex: exact.partIndex });
        if (approved === latest) throw new Error('Council approval locator changed');
        checkSignal(signal);
        await seat.store.save(approved);
        checkSignal(signal);
        req.onTools?.(pendingSnapshot(key, approved));
        conversation = approved;
      }
      checkSignal(signal);
      conversation = await runToolLoopContinuation(conversation, deps, loop);
    }
    const messages: UIMessage[] = currentMessages(conversation);
    if (messages.some((message: UIMessage): boolean => message.parts.some((part: UIMessagePart): boolean =>
      part.type === 'tool' && part.output.length === 0))) throw new Error('Council seat has unresolved tools');
    const text: string = finalText(messages).slice(0, req.outputBudgetChars);
    if (text.trim().length === 0) throw new Error('Council seat produced no final text');
    checkSignal(signal);
    return { text: text, warnings: [], toolMessages: messages.filter((message: UIMessage): boolean =>
      message.parts.some((part: UIMessagePart): boolean => part.type === 'tool')),
      sources: councilSourcesFromMessages(messages) };
  }
}

export const createCouncilToolRunner = (deps: CouncilToolRunnerDeps): ModelCouncilTextRunner => new CouncilToolRunner(deps);
