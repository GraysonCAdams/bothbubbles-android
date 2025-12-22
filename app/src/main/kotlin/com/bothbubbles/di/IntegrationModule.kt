package com.bothbubbles.di

import com.bothbubbles.services.calendar.CalendarHeaderIntegration
import com.bothbubbles.services.life360.Life360HeaderIntegration
import com.bothbubbles.ui.chat.integration.ChatHeaderIntegration
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Hilt module for registering chat header integrations.
 *
 * Integrations are collected via multibinding and injected into
 * [ChatHeaderIntegrationsDelegate] as a Set.
 *
 * ## Adding a New Integration
 *
 * 1. Create your integration class implementing [ChatHeaderIntegration]
 * 2. Add a binding method here using @Binds @IntoSet
 *
 * Example:
 * ```kotlin
 * @Binds
 * @IntoSet
 * abstract fun bindMyIntegration(impl: MyHeaderIntegration): ChatHeaderIntegration
 * ```
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class IntegrationModule {

    @Binds
    @IntoSet
    abstract fun bindLife360Integration(
        impl: Life360HeaderIntegration
    ): ChatHeaderIntegration

    @Binds
    @IntoSet
    abstract fun bindCalendarIntegration(
        impl: CalendarHeaderIntegration
    ): ChatHeaderIntegration
}
