package app.amber.feature.live

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween

/**
 * Live 伴随的动效词汇表（仿 NovelMotion 先例）：页面与气泡共用同一组时长与曲线，
 * 避免各文件私设常量漂移。约定沿用全 App 惯例——即时反馈 130-160ms、区块切换
 * 220ms、布局级变化 320ms；出场约为入场的 75%，不用回弹。
 */
object LiveMotion {
    /** 即时反馈（按压、入场交叉淡化、chip 入场）。 */
    const val FastMs = 160

    /** 微反馈（倒计时下划线之类的极淡入淡出）。 */
    const val MicroMs = 100

    /** 区块显隐、状态切换。 */
    const val MediumMs = 220

    /** 布局级变化（展开行、面板）。 */
    const val SlowMs = 320

    /** 气泡卡片 pop 专用（与任务气泡既有手感一致，勿改）。 */
    const val BubblePopMs = 130

    /** 一次性强调脉冲的衰减（深链高亮边框）。 */
    const val PulseDecayMs = 700

    /** 导航同款曲线的 Local 版（RouteActivity 320ms slide 的 CubicBezier(0.25,0.1,0.25,1)）。 */
    val Emphasized: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    /** 通用 easeOut（与气泡 pop 的 FastOutSlowInEasing 同族）。 */
    val EaseOut: Easing = FastOutSlowInEasing

    fun <T> enter(durationMillis: Int = FastMs): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = EaseOut)

    fun <T> exit(durationMillis: Int = FastMs * 3 / 4): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = EaseOut)
}
