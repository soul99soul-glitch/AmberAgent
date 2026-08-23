package app.amber.feature.ui.components.message

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft02
import me.rerere.hugeicons.stroke.ArrowRight02
import me.rerere.hugeicons.stroke.Tick01
import app.amber.feature.ui.pages.chat.LocalChatTheme

/**
 * V3 ToolResultPreview —— 工具预览卡 (sticky shelf, 常驻 composer 上方)
 *
 * 设计规格（claude design 工具预览卡）：
 *  - 容器: padding(start=14, end=32, top=10, bottom=8) asymmetric, gap=10, align=Bottom
 *  - 缩略图: 72×96 (3:4) + 8dp radius + 1dp border 8% ink + 单层柔影 0.06
 *  - 缩略图内: Google 字标 / search input / tab strip / 2 result blocks / divider
 *  - 结果胶囊: 22dp 高 + 16dp accent 圆 + 白勾 + tool · query + page chevrons
 *  - 主题色: previewBg / previewEdge / Google 字标 / 状态圆点 / 胶囊底 跟主题
 */
@Composable
fun ToolResultPreview(
    tool: String,
    query: String,
    page: Int,
    total: Int,
    modifier: Modifier = Modifier,
    onPrev: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 32.dp, top = 10.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom,  // 设计稿：胶囊底沿与缩略图底沿对齐
    ) {
        PreviewThumb()
        Box(modifier = Modifier.weight(1f)) {
            ResultPill(tool = tool, query = query, page = page, total = total, onPrev = onPrev, onNext = onNext)
        }
    }
}

@Composable
private fun PreviewThumb() {
    val theme = LocalChatTheme.current
    // 设计稿: previewBg 主题 surface (Whisper/Plain 白 / Paper 纸黄 / Midnight 半透白)
    // border 8% ink (比 composer 5% 略明显) + 单层柔影 0.06 (比 composer 0.08 轻一档)
    Box(
        modifier = Modifier
            .size(width = 72.dp, height = 96.dp)
            .shadow(
                elevation = 3.dp,
                shape = RoundedCornerShape(8.dp),
                ambientColor = Color(0x0F0F1419),  // rgba(15,20,25,0.06)
                spotColor = Color(0x0F0F1419),
            )
            .clip(RoundedCornerShape(8.dp))
            .background(theme.surface)
            .border(
                BorderStroke(1.dp, Color(0x140F1419)),  // rgba(15,20,25,0.08)
                RoundedCornerShape(8.dp),
            ),
    ) {
        FakeBrowser()
    }
}

/** 假浏览器 mock —— 让眼睛 250ms 内识别"这是网页"，所有文字都用 div 色块替代 */
@Composable
private fun FakeBrowser() {
    val theme = LocalChatTheme.current
    // 设计稿: previewDim rgba(15,20,25,0.08) / previewDimSoft rgba(15,20,25,0.04)
    val dim = Color(0x140F1419)
    val dimSoft = Color(0x0A0F1419)
    // Google 字标色: 浅色主题 #4285F4, 深色主题改更亮的 #6A9DEB
    val googleColor = if (theme.isDark) Color(0xFF6A9DEB) else Color(0xFF4285F4)

    Column(modifier = Modifier.fillMaxSize()) {
        // 1) Google 字标 — 8sp / W700 / 顶 6dp 底 3dp
        Text(
            text = "Google",
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            color = googleColor,
            letterSpacing = 0.3.sp,
            modifier = Modifier.padding(start = 7.dp, end = 7.dp, top = 6.dp, bottom = 3.dp),
        )
        // 2) 搜索框 — 高 11dp / 6dp 圆角 / dimSoft 底 / 0.5dp dim 描边
        Row(
            modifier = Modifier
                .padding(horizontal = 7.dp)
                .height(11.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(dimSoft)
                .border(0.5.dp, dim, RoundedCornerShape(6.dp))
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // 3dp 圆形空心描边
            Box(
                modifier = Modifier
                    .size(3.dp)
                    .clip(CircleShape)
                    .border(0.5.dp, theme.inkSoft.copy(alpha = 0.6f), CircleShape),
            )
            // 2dp 横线模拟输入文字
            Box(
                modifier = Modifier
                    .height(2.dp)
                    .weight(1f)
                    .clip(RoundedCornerShape(1.dp))
                    .background(dim),
            )
        }
        // 3) 标签栏 — 5.5sp / 4 标签 / 当前激活"全部"用 accent 蓝 + W700
        Row(
            modifier = Modifier.padding(start = 7.dp, end = 7.dp, top = 5.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("AI", fontSize = 5.5.sp, color = theme.inkFaint)
            Text("全部", fontSize = 5.5.sp, color = theme.accent, fontWeight = FontWeight.Bold)
            Text("图片", fontSize = 5.5.sp, color = theme.inkFaint)
            Text("购物", fontSize = 5.5.sp, color = theme.inkFaint)
        }
        // 4) 结果块 1 — 标题 2dp 宽 45% accent 0.85, 摘要 1.5dp 宽 90%/80% dim
        Column(
            modifier = Modifier.padding(start = 7.dp, end = 7.dp, top = 2.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.45f)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(theme.accent.copy(alpha = 0.85f)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.90f)
                    .height(1.5.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(dim),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.80f)
                    .height(1.5.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(dim),
            )
        }
        // 5) 分隔线 — 0.5dp dimSoft / 左右 margin 7dp
        Box(
            modifier = Modifier
                .padding(horizontal = 7.dp, vertical = 2.dp)
                .fillMaxWidth()
                .height(0.5.dp)
                .background(dimSoft),
        )
        // 6) 结果块 2 — 标题宽 38% / opacity 0.7
        Column(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.38f)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(theme.accent.copy(alpha = 0.7f)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(1.5.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(dim),
            )
        }
    }
}

/** 状态胶囊 —— 22dp 高 / fillMaxWidth / 16dp accent 圆 + 白勾 / inline name·query + 分页 */
@Composable
private fun ResultPill(
    tool: String,
    query: String,
    page: Int,
    total: Int,
    onPrev: (() -> Unit)?,
    onNext: (() -> Unit)?,
) {
    val theme = LocalChatTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(theme.toolPillBg)
            .border(BorderStroke(1.dp, Color(0x0D0F1419)), CircleShape)  // 5% ink
            .padding(start = 3.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 状态圆点：16dp accent 实心圆 + 白勾 (3 stroke) - 不要描边
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(theme.toolDoneBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = HugeIcons.Tick01,
                contentDescription = null,
                modifier = Modifier.size(10.dp),
                tint = theme.toolDoneBadgeInk,
            )
        }
        // tool · query inline，工具名 ink + W500，查询词 inkSoft + W400
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = tool,
                fontSize = 11.5.sp,
                color = theme.toolLabelInk,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.2.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = " $query",
                fontSize = 11.5.sp,
                color = theme.inkSoft,
                fontWeight = FontWeight.Normal,
                letterSpacing = 0.2.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 翻页器：9dp chevrons + tabular-nums 数字 10.5sp inkSoft
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = HugeIcons.ArrowLeft02,
                contentDescription = "prev",
                modifier = Modifier
                    .size(9.dp)
                    .then(if (onPrev != null) Modifier.clickable { onPrev() } else Modifier),
                tint = theme.inkSoft,
            )
            Text(
                text = "$page/$total",
                fontSize = 10.5.sp,
                color = theme.inkSoft,
                letterSpacing = 0.4.sp,
            )
            Icon(
                imageVector = HugeIcons.ArrowRight02,
                contentDescription = "next",
                modifier = Modifier
                    .size(9.dp)
                    .then(if (onNext != null) Modifier.clickable { onNext() } else Modifier),
                tint = theme.inkSoft,
            )
        }
    }
}
