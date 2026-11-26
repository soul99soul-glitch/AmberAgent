package app.amber.agent

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

sealed interface Screen : NavKey {

    @Serializable
    data class Chat(
        val id: String,
        val text: String? = null,
        val files: List<String> = emptyList(),
        val nodeId: String? = null,
        val messageId: String? = null,
        val toolCallId: String? = null,
    ) : Screen

    /**
     * Host-led Council Room page. conversationId links back to the conversation
     * that owns the Room (1 conversation : 1 Room). The Room state is loaded
     * by [app.amber.feature.ui.pages.councilroom.CouncilRoomVM] from the
     * [app.amber.feature.modelcouncil.CouncilRoomManager].
     */
    @Serializable
    data class CouncilRoom(val conversationId: String) : Screen

    @Serializable
    data class ShareHandler(
        val text: String,
        val streamUri: String? = null,
        val streamUris: List<String> = emptyList(),
        val sourceAction: String? = null,
        val declaredMimeType: String? = null,
        val deliveryId: String = Uuid.random().toString(),
    ) : Screen

    @Serializable
    data object History : Screen

    /** Personal Agent 主舞台 —— 默认产品首页路由。 */
    @Serializable
    data object PersonalAgent : Screen

    /** 会话首页 —— 聊天侧滑抽屉提升成的固定首页。 */
    @Serializable
    data object SessionHome : Screen

    /** 个人资料 / 统计页（头像入口）。 */
    @Serializable
    data object Profile : Screen

    @Serializable
    data object Favorite : Screen

    @Serializable
    data object LiveCompanion : Screen

    /** Configure LAN connection to a Mac-hosted Synara workbench. */
    @Serializable
    data object SynaraCompanion : Screen

    /**
     * Full-screen WebView of the remote Synara workbench.
     * Connection is passed on the route so open does not race DataStore / ViewModel init.
     */
    @Serializable
    data class SynaraWorkspace(
        val host: String,
        val port: Int = 3773,
        val token: String = "",
        val useHttps: Boolean = false,
    ) : Screen

    /** Paste a 智谱 ZCode share URL and open its mobile web UI. */
    @Serializable
    data object ZCode : Screen

    /** Full-screen ZCode WebView (no Amber chrome). */
    @Serializable
    data class ZCodeSession(val url: String) : Screen

    @Serializable
    data object Setting : Screen

    @Serializable
    data object Backup : Screen

    @Serializable
    data class WebView(val url: String = "", val content: String = "") : Screen

    @Serializable
    data class WebMountSession(
        val sessionId: String,
        val reopen: Boolean = false,
    ) : Screen

    @Serializable
    data object SettingDisplay : Screen

    @Serializable
    data object SettingAppearance : Screen

    @Serializable
    data object SettingProvider : Screen

    @Serializable
    data class SettingProviderDetail(val providerId: String) : Screen

    @Serializable
    data object SettingModels : Screen

    @Serializable
    data object SettingAbout : Screen

    @Serializable
    data object SettingAgentMemory : Screen

    @Serializable
    data object SettingAgentMemoryRecall : Screen

    @Serializable
    data object SettingAgentMemoryWorker : Screen

    @Serializable
    data object SettingAgentMemoryCompaction : Screen

    @Serializable
    data object SettingAgentMemoryLibrary : Screen

    @Serializable
    data object SettingAgentExtensions : Screen

    @Serializable
    data object SettingSlidesFonts : Screen

    @Serializable
    data object SettingCronTasks : Screen

    @Serializable
    data object SettingAgentRuntimeTasks : Screen

    @Serializable
    data object SettingAgentExecution : Screen
    data object SettingTts : Screen

    @Serializable
    data object SettingAgentPermissions : Screen

    @Serializable
    data object SettingCapabilityPermissions : Screen

    @Serializable
    data object SettingSearch : Screen

    @Serializable
    data object SettingJev : Screen

    @Serializable
    data object SettingMcp : Screen

    @Serializable
    data object SettingFiles : Screen

    @Serializable
    data object SettingChatStorage : Screen

    @Serializable
    data object SettingStorage : Screen

    @Serializable
    data object SettingSandbox : Screen

    @Serializable
    data object SettingExperimental : Screen

    @Serializable
    data object SettingExperimentalICloud : Screen

    @Serializable
    data object SettingExperimentalMacGateway : Screen

    @Serializable
    data object SettingExperimentalSubAgent : Screen

    @Serializable
    data object SettingExperimentalModelCouncil : Screen

    @Serializable
    data object SettingExperimentalWebMount : Screen

    @Serializable
    data object SettingSystemAccess : Screen

    @Serializable
    data object Developer : Screen

    @Serializable
    data object Debug : Screen

    @Serializable
    data object Log : Screen

    @Serializable
    data object Extensions : Screen

    @Serializable
    data object QuickMessages : Screen

    @Serializable
    data object Prompts : Screen

    @Serializable
    data object Skills : Screen

    @Serializable
    data class SkillDetail(val skillName: String) : Screen

    @Serializable
    data object MessageSearch : Screen

    @Serializable
    data object Stats : Screen

    @Serializable
    data object TodayBoard : Screen

    @Serializable
    data object MiniAppList : Screen

    @Serializable
    data object NovelProjects : Screen

    @Serializable
    data class NovelMarkdown(
        val projectId: String,
        val branchSlug: String? = null,
        val jobId: String? = null,
    ) : Screen

    @Serializable
    data class MiniAppRunner(val appId: String) : Screen

    @Serializable
    data object MiniAppSettings : Screen

    @Serializable
    data class MiniAppSettingsDetail(val group: String) : Screen

    @Serializable
    data object DeepReadTemplateWorkbench : Screen

    @Serializable
    data class DeepRead(
        val topicId: String,
        val title: String,
        val sourceUrl: String? = null,
        val forceRegenerate: Boolean = false,
        val fromHistory: Boolean = false,
    ) : Screen

    @Serializable
    data object DeepReadHistory : Screen

    @Serializable
    data object SettingTodayBoard : Screen

    @Serializable
    data class SettingTodayBoardDetail(val pane: String) : Screen
}
