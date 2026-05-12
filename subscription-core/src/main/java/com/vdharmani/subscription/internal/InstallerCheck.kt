package com.vdharmani.subscription.internal

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build

/**
 * Returns an [IllegalStateException] when the app should be blocked from
 * making purchases (debuggable build, or no Play Store installer source),
 * or `null` when the install is OK.
 *
 * Shared by [com.vdharmani.subscription.SubscriptionClient] and
 * [com.vdharmani.subscription.compose.ComposeSubscription] so the rule is
 * defined once.
 */
internal fun playStoreInstallerCheck(context: Context): IllegalStateException? {
    val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    val installer = readInstallerPackageName(context)
    return if (debuggable || installer == null) {
        IllegalStateException("This app must be installed from the Play Store to make purchases.")
    } else {
        null
    }
}

private fun readInstallerPackageName(context: Context): String? = try {
    val pm = context.packageManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        pm.getInstallSourceInfo(context.packageName).installingPackageName
    } else {
        @Suppress("DEPRECATION")
        pm.getInstallerPackageName(context.packageName)
    }
} catch (_: Exception) {
    null
}
