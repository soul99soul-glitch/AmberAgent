import { router } from '@kit.ArkUI';
import { makeConversation, makeUIMessage, makeUserMessage, newId, patchConversation,
  seedConversationWithPresets, toMessageNode } from '@amber/chat-domain';
import type { AgentTool, Assistant, Conversation, ConversationRepository, JsonObject, MessageNode,
  PluginLoopAdapter, UIMessagePartTool } from '@amber/chat-domain';

export interface PluginPageLaunchDeps {
  assistant: Assistant;
  primitives: AgentTool[];
  adapter: PluginLoopAdapter;
  repository: ConversationRepository;
}

interface PluginConversationNodesPatch { messageNodes: MessageNode[]; }
interface PluginPageRouteParams { conversationId: string; pluginManualToolCallId: string; }

// DI supplies the real assistant/model context and its repository save barrier.
// Preparation pins content and inputs; execution belongs to Chat's same adapter.
export const startEntryPluginFromPage = async (
  deps: PluginPageLaunchDeps, parent: UIMessagePartTool, title: string,
  shouldNavigate: () => boolean,
): Promise<void> => {
  if (!deps.adapter.supports(parent.toolName)) throw new Error('plugin_manual_tool_unavailable');
  const prepared: UIMessagePartTool = await deps.adapter.prepare(parent, deps.primitives);
  const metadata: JsonObject = {};
  if (prepared.metadata !== null) {
    for (const key of Object.keys(prepared.metadata)) metadata[key] = prepared.metadata[key];
  }
  metadata['plugin_manual'] = true;
  const manual: UIMessagePartTool = {
    type: 'tool', toolCallId: prepared.toolCallId, toolName: prepared.toolName, input: prepared.input,
    output: prepared.output, approvalState: prepared.approvalState, metadata: metadata,
  };
  const conversation: Conversation = seedConversationWithPresets(makeConversation(newId(), [], {
    assistantId: deps.assistant.id, title: `Plugin · ${title}`,
  }), deps.assistant);
  const nodes: MessageNode[] = conversation.messageNodes.concat([
    toMessageNode(makeUserMessage(title)),
    toMessageNode(makeUIMessage('assistant', [manual])),
  ]);
  const patch: PluginConversationNodesPatch = { messageNodes: nodes };
  await deps.repository.save(patchConversation(conversation, patch));
  if (!shouldNavigate()) return;
  const params: PluginPageRouteParams = {
    conversationId: conversation.id, pluginManualToolCallId: manual.toolCallId,
  };
  const route: router.RouterOptions = { url: 'pages/ChatPage', params: params };
  await router.pushUrl(route);
};
