// 用户主动将消息保存为既有 Workspace 中的 Markdown 产物。
import { messageArchiveMarkdown, newId } from '@amber/chat-domain';
import type { Conversation } from '@amber/chat-domain';
import { createEntryWorkspaceManager } from './WorkspaceStore.ets';
import { WorkspaceFileInfo } from '../components/WorkspaceFileVM.ets';

export const archiveChatMessage = async (
  conversation: Conversation, messageId: string,
): Promise<WorkspaceFileInfo> => {
  const manager = createEntryWorkspaceManager();
  const name: string = `chat-message-${Date.now()}-${newId()}.md`;
  const entry = await manager.writeText(`chat-archives/${name}`, messageArchiveMarkdown(conversation, messageId));
  return { name: entry.name, path: entry.path, size: entry.sizeBytes ?? 0,
    modifiedAt: new Date().toISOString(), mimeType: 'text/markdown' };
};

export const readArchivedMessage = async (file: WorkspaceFileInfo): Promise<string> =>
  createEntryWorkspaceManager().readText(file.path);
