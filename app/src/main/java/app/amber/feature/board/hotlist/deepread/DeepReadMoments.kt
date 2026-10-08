package app.amber.feature.board.hotlist.deepread

import android.icu.util.ChineseCalendar
import java.util.Calendar
import java.util.GregorianCalendar

/**
 * Date/time-driven flourishes for the deep-read surface (iOS `DeepReadMoment` parity):
 * night lamp, festival badge and the completion seal. The calendar rules are kept
 * pure so they stay unit-testable; the `java.util.Calendar` wrapper is the only
 * Android-dependent piece.
 */
object DeepReadMoments {

    enum class Festival { LUNAR_NEW_YEAR, LANTERN, MID_AUTUMN, NEW_YEAR, BOOK_DAY, NATIONAL_DAY, CHRISTMAS }

    /** 22:00–04:59 — the reader picks up a warm desk-lamp glow. */
    fun isNightHour(hour: Int): Boolean = hour >= 22 || hour < 5

    fun isNight(now: Calendar = Calendar.getInstance()): Boolean =
        isNightHour(now.get(Calendar.HOUR_OF_DAY))

    /** Pure festival rules: lunar dates arrive pre-resolved so JVM tests never touch ICU. */
    fun festival(
        month: Int,
        day: Int,
        lunarMonth: Int,
        lunarDay: Int,
        leapMonth: Boolean,
    ): Festival? {
        if (!leapMonth) {
            when {
                lunarMonth == 1 && lunarDay in 1..7 -> return Festival.LUNAR_NEW_YEAR
                lunarMonth == 1 && lunarDay == 15 -> return Festival.LANTERN
                lunarMonth == 8 && lunarDay == 15 -> return Festival.MID_AUTUMN
            }
        }
        return when {
            month == 1 && day == 1 -> Festival.NEW_YEAR
            month == 4 && day == 23 -> Festival.BOOK_DAY
            month == 10 && day in 1..7 -> Festival.NATIONAL_DAY
            month == 12 && day in 24..25 -> Festival.CHRISTMAS
            else -> null
        }
    }

    fun festival(now: Calendar = GregorianCalendar.getInstance()): Festival? {
        val lunar = ChineseCalendar(now.time)
        return festival(
            month = now.get(Calendar.MONTH) + 1,
            day = now.get(Calendar.DAY_OF_MONTH),
            lunarMonth = lunar.get(ChineseCalendar.MONTH) + 1,
            lunarDay = lunar.get(ChineseCalendar.DAY_OF_MONTH),
            leapMonth = lunar.get(ChineseCalendar.IS_LEAP_MONTH) == 1,
        )
    }

    const val SEAL_PRINTED = "付印"
    const val SEAL_FIRST = "首篇"
    const val SEAL_TENTH = "十篇"
    const val SEAL_HUNDREDTH = "百篇"

    fun milestoneInscription(completedCount: Int): String? = when (completedCount) {
        1 -> SEAL_FIRST
        10 -> SEAL_TENTH
        100 -> SEAL_HUNDREDTH
        else -> null
    }

    /** [milestone] is the article ordinal when the inscription is a milestone seal. */
    data class PressSeal(val inscription: String, val milestone: Int?)

    /**
     * Seal for a run that just finished while the reader watched. A retry that kept
     * the old article after cancel/failure is not a fresh print; only a first draft
     * counts toward milestones.
     */
    fun pressSeal(
        finishedWithError: Boolean,
        wasFirstDraft: Boolean,
        completedCount: Int,
    ): PressSeal? {
        if (finishedWithError) return null
        if (wasFirstDraft) {
            milestoneInscription(completedCount)?.let { return PressSeal(it, completedCount) }
        }
        return PressSeal(SEAL_PRINTED, null)
    }
}
