package app.amber.feature.board.hotlist

import app.amber.feature.board.hotlist.deepread.DeepReadMoments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepReadMomentsTest {

    @Test
    fun nightCoversLateEveningAndEarlyMorning() {
        assertEquals(true, DeepReadMoments.isNightHour(22))
        assertEquals(true, DeepReadMoments.isNightHour(0))
        assertEquals(true, DeepReadMoments.isNightHour(4))
        assertEquals(false, DeepReadMoments.isNightHour(5))
        assertEquals(false, DeepReadMoments.isNightHour(21))
    }

    @Test
    fun lunarFestivalsResolveFromLunarDates() {
        assertEquals(
            DeepReadMoments.Festival.LUNAR_NEW_YEAR,
            DeepReadMoments.festival(month = 2, day = 17, lunarMonth = 1, lunarDay = 1, leapMonth = false),
        )
        assertEquals(
            DeepReadMoments.Festival.LANTERN,
            DeepReadMoments.festival(month = 3, day = 3, lunarMonth = 1, lunarDay = 15, leapMonth = false),
        )
        assertEquals(
            DeepReadMoments.Festival.MID_AUTUMN,
            DeepReadMoments.festival(month = 10, day = 6, lunarMonth = 8, lunarDay = 15, leapMonth = false),
        )
    }

    @Test
    fun leapMonthSuppressesLunarFestivals() {
        assertNull(
            DeepReadMoments.festival(month = 8, day = 1, lunarMonth = 8, lunarDay = 15, leapMonth = true),
        )
    }

    @Test
    fun gregorianFestivals() {
        assertEquals(
            DeepReadMoments.Festival.NEW_YEAR,
            DeepReadMoments.festival(month = 1, day = 1, lunarMonth = 12, lunarDay = 10, leapMonth = false),
        )
        assertEquals(
            DeepReadMoments.Festival.BOOK_DAY,
            DeepReadMoments.festival(month = 4, day = 23, lunarMonth = 3, lunarDay = 15, leapMonth = false),
        )
        assertEquals(
            DeepReadMoments.Festival.NATIONAL_DAY,
            DeepReadMoments.festival(month = 10, day = 3, lunarMonth = 8, lunarDay = 20, leapMonth = false),
        )
        assertEquals(
            DeepReadMoments.Festival.CHRISTMAS,
            DeepReadMoments.festival(month = 12, day = 25, lunarMonth = 11, lunarDay = 20, leapMonth = false),
        )
        assertNull(
            DeepReadMoments.festival(month = 6, day = 15, lunarMonth = 5, lunarDay = 20, leapMonth = false),
        )
    }

    @Test
    fun pressSealRules() {
        // Failure suppresses the seal entirely.
        assertNull(DeepReadMoments.pressSeal(finishedWithError = true, wasFirstDraft = true, completedCount = 1))
        // First draft at milestones stamps the milestone seal.
        assertEquals("首篇", DeepReadMoments.pressSeal(false, true, 1)?.inscription)
        assertEquals("十篇", DeepReadMoments.pressSeal(false, true, 10)?.inscription)
        assertEquals("百篇", DeepReadMoments.pressSeal(false, true, 100)?.inscription)
        // First draft off-milestone stamps 付印.
        assertEquals("付印", DeepReadMoments.pressSeal(false, true, 3)?.inscription)
        // A retry that kept old content is never a milestone.
        val retry = DeepReadMoments.pressSeal(false, false, 10)
        assertEquals("付印", retry?.inscription)
        assertNull(retry?.milestone)
        // Milestone seals carry the ordinal for the caption.
        assertEquals(10, DeepReadMoments.pressSeal(false, true, 10)?.milestone)
    }
}
