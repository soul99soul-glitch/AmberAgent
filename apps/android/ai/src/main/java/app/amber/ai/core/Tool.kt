package app.amber.ai.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart

@Serializable
data class Tool(
    val name: String,
    val description: String,
    val parameters: () -> InputSchema? = { null },
    val systemPrompt: (model: Model, messages: List<UIMessage>) -> String = { _, _ -> "" },
    val needsApproval: Boolean = false,
    val allowsAutoApproval: Boolean = true,
    // When true, this tool bypasses ordinary auto-approval, prior in-run trust,
    // and category fast-paths. Only the explicit "auto approve high-risk tools"
    // setting may run it unattended. Used for tools whose blast radius deserves
    // a stronger gate by default (e.g. wm_eval — arbitrary JS in a logged-in
    // WebView). See PermissionDecisionResolver for enforcement.
    val mandatoryApproval: Boolean = false,
    /**
     * Composite tools delegate their real side effects to nested primitive
     * calls. Their outer call still participates in approval, but must not
     * create a second ledger effect while a nested approval is waiting.
    */
    val ledgerManaged: Boolean = true,
    /** Optional per-round provider for tools registered after this catalog was built. */
    val dynamicToolsProvider: (() -> List<Tool>)? = null,
    val execute: suspend (JsonElement) -> List<UIMessagePart>
)

@Serializable
sealed class InputSchema {
    @Serializable
    @SerialName("object")
    data class Obj(
        val properties: JsonObject,
        val required: List<String>? = null,
        /**
         * P2-02 MCP namespace (parity plan §P2-02): original MCP server name
         * preserved inside the schema so routing stays stable even after the
         * expanded tool name is sanitized/truncated/hash-suffixed. Null for
         * non-MCP tools; omitted from the wire schema (explicitNulls=false).
         */
        val originalServerName: String? = null,
        /** P2-02: original MCP tool name preserved in the schema. */
        val originalToolName: String? = null,
    ) : InputSchema()
}
