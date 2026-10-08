import type { AgentTool } from './tool.ts';
import type { UIMessagePartTool } from './message.ts';
import type { JsonObject } from './json.ts';
import type { AbortSignalLike } from '@amber/deepread-domain';
import type { AgentToolDispatcher, ToolBatchResultPair } from './tool_dispatcher.ts';
import type { ToolInvocationContext, PermissionDecision } from './tool_permission.ts';
import type { GenerationRetrySetting } from './generation_retry.ts';
import { permissionDecisionTraceToJson } from './tool_permission.ts';
import { makeGenerationRetrySetting } from './generation_retry.ts';
import type { RecipeLoopAdapter, RecipeExecutionPort } from './recipes/runner.ts';
import type { PluginLoopAdapter } from './plugins/ports.ts';

export interface RecipeBatchOptions {
  adapter?: RecipeLoopAdapter;
  pluginAdapter?: RecipeLoopAdapter;
  // E12:wm_run_goal 编排 adapter(goal_v1 checkpoint);与 recipe/plugin 并列的第三类
  goalAdapter?: RecipeLoopAdapter;
  tools: AgentTool[];
  dispatcher: AgentToolDispatcher;
  autoApproveTools: boolean;
  autoApproveHighRiskTools: boolean;
  autoApprovedToolNames: string[];
  toolRetry: GenerationRetrySetting;
  invocationContext: ToolInvocationContext;
  capture?: (part: UIMessagePartTool) => JsonObject | null;
  save: (previous: UIMessagePartTool, next: UIMessagePartTool) => Promise<void>;
  signal?: AbortSignalLike;
}

export interface RecipeBatchResult {
  pairs: ToolBatchResultPair[];
  paused: boolean;
}

// Batches containing a Recipe or plugin use this ordered path. The adapter owns the
// pinned plan; this bridge supplies the same permission resolver and dispatcher.
export const executeRecipeBatch = async (
  parts: UIMessagePartTool[], options: RecipeBatchOptions,
): Promise<RecipeBatchResult> => {
  const definitions = new Map<string, AgentTool>();
  for (const tool of options.tools) definitions.set(tool.name, tool);
  const primitives = options.tools.filter((tool: AgentTool): boolean =>
    !tool.name.startsWith('recipe_') && tool.name !== 'recipes_list'
      && !tool.name.startsWith('plugin_') && tool.name !== 'plugins_list'
      && tool.name !== 'wm_run_goal');
  const pairs: ToolBatchResultPair[] = [];
  const retry = makeGenerationRetrySetting({ enabled: false });
  for (const original of parts) {
    if (options.signal?.aborted) return { pairs, paused: true };
    let current: UIMessagePartTool = original;
    const save = async (next: UIMessagePartTool): Promise<void> => {
      await options.save(current, next);
      current = next;
    };
    const isPlugin: boolean = options.pluginAdapter?.supports(current.toolName) === true;
    const isGoal: boolean = !isPlugin && options.goalAdapter?.supports(current.toolName) === true;
    const adapter: RecipeLoopAdapter | undefined = isPlugin
      ? options.pluginAdapter : isGoal ? options.goalAdapter
      : options.adapter?.supports(current.toolName) ? options.adapter : undefined;
    const checkpointKey: string = isPlugin ? 'plugin_v1' : isGoal ? 'goal_v1' : 'recipe_v1';
    if (adapter !== undefined) {
      try {
        if (current.metadata?.[checkpointKey] === undefined && !definitions.has(current.toolName)) {
          throw new Error('Package tool is unavailable in this tool scope.');
        }
        await save(await adapter.prepare(current, primitives));
        const port: RecipeExecutionPort = {
          primitive: (name: string): AgentTool | null => definitions.get(name) ?? null,
          capture: (part: UIMessagePartTool): JsonObject | null => options.capture?.(part) ?? null,
          decide: (part: UIMessagePartTool, tool: AgentTool, signal?: AbortSignalLike): Promise<PermissionDecision> => options.dispatcher.resolveReviewedDecision(tool, part,
            options.autoApproveTools, options.autoApproveHighRiskTools, [], options.invocationContext, signal ?? options.signal),
          dispatch: (part: UIMessagePartTool, tool: AgentTool, signal?: AbortSignalLike): Promise<UIMessagePartTool | null> => options.dispatcher.execute(part, tool,
            options.autoApproveTools, options.autoApproveHighRiskTools, [],
            options.invocationContext, retry, signal),
          saveParent: save,
        };
        const result = await adapter.advance(current, port, options.signal);
        if (result !== current) await save(result);
      } catch (error) {
        // A started checkpoint was already saved before dispatch. Never replace
        // it with a fresh plan or retry the primitive after an interrupted save.
        if (current.metadata?.[checkpointKey] !== undefined) throw error;
        await save({ ...current, output: [{ type: 'text', metadata: null,
          text: JSON.stringify({ status: 'failed', error_code: checkpointKey === 'plugin_v1' ? 'plugin_prepare_failed' : 'recipe_prepare_failed',
            message: error instanceof Error ? error.message : String(error) }) }] });
      }
    } else {
      if (current.approvalState.type === 'auto' && options.capture !== undefined) {
        const captured = options.capture(current);
        if (captured !== null) await save({ ...current,
          metadata: { ...(current.metadata ?? {}), ...captured } });
      }
      const tool = definitions.get(current.toolName) ?? null;
      const decision = await options.dispatcher.resolveReviewedDecision(tool, current,
        options.autoApproveTools, options.autoApproveHighRiskTools,
        options.autoApprovedToolNames, options.invocationContext, options.signal);
      if (options.signal?.aborted) return { pairs, paused: true };
      if (decision.action === 'ask') {
        await save({ ...current, approvalState: { type: 'pending' },
          metadata: { ...(current.metadata ?? {}), permission_trace: permissionDecisionTraceToJson(decision.trace) } });
      } else {
        const result = await options.dispatcher.execute(current, tool,
          options.autoApproveTools, options.autoApproveHighRiskTools, options.autoApprovedToolNames,
          options.invocationContext, options.toolRetry, options.signal);
        if (result !== null) await save(result);
      }
    }
    pairs.push({ input: original, result: current });
    if (current.output.length === 0 || options.signal?.aborted) return { pairs, paused: true };
  }
  return { pairs, paused: false };
};
