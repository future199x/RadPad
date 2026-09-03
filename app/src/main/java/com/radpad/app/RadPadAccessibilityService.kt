package com.radpad.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/**
 * RadPadAccessibilityService: System-wide touch & gesture dispatcher.
 * Enables the Virtual Mouse Layer to perform real taps and long-presses anywhere on screen.
 */
class RadPadAccessibilityService : AccessibilityService() {

    companion object {
        var instance: RadPadAccessibilityService? = null
            private set

        val isEnabled: Boolean
            get() = instance != null

        fun dispatchTap(x: Float, y: Float, durationMs: Long = 50L, onComplete: (() -> Unit)? = null): Boolean {
            val service = instance ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

            val path = Path().apply {
                moveTo(x, y)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            return service.dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onComplete?.invoke()
                }
            }, null)
        }

        fun performBack(): Boolean {
            return instance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false
        }

        fun performRecents(): Boolean {
            return instance?.performGlobalAction(GLOBAL_ACTION_RECENTS) ?: false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
    }
}
