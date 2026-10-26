import type { AbortSignalLike } from '@amber/deepread-domain';
import type { AgentTool, AgentToolActivityStore, JsonValue, UIMessagePart } from '@amber/chat-domain';
import { makeAgentTool } from '@amber/chat-domain';

export const scopeRunTools = (
  tools: AgentTool[], conversationId: string, activityStore: AgentToolActivityStore,
): AgentTool[] => tools.map((tool: AgentTool): AgentTool => makeAgentTool({
  name: tool.name, description: tool.description, parameters: tool.parameters, systemPrompt: tool.systemPrompt,
  needsApproval: tool.needsApproval, allowsAutoApproval: tool.allowsAutoApproval,
  mandatoryApproval: tool.mandatoryApproval, recipeEnvelope: tool.recipeEnvelope,
  pluginEnvelope: tool.pluginEnvelope, mcpTarget: tool.mcpTarget,
  execute: (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> =>
    activityStore.withConversation(conversationId, (): Promise<UIMessagePart[]> => tool.execute(input, signal)),
}));
