package app.amber.feature.ui.pages.board

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.composables.icons.lucide.CalendarClock
import com.composables.icons.lucide.CodeXml
import com.composables.icons.lucide.GalleryVertical
import com.composables.icons.lucide.ListChecks
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircleQuestion
import com.composables.icons.lucide.Newspaper
import com.composables.icons.lucide.Users
import com.composables.icons.lucide.WandSparkles
import com.composables.icons.lucide.Zap
import app.amber.agent.R
import app.amber.feature.board.DeepReadTemplateIds

/**
 * Display metadata for built-in deep-read generation/display templates —
 * iOS `DeepReadSynthesisTemplate.options` parity (auto → magazine pair → the
 * five synthesis kinds). Custom templates keep their own stored name.
 */
object DeepReadTemplateCatalog {

    /** Built-in choice ids in iOS display order. */
    val options: List<String> = DeepReadTemplateIds.GENERATION_OPTIONS

    /** Localized display name for a built-in id; custom/unknown ids need [customName]. */
    @Composable
    fun name(id: String?, customName: String? = null): String {
        val normalized = DeepReadTemplateIds.normalize(id)
        val res = when (normalized) {
            DeepReadTemplateIds.AUTO -> R.string.deep_read_template_auto
            DeepReadTemplateIds.EDITORIAL_SLANT -> R.string.deep_read_template_editorial
            DeepReadTemplateIds.BRIEF -> R.string.deep_read_template_brief
            DeepReadTemplateIds.QA -> R.string.deep_read_template_qa
            DeepReadTemplateIds.DEBATE -> R.string.deep_read_template_debate
            DeepReadTemplateIds.TIMELINE -> R.string.deep_read_template_timeline
            DeepReadTemplateIds.REVIEW -> R.string.deep_read_template_review
            else -> R.string.deep_read_template_magazine
        }
        return if (normalized.startsWith(DeepReadTemplateIds.CUSTOM_PREFIX)) {
            customName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.deep_read_template_custom)
        } else {
            stringResource(res)
        }
    }

    /** Lucide stand-ins for the iOS SF Symbols. */
    fun icon(id: String?): ImageVector {
        if (id?.startsWith(DeepReadTemplateIds.CUSTOM_PREFIX) == true) return Lucide.CodeXml
        return when (DeepReadTemplateIds.normalize(id)) {
            DeepReadTemplateIds.AUTO -> Lucide.WandSparkles
            DeepReadTemplateIds.EDITORIAL_SLANT -> Lucide.GalleryVertical
            DeepReadTemplateIds.BRIEF -> Lucide.Zap
            DeepReadTemplateIds.QA -> Lucide.MessageCircleQuestion
            DeepReadTemplateIds.DEBATE -> Lucide.Users
            DeepReadTemplateIds.TIMELINE -> Lucide.CalendarClock
            DeepReadTemplateIds.REVIEW -> Lucide.ListChecks
            else -> Lucide.Newspaper
        }
    }
}
