package me.rerere.rikkahub.data.agent.icloud

import kotlinx.serialization.json.JsonObject

enum class ICloudDriveStatus(val wireName: String) {
    NOT_CONFIGURED("not_configured"),
    LOGIN_REQUIRED("login_required"),
    PROBING("probing"),
    READ_ONLY("read_only"),
    READ_WRITE("read_write"),
    ERROR("error"),
}

enum class ICloudDriveCapability(val wireName: String) {
    NONE("none"),
    READ_ONLY("read_only"),
    READ_WRITE("read_write"),
}

data class ICloudDriveState(
    val enabled: Boolean = false,
    val vaultPath: String = "",
    val status: ICloudDriveStatus = ICloudDriveStatus.NOT_CONFIGURED,
    val capability: ICloudDriveCapability = ICloudDriveCapability.NONE,
    val message: String? = null,
    val updatedAtMillis: Long = 0L,
)

data class ICloudDriveCookieBundle(
    val header: String,
    val sourceUrls: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = header.isBlank()

    fun value(name: String): String? =
        header.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter("=")
}

data class ICloudDriveSession(
    val clientId: String,
    val dsid: String,
    val drivewsUrl: String,
    val docwsUrl: String,
    val cookies: ICloudDriveCookieBundle,
    val endpoint: ICloudDriveWebEndpoint = ICloudDriveWebEndpoints.GLOBAL,
) {
    val params: Map<String, String>
        get() = mapOf(
            "clientBuildNumber" to ICLOUD_CLIENT_BUILD_NUMBER,
            "clientMasteringNumber" to ICLOUD_CLIENT_MASTERING_NUMBER,
            "clientId" to clientId,
            "dsid" to dsid,
        )
}

data class ICloudDriveNode(
    val name: String,
    val type: String,
    val drivewsid: String,
    val docwsid: String?,
    val zone: String,
    val etag: String?,
    val sizeBytes: Long?,
    val raw: JsonObject,
) {
    val isDirectory: Boolean get() = type.lowercase() != "file"
}

data class ICloudDriveEntry(
    val path: String,
    val name: String,
    val directory: Boolean,
    val sizeBytes: Long?,
)

data class ICloudDriveResolvedPath(
    val vaultPath: String,
    val relativePath: String,
    val iCloudPath: String,
)

data class ICloudDriveWebEndpoint(
    val id: String,
    val displayName: String,
    val loginUrl: String,
    val setupEndpoint: String,
    val origin: String,
    val cookieUrls: List<String>,
) {
    val referer: String get() = "$origin/"
}

object ICloudDriveWebEndpoints {
    val GLOBAL = ICloudDriveWebEndpoint(
        id = "global",
        displayName = "iCloud",
        loginUrl = "https://www.icloud.com/iclouddrive",
        setupEndpoint = "https://setup.icloud.com/setup/ws/1",
        origin = "https://www.icloud.com",
        cookieUrls = listOf(
            "https://www.icloud.com",
            "https://setup.icloud.com",
            "https://www.icloud.com/iclouddrive",
        ),
    )

    val CHINA = ICloudDriveWebEndpoint(
        id = "china",
        displayName = "iCloud China",
        loginUrl = "https://www.icloud.com.cn/iclouddrive",
        setupEndpoint = "https://setup.icloud.com.cn/setup/ws/1",
        origin = "https://www.icloud.com.cn",
        cookieUrls = listOf(
            "https://www.icloud.com.cn",
            "https://setup.icloud.com.cn",
            "https://www.icloud.com.cn/iclouddrive",
            "https://www.icloud.cn",
            "https://setup.icloud.cn",
            "https://www.icloud.cn/iclouddrive",
        ),
    )

    val ALL = listOf(GLOBAL, CHINA)

    fun preferredFor(cookies: ICloudDriveCookieBundle): List<ICloudDriveWebEndpoint> {
        val sourceSet = cookies.sourceUrls.toSet()
        val sourceMatched = ALL.sortedByDescending { endpoint ->
            if (endpoint.cookieUrls.any { it in sourceSet }) 1 else 0
        }
        return sourceMatched.ifEmpty { ALL }
    }
}

const val ICLOUD_GLOBAL_LOGIN_URL = "https://www.icloud.com/iclouddrive"
const val ICLOUD_CHINA_LOGIN_URL = "https://www.icloud.com.cn/iclouddrive"
const val ICLOUD_LOGIN_URL = ICLOUD_GLOBAL_LOGIN_URL
const val ICLOUD_SETUP_ENDPOINT = "https://setup.icloud.com/setup/ws/1"
const val ICLOUD_ROOT_DRIVEWS_ID = "FOLDER::com.apple.CloudDocs::root"
const val ICLOUD_CLIENT_BUILD_NUMBER = "2534Project66"
const val ICLOUD_CLIENT_MASTERING_NUMBER = "2534B22"
