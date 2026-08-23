package shared

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Kotlin/Native 把 Kotlin 异常桥接到 Swift NSError 的前提是函数标注了 `@Throws`；
 * 未标注的函数抛出的异常会在 iOS 上直接终止进程 (SIGABRT)，Swift 的 `do/catch`/`try?`
 * 永远接不住。这两个 bridge 的 `decode` 消费外部持久化数据（后台 handoff payload、
 * settings JSON），输入损坏（突然切后台导致的半截写、跨版本 schema 不兼容、同步恢复
 * 带入坏数据）时会抛 `SerializationException`，因此必须显式声明 `@Throws`，否则冷启动
 * 恢复会崩溃而不是回退默认配置。详见 ai-core/Provider.kt 对 Swift suspend 边界的同类契约。
 */
class KotlinNativeBridgeThrowsContractTest {

    @Test
    fun chatBackgroundPayloadDecodeIsAnnotatedForSwiftThrowableBoundary() {
        val source = bridgeSource("IosChatBackgroundPayloadJsonBridge.kt")
        assertDecodeAnnotatedWithThrows(source)
    }

    @Test
    fun settingsDecodeIsAnnotatedForSwiftThrowableBoundary() {
        val source = bridgeSource("IosSettingsJsonBridge.kt")
        assertDecodeAnnotatedWithThrows(source)
    }

    private fun bridgeSource(fileName: String): String {
        // commonTest 与 commonMain 同属 shared 模块；按模块源根相对路径读取。
        val candidates = listOf(
            "src/commonMain/kotlin/shared/$fileName",
            "../shared/src/commonMain/kotlin/shared/$fileName",
        )
        val base = System.getProperty("user.dir")
        for (candidate in candidates) {
            val resolved = java.io.File(base, candidate)
            if (resolved.exists()) return resolved.readText()
        }
        fail("无法定位 bridge 源文件 $fileName（user.dir=$base）；请核对测试相对路径。")
    }

    private fun assertDecodeAnnotatedWithThrows(source: String) {
        // 这两个文件各自只有一个 decode(json:) 函数。直接断言紧邻 decode 声明行的
        // 上一行（跳过空行）就是 @Throws 注解——锁定它真的挂在 decode 上，而不是
        // 文件里别处恰好出现了 @Throws 字样。
        val lines = source.lines()
        val decodeIndex = lines.indexOfFirst {
            val t = it.trimStart()
            t.startsWith("fun decode(") || t.contains("fun decode(json:")
        }
        if (decodeIndex < 0) fail("未声明 decode(json:) 函数。")
        val annotationIndex = (decodeIndex - 1 downTo 0).firstOrNull {
            lines[it].trim().isNotEmpty()
        } ?: fail("decode 上方没有 @Throws 注解。")
        val annotation = lines[annotationIndex].trim()
        assertTrue(
            annotation.contains(Regex("@Throws\\s*\\(")),
            "decode 的 Swift throwable 边界缺少 @Throws 注解（上方紧邻行实际为：\"$annotation\"）；" +
                "未标注时 Kotlin/Native 抛出的 SerializationException 会终止 iOS 进程而非被 Swift 捕获。",
        )
    }
}
