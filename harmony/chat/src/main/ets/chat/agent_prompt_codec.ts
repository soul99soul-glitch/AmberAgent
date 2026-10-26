// agent_prompts 专属 Markdown grammar 与格式映射；所有函数无文件/设置副作用。
import { SUB_AGENT_BUILT_INS, subAgentApplyOverride } from './subagent_definitions.ts';
import type { SubAgentDefinition, SubAgentRuntimeSetting } from './subagent_definitions.ts';

export const DEFAULT_IMAGE_PROMPT_INJECTION: string =
'Prefer clean composition, clear focal subject, natural lighting, coherent anatomy, restrained details, and a polished final image. Keep the scene readable at mobile size.';

export const DEFAULT_IMAGE_NEGATIVE_PROMPT_INJECTION: string =
'Overcrowded micro-details, muddy textures, distorted hands or faces, unreadable text, duplicated limbs, heavy artifacts, random decorative clutter, and low-contrast composition.';

export const DEFAULT_CONTEXT_COMPACTION_HANDOFF_PROMPT: string = `# Context Compaction Handoff

Write a continuation handoff for another model that will resume the same conversation.

Return valid JSON only. The JSON must include:
- \`schema_version\`: 2
- \`timeline_summary\`: 4-5 human-readable sentences in the user's language, written for the chat timeline
- \`handoff_markdown\`: dense Markdown for the next model, with sections: Goal, Constraints, Progress, Decisions, Current State, Next Steps, Critical Context, Relevant Files
- \`covered_compact_ids\`: the compact ids from the provided previous handoffs that this handoff carries forward
- \`source_message_ids\`: exactly the source ids provided for this compact pass
- \`created_at\`: the unix epoch millis provided by the app

The timeline summary is for humans. The handoff Markdown is for the model. Preserve concrete names, files, commands, errors, user preferences, approvals, rejected approaches, and unresolved decisions. Do not include raw tool logs unless they are needed to continue safely.`;

export interface CouncilSettingStrategy<Setting, Seat> {
seatsOf(setting: Setting): Seat[];
withSeats(setting: Setting, seats: Seat[]): Setting;
seatIdOf(seat: Seat): string;
seatNameOf(seat: Seat): string;
seatRoleOf(seat: Seat): string;
seatPromptOf(seat: Seat): string;
withSeatPrompt(seat: Seat, systemPrompt: string): Seat;
}

export interface ImagePromptInjectionConfig {
enabled: boolean;
defaultPrompt: string;
negativePrompt: string;
}

export const makeImagePromptInjectionConfig = (): ImagePromptInjectionConfig => ({
enabled: true,
defaultPrompt: DEFAULT_IMAGE_PROMPT_INJECTION,
negativePrompt: DEFAULT_IMAGE_NEGATIVE_PROMPT_INJECTION,
});

interface MarkdownSection {
heading: string;
body: string;
}

const FENCE_REGEX: RegExp = /```(?:text|prompt|markdown)?\s*\n([\s\S]*?)\n```/;

export function applyImagePrompt(userPrompt: string, config: ImagePromptInjectionConfig): string {
  const defaultPrompt: string = config.defaultPrompt.trim();
  const negativePrompt: string = config.negativePrompt.trim();
  if (!config.enabled || (defaultPrompt.length === 0 && negativePrompt.length === 0)) {
    return userPrompt;
  }
  let out: string = userPrompt.trim();
  out += '\n\nImage generation defaults to apply unless the user explicitly conflicts:\n';
  if (defaultPrompt.length > 0) out += defaultPrompt;
  if (negativePrompt.length > 0) {
    out += '\n\nAvoid / de-emphasize:\n';
    out += negativePrompt;
  }
  return out;
}

export function parseImageConfig(markdown: string): ImagePromptInjectionConfig {
  const enabledMeta: string | null = findMetadataValue(markdown, 'enabled');
  const enabled: boolean = !(enabledMeta !== null && enabledMeta.toLowerCase() === 'false');
  const dp: string = extractFencedSection(markdown, 'Default prompt');
  const np: string = extractFencedSection(markdown, 'Negative prompt');
  return {
    enabled,
    defaultPrompt: dp.trim().length === 0 ? DEFAULT_IMAGE_PROMPT_INJECTION : dp,
    negativePrompt: np.trim().length === 0 ? DEFAULT_IMAGE_NEGATIVE_PROMPT_INJECTION : np,
  };
}

export function renderImageConfig(config: ImagePromptInjectionConfig): string {
  return `# Image Generation Prompt Injection

enabled: ${config.enabled}

Agent-editable defaults appended to every image-generation request. Keep this file concise:
it is injected into the provider prompt, not shown as a UI preset.

## Default prompt

\`\`\`text
${config.defaultPrompt.trim().replace(/```/g, '` ` `')}
\`\`\`

## Negative prompt

\`\`\`text
${config.negativePrompt.trim().replace(/```/g, '` ` `')}
\`\`\`
` + '';
}

export function renderSubAgentPrompts(setting: SubAgentRuntimeSetting): string {
  let out: string = '# SubAgent Prompt Overrides\n';
  out += '\n';
  out += 'Edit built-in role prompts here. AmberAgent settings and agent tools mirror these entries.\n';
  out += 'Use the built-in id as the level-2 heading.\n';
  out += '\n';
  SUB_AGENT_BUILT_INS.forEach((def: SubAgentDefinition): void => {
    const effective: SubAgentDefinition = subAgentApplyOverride(
      def, setting.overrides.get(def.id) ?? null);
    out += `## ${def.id}\n`;
    out += '\n';
    out += `name: ${def.name}\n`;
    out += '\n';
    out += '```text\n';
    out += effective.systemPrompt.trim().replace(/```/g, '` ` `') + '\n';
    out += '```\n';
    out += '\n';
  });
  return out;
}

export function renderModelCouncilPrompts<Setting, Seat>(
setting: Setting, council: CouncilSettingStrategy<Setting, Seat>,
): string {
  let out: string = '# Model Council Seat Prompts\n';
  out += '\n';
  out += 'Edit persistent council seat prompts here. Use the seat id heading for stable sync.\n';
  out += '\n';
  council.seatsOf(setting).forEach((seat: Seat): void => {
    out += `## ${council.seatIdOf(seat)}\n`;
    out += '\n';
    out += `name: ${council.seatNameOf(seat)}\n`;
    out += `role: ${council.seatRoleOf(seat)}\n`;
    out += '\n';
    out += '```text\n';
    out += council.seatPromptOf(seat).trim().replace(/```/g, '` ` `') + '\n';
    out += '```\n';
    out += '\n';
  });
  return out;
}

export function renderContextCompactionPrompt(prompt: string): string {
  return `# Context Compaction Handoff Prompt

Agent-editable instructions appended to every conversation compaction request.
Keep the JSON contract intact unless the app is updated to parse a new schema.

## Prompt

\`\`\`text
${prompt.trim().replace(/```/g, '` ` `')}
\`\`\`
` + '';
}

export function extractFencedSection(markdown: string, heading: string): string {
  const section: string = extractSection(markdown, heading);
  const m: RegExpMatchArray | null = section.match(FENCE_REGEX);
  const body: string = m !== null && m[1] !== undefined ? m[1] : '';
  return body.trim();
}

function extractSection(markdown: string, heading: string): string {
  const hit: MarkdownSection | undefined = splitSecondLevelSections(markdown)
    .find((s: MarkdownSection): boolean => s.heading.toLowerCase() === heading.toLowerCase());
  return hit === undefined ? '' : hit.body;
}

export function parseIdPromptSections(markdown: string): Map<string, string> {
  const out: Map<string, string> = new Map<string, string>();
  splitSecondLevelSections(markdown).forEach((s: MarkdownSection): void => {
    const id: string = s.heading.trim();
    const m: RegExpMatchArray | null = s.body.match(FENCE_REGEX);
    const prompt: string = (m !== null && m[1] !== undefined ? m[1] : '').trim();
    if (id.length > 0 && prompt.length > 0 && !out.has(id)) out.set(id, prompt);
  });
  return out;
}

function splitSecondLevelSections(markdown: string): MarkdownSection[] {
  const sections: MarkdownSection[] = [];
  let inFence: boolean = false;
  let heading: string | null = null;
  let body: string = '';
  const flush = (): void => {
    if (heading === null) return;
    sections.push({ heading, body });
    body = '';
  };
  markdown.split('\n').forEach((line: string): void => {
    const trimmedStart: string = line.replace(/^\s+/, '');
    const isFence: boolean = trimmedStart.startsWith('```');
    if (!inFence && trimmedStart.startsWith('## ') && !trimmedStart.startsWith('###')) {
      flush();
      heading = trimmedStart.substring(3).trim();
      return;
    }
    if (heading !== null) {
      body += line + '\n';
    }
    if (isFence) {
      inFence = !inFence;
    }
  });
  flush();
  return sections;
}

function findMetadataValue(markdown: string, key: string): string | null {
  let inFence: boolean = false;
  const lines: string[] = markdown.split('\n');
  for (const line of lines) {
    const trimmed: string = line.trim();
    if (trimmed.startsWith('```')) {
      inFence = !inFence;
      continue;
    }
    if (!inFence && trimmed.toLowerCase().startsWith(`${key.toLowerCase()}:`)) {
      const idx: number = trimmed.indexOf(':');
      return trimmed.substring(idx + 1).trim();
    }
  }
  return null;
}
