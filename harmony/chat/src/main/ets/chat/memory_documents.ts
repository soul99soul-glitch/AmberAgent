import type { MemoryRecord } from './memory_models.ts';
import type { MemoryProfile } from './memory_profile.ts';
import { selectMemoryProfile } from './memory_profile.ts';
import { isMemoryTopicSource, pruneMemoryTopics } from './memory_topics.ts';

export interface MemoryDocumentProjection {
  relativePath: string;
  title: string;
  preview: string;
  content: string;
  topicId: number | null;
}

export interface MemoryDerivedDocument {
  relativePath: string;
  title: string;
  preview: string;
  sizeBytes: number;
  modifiedAt: number;
  topicId: number | null;
}

const preview = (text: string): string => Array.from(text.replace(/\s+/g, ' ').trim()).slice(0, 120).join('');
const inline = (text: string): string => text.replace(/[\\`*_[\]<>]/g, '\\$&').replace(/\r?\n/g, ' ');
const slug = (title: string): string => {
  const safe: string = title.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, '-').replace(/^-+|-+$/g, '');
  return Array.from(safe).slice(0, 36).join('') || 'topic';
};
const date = (time: number): string => new Date(time).toISOString();
const sourceText = (record: MemoryRecord): string =>
  `### 源记忆 #${record.id}\n\n` +
  `- 分类：${record.scope} / ${record.kind}\n` +
  `- 创建：${date(record.createdAt)}\n- 更新：${date(record.updatedAt)}\n` +
  (record.expiresAt === null ? '' : `- 到期：${date(record.expiresAt)}\n`) +
  `- 来源会话：${record.sourceConversationId === null ? '无来源会话记录' : inline(record.sourceConversationId)}\n` +
  `- 来源消息：${record.sourceMessageIds.length === 0 ? '无来源消息记录' : record.sourceMessageIds.map(inline).join('、')}\n\n` +
  (record.evidence == null ? '' : `- 用户原话：${inline(record.evidence)}\n\n`) +
  `${record.content}\n\n`;

export const renderMemoryDocuments = (records: MemoryRecord[], now: number, profile: MemoryProfile | null = null): MemoryDocumentProjection[] => {
  const sources: MemoryRecord[] = records.filter((record: MemoryRecord): boolean => isMemoryTopicSource(record, now));
  if (sources.length === 0) return [];
  const byId: Map<number, MemoryRecord> = new Map<number, MemoryRecord>(sources.map(
    (record: MemoryRecord): [number, MemoryRecord] => [record.id, record]));
  const topics: MemoryRecord[] = pruneMemoryTopics(records, now).records.filter(
    (record: MemoryRecord): boolean => record.kind === 'topic' && !record.archived);
  const covered: Set<number> = new Set<number>();
  const projections: MemoryDocumentProjection[] = [];
  let topicIndex: string = '';
  for (const topic of topics) {
    const title: string = topic.topicTitle ?? `主题 #${topic.id}`;
    const relativePath: string = `topics/${topic.id}-${slug(title)}.md`;
    const members: MemoryRecord[] = topic.memberIds.map((id: number): MemoryRecord | undefined => byId.get(id))
      .filter((record: MemoryRecord | undefined): record is MemoryRecord => record !== undefined);
    members.forEach((record: MemoryRecord): void => { covered.add(record.id); });
    const content: string = `# ${inline(title)}\n\n${topic.content}\n\n` +
      `主题 #${topic.id} · ${members.length} 条源记忆 · 摘要生成：${date(topic.updatedAt)}\n\n` +
      members.map(sourceText).join('');
    projections.push({ relativePath, title, preview: preview(topic.content), content, topicId: topic.id });
    topicIndex += `- [${inline(title)}](${relativePath})：${members.length} 条源记忆。${inline(preview(topic.content))}\n`;
  }
  const ungrouped: MemoryRecord[] = sources.filter((record: MemoryRecord): boolean => !covered.has(record.id));
  const profileSelection = selectMemoryProfile(profile, records, ['core', 'short_term', 'long_term'], null, now, 12000);
  const profileText: string = profileSelection.prompt.length === 0 ? '' : '## 用户画像\n\n' +
    (profile?.items.map((item): string => `- ${inline(item.text)}（依据：${item.memoryIds.map((id: number): string => '#' + id).join('、')}）`).join('\n') ?? '') + '\n\n';
  const content: string = '# 记忆索引\n\n' + profileText +
    `${sources.length} 条有效源记忆，${topics.length} 个有效主题，已归组 ${covered.size} 条，未归组 ${ungrouped.length} 条。\n\n` +
    '## 主题\n\n' + (topicIndex || '尚无有效主题。\n') + '\n## 未归组源记忆\n\n' +
    (ungrouped.length === 0 ? '所有有效源记忆均已归组。\n' : ungrouped.map(sourceText).join(''));
  return [{ relativePath: 'index.md', title: '记忆索引', preview: `${sources.length} 条源记忆 · ${topics.length} 个主题`,
    content, topicId: null }, ...projections];
};
