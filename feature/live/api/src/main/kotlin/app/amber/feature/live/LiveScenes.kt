package app.amber.feature.live

/** 伴随场景：决定该屏默认做什么（CHAT→回复草稿，READING→提炼重点，OTHER→静默待命）。 */
enum class LiveScene { CHAT, READING, OTHER }

object LiveScenes {
    // 精确匹配的聊天类 app
    private val chatPackages = setOf(
        "com.tencent.mm",                // 微信
        "com.tencent.mobileqq",          // QQ
        "com.tencent.tim",
        "org.telegram.messenger",
        "org.thunderdog.challegram",
        "com.alibaba.android.rimet",     // 钉钉
        "com.ss.android.lark",           // 飞书
        "com.whatsapp",
        "jp.naver.line.android",
        "com.discord",
    )

    // 前缀匹配的阅读/资讯类（"com.android.chrome" 与 "com.android.chrome.beta" 都算）
    private val readingPrefixes = listOf(
        "com.android.chrome",
        "org.mozilla",
        "com.microsoft.emmx",
        "com.quark",
        "com.UCMobile",
        "com.tencent.mtt",               // QQ 浏览器
        "com.zhihu.android",
        "com.ss.android.article",        // 今日头条系
        "com.tencent.news",
        "com.netease.newsreader",
        "com.sina.weibo",
        "com.xingin.xhs",                // 小红书
        "com.coolapk.market",
    )

    fun classify(packageName: String): LiveScene = when {
        packageName in chatPackages -> LiveScene.CHAT
        readingPrefixes.any { packageName == it || packageName.startsWith("$it.") } -> LiveScene.READING
        else -> LiveScene.OTHER
    }

    /** 场景默认动作标签；null = 静默，等用户主动召唤。 */
    fun defaultActionLabel(scene: LiveScene): String? = when (scene) {
        LiveScene.CHAT -> "写回复"
        LiveScene.READING -> "找重点"
        LiveScene.OTHER -> null
    }
}
