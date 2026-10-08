package app.amber.feature.runtime

import app.amber.ai.util.HttpException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.EOFException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** 失败卡片按原因归类，而不是把异常类名显示给用户。 */
class LiveFailureReasonTest {

    @Test
    fun `network failures anywhere in the cause chain map to NETWORK`() {
        assertEquals(LiveFailureReason.NETWORK, liveFailureReason(SocketTimeoutException("timeout")))
        assertEquals(LiveFailureReason.NETWORK, liveFailureReason(UnknownHostException("api.example.com")))
        assertEquals(LiveFailureReason.NETWORK, liveFailureReason(EOFException("stream closed")))
        assertEquals(
            LiveFailureReason.NETWORK,
            liveFailureReason(IllegalStateException("wrapped", SocketTimeoutException("timeout"))),
        )
    }

    @Test
    fun `quota and rate limit messages map to QUOTA even over network types`() {
        assertEquals(LiveFailureReason.QUOTA, liveFailureReason(HttpException("429 Too Many Requests")))
        assertEquals(LiveFailureReason.QUOTA, liveFailureReason(HttpException("You exceeded your current quota")))
        assertEquals(LiveFailureReason.QUOTA, liveFailureReason(HttpException("账户余额不足")))
        assertEquals(LiveFailureReason.QUOTA, liveFailureReason(java.io.IOException("rate limit exceeded")))
    }

    @Test
    fun `other http errors map to SERVICE and everything else to OTHER`() {
        assertEquals(LiveFailureReason.SERVICE, liveFailureReason(HttpException("500 internal error")))
        assertEquals(LiveFailureReason.OTHER, liveFailureReason(IllegalStateException("boom")))
    }
}
