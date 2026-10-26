// Run-scoped catalog and refresh retain the captured provider and settings.
import { hilog } from '@kit.PerformanceAnalysisKit';
import type { AgentTool, ChatStreamProvider, ChatToolDefinition, TextGenerationParams, ToolLoopOptions, UIMessage } from '@amber/chat-domain';
import { AgentToolDispatcher, asChatStreamProvider, defaultToolInvocationHooks, makeTextGenerationParams } from '@amber/chat-domain';
import type { DetachedConversationContext } from './ConversationRunService.ets';
import type { ConversationRunToolPorts, PreparedConversationTools } from './ConversationRunPorts.ets';
import type { RunToolCatalogSnapshot } from './RunToolCatalog.ets';
import { scopeRunTools } from './ConversationRunToolScope.ets';

export const buildConversationToolLoop = async (
  ctx: DetachedConversationContext, ports: ConversationRunToolPorts,
): Promise<ToolLoopOptions> => {
  const seed = ctx.seed;
  const rt = seed.runtime;
  const runtime = seed.agentRuntime;
  const conversation = ctx.activeConversation();
  const prepared: PreparedConversationTools = await ports.prepareTools(ctx, ports.compressProvider(seed));
  const terminalGuard = prepared.terminalGuard;
  ctx.subAgentTools = [];
  if (seed.subAgentEnabled) {
    try { ctx.subAgentTools = await ports.loadSubAgentTools(ctx); }
    catch (error) {
      const message: string = error instanceof Error ? error.message : String(error);
      hilog.warn(0x0001, 'Chat.RunTools', 'prepareSubAgentTools failed: %{public}s', message);
    }
  }
  ports.warnIfToolAbilityMissing(ctx);
  const catalog = async (): Promise<RunToolCatalogSnapshot> => {
    // Only enabled skills refresh; provider/model and other run settings stay captured.
    const skillTools: AgentTool[] = await ports.createSkillTools(ctx);
    return ports.createCatalog(ctx, [...prepared.profiledTools, ...skillTools]);
  };
  let snapshot: RunToolCatalogSnapshot = await catalog();
  ctx.baseTools = snapshot.baseTools;
  const tools: AgentTool[] = scopeRunTools([...snapshot.tools, ...ctx.subAgentTools], ctx.conversationId, ctx.activityStore);
  const options: ToolLoopOptions = {
    dispatcher: new AgentToolDispatcher({ autoApprovalReview: ports.createAutoApprovalReview(ctx),
      hooks: terminalGuard === null
      ? defaultToolInvocationHooks() : [...defaultToolInvocationHooks(), terminalGuard.hook] }),
    captureInvocationMetadata: terminalGuard === null ? undefined : terminalGuard.captureInvocationMetadata,
    tools,
    recipeAdapter: snapshot.recipeAdapter,
    pluginAdapter: snapshot.pluginAdapter,
    goalAdapter: prepared.goalAdapter,
    refreshTools: async (): Promise<AgentTool[]> => {
      snapshot = await catalog();
      ctx.baseTools = snapshot.baseTools;
      options.recipeAdapter = snapshot.recipeAdapter;
      options.pluginAdapter = snapshot.pluginAdapter;
      return scopeRunTools([...snapshot.tools, ...ctx.subAgentTools], ctx.conversationId, ctx.activityStore);
    },
    makeProviderForStep: (definitions: ChatToolDefinition[]): ChatStreamProvider =>
      asChatStreamProvider(rt.api, (): TextGenerationParams => makeTextGenerationParams({
        model: rt.params.model, temperature: rt.params.temperature, topP: rt.params.topP,
        maxTokens: rt.params.maxTokens, tools: definitions, reasoningLevel: rt.params.reasoningLevel,
        customBody: rt.params.customBody,
      })),
    consumeSteerMessages: (): Promise<UIMessage[]> => ctx.consumeSteer(),
    toolPromptModel: rt.params.model,
    autoApproveTools: runtime.autoApproveAllToolCalls || conversation.autoApproveToolCalls,
    autoApproveHighRiskTools: runtime.autoApproveHighRiskToolCalls,
    speculativeEnabled: runtime.speculativeToolExecution.enabled,
    speculativeMaxConcurrentTools: runtime.speculativeToolExecution.maxConcurrentTools,
    maxSteps: runtime.maxToolLoopSteps,
  };
  return options;
};
