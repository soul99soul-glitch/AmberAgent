package app.amber.feature.ui.pages.novel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelInkPlayTest {

    @Test
    fun streakRevealsAtFiveTapsInsideWindow() {
        val streak = InkTapStreak()
        repeat(4) { assertFalse(streak.tap(1000L + it * 200)) }
        assertTrue(streak.tap(1900))
    }

    @Test
    fun streakResetsAfterPause() {
        val streak = InkTapStreak()
        repeat(4) { streak.tap(1000L + it * 200) }
        assertEquals(4, streak.sink)
        streak.settle(4000)
        assertEquals(0, streak.sink)
        // Fresh streak needs all five taps again.
        repeat(4) { assertFalse(streak.tap(5000L + it * 200)) }
        assertTrue(streak.tap(5900))
    }

    @Test
    fun streakFirstTapAlwaysStartsAtOne() {
        val streak = InkTapStreak()
        // The very first tap (lastTapMs = 0 sentinel) must count as 1, not 2.
        assertFalse(streak.tap(500L))
        assertEquals(1, streak.sink)
    }

    @Test
    fun streakResetsAfterSuccessfulReveal() {
        val streak = InkTapStreak()
        repeat(4) { streak.tap(1000L + it * 200) }
        assertTrue(streak.tap(1900))
        assertEquals(0, streak.sink)
        // A follow-up tap starts a brand-new streak instead of re-triggering.
        repeat(4) { assertFalse(streak.tap(3000L + it * 200)) }
        assertTrue(streak.tap(3900))
    }

    @Test
    fun streakTapExactlyAtWindowEdgeResets() {
        val streak = InkTapStreak()
        streak.tap(1000L)
        // nowMs - lastTapMs == windowMs falls outside the streak window.
        streak.tap(1000L + 1200L)
        assertEquals(1, streak.sink)
    }

    @Test
    fun launchMoodPicksDateAwareTagline() {
        assertEquals(NovelLaunchMood.NEW_YEAR, novelLaunchMood(month = 1, day = 1, hour = 10))
        assertEquals(NovelLaunchMood.LATE_NIGHT, novelLaunchMood(month = 6, day = 10, hour = 2))
        assertEquals(NovelLaunchMood.WRITING_MONTH, novelLaunchMood(month = 11, day = 14, hour = 12))
        assertEquals(NovelLaunchMood.STANDARD, novelLaunchMood(month = 6, day = 10, hour = 12))
        // Hour boundary: 04:xx is still late night, 05:xx is not.
        assertEquals(NovelLaunchMood.LATE_NIGHT, novelLaunchMood(month = 6, day = 10, hour = 4))
        assertEquals(NovelLaunchMood.STANDARD, novelLaunchMood(month = 6, day = 10, hour = 5))
        // Late night wins over the writing-month rule, new year wins over both.
        assertEquals(NovelLaunchMood.LATE_NIGHT, novelLaunchMood(month = 11, day = 14, hour = 3))
        assertEquals(NovelLaunchMood.NEW_YEAR, novelLaunchMood(month = 1, day = 1, hour = 2))
    }
}
