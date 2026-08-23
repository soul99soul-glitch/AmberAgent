package app.amber.feature.novel.serialization

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Naive kotlinx.serialization shapes used only to prove default encoding is NOT
 * Swift-compatible. Production models in Phase 1 use custom serializers instead.
 */
@Serializable
data class DefaultTypedId(
    val rawValue: String,
)

@Serializable
data class DefaultWrapperId(
    val id: DefaultTypedId,
)

@Serializable
data class DefaultDateHolder(
    val createdAt: Double,
)

@Serializable
sealed class DefaultModelPolicy {
    @Serializable
    @SerialName("global")
    data object Global : DefaultModelPolicy()

    @Serializable
    @SerialName("fixed")
    data class Fixed(
        val providerID: String,
        val modelID: String,
    ) : DefaultModelPolicy()
}

@Serializable
data class DefaultModelPolicyHolder(
    val modelPolicy: DefaultModelPolicy,
)

@Serializable
sealed class DefaultMaterialKind {
    @Serializable
    @SerialName("world")
    data object World : DefaultMaterialKind()

    @Serializable
    @SerialName("custom")
    data class Custom(val value: String) : DefaultMaterialKind()
}

@Serializable
data class DefaultMaterialKindHolder(
    val kind: DefaultMaterialKind,
)
