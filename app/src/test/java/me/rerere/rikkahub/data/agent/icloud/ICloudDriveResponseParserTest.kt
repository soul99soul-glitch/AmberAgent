package me.rerere.rikkahub.data.agent.icloud

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test

class ICloudDriveResponseParserTest {
    @Test
    fun parsesSessionEndpointsFromValidateResponse() {
        val raw = JsonInstant.parseToJsonElement(
            """
            {
              "dsInfo": {"dsid": "123456"},
              "webservices": {
                "drivews": {"url": "https://drivews.icloud.com/ws"},
                "docws": {"url": "https://docws.icloud.com/docws/"}
              }
            }
            """.trimIndent(),
        ).jsonObject

        val session = ICloudDriveResponseParser.parseSession(
            clientId = "client-1",
            cookies = ICloudDriveCookieBundle("X-APPLE-WEBAUTH-TOKEN=ok"),
            raw = raw,
        )

        assertEquals("123456", session.dsid)
        assertEquals("https://drivews.icloud.com/ws", session.drivewsUrl)
        assertEquals("https://docws.icloud.com/docws", session.docwsUrl)
        assertEquals("client-1", session.params["clientId"])
    }

    @Test
    fun parsesChinaSessionEndpointsFromValidateResponse() {
        val raw = JsonInstant.parseToJsonElement(
            """
            {
              "dsInfo": {"dsid": "cn-123"},
              "webservices": {
                "drivews": {"url": "https://drivews.icloud.com.cn/ws/"},
                "docws": {"url": "https://docws.icloud.com.cn/docws/"}
              }
            }
            """.trimIndent(),
        ).jsonObject

        val session = ICloudDriveResponseParser.parseSession(
            clientId = "client-cn",
            cookies = ICloudDriveCookieBundle("X-APPLE-WEBAUTH-TOKEN=ok"),
            raw = raw,
            endpoint = ICloudDriveWebEndpoints.CHINA,
        )

        assertEquals("cn-123", session.dsid)
        assertEquals("https://drivews.icloud.com.cn/ws", session.drivewsUrl)
        assertEquals("https://docws.icloud.com.cn/docws", session.docwsUrl)
        assertEquals(ICloudDriveWebEndpoints.CHINA, session.endpoint)
    }

    @Test
    fun parsesNodeNameWithExtension() {
        val node = ICloudDriveResponseParser.parseNode(
            buildJsonObject {
                put("name", "Daily")
                put("extension", "md")
                put("type", "FILE")
                put("drivewsid", "FILE::zone::daily")
                put("docwsid", "doc-1")
                put("zone", "com.apple.CloudDocs")
                put("etag", "etag-1")
                put("size", "42")
            },
        )

        assertEquals("Daily.md", node.name)
        assertEquals("file", node.type)
        assertEquals(false, node.isDirectory)
        assertEquals(42L, node.sizeBytes)
    }
}
