package app.amber.core.utils

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.amber.agent.BuildConfig
import app.amber.agent.R

/**
 * 通知构建器的配置 DSL
 */
class NotificationConfig {
    var title: String = ""
    var content: String = ""
    var subText: String? = null
    var smallIcon: Int = R.drawable.small_icon
    var largeIcon: Int? = null
    var color: Int? = null
    var colorized: Boolean = false
    var priority: Int? = null
    var silent: Boolean = false
    var showWhen: Boolean = true
    var autoCancel: Boolean = false
    var ongoing: Boolean = false
    var onlyAlertOnce: Boolean = false
    var category: String? = null
    var visibility: Int = NotificationCompat.VISIBILITY_PRIVATE
    var contentIntent: PendingIntent? = null
    var useBigTextStyle: Boolean = false
    var actions: List<NotificationActionConfig> = emptyList()
    var progressMax: Int? = null
    var progress: Int = 0
    var progressIndeterminate: Boolean = false

    // Live Update 相关
    var requestPromotedOngoing: Boolean = false
    var shortCriticalText: String? = null
    var xiaomiSuperIsland: XiaomiSuperIslandConfig? = null

    // 默认通知效果
    var useDefaults: Boolean = false
}

data class NotificationActionConfig(
    val icon: Int = 0,
    val title: String,
    val intent: PendingIntent,
    val remoteInput: RemoteInput? = null,
    val allowGeneratedReplies: Boolean = true,
)

data class XiaomiSuperIslandConfig(
    val title: String,
    val content: String,
    val chipText: String,
    val brandText: String = "Amber",
    val business: String = "amberagent",
    val ticker: String = chipText,
    val iconRes: Int? = null,
    val progressPercent: Int? = null,
    val progressText: String? = null,
    val accentColor: String = "#FF7A1A",
    val trackColor: String = "#33FF7A1A",
    val enableFloat: Boolean = true,
    val updatable: Boolean = true,
    val timeoutSeconds: Int = 60 * 60,
    val actions: List<XiaomiSuperIslandActionConfig> = emptyList(),
)

data class XiaomiSuperIslandActionConfig(
    val key: String,
    val title: String,
    val intent: PendingIntent,
    val icon: Int,
)

object NotificationUtil {

    /**
     * 检查是否有通知权限
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 使用 DSL 风格创建并发送通知
     *
     * @param context 上下文
     * @param channelId 通知渠道 ID
     * @param notificationId 通知 ID
     * @param config 通知配置 lambda
     * @return 是否成功发送
     */
    @SuppressLint("MissingPermission")
    fun notify(
        context: Context,
        channelId: String,
        notificationId: Int,
        config: NotificationConfig.() -> Unit
    ): Boolean {
        if (!hasNotificationPermission(context)) {
            return false
        }

        val notificationConfig = NotificationConfig().apply(config)
        val notification = buildNotification(context, channelId, notificationConfig)

        NotificationManagerCompat.from(context).notify(notificationId, notification.build())
        return true
    }

    /**
     * 构建通知
     */
    fun buildNotification(
        context: Context,
        channelId: String,
        config: NotificationConfig
    ): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, channelId).apply {
            setContentTitle(config.title)
            setContentText(config.content)
            setSmallIcon(config.smallIcon)
            setAutoCancel(config.autoCancel)
            setOngoing(config.ongoing)
            setOnlyAlertOnce(config.onlyAlertOnce)
            setVisibility(config.visibility)
            setSilent(config.silent)
            setShowWhen(config.showWhen)

            config.color?.let { setColor(it) }
            setColorized(config.colorized)
            config.priority?.let { setPriority(it) }
            config.subText?.let { setSubText(it) }
            config.category?.let { setCategory(it) }
            config.contentIntent?.let { setContentIntent(it) }
            config.largeIcon?.let { iconRes ->
                ContextCompat.getDrawable(context, iconRes)?.toNotificationBitmap()?.let { setLargeIcon(it) }
            }
            config.actions.forEach { action ->
                if (action.remoteInput == null) {
                    addAction(action.icon, action.title, action.intent)
                } else {
                    addAction(
                        NotificationCompat.Action.Builder(action.icon, action.title, action.intent)
                            .addRemoteInput(action.remoteInput)
                            .setAllowGeneratedReplies(action.allowGeneratedReplies)
                            .build()
                    )
                }
            }
            config.progressMax?.let { max ->
                setProgress(max, config.progress.coerceIn(0, max), config.progressIndeterminate)
            }

            if (config.useBigTextStyle) {
                setStyle(NotificationCompat.BigTextStyle().bigText(config.content))
            }

            if (config.useDefaults) {
                setDefaults(NotificationCompat.DEFAULT_ALL)
            }

            // Android 15+ Live Update 支持
            if (config.requestPromotedOngoing && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                setRequestPromotedOngoing(true)
            }

            // Android 16+ 状态栏 chip 文本
            if (config.shortCriticalText != null && Build.VERSION.SDK_INT >= 36) {
                setShortCriticalText(config.shortCriticalText!!)
            }

            config.xiaomiSuperIsland
                ?.takeIf { XiaomiSuperIsland.isAvailable(context) }
                ?.let { XiaomiSuperIsland.toExtras(context, it) }
                ?.let { addExtras(it) }
        }
    }

    /**
     * 取消通知
     */
    fun cancel(context: Context, notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    /**
     * 取消所有通知
     */
    fun cancelAll(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }
}

object XiaomiSuperIsland {
    private const val TAG = "XiaomiSuperIsland"
    private const val FocusParamKey = "miui.focus.param"
    private const val FocusPicsKey = "miui.focus.pics"
    private const val FocusActionsKey = "miui.focus.actions"
    private const val AppIconPicKey = "miui.focus.pic_app"
    private const val FocusProtocolSetting = "notification_focus_protocol"
    private const val IslandFeatureProperty = "persist.sys.feature.island"
    private const val FocusPermissionUri = "content://miui.statusbar.notification.public"
    private const val XmsAppIdMetaData = "com.xiaomi.xms.APP_ID"
    private const val XmsDebugMetaData = "com.xiaomi.xms.BUILD_TYPE_DEBUG"
    private const val CapabilityCacheMillis = 60 * 1000L
    private val XiaomiManufacturers = setOf("xiaomi", "redmi", "poco")
    @Volatile
    private var cachedAvailability: Pair<Long, Boolean>? = null

    fun isAvailable(context: Context): Boolean {
        val now = System.currentTimeMillis()
        cachedAvailability?.let { (checkedAt, available) ->
            if (now - checkedAt < CapabilityCacheMillis) return available
        }
        val protocol = focusProtocolVersion(context)
        val hasIslandFeature = systemBoolean(IslandFeatureProperty, defaultValue = protocol >= 3)
        val hasPermission = hasFocusPermission(context)
        val xmsAppId = xmsAppId(context)
        val xmsDebug = xmsDebugBuild(context)
        val available = isXiaomiDevice() && protocol >= 3 && xmsAppId.isNotBlank()
        Log.d(
            TAG,
            "available=$available protocol=$protocol islandFeature=$hasIslandFeature " +
                "focusPermission=$hasPermission xmsAppIdConfigured=${xmsAppId.isNotBlank()} " +
                "xmsDebug=$xmsDebug package=${context.packageName} " +
                "manufacturer=${Build.MANUFACTURER} brand=${Build.BRAND}",
        )
        if (xmsAppId.isBlank()) {
            Log.w(TAG, "Xiaomi Super Island requires com.xiaomi.xms.APP_ID metadata from Xiaomi Open Platform.")
        }
        if (!hasPermission) {
            Log.w(TAG, "Focus notification permission is not granted or package/device is not whitelisted yet.")
        }
        cachedAvailability = now to available
        return available
    }

    fun focusProtocolVersion(context: Context): Int =
        runCatching {
            Settings.System.getInt(context.contentResolver, FocusProtocolSetting, 0)
        }.getOrDefault(0)

    fun hasFocusPermission(context: Context): Boolean =
        runCatching {
            val extras = Bundle().apply {
                putString("package", context.packageName)
            }
            context.contentResolver
                .call(Uri.parse(FocusPermissionUri), "canShowFocus", null, extras)
                ?.getBoolean("canShowFocus", false)
        }.getOrNull() == true

    private fun systemBoolean(key: String, defaultValue: Boolean): Boolean =
        runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getDeclaredMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
            method.invoke(null, key, defaultValue) as? Boolean
        }.getOrNull() ?: defaultValue

    private fun isXiaomiDevice(): Boolean =
        Build.MANUFACTURER.lowercase() in XiaomiManufacturers ||
            Build.BRAND.lowercase() in XiaomiManufacturers

    private fun xmsAppId(context: Context): String =
        BuildConfig.XIAOMI_XMS_APP_ID.takeIf { BuildConfig.XIAOMI_XMS_APP_ID_CONFIGURED }
            ?: applicationMetaData(context)?.getString(XmsAppIdMetaData).orEmpty()

    private fun xmsDebugBuild(context: Context): Boolean =
        applicationMetaData(context)?.getBoolean(XmsDebugMetaData, false) == true

    private fun applicationMetaData(context: Context): Bundle? =
        runCatching {
            context.packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.GET_META_DATA,
            ).metaData
        }.getOrNull()

    fun toExtras(context: Context, config: XiaomiSuperIslandConfig): Bundle = config.run {
        val focusParam = buildFocusParam()
        Log.d(TAG, "focusParam=$focusParam")
        Bundle().apply {
            putString(FocusParamKey, focusParam)
            iconRes?.let { res ->
                putBundle(
                    FocusPicsKey,
                    Bundle().apply {
                        putParcelable(AppIconPicKey, Icon.createWithResource(context, res))
                    },
                )
            }
            if (actions.isNotEmpty()) {
                putBundle(
                    FocusActionsKey,
                    Bundle().apply {
                        actions.forEach { action ->
                            putParcelable(
                                action.key,
                                Notification.Action.Builder(
                                    Icon.createWithResource(context, action.icon),
                                    action.title,
                                    action.intent,
                                ).build(),
                            )
                        }
                    },
                )
            }
        }
    }

    private fun XiaomiSuperIslandConfig.buildFocusParam(): String {
        val usePic = iconRes != null
        val progress = progressPercent?.coerceIn(0, 100)
        val conciseTitle = title.takeFocusText(16)
        val conciseContent = content.takeFocusText(28)
        val conciseBrand = brandText.takeFocusText(6)
        val conciseProgressText = (progressText ?: chipText).takeFocusText(4)
        val param = buildJsonObject {
            put(
                "param_v2",
                buildJsonObject {
                    put("protocol", 1)
                    put("business", business)
                    put("enableFloat", enableFloat)
                    put("updatable", updatable)
                    put("timeout", (timeoutSeconds / 60).coerceAtLeast(1))
                    put("filterWhenNoPermission", false)
                    put("ticker", ticker.takeFocusText(12))
                    if (usePic) {
                        put("tickerPic", AppIconPicKey)
                        put("aodPic", AppIconPicKey)
                    }
                    put("aodTitle", conciseTitle)
                    put(
                        "param_island",
                        buildJsonObject {
                            put("islandProperty", 1)
                            put("islandFirstFloat", enableFloat)
                            put("islandTimeout", timeoutSeconds)
                            put("highlightColor", accentColor)
                            put(
                                "bigIslandArea",
                                buildJsonObject {
                                    put(
                                        "imageTextInfoLeft",
                                        buildJsonObject {
                                            put("type", 1)
                                            if (usePic) put("picInfo", iconPicInfo())
                                            put(
                                                "textInfo",
                                                buildJsonObject {
                                                    put("frontTitle", conciseBrand)
                                                    put("title", conciseTitle)
                                                    put("content", conciseContent)
                                                    put("useHighLight", true)
                                                    put("colorTitle", accentColor)
                                                },
                                            )
                                        },
                                    )
                                    if (usePic) put("picInfo", iconPicInfo())
                                },
                            )
                            put(
                                "smallIslandArea",
                                buildJsonObject {
                                    if (usePic) put("picInfo", iconPicInfo())
                                },
                            )
                            put(
                                "shareData",
                                buildJsonObject {
                                    put("title", conciseTitle)
                                },
                            )
                        },
                    )
                    put(
                        "baseInfo",
                        buildJsonObject {
                            put("title", conciseTitle)
                            put("content", conciseContent)
                            put("colorTitle", accentColor)
                            put("type", 2)
                        },
                    )
                    put(
                        "hintInfo",
                        buildJsonObject {
                            put("type", 1)
                            put("title", conciseProgressText)
                            progress?.let {
                                put("progress", it)
                                put("progressColor", accentColor)
                                put("trackColor", trackColor)
                            }
                            actions.firstOrNull()?.let { action ->
                                put(
                                    "actionInfo",
                                    buildJsonObject {
                                        put("action", action.key)
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }
        return Json.encodeToString(param)
    }

    private fun iconPicInfo() = buildJsonObject {
        put("type", 1)
        put("pic", AppIconPicKey)
    }

    private fun String.takeFocusText(maxChars: Int): String =
        replace(Regex("\\s+"), " ").trim().take(maxChars)
}

private fun android.graphics.drawable.Drawable.toNotificationBitmap(): Bitmap {
    if (this is android.graphics.drawable.BitmapDrawable && bitmap != null) {
        return bitmap
    }
    val size = maxOf(intrinsicWidth, intrinsicHeight).takeIf { it > 0 } ?: 128
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
    }
}

/**
 * Context 扩展函数，简化通知发送
 */
fun Context.sendNotification(
    channelId: String,
    notificationId: Int,
    config: NotificationConfig.() -> Unit
): Boolean = NotificationUtil.notify(this, channelId, notificationId, config)

/**
 * Context 扩展函数，取消通知
 */
fun Context.cancelNotification(notificationId: Int) {
    NotificationUtil.cancel(this, notificationId)
}
