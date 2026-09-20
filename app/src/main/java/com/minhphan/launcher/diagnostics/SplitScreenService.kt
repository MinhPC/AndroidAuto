package com.minhphan.launcher.diagnostics

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * An accessibility service that only exists to send Android's standard "toggle split screen" action
 * (a public API, unlike embedding another app's window). It subscribes to no events and reads no
 * screen content; see res/xml/split_screen_service.xml.
 */
class SplitScreenService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        @Volatile
        var instance: SplitScreenService? = null
            private set

        /** True if the command was handed to the system; false if the service is not enabled. */
        fun toggleSplitScreen(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN) ?: false
    }
}
