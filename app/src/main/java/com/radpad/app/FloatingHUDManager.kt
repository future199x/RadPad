package com.radpad.app

import java.util.concurrent.CopyOnWriteArrayList
import androidx.core.net.toUri

/**
 * Shared communication hub and observer coordinator linking [ControllerIME], [MainActivity],
 * and the background [FloatingHUDService].
 *
 * Maintains the latest controller stick telemetry, active layer tier, and modifier bitmask state,
 * broadcasting changes thread-safely across all registered [Listener] instances via [CopyOnWriteArrayList].
 */
object FloatingHUDManager {

    /**
     * Callback interface for receiving real-time input telemetry and layer updates.
     */
    interface Listener {
        /**
         * Invoked whenever the controller stick moves or modifier/layer states transition.
         *
         * @param x Right stick X coordinate (-1.0f to 1.0f).
         * @param y Right stick Y coordinate (-1.0f to 1.0f).
         * @param layer The active [InputEngine.Layer].
         * @param secondLayer Whether the secondary page of the layer is active.
         * @param shift Whether Shift modifier is active.
         * @param ctrl Whether Ctrl modifier is active.
         * @param alt Whether Alt modifier is active.
         * @param caps Whether Caps Lock is active.
         * @param superKey Whether Super / Windows key is active.
         */
        fun onStateUpdated(
            x: Float,
            y: Float,
            layer: InputEngine.Layer,
            secondLayer: Boolean,
            shift: Boolean,
            ctrl: Boolean,
            alt: Boolean,
            caps: Boolean,
            superKey: Boolean = false
        )

        /**
         * Invoked whenever the always-on floating HUD service starts or stops.
         *
         * @param isRunning True if [FloatingHUDService] is active, false if stopped.
         */
        fun onFloaterStateChanged(isRunning: Boolean) {}
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    /** Active instance of [InputEngine] shared by the IME or setup activity. */
    var activeEngine: InputEngine? = null

    var lastX: Float = 0f
        private set
    var lastY: Float = 0f
        private set
    var lastLayer: InputEngine.Layer = InputEngine.Layer.BASE
        private set
    var isSecondLayer: Boolean = false
        private set
    var isShift: Boolean = false
        private set
    var isCtrl: Boolean = false
        private set
    var isAlt: Boolean = false
        private set
    var isCaps: Boolean = false
        private set
    var isSuper: Boolean = false
        private set

    /**
     * Registers a [Listener] and immediately sends the most recent known telemetry and floater states.
     */
    fun register(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
        listener.onStateUpdated(lastX, lastY, lastLayer, isSecondLayer, isShift, isCtrl, isAlt, isCaps, isSuper)
        listener.onFloaterStateChanged(FloatingHUDService.isRunning)
    }

    /**
     * Unregisters a previously registered [Listener].
     */
    fun unregister(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * Broadcasts floating overlay service running state changes to all registered listeners.
     *
     * @param running True if the overlay service is started, false if stopped.
     */
    fun notifyFloaterStateChanged(running: Boolean) {
        for (listener in listeners) {
            listener.onFloaterStateChanged(running)
        }
    }

    /**
     * Broadcasts updated stick coordinates and modifier/layer states to all active listeners.
     */
    fun updateInput(
        x: Float,
        y: Float,
        layer: InputEngine.Layer,
        secondLayer: Boolean,
        shift: Boolean,
        ctrl: Boolean,
        alt: Boolean,
        caps: Boolean = false,
        superKey: Boolean = false
    ) {
        lastX = x
        lastY = y
        lastLayer = layer
        isSecondLayer = secondLayer
        isShift = shift
        isCtrl = ctrl
        isAlt = alt
        isCaps = caps
        isSuper = superKey
        for (listener in listeners) {
            listener.onStateUpdated(x, y, layer, secondLayer, shift, ctrl, alt, caps, superKey)
        }
    }

    /**
     * Toggles the always-on floating overlay. If overlay permission is not granted,
     * launches the Android system overlay permission screen.
     * Returns true if overlay was started, false if stopped or permission needed.
     */
    fun toggleFloater(context: android.content.Context): Boolean {
        if (!android.provider.Settings.canDrawOverlays(context)) {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:${context.packageName}".toUri()
            ).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return false
        }

        val serviceIntent = android.content.Intent(context, FloatingHUDService::class.java)
        return if (FloatingHUDService.isRunning) {
            context.stopService(serviceIntent)
            false
        } else {
            context.startService(serviceIntent)
            true
        }
    }
}
