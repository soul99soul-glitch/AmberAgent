package shared

import app.amber.core.agent.utils.JsonInstant
import app.amber.core.settings.Settings
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** Swift-facing bridge for serializing the real KMP Settings model. */
object IosSettingsJsonBridge {
    fun encode(settings: Settings): String = JsonInstant.encodeToString(settings)

    fun decode(json: String): Settings = JsonInstant.decodeFromString(json)
}
