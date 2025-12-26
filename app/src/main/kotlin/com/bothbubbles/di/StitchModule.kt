package com.bothbubbles.di

import com.bothbubbles.seam.stitches.Stitch
import com.bothbubbles.seam.stitches.bluebubbles.BlueBubblesStitch
import com.bothbubbles.seam.stitches.sms.SmsStitch
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class StitchModule {
    @Binds
    @IntoSet
    abstract fun bindSmsStitch(impl: SmsStitch): Stitch

    @Binds
    @IntoSet
    abstract fun bindBlueBubblesStitch(impl: BlueBubblesStitch): Stitch
}
