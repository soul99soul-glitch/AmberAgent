package app.amber.feature.ui.pages.synara

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SynaraWorkspaceLoadStateTest {

    @Test
    fun mainFrameErrorIsVisibleAndRetainedUntilRetry() {
        val loading = synaraLoadStarted()
        val error = synaraMainFrameError(loading, description = "net::ERR_CONNECTION_REFUSED")

        assertEquals(
            SynaraWorkspaceLoadState.Error("net::ERR_CONNECTION_REFUSED"),
            error,
        )
        assertEquals(error, synaraLoadFinished(error))
        assertEquals(SynaraWorkspaceLoadState.Loading(), synaraLoadStarted())
    }

    @Test
    fun subresourceErrorDoesNotReplacePageState() {
        val loading = synaraLoadProgress(synaraLoadStarted(), 42)

        assertEquals(
            loading,
            synaraMainFrameError(
                loading,
                description = "net::ERR_BLOCKED_BY_CLIENT",
                isMainFrame = false,
            ),
        )
        assertEquals(
            loading,
            synaraMainFrameHttpError(
                loading,
                statusCode = 503,
                reason = "Service Unavailable",
                isMainFrame = false,
            ),
        )
    }

    @Test
    fun httpMainFrameErrorUsesStatusWhenReasonIsMissing() {
        val error = synaraMainFrameHttpError(
            synaraLoadStarted(),
            statusCode = 502,
            reason = " ",
        )

        assertEquals(SynaraWorkspaceLoadState.Error("HTTP 502"), error)
    }

    @Test
    fun progressIsClampedOnlyWhileLoading() {
        assertEquals(
            SynaraWorkspaceLoadState.Loading(100),
            synaraLoadProgress(synaraLoadStarted(), 140),
        )
        assertEquals(
            SynaraWorkspaceLoadState.Loading(0),
            synaraLoadProgress(synaraLoadStarted(), -10),
        )
        assertTrue(
            synaraLoadProgress(SynaraWorkspaceLoadState.Ready, 50) is SynaraWorkspaceLoadState.Ready,
        )
    }
}
