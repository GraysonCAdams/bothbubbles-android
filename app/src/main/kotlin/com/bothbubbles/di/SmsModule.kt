package com.bothbubbles.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt module for SMS/MMS related dependencies.
 *
 * All SMS classes are auto-wired by Hilt via @Inject constructors:
 * - SmsRepository
 * - SmsContentProvider
 * - SmsSendService
 * - MmsSendService
 * - SmsContentObserver
 * - SmsPermissionHelper
 *
 * This module exists as documentation and to group SMS-related
 * dependencies conceptually. No manual @Provides methods are needed.
 */
@Module
@InstallIn(SingletonComponent::class)
object SmsModule
