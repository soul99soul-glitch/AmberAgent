// memory_extraction_prompt — MemoryExtractionPrompt.kt 全文移植(D-085d)
//
// Android 基准: core/memory/prompt/MemoryExtractionPrompt.kt(全文 47 行)
//   - 每消息块 trimIndent 逐字:message_id/role(小写)/text(take 4000)
//   - 模板块 trimIndent 逐字:Locale 行/JSON 骨架/Rules 九条/来源 ids/Conversation
// 偏差:role.name.lowercase() — MessageRole 已小写枚举文本,直接输出

import type { UIMessage } from './message.ts';
import { toText } from './message.ts';

export const buildMemoryExtractionPrompt = (
  messages: UIMessage[], sourceMessageIds: string[], locale: string,
): string => {
  const blocks: string[] = messages.map((message: UIMessage): string =>
    `message_id: ${message.id}\nrole: ${message.role}\ntext: ${toText(message).slice(0, 4000)}`);
  const content: string = blocks.join('\n\n');
  return 'You extract durable memory candidates for AmberAgent.\n' +
    `Locale: ${locale}\n` +
    '\n' +
    'Return only valid JSON:\n' +
    '{\n' +
    '  "candidates": [\n' +
    '    {\n' +
    '      "content": "short useful memory",\n' +
    '      "scope": "short_term|long_term|core",\n' +
    '      "kind": "user|feedback|project|reference|routine|note",\n' +
    '      "confidence": 0.0,\n' +
    '      "reason": "why this is worth remembering",\n' +
    '      "expires_in_days": null\n' +
    '    }\n' +
    '  ]\n' +
    '}\n' +
    '\n' +
    'Rules:\n' +
    '- Extract only information likely useful in future conversations.\n' +
    '- Do not store sensitive personal data unless the user explicitly asked to remember it.\n' +
    '- Prefer short_term/project for active work and long_term for stable preferences.\n' +
    '- For temporary plans, trips, meetings, deadlines, or short-lived preferences, set expires_in_days.\n' +
    '- Keep expires_in_days null for stable user preferences, feedback, core facts, historical facts, and uncertain relative dates.\n' +
    '- Do not invent facts.\n' +
    '- At most 5 candidates.\n' +
    `Use these source_message_ids when relevant: ${sourceMessageIds.join(', ')}.\n` +
    '\n' +
    'Conversation:\n' +
    content;
};
