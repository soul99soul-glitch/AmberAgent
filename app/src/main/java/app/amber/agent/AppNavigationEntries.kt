package app.amber.agent

import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.core.net.toUri
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import app.amber.feature.ui.pages.backup.BackupPage
import app.amber.feature.ui.pages.chat.ChatPage
import app.amber.feature.ui.pages.councilroom.CouncilRoomPage
import app.amber.feature.ui.pages.debug.DebugPage
import app.amber.feature.ui.pages.developer.DeveloperPage
import app.amber.feature.ui.pages.extensions.ExtensionsPage
import app.amber.feature.ui.pages.extensions.PromptPage
import app.amber.feature.ui.pages.extensions.QuickMessagesPage
import app.amber.feature.ui.pages.extensions.SkillDetailPage
import app.amber.feature.ui.pages.extensions.SkillsPage
import app.amber.feature.ui.pages.favorite.FavoritePage
import app.amber.feature.ui.pages.history.HistoryPage
import app.amber.feature.ui.pages.personalagent.PersonalAgentPage
import app.amber.feature.ui.pages.phoneuse.PhoneUseLivePage
import app.amber.feature.ui.pages.phoneuse.SettingPhoneUseDisplayPage
import app.amber.feature.ui.pages.sessionhome.SessionHomePage
import app.amber.feature.ui.pages.profile.ProfilePage
import app.amber.feature.ui.pages.live.LiveCompanionPage
import app.amber.feature.ui.pages.synara.SynaraConnectPage
import app.amber.feature.ui.pages.synara.SynaraConnection
import app.amber.feature.ui.pages.synara.SynaraWorkspacePage
import app.amber.feature.ui.pages.zcode.ZCodePage
import app.amber.feature.ui.pages.zcode.ZCodeSessionPage
import app.amber.feature.ui.pages.log.LogPage
import app.amber.feature.ui.pages.miniapp.MiniAppListPage
import app.amber.feature.ui.pages.novel.NovelMarkdownWorkspacePage
import app.amber.feature.ui.pages.novel.NovelProjectsPage
import app.amber.feature.ui.pages.miniapp.MiniAppRunnerPage
import app.amber.feature.ui.pages.miniapp.MiniAppSettingsPage
import app.amber.feature.ui.pages.search.SearchPage
import app.amber.feature.ui.pages.setting.SettingAboutPage
import app.amber.feature.ui.pages.setting.SettingAgentExecutionPage
import app.amber.feature.ui.pages.setting.SettingTtsPage
import app.amber.feature.ui.pages.setting.SettingAgentExtensionsPage
import app.amber.feature.ui.pages.setting.SettingAgentMemoryPage
import app.amber.feature.ui.pages.setting.SettingAgentMemoryCompactionPage
import app.amber.feature.ui.pages.setting.SettingAgentMemoryLibraryPage
import app.amber.feature.ui.pages.setting.SettingAgentMemoryRecallPage
import app.amber.feature.ui.pages.setting.SettingAgentMemoryWorkerPage
import app.amber.feature.ui.pages.setting.SettingAgentPermissionsPage
import app.amber.feature.ui.pages.setting.SettingCapabilityPermissionsPage
import app.amber.feature.ui.pages.setting.SettingAgentRuntimeTasksPage
import app.amber.feature.ui.pages.setting.SettingCronTasksPage
import app.amber.feature.ui.pages.setting.SettingAppearancePage
import app.amber.feature.ui.pages.setting.SettingDisplayPage
import app.amber.feature.ui.pages.setting.SettingExperimentalICloudPage
import app.amber.feature.ui.pages.setting.SettingExperimentalMacGatewayPage
import app.amber.feature.ui.pages.setting.SettingExperimentalModelCouncilPage
import app.amber.feature.ui.pages.setting.SettingExperimentalPage
import app.amber.feature.ui.pages.setting.SettingExperimentalSubAgentPage
import app.amber.feature.ui.pages.setting.SettingExperimentalWebMountPage
import app.amber.feature.ui.pages.board.TodayBoardPage
import app.amber.feature.ui.pages.board.SettingTodayBoardPage
import app.amber.feature.ui.pages.board.DeepReadScreen
import app.amber.feature.ui.pages.board.DeepReadHistoryPage
import app.amber.feature.ui.pages.board.DeepReadTemplateWorkbenchPage
import app.amber.feature.ui.pages.setting.SettingFilesPage
import app.amber.feature.ui.pages.setting.SettingChatStoragePage
import app.amber.feature.ui.pages.setting.SettingStoragePage
import app.amber.feature.ui.pages.setting.SettingMcpPage
import app.amber.feature.ui.pages.setting.SettingModelPage
import app.amber.feature.ui.pages.setting.SettingPage
import app.amber.feature.ui.pages.setting.SettingProviderDetailPage
import app.amber.feature.ui.pages.setting.SettingProviderPage
import app.amber.feature.ui.pages.setting.SettingSandboxPage
import app.amber.feature.ui.pages.setting.SettingSearchPage
import app.amber.feature.ui.pages.setting.SettingJevPage
import app.amber.feature.ui.pages.setting.SettingSlidesFontPage
import app.amber.feature.ui.pages.setting.SettingSystemAccessPage
import app.amber.feature.ui.pages.share.handler.ShareHandlerPage
import app.amber.feature.ui.pages.stats.StatsPage
import app.amber.feature.ui.pages.webview.WebViewPage
import app.amber.feature.ui.pages.webmount.WebMountSessionPage
import app.amber.feature.ui.adaptive.TwoPaneRole
import app.amber.feature.ui.adaptive.twoPaneMetadata
import app.amber.feature.ui.theme.ThemePageChrome
import app.amber.feature.ui.theme.themePageChromeMetadata
import kotlin.uuid.Uuid
import androidx.navigation3.runtime.NavEntry

internal fun appNavigationEntries(): (NavKey) -> NavEntry<NavKey> = entryProvider {
    entry<Screen.Chat>(metadata = twoPaneMetadata(TwoPaneRole.Detail)) { key ->
        ChatPage(
            enterTransition = LocalNavAnimatedContentScope.current.transition,
            id = Uuid.parse(key.id),
            text = key.text,
            files = key.files.map { it.toUri() },
            nodeId = key.nodeId?.let { Uuid.parse(it) },
            messageId = key.messageId,
            toolCallId = key.toolCallId,
        )
    }

    // No transition override: fall back to NavDisplay's default
    // slide-in/slide-out push (and default pop) so entering the
    // Council Room animates exactly like the Stats page.
    entry<Screen.CouncilRoom> { key ->
        CouncilRoomPage(conversationId = key.conversationId)
    }

    entry<Screen.ShareHandler> { key ->
        ShareHandlerPage(
            text = key.text,
            streamUris = key.streamUris.ifEmpty {
                key.streamUri?.let { listOf(it) }.orEmpty()
            },
            sourceAction = key.sourceAction,
            declaredMimeType = key.declaredMimeType,
            deliveryId = key.deliveryId,
        )
    }

    entry<Screen.History> {
        HistoryPage()
    }

    entry<Screen.PersonalAgent> {
        PersonalAgentPage(
            enterTransition = LocalNavAnimatedContentScope.current.transition,
        )
    }

    entry<Screen.SessionHome>(
        metadata = themePageChromeMetadata(ThemePageChrome.Home) + twoPaneMetadata(TwoPaneRole.List),
    ) {
        SessionHomePage()
    }

    entry<Screen.Profile> {
        ProfilePage()
    }

    entry<Screen.Favorite> {
        FavoritePage()
    }

    entry<Screen.LiveCompanion> {
        LiveCompanionPage()
    }

    entry<Screen.SynaraCompanion> {
        SynaraConnectPage()
    }

    entry<Screen.SynaraWorkspace> { key ->
        SynaraWorkspacePage(
            connection = SynaraConnection(
                host = key.host,
                port = key.port,
                token = key.token,
                useHttps = key.useHttps,
            ),
        )
    }

    entry<Screen.ZCode> {
        ZCodePage()
    }

    entry<Screen.ZCodeSession> { key ->
        ZCodeSessionPage(url = key.url)
    }

    entry<Screen.Setting>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingPage()
    }

    entry<Screen.Backup>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        BackupPage()
    }

    entry<Screen.WebView> { key ->
        WebViewPage(key.url, key.content)
    }

    entry<Screen.WebMountSession> { key ->
        WebMountSessionPage(
            sessionId = key.sessionId,
            reopen = key.reopen,
        )
    }

    entry<Screen.SettingDisplay>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingDisplayPage()
    }

    entry<Screen.SettingAppearance>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingAppearancePage()
    }

    entry<Screen.SettingProvider>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingProviderPage()
    }

    entry<Screen.SettingProviderDetail>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) { key ->
        val id = Uuid.parse(key.providerId)
        SettingProviderDetailPage(id = id)
    }

    entry<Screen.SettingModels>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingModelPage()
    }

    entry<Screen.SettingAbout>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingAboutPage()
    }

    entry<Screen.SettingAgentMemory>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingAgentMemoryPage()
    }

    entry<Screen.SettingAgentMemoryRecall>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingAgentMemoryRecallPage()
    }

    entry<Screen.SettingAgentMemoryWorker>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingAgentMemoryWorkerPage()
    }

    entry<Screen.SettingAgentMemoryCompaction>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingAgentMemoryCompactionPage()
    }

    entry<Screen.SettingAgentMemoryLibrary>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingAgentMemoryLibraryPage()
    }

    entry<Screen.SettingAgentExtensions>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingAgentExtensionsPage()
    }

    entry<Screen.SettingSlidesFonts>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingSlidesFontPage()
    }

    entry<Screen.SettingCronTasks>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingCronTasksPage()
    }

    entry<Screen.SettingAgentRuntimeTasks>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingAgentRuntimeTasksPage()
    }

    entry<Screen.SettingAgentExecution>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingAgentExecutionPage()
    }

    entry<Screen.SettingTts>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingTtsPage()
    }

    entry<Screen.SettingAgentPermissions>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingAgentPermissionsPage()
    }

    entry<Screen.SettingCapabilityPermissions>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingCapabilityPermissionsPage()
    }

    entry<Screen.SettingSearch>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingSearchPage()
    }

    entry<Screen.SettingJev>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingJevPage()
    }

    entry<Screen.PhoneUseLive> { key ->
        PhoneUseLivePage(runId = key.runId)
    }

    entry<Screen.SettingPhoneUseDisplay>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingPhoneUseDisplayPage()
    }

    entry<Screen.SettingMcp>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingMcpPage()
    }

    entry<Screen.SettingFiles>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingFilesPage()
    }

    entry<Screen.SettingChatStorage>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingChatStoragePage()
    }

    entry<Screen.SettingStorage>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingStoragePage()
    }

    entry<Screen.SettingSandbox>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingSandboxPage()
    }

    entry<Screen.SettingExperimental>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingExperimentalPage()
    }

    entry<Screen.SettingExperimentalICloud>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingExperimentalICloudPage()
    }

    entry<Screen.SettingExperimentalMacGateway>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingExperimentalMacGatewayPage()
    }

    entry<Screen.SettingExperimentalSubAgent>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingExperimentalSubAgentPage()
    }

    entry<Screen.SettingExperimentalModelCouncil>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingExperimentalModelCouncilPage()
    }

    entry<Screen.SettingExperimentalWebMount>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingExperimentalWebMountPage()
    }

    entry<Screen.TodayBoard> {
        TodayBoardPage()
    }

    entry<Screen.DeepRead>(
        // Standalone product opens articles like iOS's
        // `.zoom` transition — the page rises out of the
        // card instead of sliding in. The full app keeps
        // the shared slide.
        metadata = NavDisplay.transitionSpec {
            if (StandaloneSurfaces.current == StandaloneSurfaces.DEEP_READ) {
                (scaleIn(animationSpec = RouteTransitionSpecFloat, initialScale = 0.94f) + fadeIn(tween(220))) togetherWith fadeOut(tween(140))
            } else {
                routePushTransition()
            }
        } + NavDisplay.popTransitionSpec {
            if (StandaloneSurfaces.current == StandaloneSurfaces.DEEP_READ) {
                fadeIn(tween(200)) togetherWith
                    (scaleOut(animationSpec = RouteTransitionSpecFloat, targetScale = 0.94f) + fadeOut(tween(240)))
            } else {
                routePopTransition()
            }
        } + NavDisplay.predictivePopTransitionSpec {
            if (StandaloneSurfaces.current == StandaloneSurfaces.DEEP_READ) {
                fadeIn(tween(200)) togetherWith
                    (scaleOut(animationSpec = RouteTransitionSpecFloat, targetScale = 0.94f) + fadeOut(tween(240)))
            } else {
                routePopTransition()
            }
        },
    ) { key ->
        DeepReadScreen(
            topicId = key.topicId,
            title = key.title,
            sourceUrl = key.sourceUrl,
            initialForceRegenerate = key.forceRegenerate,
            fromHistory = key.fromHistory,
        )
    }

    entry<Screen.DeepReadHistory> {
        DeepReadHistoryPage()
    }

    entry<Screen.SettingTodayBoard>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingTodayBoardPage()
    }

    entry<Screen.SettingTodayBoardDetail>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) { key ->
        SettingTodayBoardPage(paneRoute = key.pane)
    }

    entry<Screen.DeepReadTemplateWorkbench> {
        DeepReadTemplateWorkbenchPage()
    }

    entry<Screen.MiniAppList> {
        MiniAppListPage()
    }

    entry<Screen.NovelProjects> {
        NovelProjectsPage()
    }

    entry<Screen.NovelMarkdown> { key ->
        NovelMarkdownWorkspacePage(
            projectId = key.projectId,
            branchSlug = key.branchSlug,
            jobId = key.jobId,
        )
    }

    entry<Screen.MiniAppRunner> { key ->
        MiniAppRunnerPage(appId = key.appId)
    }

    entry<Screen.MiniAppSettings> {
        MiniAppSettingsPage()
    }

    entry<Screen.MiniAppSettingsDetail> { key ->
        MiniAppSettingsPage(groupRoute = key.group)
    }

    entry<Screen.SettingSystemAccess>(metadata = themePageChromeMetadata(ThemePageChrome.Shell)) {
        SettingSystemAccessPage()
    }

    entry<Screen.Developer> {
        DeveloperPage()
    }

    entry<Screen.Debug> {
        DebugPage()
    }

    entry<Screen.Log> {
        LogPage()
    }

    entry<Screen.Extensions> {
        ExtensionsPage()
    }

    entry<Screen.QuickMessages> {
        QuickMessagesPage()
    }

    entry<Screen.Prompts> {
        PromptPage()
    }

    entry<Screen.Skills> {
        SkillsPage()
    }

    entry<Screen.SkillDetail> { key ->
        SkillDetailPage(skillName = key.skillName)
    }

    entry<Screen.MessageSearch> {
        SearchPage()
    }

    entry<Screen.Stats> {
        StatsPage()
    }
}
