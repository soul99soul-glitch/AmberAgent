// Neutral runtime data contracts shared by composition and detached conversation execution.
import type { OpenAIChatApi, Assistant, TextGenerationParams, ProviderSetting, ProviderModel, ModelAbility, SkillMetadata, TerminalRuntime, PythonRuntime, MoshRuntime, SSHProfileStore, AgentTool } from '@amber/chat-domain';
import type { AgentRuntimeSnapshot } from '../platform_impl/AgentRuntimePrefs.ets';
export interface ChatProviderChoice {
    providerContainerId: string;
    // D-046:三变体(openai/google/claude)统一解析;api 层均为结构 OpenAIChatApi
    provider: ProviderSetting;
    model: ProviderModel;
}
export interface ChatProviderRuntime {
    api: OpenAIChatApi;
    resolvedChoice: ChatProviderChoice;
    assistant: Assistant;
    params: TextGenerationParams;
    contextMessageSize: number;
    // D-055:压缩规划用的模型上下文窗口(Android Model.contextWindowTokens,null → 引擎默认窗口)
    modelContextWindowTokens: number | null;
    // D-071b:model.inputModalities.contains(Modality.IMAGE)(GenerationHandler.kt:431
    //   canUseVisionFallback 前半 + OcrTransformer.kt:50 transform 门)
    modelSupportsImageInput: boolean;
    // D-121:model.abilities(ChatService.kt:1278 tools_warning 门 —
    //   !abilities.contains(ModelAbility.TOOL))
    modelAbilities: ModelAbility[];
}
export interface ChatRuntime extends ChatProviderRuntime {
    // D-123:skillManager.listSkills() 快照(ChatService.kt:2241 createSkillTools
    //   入参;createRunTools 时机 ≈ resolveChatRuntime 时机 = 每次发送)
    allSkills: SkillMetadata[];
    terminalRuntime: TerminalRuntime | null;
    pythonRuntime: PythonRuntime | null;
    moshRuntime: MoshRuntime | null;
    sshProfiles: SSHProfileStore | null;
}
export interface SubAgentToolsContext {
    parentConversationId: string;
    parentToolsProvider: () => AgentTool[];
    parentRuntime?: ChatRuntime;
    parentAgentRuntime?: AgentRuntimeSnapshot;
}
export interface TaskPrompts {
    titlePrompt: string;
    suggestionPrompt: string;
    compressPrompt: string;
}
