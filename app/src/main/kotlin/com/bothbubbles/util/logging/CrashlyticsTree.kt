package com.bothbubbles.util.logging

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import timber.log.Timber

/**
 * Timber tree that forwards logs to Firebase Crashlytics.
 *
 * This tree is planted in release builds to send breadcrumbs and error reports
 * to Crashlytics for production debugging. It logs INFO+ messages as breadcrumbs
 * and records ERROR+ exceptions to Crashlytics.
 *
 * In debug builds, we use Timber.DebugTree() instead which logs to Logcat.
 */
class CrashlyticsTree : Timber.Tree() {

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // Only log INFO and above to reduce noise in Crashlytics
        if (priority >= Log.INFO) {
            val logMessage = tag?.let { "$it: $message" } ?: message
            FirebaseCrashlytics.getInstance().log(logMessage)
        }

        // Record exceptions for ERROR and above
        if (t != null && priority >= Log.ERROR) {
            FirebaseCrashlytics.getInstance().recordException(t)
        }
    }
}
