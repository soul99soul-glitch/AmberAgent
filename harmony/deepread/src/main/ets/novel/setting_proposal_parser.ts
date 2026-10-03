import { parseNovelMaterialFields } from './material_fields.ts';
import type { NovelMaterialFieldsPayload } from './material_fields.ts';
// novel/setting_proposal_parser — 讨论/写作输出中的设定提案 JSON 解析
//
// iOS: NovelDiscussionAskParser / setting proposal card 同源语义。
// 模型可产出 fenced 或裸 JSON；解析失败返回 null(不抛,避免打断聊天流)。

import { mapSuggestionKind } from './suggestion_engine.ts';
import { makeNovelSettingProposal } from './models.ts';
import type { NovelSettingProposal } from './models.ts';

export const SETTING_PROPOSAL_MARKER = 'novel_setting_proposal';

const TITLE_CHARS = 300;
const CONTENT_CHARS = 20_000;
export const MAX_PROPOSALS = 12;

interface RawChange extends NovelMaterialFieldsPayload {
  kind?: unknown;
  title?: unknown;
  key?: unknown;
  value?: unknown;
  content?: unknown;
  reason?: unknown;
}

interface RawEnvelope {
  type?: unknown;
  changes?: RawChange[];
  proposals?: RawChange[];
}

const extractJsonObject = (text: string): string | null => {
  // 优先 ```json / ``` 围栏
  const fence: RegExpMatchArray | null = /```(?:json)?\s*([\s\S]*?)```/i.exec(text);
  if (fence !== null) {
    const inner: string = fence[1];
    const s: number = inner.indexOf('{');
    const e: number = inner.lastIndexOf('}');
    if (s >= 0 && e > s) return inner.slice(s, e + 1);
  }
  const start: number = text.indexOf('{');
  const end: number = text.lastIndexOf('}');
  if (start < 0 || end <= start) return null;
  return text.slice(start, end + 1);
};

export const parseSettingProposals = (
  text: string, sourceMessageId: string, now: number,
): NovelSettingProposal[] => {
  const jsonText: string | null = extractJsonObject(text);
  if (jsonText === null) return [];
  let parsed: RawEnvelope;
  try {
    parsed = JSON.parse(jsonText) as RawEnvelope;
  } catch {
    return [];
  }
  const type: string = String(parsed.type ?? '').trim();
  if (type.length > 0 && type !== SETTING_PROPOSAL_MARKER) return [];
  const rawItems: RawChange[] | undefined =
    parsed.changes !== undefined && Array.isArray(parsed.changes)
      ? parsed.changes
      : (Array.isArray(parsed.proposals) ? parsed.proposals : undefined);
  if (rawItems === undefined) return [];
  const out: NovelSettingProposal[] = [];
  for (let i = 0; i < rawItems.length && out.length < MAX_PROPOSALS; i++) {
    const it: RawChange = rawItems[i];
    const title: string = String(it.title ?? it.key ?? '').trim().slice(0, TITLE_CHARS);
    const content: string = String(it.content ?? it.value ?? '').trim().slice(0, CONTENT_CHARS);
    if (title.length === 0 || content.length === 0) continue;
    out.push(makeNovelSettingProposal({
      sourceMessageId: sourceMessageId,
      kind: mapSuggestionKind(it.kind),
      ...parseNovelMaterialFields(it),
      title: title,
      content: content,
      now: now,
    }));
  }
  return out;
};

export const isSettingProposalEnvelope = (text: string): boolean => {
  const jsonText: string | null = extractJsonObject(text);
  if (jsonText === null) return false;
  try {
    const parsed = JSON.parse(jsonText) as RawEnvelope;
    return String(parsed.type ?? '') === SETTING_PROPOSAL_MARKER;
  } catch {
    return false;
  }
};
