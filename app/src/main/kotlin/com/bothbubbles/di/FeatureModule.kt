package com.bothbubbles.di

import com.bothbubbles.seam.hems.Feature
import com.bothbubbles.seam.hems.eta.EtaFeature
import com.bothbubbles.seam.hems.life360.Life360Feature
import com.bothbubbles.seam.hems.reels.ReelsFeature
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class FeatureModule {
    @Binds
    @IntoSet
    abstract fun bindReelsFeature(impl: ReelsFeature): Feature

    @Binds
    @IntoSet
    abstract fun bindLife360Feature(impl: Life360Feature): Feature

    @Binds
    @IntoSet
    abstract fun bindEtaFeature(impl: EtaFeature): Feature
}
