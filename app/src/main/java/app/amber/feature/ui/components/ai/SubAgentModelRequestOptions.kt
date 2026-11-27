package app.amber.feature.ui.components.ai

import app.amber.ai.provider.CustomBody
import app.amber.ai.provider.CustomHeader
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting

internal fun Model.requestHeaders(providers: List<ProviderSetting>): List<CustomHeader> {
    val canonicalHeaders = providers.findCanonicalModel(this)?.customHeaders.orEmpty()
    return mergeHeaders(canonicalHeaders, customHeaders)
}

internal fun Model.requestBodies(providers: List<ProviderSetting>): List<CustomBody> {
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
