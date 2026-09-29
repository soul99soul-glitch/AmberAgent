import { router } from '@kit.ArkUI';
import {
  createRecipeLoopAdapter, createRecipeRun, makeConversation, makeUIMessage, makeUserMessage,
  newId, patchConversation, seedConversationWithPresets, toMessageNode, validateRecipe,
} from '@amber/chat-domain';
import type {
  AgentTool, Assistant, Conversation, ConversationRepository, InstalledRecipe, JsonObject,
  MessageNode, RecipeIssue, RecipeLoopAdapter, RecipeRunCheckpoint, RecipeStore, UIMessagePartTool,
} from '@amber/chat-domain';

export interface RecipePageLaunchDeps {
  store: RecipeStore;
  primitives: AgentTool[];
  assistant: Assistant;
  repository: ConversationRepository;
}

class RecipePageLaunchError extends Error {
  public readonly code: string;
  constructor(code: string, message: string) { super(message); this.code = code; }
}

interface RecipeConversationNodesPatch { messageNodes: MessageNode[]; }
interface RecipePageRouteParams { conversationId: string; recipeManualToolCallId: string; }

export const startEntryRecipeFromPage = async (
  deps: RecipePageLaunchDeps, name: string, expectedHash: string, inputs: JsonObject,
  shouldNavigate: () => boolean,
): Promise<void> => {
  const installed: InstalledRecipe[] = await deps.store.listInstalled();
  const recipe: InstalledRecipe | undefined = installed.find((item: InstalledRecipe): boolean =>
    item.enabled && item.descriptor.manifest.name === name && item.descriptor.hash === expectedHash);
  if (recipe === undefined) throw new RecipePageLaunchError('stale_base', 'Recipe 已更新或停用，请重新选择。');
  const checkpoint: RecipeRunCheckpoint = createRecipeRun(recipe.descriptor, inputs);
  const issues: RecipeIssue[] = validateRecipe(checkpoint.descriptor.manifest, deps.primitives);
  if (issues.length > 0) throw new RecipePageLaunchError('invalid_recipe', issues.map(
    (issue: RecipeIssue): string => `${issue.path}: ${issue.message}`).join('\n'));
  const parent: UIMessagePartTool = {
    type: 'tool', toolCallId: newId(), toolName: `recipe__${name}`, input: JSON.stringify(checkpoint.inputs),
    output: [], approvalState: { type: 'auto' }, metadata: {
      recipe_manual: true, recipe_v1: JSON.parse(JSON.stringify(checkpoint)) as JsonObject,
    },
  };
  const adapter: RecipeLoopAdapter = createRecipeLoopAdapter({ store: deps.store, installed: [recipe] });
  const prepared: UIMessagePartTool = await adapter.prepare(parent, deps.primitives);
  const conversation: Conversation = seedConversationWithPresets(makeConversation(newId(), [], {
    assistantId: deps.assistant.id, title: `Recipe · ${name}`,
  }), deps.assistant);
  const nodes: MessageNode[] = conversation.messageNodes.concat([
    toMessageNode(makeUserMessage(`运行 Recipe ${name}（${checkpoint.descriptor.manifest.version}）。`)),
    toMessageNode(makeUIMessage('assistant', [prepared])),
  ]);
  const patch: RecipeConversationNodesPatch = { messageNodes: nodes };
  await deps.repository.save(patchConversation(conversation, patch));
  if (!shouldNavigate()) return;
  const params: RecipePageRouteParams = {
    conversationId: conversation.id, recipeManualToolCallId: prepared.toolCallId,
  };
  const route: router.RouterOptions = { url: 'pages/ChatPage', params: params };
  await router.pushUrl(route);
};
