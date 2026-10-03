import { parseNovelMaterialFields } from './material_fields.ts';
import type { NovelMaterialFieldsPayload } from './material_fields.ts';
// Quick start stores the original rich message and stages each material separately.
import { defaultGhostwriteDigest } from './ghostwrite.ts';
import { invalidInput, invalidModelOutput } from './error.ts';
import { makeNovelSettingProposal } from './models.ts';
import type { NovelMaterialKind, NovelMessage, NovelProject, NovelSettingProposal } from './models.ts';
import { novelMessageText } from './transcript.ts';

export interface NovelQuickStartOutput {
  overview: string;
  proposals: NovelSettingProposal[];
}
interface RawQuickStartItem extends NovelMaterialFieldsPayload { kind?: unknown; title?: unknown; content?: unknown }
interface RawQuickStart { overview?: unknown; proposals?: unknown }
const KINDS: NovelMaterialKind[] = ['world', 'character', 'relationship', 'outline', 'requirement', 'other'];

export const parseNovelQuickStart = (text: string, sourceMessageId: string, now: number): NovelQuickStartOutput => {
  let raw: RawQuickStart | null;
  try { raw = JSON.parse(text.trim()) as RawQuickStart; }
  catch { throw invalidModelOutput('快速开始解析失败：请重试，模型未返回有效 JSON'); }
  if (raw === null || typeof raw !== 'object' || typeof raw.overview !== 'string' ||
    raw.overview.trim().length === 0 || raw.overview.length > 12_000 ||
    !Array.isArray(raw.proposals) || raw.proposals.length < 1 || raw.proposals.length > 12) {
    throw invalidModelOutput('快速开始解析失败：需要概览和 1–12 项资料提案');
  }
  const proposals: NovelSettingProposal[] = [];
  const items: RawQuickStartItem[] = raw.proposals as RawQuickStartItem[];
  for (let index: number = 0; index < items.length; index++) {
    const item: RawQuickStartItem = items[index];
    if (item === null || typeof item !== 'object' || typeof item.kind !== 'string' ||
      !KINDS.includes(item.kind as NovelMaterialKind) || typeof item.title !== 'string' ||
      item.title.trim().length === 0 || item.title.length > 300 || typeof item.content !== 'string' ||
      item.content.trim().length === 0 || item.content.length > 20_000) {
      throw invalidModelOutput(`快速开始解析失败：第 ${index + 1} 项资料提案无效`);
    }
    proposals.push(makeNovelSettingProposal({ id: `quick-${defaultGhostwriteDigest(sourceMessageId)}-${index}`, sourceMessageId,
      ...parseNovelMaterialFields(item), kind: item.kind as NovelMaterialKind, title: item.title.trim(), content: item.content.trim(), now }));
  }
  return { overview: raw.overview.trim(), proposals };
};

export const stageNovelQuickStart = (
  project: NovelProject, sourceMessageId: string, text: string, now: number,
): NovelProject => {
  const source: NovelMessage | undefined = project.messages.find(message => message.id === sourceMessageId);
  if (source === undefined || source.role !== 'assistant' || source.interrupted || novelMessageText(source) !== text) {
    throw invalidInput('快速开始来源消息已变更，提案未保存');
  }
  const output: NovelQuickStartOutput = parseNovelQuickStart(text, sourceMessageId, now);
  const existing: Set<string> = new Set(project.settingProposals.map(proposal => proposal.id));
  return { ...project, settingProposals: project.settingProposals.concat(
    output.proposals.filter(proposal => !existing.has(proposal.id))), updatedAt: now };
};

export const formatNovelQuickStartOverview = (project: NovelProject, message: NovelMessage): string | null => {
  if (!project.settingProposals.some(proposal => proposal.sourceMessageId === message.id &&
    proposal.id.startsWith(`quick-${defaultGhostwriteDigest(message.id)}-`))) {
    if (message.runKind !== 'quick_start') return null;
    return message.interrupted ? '快速开始已停止，未生成可采用的资料提案。'
      : '快速开始未完成资料提案，请重新生成。';
  }
  try { return parseNovelQuickStart(novelMessageText(message), message.id, message.createdAt).overview; }
  catch { return null; }
};
