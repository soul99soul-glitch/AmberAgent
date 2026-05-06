package me.rerere.rikkahub.data.agent.icloud

import org.junit.Assert.assertEquals
import org.junit.Test

class ICloudDriveCookieProviderTest {
    @Test
    fun mergesCookieHeadersByName() {
        val target = linkedMapOf<String, String>()

        ICloudDriveCookieProvider.mergeCookieHeaderInto(
            raw = "X-APPLE-WEBAUTH-TOKEN=global; other=1",
            target = target,
        )
        ICloudDriveCookieProvider.mergeCookieHeaderInto(
            raw = "X-APPLE-WEBAUTH-TOKEN=china; X-APPLE-WEBAUTH-VALIDATE=t=upload:1",
            target = target,
        )

        assertEquals(
            "X-APPLE-WEBAUTH-TOKEN=china; other=1; X-APPLE-WEBAUTH-VALIDATE=t=upload:1",
            target.values.joinToString("; "),
        )
    }

    @Test
    fun prefersChinaEndpointWhenChinaCookieSourceIsPresent() {
        val cookies = ICloudDriveCookieBundle(
            header = "X-APPLE-WEBAUTH-TOKEN=ok",
            sourceUrls = listOf("https://www.icloud.com.cn"),
        )

        assertEquals(ICloudDriveWebEndpoints.CHINA, ICloudDriveWebEndpoints.preferredFor(cookies).first())
    }
}
