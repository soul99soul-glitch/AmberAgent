package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MainAgentToolProfile {
    @SerialName("full")
    FULL,

    @SerialName("minimal")
    MINIMAL,

    @SerialName("web_read")
    WEB_READ,

    @SerialName("workspace_read")
    WORKSPACE_READ,

    @SerialName("coding")
    CODING,

    @SerialName("mobile_control")
    MOBILE_CONTROL,
}
