import type { AgentTool, Assistant, LocalToolOption, AgentToolActivityStore, PosixWorkspaceManager,
  TerminalRuntime, MoshRuntime, SSHProfileStore, PythonRuntime } from '@amber/chat-domain';
import { createTimeTool, createTerminalTools, createMoshTools, createPythonTool, createAskUserTool,
  createWorkspaceTools, createWorkspaceArtifactTools } from '@amber/chat-domain';
import { createJavascriptExecuteTool } from './JsSandboxTool.ets';
import { createArtifactHttpPort, createArtifactImagePort, createDeflateRawPort } from './ArtifactPorts.ets';
import { inflateRawEntry, createEntryXmlPull } from './EntryDocumentParser.ets';

export interface AssistantLocalPrimitivesDeps {
  assistant: Assistant;
  conversationId: string;
  workspace: PosixWorkspaceManager;
  activityStore: AgentToolActivityStore;
  terminalRuntime: TerminalRuntime | null;
  moshRuntime: MoshRuntime | null;
  sshProfiles: SSHProfileStore | null;
  pythonRuntime: PythonRuntime | null;
}

// The caller owns runtime initialization, tool-profile filtering and host mounting.
export const createAssistantLocalPrimitives = (deps: AssistantLocalPrimitivesDeps): AgentTool[] => {
  const tools: AgentTool[] = [];
  const options: LocalToolOption[] = deps.assistant.localTools;
  if (options.indexOf('time_info') >= 0) tools.push(createTimeTool());
  // Preserve ChatPage's terminalGuard availability gate for both SSH and Mosh.
  if (options.indexOf('terminal') >= 0 && deps.terminalRuntime !== null && deps.sshProfiles !== null) {
    tools.push(...createTerminalTools({ runtime: deps.terminalRuntime, profiles: deps.sshProfiles,
      activityStore: deps.activityStore, conversationId: deps.conversationId }));
    if (deps.moshRuntime !== null) {
      tools.push(...createMoshTools({ runtime: deps.moshRuntime, profiles: deps.sshProfiles,
        activityStore: deps.activityStore, conversationId: deps.conversationId }));
    }
  }
  if (options.indexOf('python') >= 0 && deps.pythonRuntime !== null) {
    tools.push(createPythonTool({ runtime: deps.pythonRuntime,
      activityStore: deps.activityStore, conversationId: deps.conversationId }));
  }
  if (options.indexOf('ask_user') >= 0) tools.push(createAskUserTool());
  if (options.indexOf('javascript_engine') >= 0) tools.push(createJavascriptExecuteTool());
  if (options.indexOf('workspace_files') >= 0) {
    tools.push(...createWorkspaceTools(deps.workspace, deps.activityStore));
    tools.push(...createWorkspaceArtifactTools({
      workspaceManager: deps.workspace,
      activityStore: deps.activityStore,
      http: createArtifactHttpPort(),
      image: createArtifactImagePort(),
      inflateRaw: inflateRawEntry,
      deflateRaw: createDeflateRawPort(),
      newXmlParser: createEntryXmlPull,
    }));
  }
  return tools;
};
