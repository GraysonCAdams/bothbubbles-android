package com.bothbubbles.seam.settings

import com.bothbubbles.seam.hems.Feature
import com.bothbubbles.seam.hems.FeatureRegistry
import com.bothbubbles.seam.stitches.Stitch
import com.bothbubbles.seam.stitches.StitchRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides aggregated settings contributions from all Stitches and Features.
 *
 * This class collects [SettingsContribution] instances from the [StitchRegistry]
 * and [FeatureRegistry], organizing them for consumption by the Settings UI.
 *
 * ## Usage
 * The SettingsScreen (or SettingsViewModel) injects this provider to dynamically
 * render settings items contributed by Stitches and Features.
 *
 * ## Thread Safety
 * This class is stateless and safe to use from any thread. The underlying
 * registries are @Singleton and thread-safe.
 */
@Singleton
class SettingsContributionProvider @Inject constructor(
    private val stitchRegistry: StitchRegistry,
    private val featureRegistry: FeatureRegistry
) {
    /**
     * Get all dedicated menu items from Stitches.
     *
     * @return List of dedicated menu items sorted by section, then by title
     */
    fun getStitchMenuItems(): List<DedicatedSettingsMenuItem> {
        return stitchRegistry.getAllStitches()
            .mapNotNull { it.settingsContribution.dedicatedMenuItem }
            .sortedWith(compareBy({ it.section.ordinal }, { it.title }))
    }

    /**
     * Get all dedicated menu items from Features.
     *
     * @return List of dedicated menu items sorted by section, then by title
     */
    fun getFeatureMenuItems(): List<DedicatedSettingsMenuItem> {
        return featureRegistry.getAllFeatures()
            .mapNotNull { it.settingsContribution.dedicatedMenuItem }
            .sortedWith(compareBy({ it.section.ordinal }, { it.title }))
    }

    /**
     * Get all additional items for a specific section from Stitches.
     *
     * @param section The section to get items for
     * @return List of settings items sorted by priority, then by title
     */
    fun getStitchItemsForSection(section: SettingsSection): List<SettingsItem> {
        return stitchRegistry.getAllStitches()
            .flatMap { it.settingsContribution.additionalItems[section] ?: emptyList() }
            .sortedWith(compareBy({ it.priority }, { it.title }))
    }

    /**
     * Get all additional items for a specific section from Features.
     *
     * @param section The section to get items for
     * @return List of settings items sorted by priority, then by title
     */
    fun getFeatureItemsForSection(section: SettingsSection): List<SettingsItem> {
        return featureRegistry.getAllFeatures()
            .flatMap { it.settingsContribution.additionalItems[section] ?: emptyList() }
            .sortedWith(compareBy({ it.priority }, { it.title }))
    }

    /**
     * Get all dedicated menu items for a specific section.
     *
     * Combines items from both Stitches and Features, sorted alphabetically.
     *
     * @param section The section to get menu items for
     * @return List of dedicated menu items for the section
     */
    fun getMenuItemsForSection(section: SettingsSection): List<DedicatedSettingsMenuItem> {
        val stitchItems = stitchRegistry.getAllStitches()
            .mapNotNull { it.settingsContribution.dedicatedMenuItem }
            .filter { it.section == section }

        val featureItems = featureRegistry.getAllFeatures()
            .mapNotNull { it.settingsContribution.dedicatedMenuItem }
            .filter { it.section == section }

        return (stitchItems + featureItems).sortedBy { it.title }
    }

    /**
     * Get all additional items for a specific section.
     *
     * Combines items from both Stitches and Features, sorted by priority then title.
     *
     * @param section The section to get items for
     * @return List of all additional settings items for the section
     */
    fun getAdditionalItemsForSection(section: SettingsSection): List<SettingsItem> {
        val stitchItems = getStitchItemsForSection(section)
        val featureItems = getFeatureItemsForSection(section)
        return (stitchItems + featureItems).sortedWith(compareBy({ it.priority }, { it.title }))
    }

    /**
     * Check if any Stitch or Feature has contributed items to a section.
     *
     * @param section The section to check
     * @return True if there are any contributed items for this section
     */
    fun hasContributionsForSection(section: SettingsSection): Boolean {
        return getMenuItemsForSection(section).isNotEmpty() ||
               getAdditionalItemsForSection(section).isNotEmpty()
    }

    /**
     * Get a specific Stitch by ID for accessing its contribution.
     */
    fun getStitchById(id: String): Stitch? = stitchRegistry.getStitchById(id)

    /**
     * Get a specific Feature by ID for accessing its contribution.
     */
    fun getFeatureById(id: String): Feature? = featureRegistry.getFeatureById(id)
}
