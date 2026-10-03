import type { NovelDiscussionArchive, NovelMessage } from '@amber/deepread-domain';

// 归档范围来自已确认记录；展开只是当前页面的阅读偏好。
export const discussionArchiveAtMessage = (archives: NovelDiscussionArchive[], message: NovelMessage): NovelDiscussionArchive | null =>
  message.mode === 'discuss' ? archives.find((archive: NovelDiscussionArchive): boolean => archive.sourceMessageIds[0] === message.id) ?? null : null;

export const discussionMessageVisible = (archives: NovelDiscussionArchive[], expandedIds: string[], message: NovelMessage): boolean =>
  message.mode !== 'discuss' || !archives.some((archive: NovelDiscussionArchive): boolean =>
    archive.sourceMessageIds.includes(message.id) && !expandedIds.includes(archive.id));
