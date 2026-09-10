package app.amber.feature.health

import android.content.Context
import android.os.Build

/** Explicit availability; opening a settings page never changes this state. */
enum class HealthConnectAvailability(
    val title: String,
    val message: String,
) {
    AVAILABLE("Health Connect 可用", "当前系统提供 Health Connect 服务，可以在用户授权后读取健康摘要。"),
    UNSUPPORTED_BY_OS_VERSION("系统版本不支持", "当前 Android 版本低于 API 34；本版本不引入 Jetpack Health Connect，因此不提供健康读取。"),
    SERVICE_NOT_FOUND("找不到 Health Connect 服务", "当前 Android 版本满足要求，但系统没有提供 Health Connect 服务；请使用带该系统服务的设备。"),
}

object HealthConnectAvailabilityDetector {
    fun detect(sdkInt: Int, servicePresent: Boolean): HealthConnectAvailability = when {
        sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> HealthConnectAvailability.UNSUPPORTED_BY_OS_VERSION
        servicePresent -> HealthConnectAvailability.AVAILABLE
        else -> HealthConnectAvailability.SERVICE_NOT_FOUND
    }

    fun detect(context: Context): HealthConnectAvailability {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return HealthConnectAvailability.UNSUPPORTED_BY_OS_VERSION
        }
        val service = context.getSystemService(Context.HEALTHCONNECT_SERVICE)
        return detect(Build.VERSION.SDK_INT, service != null)
    }
}

enum class HealthAdapterState {
    READY,
    AUTHORIZATION_REQUIRED,
    AUTHORIZATION_DENIED,
    AUTHORIZATION_REVOKED,
    UNSUPPORTED,
    SERVICE_UNAVAILABLE,
    READ_FAILED,
}

data class HealthAdapterStatus(
    val state: HealthAdapterState,
    val message: String,
) {
    val isSuccess: Boolean get() = state == HealthAdapterState.READY
}

/** Pure transition reducer, kept separate so authorization semantics are JVM-testable. */
object HealthAdapterStateMachine {
    fun afterAuthorization(granted: Boolean): HealthAdapterState =
        if (granted) HealthAdapterState.READY else HealthAdapterState.AUTHORIZATION_DENIED

    fun afterReadFailure(securityFailure: Boolean): HealthAdapterState =
        if (securityFailure) HealthAdapterState.AUTHORIZATION_REVOKED else HealthAdapterState.READ_FAILED
}

object HealthAdapterStatusText {
    fun forAvailability(availability: HealthConnectAvailability): String = availability.message

    fun forState(state: HealthAdapterState): String = when (state) {
        HealthAdapterState.READY -> "健康数据已授权，可读取健康摘要。"
        HealthAdapterState.AUTHORIZATION_REQUIRED -> "尚未授权健康数据；请在 Health Connect 授权页选择读取权限。"
        HealthAdapterState.AUTHORIZATION_DENIED -> "用户拒绝了健康数据授权，未读取任何健康数据。"
        HealthAdapterState.AUTHORIZATION_REVOKED -> "健康数据授权已撤销，请重新授权后再读取。"
        HealthAdapterState.UNSUPPORTED -> "当前系统版本不支持本适配器。"
        HealthAdapterState.SERVICE_UNAVAILABLE -> "Health Connect 系统服务不可用，未把空数据视为读取成功。"
        HealthAdapterState.READ_FAILED -> "Health Connect 读取失败，未把空数据视为读取成功。"
    }
}
