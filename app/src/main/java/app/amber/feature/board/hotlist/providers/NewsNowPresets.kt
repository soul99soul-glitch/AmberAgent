package app.amber.feature.board.hotlist.providers

import app.amber.agent.data.db.entity.HotListSourceEntity

data class NewsNowPreset(
    val id: String,
    val displayName: String,
)

object NewsNowPresets {
    const val ENDPOINT_BASE = "https://newsnow.busiyi.world/api/s"
    const val ID_PREFIX = "custom:newsnow:"
    private const val FIELD_MAPPING_JSON =
        """{"itemsPath":"items","titlePath":"title","urlPath":"url","heatPath":"extra.info"}"""

    val ALL: List<NewsNowPreset> = listOf(
        NewsNowPreset("zhihu", "知乎热榜"),
        NewsNowPreset("weibo", "微博热搜"),
        NewsNowPreset("douyin", "抖音热搜"),
        NewsNowPreset("coolapk", "酷安"),
        NewsNowPreset("bilibili-hot-search", "B 站热搜"),
        NewsNowPreset("v2ex-share", "V2EX 分享"),
        NewsNowPreset("github-trending-today", "GitHub 趋势"),
        NewsNowPreset("36kr-quick", "36 氪 快讯"),
        NewsNowPreset("hupu-zhugandaoretie", "虎扑步行街"),
        NewsNowPreset("xueqiu-hotstock", "雪球 热股"),
        NewsNowPreset("wallstreetcn-hot", "华尔街见闻 热门"),
        NewsNowPreset("cls-telegraph", "财联社 电报"),
    )

    fun entityIdFor(preset: NewsNowPreset): String = "$ID_PREFIX${preset.id}"

    fun urlFor(preset: NewsNowPreset): String = "$ENDPOINT_BASE?id=${preset.id}"

    fun entityFor(
        preset: NewsNowPreset,
        now: Long,
        sortOrder: Int,
        enabled: Boolean = true,
    ): HotListSourceEntity = HotListSourceEntity(
        id = entityIdFor(preset),
        displayName = "${preset.displayName} · NewsNow",
        sourceType = CustomHotListSourceTypes.JSON,
        url = urlFor(preset),
        enabled = enabled,
        fieldMappingJson = FIELD_MAPPING_JSON,
        sortOrder = sortOrder,
        createdAt = now,
        updatedAt = now,
    )
}
