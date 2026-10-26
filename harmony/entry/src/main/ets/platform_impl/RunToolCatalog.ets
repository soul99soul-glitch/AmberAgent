import type { AgentTool, InstalledPlugin, InstalledRecipe,
  MainAgentToolProfile, PluginLoopAdapter, PluginStore, RecipeLoopAdapter, RecipeStore,
  ToolRegistry, ToolSearchReranker } from '@amber/chat-domain';
import { createPluginTools, createRecipeLoopAdapter, createRecipeTools, createRegistryIntrospectionTools,
  createToolRegistry, createToolSearchTool, filterToolProfile } from '@amber/chat-domain';
import { createEntryPluginAdapter } from './EntryPluginRuntime.ets';

export interface RunToolCatalogDeps {
  primitives: AgentTool[];
  profile: MainAgentToolProfile;
  conversationId: string;
  recipeStore: RecipeStore;
  pluginStore: PluginStore;
  reranker?: ToolSearchReranker;
}

export interface RunToolCatalogSnapshot {
  baseTools: AgentTool[];
  tools: AgentTool[];
  recipeAdapter: RecipeLoopAdapter;
  pluginAdapter: PluginLoopAdapter;
}

// The primitive oracle used to validate packages is the one discovery can expose.
// A caller removes unavailable hosts before entering this shared catalog.
export const createRunToolCatalog = async (deps: RunToolCatalogDeps): Promise<RunToolCatalogSnapshot> => {
  const primitives: AgentTool[] = filterToolProfile(deps.primitives, deps.profile).tools;
  const primitiveRegistry: ToolRegistry = createToolRegistry(primitives);
  const pluginPrimitives: AgentTool[] = [...primitives, ...createRegistryIntrospectionTools(primitiveRegistry),
    createToolSearchTool(primitiveRegistry, deps.profile, deps.reranker)];
  const installed: InstalledRecipe[] = await deps.recipeStore.listInstalled();
  const installedPlugins: InstalledPlugin[] = await deps.pluginStore.listInstalled(pluginPrimitives);
  const recipeTools: AgentTool[] = filterToolProfile(createRecipeTools({ store: deps.recipeStore, installed,
    primitives }), deps.profile).tools;
  const pluginTools: AgentTool[] = filterToolProfile(createPluginTools({ store: deps.pluginStore,
    installed: installedPlugins, primitives: pluginPrimitives }), deps.profile).tools;
  const registry: ToolRegistry = createToolRegistry([...primitives, ...recipeTools, ...pluginTools]);
  const baseTools: AgentTool[] = [...registry.tools(), ...createRegistryIntrospectionTools(registry)];
  return { baseTools, tools: [...baseTools, createToolSearchTool(registry, deps.profile, deps.reranker)],
    recipeAdapter: createRecipeLoopAdapter({ store: deps.recipeStore, installed }),
    pluginAdapter: createEntryPluginAdapter(deps.pluginStore, installedPlugins, pluginPrimitives, deps.conversationId) };
};

export { scopeRunTools } from './ConversationRunToolScope.ets';
