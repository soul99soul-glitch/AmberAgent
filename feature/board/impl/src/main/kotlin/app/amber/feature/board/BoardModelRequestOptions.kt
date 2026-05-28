package app.amber.feature.board

import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting

fun Model.boardRequestHeaders(providers: List<ProviderSetting>): List<CustomHeader> {
    val canonicalHeaders = providers.findCanonicalModel(this)?.customHeaders.orEmpty()
    return mergeHeaders(canonicalHeaders, customHeaders)
}

fun Model.boardRequestBodies(providers: List<ProviderSetting>): List<CustomBody> {
    val canonicalBodies = providers.findCanonicalModel(this)?.customBodies.orEmpty()
    return mergeBodies(canonicalBodies, customBodies)
}

private fun List<ProviderSetting>.findCanonicalModel(model: Model): Model? {
    forEach { provider ->
        provider.models.firstOrNull { it.id == model.id }?.let { return it }
    }
    return null
}

private fun mergeHeaders(base: List<CustomHeader>, override: List<CustomHeader>): List<CustomHeader> {
    if (base.isEmpty()) return override
    if (override.isEmpty()) return base

    val overrideNames = override.map { it.name.lowercase() }.toSet()
    return base.filterNot { it.name.lowercase() in overrideNames } + override
}

private fun mergeBodies(base: List<CustomBody>, override: List<CustomBody>): List<CustomBody> {
    if (base.isEmpty()) return override
    if (override.isEmpty()) return base

    val overrideKeys = override.map { it.key }.toSet()
    return base.filterNot { it.key in overrideKeys } + override
}
