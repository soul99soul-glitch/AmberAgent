package app.amber.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Polymorphic toggle that enables one [LocalTools] capability for an assistant.
 *
 * Persisted via `kotlinx.serialization` with stable `@SerialName` tags so
 * existing user settings remain forward/backward-compatible. Adding a new
 * variant only requires a new `data object` with a fresh `@SerialName` —
 * never rename or remove an existing one without a migration.
 *
 * Used as `List<LocalToolOption>` on Assistant configurations; the catalog
 * builder [LocalTools.getTools] reads the list and adds the matching tools
 * to the agent's runtime tool surface.
 *
 * Extracted from `LocalTools.kt` in M1.4 continuation (cosmetic — same
 * package, no caller import changes needed).
 */
@Serializable
sealed class LocalToolOption {
    @Serializable
    @SerialName("javascript_engine")
    data object JavascriptEngine : LocalToolOption()

    @Serializable
    @SerialName("time_info")
    data object TimeInfo : LocalToolOption()

    @Serializable
    @SerialName("clipboard")
    data object Clipboard : LocalToolOption()

    @Serializable
    @SerialName("tts")
    data object Tts : LocalToolOption()

    @Serializable
    @SerialName("ask_user")
    data object AskUser : LocalToolOption()

    @Serializable
    @SerialName("workspace_files")
    data object WorkspaceFiles : LocalToolOption()

    @Serializable
    @SerialName("terminal")
    data object Terminal : LocalToolOption()

    @Serializable
    @SerialName("screen_automation")
    data object ScreenAutomation : LocalToolOption()

    @Serializable
    @SerialName("system_access")
    data object SystemAccess : LocalToolOption()

    @Serializable
    @SerialName("webview")
    data object WebView : LocalToolOption()

    @Serializable
    @SerialName("icloud_drive")
    data object ICloudDrive : LocalToolOption()

    @Serializable
    @SerialName("webmount")
    data object WebMount : LocalToolOption()

    /**
     * Secondary toggle that enables [WebMountPrimitiveTools.evalTool] (`wm_eval`)
     * in addition to the safe primitives gated by [WebMount]. Default OFF.
     *
     * `wm_eval` runs arbitrary JavaScript inside a logged-in WebView origin —
     * it can read cookies / sessionStorage / localStorage, perform same-origin
     * fetches with credentials, and mutate the page. The framework routes it
     * through Tool.mandatoryApproval, so ordinary auto-approval cannot run it;
     * only the explicit high-risk auto-approval setting may bypass the prompt.
     * The conservative default is to keep the tool entirely out of the agent's
     * catalog unless the user opts in here.
     */
    @Serializable
    @SerialName("webmount_eval")
    data object WebMountEval : LocalToolOption()
}
