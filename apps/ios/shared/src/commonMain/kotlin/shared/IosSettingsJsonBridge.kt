package shared

import app.amber.core.agent.utils.JsonInstant
import app.amber.core.settings.Settings
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** Swift-facing bridge for serializing the real KMP Settings model. */
object IosSettingsJsonBridge {
    fun encode(settings: Settings): String = JsonInstant.encodeToString(settings)

    // @Throws 必须声明：Kotlin/Native 不会把未声明的异常桥接成 Swift NSError，
    // 否则损坏的 settings JSON 会让进程 SIGABRT 而非回退默认配置（见 IOSSharedSettingsStore 的 try?）。
    @Throws(Throwable::class)
    fun decode(json: String): Settings = JsonInstant.decodeFromString(json)
}
