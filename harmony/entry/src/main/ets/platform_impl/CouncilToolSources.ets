import type { CouncilSource } from '@amber/deepread-domain';
import type { JsonObject, JsonValue, UIMessage, UIMessagePartTool } from '@amber/chat-domain';

const sourceFromItem = (item: JsonObject, service: string): CouncilSource | null => {
  const url: string = item['url'] as string;
  if (!/^https?:\/\//i.test(url) || /[\u0000-\u0020]/.test(url)) return null;
  const title: string = (item['title'] as string | undefined) ?? url;
  return { title: title, url: url, service: service };
};

// Shared success-result item extraction: a search_web payload carries `items`
// (each with its own source_service), a scrape_web payload carries `urls`.
const collectPayloadSources = (toolName: string, payload: JsonObject): CouncilSource[] => {
  if (payload['error'] !== undefined || ['failed', 'denied', 'cancelled', 'outcome_unknown'].includes(payload['status'] as string)) return [];
  const items: JsonObject[] = (toolName === 'search_web' ? payload['items'] : payload['urls']) as JsonObject[];
  if (!Array.isArray(items)) return [];
  const sources: CouncilSource[] = [];
  for (const item of items) {
    const service: string = toolName === 'search_web' ? item['source_service'] as string : 'scrape_web';
    if (!service || service.length === 0) continue;
    const source: CouncilSource | null = sourceFromItem(item, service);
    if (source !== null) sources.push(source);
  }
  return sources;
};

const collectToolSources = (part: UIMessagePartTool): CouncilSource[] => {
  if (part.output.length === 0 || part.approvalState.type === 'denied'
    || (part.toolName !== 'search_web' && part.toolName !== 'scrape_web')) return [];
  const sources: CouncilSource[] = [];
  for (const output of part.output) {
    if (output.type !== 'text') continue;
    try {
      sources.push(...collectPayloadSources(part.toolName, JSON.parse(output.text) as JsonObject));
    } catch (_) {
      // Failed/malformed search output is not evidence. The original output remains in the transcript.
    }
  }
  return sources;
};

// A Recipe run checkpoint keeps completed step results in `stepOutputs`, aligned
// with `descriptor.manifest.steps` by index through `completedSteps`. Only steps
// actually completed and saved there are evidence; the wrapper's own terminal
// output does not export item fields and is never guessed for sources.
const collectCheckpointSources = (checkpoint: JsonObject): CouncilSource[] => {
  if (checkpoint['kind'] !== 'run') return [];
  const descriptor: JsonObject | null = checkpoint['descriptor'] as JsonObject | null;
  const manifest: JsonObject | null = descriptor === null ? null : descriptor['manifest'] as JsonObject | null;
  const steps: JsonValue | undefined = manifest?.['steps'];
  const completed: JsonValue | undefined = checkpoint['completedSteps'];
  const outputs: JsonObject | null = checkpoint['stepOutputs'] as JsonObject | null;
  if (!Array.isArray(steps) || !Array.isArray(completed) || outputs === null) return [];
  const sources: CouncilSource[] = [];
  for (let i: number = 0; i < completed.length && i < steps.length; i++) {
    const step: JsonObject = steps[i] as JsonObject;
    const tool: string = step['tool'] as string;
    if ((tool !== 'search_web' && tool !== 'scrape_web') || step['id'] !== completed[i]) continue;
    const text: JsonValue | undefined = outputs[completed[i] as string];
    if (typeof text !== 'string') continue;
    try {
      sources.push(...collectPayloadSources(tool, JSON.parse(text) as JsonObject));
    } catch (_) {
      // Malformed saved step output is not evidence.
    }
  }
  return sources;
};

const collectPackageSources = (part: UIMessagePartTool): CouncilSource[] => {
  if (part.approvalState.type === 'denied') return [];
  const sources: CouncilSource[] = [];
  const recipe: JsonObject | null = (part.metadata?.['recipe_v1'] as JsonObject | undefined) ?? null;
  if (recipe !== null) sources.push(...collectCheckpointSources(recipe));
  // A plugin with a recipe implementation pins the same checkpoint under recipeState.
  const plugin: JsonObject | null = (part.metadata?.['plugin_v1'] as JsonObject | undefined) ?? null;
  const pluginRecipe: JsonObject | null = plugin === null ? null : (plugin['recipeState'] as JsonObject | undefined) ?? null;
  if (pluginRecipe !== null) sources.push(...collectCheckpointSources(pluginRecipe));
  return sources;
};

export const councilSourcesFromMessages = (messages: UIMessage[]): CouncilSource[] => {
  const sources: CouncilSource[] = [];
  for (const message of messages) {
    for (const part of message.parts) {
      if (part.type !== 'tool') continue;
      const collected: CouncilSource[] = [...collectToolSources(part), ...collectPackageSources(part)];
      for (const source of collected) {
        if (!sources.some((existing: CouncilSource): boolean => existing.url === source.url && existing.service === source.service)) {
          sources.push(source);
        }
      }
    }
  }
  return sources;
};
