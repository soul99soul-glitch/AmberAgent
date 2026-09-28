import { util } from '@kit.ArkTS';
import { createPluginLoopAdapter, normalizeWorkspacePath } from '@amber/chat-domain';
import type { AgentTool, InstalledPlugin, PluginLoopAdapter, PluginStore,
  PosixWorkspaceManager } from '@amber/chat-domain';
import { createNativePluginJsTransport } from './NativePluginJsTransport.ets';
import { createEntryPluginHttpPort } from './PluginHttpPort.ets';
import { createEntryPluginWebMountPort } from './PluginWebMountGate.ets';
import { getWebMountSession } from './WebMountSession.ets';

export const createEntryPluginAdapter = (
  store: PluginStore, installed: InstalledPlugin[], primitives: AgentTool[],
  conversationId: string | null = null,
): PluginLoopAdapter => createPluginLoopAdapter({
  store, installed,
  js: createNativePluginJsTransport(conversationId), http: createEntryPluginHttpPort(),
  webMount: createEntryPluginWebMountPort(getWebMountSession(), conversationId),
  resolveMcpPrimitive: (serverId: string, toolName: string): AgentTool | null => primitives.find(
    (tool: AgentTool): boolean => tool.mcpTarget?.serverId === serverId
      && tool.mcpTarget?.toolName === toolName) ?? null,
});

// The center invokes this only for the user's explicit export action.
export const exportEntryPlugin = async (
  store: PluginStore, workspace: PosixWorkspaceManager, id: string, hash: string, destination: string,
): Promise<void> => {
  const path: string = normalizeWorkspacePath(destination);
  if (path.length === 0 || path === '.') throw new Error('请选择 Workspace 中的归档文件路径。');
  const bytes: Uint8Array = await store.exportArchive(id, hash);
  const text: string = util.TextDecoder.create('utf-8', { fatal: true }).decodeToString(bytes);
  await workspace.writeText(path, text);
};
