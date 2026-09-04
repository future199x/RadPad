package com.radpad.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service powering system-wide touch injection and global gestures.
 *
 * Used by [VirtualMouseManager] when the virtual mouse layer is engaged (R2 hold),
 * enabling synthetic taps, long-presses, and global navigation actions across the entire Android OS
 * via [dispatchGesture] without requiring root permissions.
 */
@SuppressLint("AccessibilityPolicy")
class RadPadAccessibilityService : AccessibilityService() {

    companion object {
        /** Singleton reference to the running accessibility service instance, or null if not enabled. */
        var instance: RadPadAccessibilityService? = null
            private set

        /** Returns true if the user has enabled RadPad in Android Accessibility settings. */
        val isEnabled: Boolean
            get() = instance != null

        /**
         * Dispatches a synthetic touch tap gesture at coordinates ([x], [y]) with [durationMs].
         *
         * @return true if the gesture was successfully enqueued to Android's accessibility dispatcher.
         */
        fun dispatchTap(x: Float, y: Float, durationMs: Long = 50L, onComplete: (() -> Unit)? = null): Boolean {
            val service = instance ?: return false

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

        private var isScrollInProgress = false

        /**
         * Dispatches a synthetic scroll gesture at pointer coordinates ([x], [y]).
         * If [scrollUp] is true, swipes downward to reveal content above (scrolling up).
         * If [scrollUp] is false, swipes upward to reveal content below (scrolling down).
         */
        fun dispatchScroll(
            x: Float,
            y: Float,
            scrollUp: Boolean,
            durationMs: Long = 120L,
            onComplete: (() -> Unit)? = null
        ): Boolean {
            val service = instance ?: return false
            if (isScrollInProgress) return false

            val metrics = service.resources.displayMetrics
            val density = metrics.density
            val screenWidth = metrics.widthPixels.toFloat()
            val screenHeight = metrics.heightPixels.toFloat()
            val margin = 40f * density
            val maxScroll = (200f * density).coerceAtMost((screenHeight - 2 * margin) / 2f)

            val clampedX = x.coerceIn(margin, screenWidth - margin)
            val (startY, endY) = if (scrollUp) {
                // Swiping downward scrolls content up (revealing content above)
                val from = (y - maxScroll / 2f).coerceIn(margin, screenHeight - margin - maxScroll)
                val to = from + maxScroll
                Pair(from, to)
            } else {
                // Swiping upward scrolls content down (revealing content below)
                val from = (y + maxScroll / 2f).coerceIn(margin + maxScroll, screenHeight - margin)
                val to = from - maxScroll
                Pair(from, to)
            }

            val path = Path().apply {
                moveTo(clampedX, startY)
                lineTo(clampedX, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            isScrollInProgress = true
            return service.dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    isScrollInProgress = false
                    onComplete?.invoke()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    isScrollInProgress = false
                }
            }, null)
        }

        /**
         * Dispatches the Android system Back navigation action.
         */
        fun performBack(): Boolean {
            return instance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false
        }

        /**
         * Dispatches the Android system Recents / Overview navigation action.
         */
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
