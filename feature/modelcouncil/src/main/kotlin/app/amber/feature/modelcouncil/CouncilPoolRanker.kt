package app.amber.feature.modelcouncil

import app.amber.core.settings.Settings
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

/**
 * 宿主注入的模型池排序器（Jev MODEL_ROUTING active 时生效）：
 * 对合法池（已启用 CHAT 模型、provider 轮转序）按任务适配度重排/筛选。
 * 返回 null 保持既有轮转；显式 seats / agent_planned 路径不经此接口。
 */
fun interface CouncilPoolRanker {
    suspend fun rank(
        input: JsonObject,
        settings: Settings,
        councilSetting: ModelCouncilRuntimeSetting,
    ): List<Uuid>?
}
