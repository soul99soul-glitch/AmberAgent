package app.amber.feature.webmount.tools

import me.rerere.ai.core.Tool
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.webmount.core.WebMountManager
import app.amber.feature.webmount.cookie.WebMountCookieProvider
import app.amber.feature.webmount.oauth.WebMountOAuthTokenStore
import app.amber.feature.webmount.primitives.WebViewPool
import app.amber.feature.webmount.profile.ProfileBridge
import app.amber.feature.webmount.profile.ProfileRegistry
import app.amber.feature.webmount.usersites.UserSiteRegistry

/**
 * Thin coordinator for the 23 WebMount primitive tools. All per-tool logic
 * lives in sibling `WebMountXxxTools.kt` factories under this package — this
 * class holds the dependency wiring and `getTools()` registry.
 *
 * See `WebMountPrimitiveShared.kt` for `WebMountDeps` + `track` and the
 * shared schema DSL. Group breakdown:
 *  - Navigation/state (NavigationTools): wm_open, wm_state, wm_observe, wm_extract,
 *    wm_get, wm_back, wm_forward
 *  - Interaction/script (InteractionTools): wm_wait, wm_click, wm_type,
 *    wm_eval, wm_scroll, wm_keys, wm_select, wm_find
 *  - Tabs (TabsTools): wm_tab_list, wm_tab_new, wm_tab_close
 *  - Capture (CaptureTools): wm_screenshot, wm_visual_snapshot, wm_visual_read
 *  - Fetch (FetchTools): wm_network_inspect, wm_fetch_replay, wm_recipe_candidates, wm_signed_fetch
 *  - Site mgmt (SiteTools): wm_stations, wm_site_add, wm_site_remove,
 *    wm_profile_synthesize
 *
 * **Default OFF**: the LocalTools aggregator only includes these when the
 * user enables `LocalToolOption.WebMount` per assistant. `wm_eval` requires
 * an additional `LocalToolOption.WebMountEval` opt-in (Phase 2 M2.0.2)
 * and is flagged `Tool.mandatoryApproval = true` (M2.0.1) so ordinary
 * auto-approval and run-trust cannot bypass the prompt.
 */
class WebMountPrimitiveTools(
    private val pool: WebViewPool,
    private val activityStore: AgentToolActivityStore,
    private val manager: WebMountManager,
    private val profileRegistry: ProfileRegistry,
    private val cookieProvider: WebMountCookieProvider,
    private val profileBridge: ProfileBridge,
    private val userSiteRegistry: UserSiteRegistry,
    private val oauthStore: WebMountOAuthTokenStore,
) {
    private val deps = WebMountDeps(pool, activityStore)

    fun getTools(includeEval: Boolean = false): List<Tool> = listOfNotNull(
        openTool,
        stateTool,
        observeTool,
        extractTool,
        getTool,
        waitTool,
        clickTool,
        tapTool,
        typeTool,
        if (includeEval) evalTool else null,
        scrollTool,
        backTool,
        forwardTool,
        keysTool,
        selectTool,
        findTool,
        tabListTool,
        tabNewTool,
        tabCloseTool,
        screenshotTool,
        visualSnapshotTool,
        visualReadTool,
        stationsTool,
        networkInspectTool,
        fetchReplayTool,
        recipeCandidatesTool,
        signedFetchTool,
        siteAddTool,
        siteRemoveTool,
        profileSynthesizeTool,
    )

    private val openTool by lazy { createOpenTool(deps, profileRegistry, cookieProvider, manager) }
    private val stateTool by lazy { createStateTool(deps, profileRegistry, cookieProvider, manager) }
    private val observeTool by lazy { createObserveTool(deps) }
    private val extractTool by lazy { createExtractTool(deps) }
    private val getTool by lazy { createGetTool(deps) }
    private val backTool by lazy { createBackTool(deps) }
    private val forwardTool by lazy { createForwardTool(deps) }

    private val waitTool by lazy { createWaitTool(deps) }
    private val clickTool by lazy { createClickTool(deps) }
    private val tapTool by lazy { createTapTool(deps) }
    private val typeTool by lazy { createTypeTool(deps) }
    private val evalTool by lazy { createEvalTool(deps) }
    private val scrollTool by lazy { createScrollTool(deps) }
    private val keysTool by lazy { createKeysTool(deps) }
    private val selectTool by lazy { createSelectTool(deps) }
    private val findTool by lazy { createFindTool(deps) }

    private val tabListTool by lazy { createTabListTool(deps) }
    private val tabNewTool by lazy { createTabNewTool(deps) }
    private val tabCloseTool by lazy { createTabCloseTool(deps) }

    private val screenshotTool by lazy { createScreenshotTool(deps) }
    private val visualSnapshotTool by lazy { createVisualSnapshotTool(deps) }
    private val visualReadTool by lazy { createVisualReadTool(deps) }

    private val networkInspectTool by lazy { createNetworkInspectTool(deps) }
    private val fetchReplayTool by lazy { createFetchReplayTool(deps) }
    private val recipeCandidatesTool by lazy { createRecipeCandidatesTool(deps) }
    private val signedFetchTool by lazy { createSignedFetchTool(deps, profileRegistry, profileBridge) }

    private val stationsTool by lazy {
        createStationsTool(deps, userSiteRegistry, manager, profileRegistry, cookieProvider, oauthStore)
    }
    private val siteAddTool by lazy { createSiteAddTool(deps, userSiteRegistry) }
    private val siteRemoveTool by lazy {
        createSiteRemoveTool(deps, userSiteRegistry, manager, profileRegistry, cookieProvider, oauthStore)
    }
    private val profileSynthesizeTool by lazy {
        createProfileSynthesizeTool(deps, userSiteRegistry, profileRegistry)
    }
}
