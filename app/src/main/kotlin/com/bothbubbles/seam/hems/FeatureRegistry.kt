package com.bothbubbles.seam.hems

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FeatureRegistry @Inject constructor(
    private val features: Set<@JvmSuppressWildcards Feature>
) {
    fun getEnabledFeatures(): List<Feature> =
        features.filter { it.isEnabled.value }

    fun getFeatureById(id: String): Feature? = features.find { it.id == id }

    fun getAllFeatures(): Set<Feature> = features
}
