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
        NewsNowPreset("zhihu", "Zhihu Hot"),
        NewsNowPreset("weibo", "Weibo Hot Search"),
        NewsNowPreset("douyin", "Douyin Hot Search"),
        NewsNowPreset("coolapk", "Coolapk"),
        NewsNowPreset("bilibili-hot-search", "Bilibili Hot Search"),
        NewsNowPreset("v2ex-share", "V2EX Share"),
        NewsNowPreset("github-trending-today", "GitHub Trending"),
        NewsNowPreset("36kr-quick", "36Kr Flash"),
        NewsNowPreset("hupu-zhugandaoretie", "Hupu Street"),
        NewsNowPreset("xueqiu-hotstock", "Xueqiu Hot Stocks"),
        NewsNowPreset("wallstreetcn-hot", "Wallstreetcn Hot"),
        NewsNowPreset("cls-telegraph", "CLS Telegraph"),
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
