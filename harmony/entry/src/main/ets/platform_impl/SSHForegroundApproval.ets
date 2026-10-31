import screenLock from '@ohos.screenLock';
import type { Conversation, ToolApprovalVerdict, ToolApprovalPartLocator, UIMessagePartTool } from '@amber/chat-domain';
import { collectToolApprovalCandidates, isSSHProfileMainSessionOnly } from '@amber/chat-domain';

// API 7 public query is deprecated; its replacement is restricted to system apps.
// Do not request unlock or new permissions. Unknown lock state denies manual approval.
export const assertSSHForegroundApproval = async (conversation: Conversation, toolCallId: string,
  verdict: ToolApprovalVerdict, locator?: ToolApprovalPartLocator): Promise<void> => {
  if (verdict.kind === 'denied') return;
  let candidates: UIMessagePartTool[] = collectToolApprovalCandidates(conversation, toolCallId);
  if (locator !== undefined) {
    candidates = [];
    for (const node of conversation.messageNodes) {
      for (const message of node.messages) {
        if (message.id !== locator.messageId) continue;
        const part = message.parts[locator.partIndex];
        if (part?.type === 'tool') candidates.push(part);
      }
    }
  }
  if (!candidates.some((part: UIMessagePartTool): boolean => isSSHProfileMainSessionOnly(part.toolName))) return;
  if (candidates.some((part: UIMessagePartTool): boolean => part.metadata?.['ssh_trust_settings_required'] === true)) {
    throw new Error('已保存的主机指纹只能在 SSH 设置中人工核对和替换');
  }
  if (AppStorage.get<boolean>('appBackgrounded') === true) throw new Error('SSH 写操作只能在解锁后的前台卡片批准');
  let locked: boolean = true;
  try { locked = await screenLock.isScreenLocked(); } catch (_error) { locked = true; }
  if (locked || AppStorage.get<boolean>('appBackgrounded') === true) {
    throw new Error('SSH 写操作只能在解锁后的前台卡片批准');
  }
};
