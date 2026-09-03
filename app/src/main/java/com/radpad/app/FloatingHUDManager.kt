package com.radpad.app

import java.util.concurrent.CopyOnWriteArrayList

/**
 * FloatingHUDManager: Shared communication hub linking ControllerIME, MainActivity,
 * and the always-on FloatingHUDService.
 */
object FloatingHUDManager {

    interface Listener {
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
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

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

    fun register(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
        listener.onStateUpdated(lastX, lastY, lastLayer, isSecondLayer, isShift, isCtrl, isAlt, isCaps, isSuper)
    }

    fun unregister(listener: Listener) {
        listeners.remove(listener)
    }

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
}
