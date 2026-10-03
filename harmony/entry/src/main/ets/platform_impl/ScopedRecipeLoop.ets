import type { AgentTool, InstalledPlugin, PluginLoopAdapter, PluginStore,
  RecipeStore, TerminalApprovalGuard, ToolLoopOptions, AutoApprovalReview } from '@amber/chat-domain';
import { AgentToolDispatcher, createPluginTools, createRecipeLoopAdapter, createRecipeTools,
  defaultToolInvocationHooks, scopedSubAgentTools } from '@amber/chat-domain';

export interface ScopedPluginDeps {
  store: PluginStore;
  createAdapter: (installed: InstalledPlugin[], primitives: AgentTool[]) => PluginLoopAdapter;
}

// The child already owns its explicit allowlist. Refresh existing Recipe names
// against that scope; a package cannot add its missing primitives to the child.
export const configureEntryScopedRecipeLoop = async (
  loop: ToolLoopOptions, store: RecipeStore, guard: TerminalApprovalGuard | null,
  plugin: ScopedPluginDeps | null = null, autoApprovalReview?: AutoApprovalReview,
): Promise<void> => {
  const allowedNames: Set<string> = new Set(loop.tools.map((tool: AgentTool): string => tool.name));
  const reportTools: AgentTool[] = loop.tools.filter((tool: AgentTool): boolean => tool.name === 'subagent_report');
  const primitives: AgentTool[] = loop.tools.filter((tool: AgentTool): boolean =>
    !tool.name.startsWith('recipe_') && tool.name !== 'recipes_list'
      && !tool.name.startsWith('plugin_') && tool.name !== 'plugins_list' && tool.name !== 'subagent_report');
  loop.dispatcher = new AgentToolDispatcher({ autoApprovalReview, hooks: guard === null
    ? defaultToolInvocationHooks() : [...defaultToolInvocationHooks(), guard.hook] });
  loop.captureInvocationMetadata = guard === null ? undefined : guard.captureInvocationMetadata;
  const refresh = async (): Promise<AgentTool[]> => {
    const installed = await store.listInstalled();
    const recipes: AgentTool[] = createRecipeTools({ store, installed, primitives }).filter(
      (tool: AgentTool): boolean => allowedNames.has(tool.name));
    loop.recipeAdapter = createRecipeLoopAdapter({ store, installed });
    let plugins: AgentTool[] = [];
    if (plugin !== null) {
      const packages: InstalledPlugin[] = await plugin.store.listInstalled(primitives);
      plugins = createPluginTools({ store: plugin.store, installed: packages, primitives }).filter(
        (tool: AgentTool): boolean => allowedNames.has(tool.name));
      loop.pluginAdapter = plugin.createAdapter(packages, primitives);
    }
    return [...scopedSubAgentTools([...primitives, ...recipes, ...plugins]), ...reportTools];
  };
  loop.refreshTools = refresh;
  loop.tools = await refresh();
};
