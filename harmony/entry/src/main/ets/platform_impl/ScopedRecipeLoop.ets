import type { AgentTool, RecipeStore, TerminalApprovalGuard, ToolLoopOptions } from '@amber/chat-domain';
import { AgentToolDispatcher, createRecipeLoopAdapter, createRecipeTools,
  defaultToolInvocationHooks, scopedSubAgentTools } from '@amber/chat-domain';

// The child already owns its explicit allowlist. Refresh existing Recipe names
// against that scope; a package cannot add its missing primitives to the child.
export const configureEntryScopedRecipeLoop = async (
  loop: ToolLoopOptions, store: RecipeStore, guard: TerminalApprovalGuard | null,
): Promise<void> => {
  const allowedNames: Set<string> = new Set(loop.tools.map((tool: AgentTool): string => tool.name));
  const reportTools: AgentTool[] = loop.tools.filter((tool: AgentTool): boolean => tool.name === 'subagent_report');
  const primitives: AgentTool[] = loop.tools.filter((tool: AgentTool): boolean =>
    !tool.name.startsWith('recipe_') && tool.name !== 'recipes_list' && tool.name !== 'subagent_report');
  loop.dispatcher = new AgentToolDispatcher({ hooks: guard === null
    ? defaultToolInvocationHooks() : [...defaultToolInvocationHooks(), guard.hook] });
  loop.captureInvocationMetadata = guard === null ? undefined : guard.captureInvocationMetadata;
  const refresh = async (): Promise<AgentTool[]> => {
    const installed = await store.listInstalled();
    const recipes: AgentTool[] = createRecipeTools({ store, installed, primitives }).filter(
      (tool: AgentTool): boolean => allowedNames.has(tool.name));
    loop.recipeAdapter = createRecipeLoopAdapter({ store, installed });
    return [...scopedSubAgentTools([...primitives, ...recipes]), ...reportTools];
  };
  loop.refreshTools = refresh;
  loop.tools = await refresh();
};
