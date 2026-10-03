import type { CustomBody } from './provider_model.ts';
import type { JsonObject } from './json.ts';

// mergeCustomBody treats keys literally and merges nested JSON objects.
export const recapCustomBody = (items: CustomBody[]): CustomBody[] => {
  const reserved = ['tools', 'tool_choice', 'parallel_tool_calls', 'thinking', 'reasoning',
    'reasoning_effort', 'enable_thinking', 'thinking_budget', 'messages', 'input', 'contents', 'stream'];
  const result: CustomBody[] = [];
  for (const item of items) {
    if (reserved.includes(item.key)) continue;
    if (item.key === 'generationConfig' && item.value !== null
      && typeof item.value === 'object' && !Array.isArray(item.value)) {
      const config: JsonObject = {};
      for (const key of Object.keys(item.value)) {
        if (key !== 'thinkingConfig') config[key] = item.value[key];
      }
      result.push({ key: item.key, value: config });
    } else {
      result.push(item);
    }
  }
  return result;
};
