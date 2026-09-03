package com.radpad.app

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputConnection
import kotlin.math.atan2

/**
 * Bare-metal bridge to the zero-latency Zig radial input engine and standalone Kotlin state machine.
 *
 * ## Architecture & Design
 * 1. **Radial Geometry Model**:
 *    - Angles are calculated relative to North (0 radians = -Y, 12 o'clock).
 *    - 8 cardinal and diagonal sectors (0..7): North (0), NorthEast (1), East (2), SouthEast (3),
 *      South (4), SouthWest (5), West (6), NorthWest (7).
 *    - Supports **Symmetric** (8 equal 45° sectors) and **Asymmetric** (wide 60° cardinals, narrow 30° diagonals) modes.
 *
 * 2. **Deadzone Hysteresis Model**:
 *    - Prevents boundary jitter and accidental inputs using dual concentric engagement thresholds:
 *      - Engagement radius: [DEADZONE_ENGAGE] (0.42 radial deflection) to enter a sector.
 *      - Release radius: [DEADZONE_RELEASE] (0.25 radial deflection) to drop back into center deadzone.
 *    - Same hysteresis principle applies to analog trigger pressure and left-stick modifier tilts.
 *
 * 3. **Modifier Bitmask Model**:
 *    - Active modifiers (Shift, Ctrl, Alt, Super/Win, Caps Lock, R2 Hold) accumulate into [currentButtonMask].
 *    - The combined bitmask is piped into the Zig engine or the Kotlin fallback decoder.
 *
 * 4. **Bare-Metal Zig Bridge with Kotlin Fallback**:
 *    - Loads `libzigengine.so` at runtime.
 *    - If unavailable or running on unsupported platforms/unit tests, all state management and decoding
 *      gracefully fallback to pure-Kotlin implementations with zero latency degradation.
 */
class InputEngine {

    /**
     * Radial input layers mapping to different character and utility sets.
     *
     * @property id Unique numeric identifier matching native Zig engine constants.
     * @property displayName Short human-readable tag displayed on HUD overlays.
     */
    enum class Layer(val id: Int, val displayName: String) {
        BASE(0, "BASE"),
        A_H(1, "A-H"),
        MORE_SYM(2, "SYM"),
        I_P(3, "I-P"),
        FN(4, "FN"),
        Q_Z(5, "Q-Z"),
        MACRO(6, "MACRO"),
        NUM_SYM(7, "NUM"),
        SYS(8, "SYS");

        companion object {
            /**
             * Resolves a [Layer] by its numeric ID, defaulting to [BASE].
             */
            fun fromId(id: Int): Layer = entries.firstOrNull { it.id == id } ?: BASE
        }
    }

    companion object {
        /**
         * Indicates whether the high-performance Zig shared library (`libzigengine.so`) was successfully loaded.
         */
        var isNativeLoaded: Boolean = false
            private set

        init {
            try {
                System.loadLibrary("zigengine")
                isNativeLoaded = true
            } catch (_: UnsatisfiedLinkError) {
                isNativeLoaded = false
            }
        }

        // --- Grouped Constant Objects ---

        /** Controller button and trigger state flags passed to JNI. */
        object Flags {
            const val R2_HOLD: Int = 1 shl 1      // Right Trigger (R2) -> Mouse Layer Hold
            const val SHIFT: Int = 1 shl 2        // Shift (Left Trigger L2): Hold to uppercase
            const val CTRL: Int = 1 shl 3         // Ctrl (Left Stick Down / Tilt)
            const val ALT: Int = 1 shl 4          // Alt (Left Stick Left / Tilt)
            const val CAPS_LOCK: Int = 1 shl 5    // Caps Lock (toggled via L3 click)
            const val SUPER: Int = 1 shl 7        // Super / Windows Key (Left Stick Right / Tilt)
        }

        /** Special Key Codes matching `main.zig` (0xF001 .. 0xF027). */
        object SpecialKeys {
            const val KEY_F1: Int = 0xF001
            const val KEY_F2: Int = 0xF002
            const val KEY_F3: Int = 0xF003
            const val KEY_F4: Int = 0xF004
            const val KEY_F5: Int = 0xF005
            const val KEY_F6: Int = 0xF006
            const val KEY_F7: Int = 0xF007
            const val KEY_F8: Int = 0xF008
            const val KEY_F9: Int = 0xF009
            const val KEY_F10: Int = 0xF00A
            const val KEY_F11: Int = 0xF00B
            const val KEY_F12: Int = 0xF00C
            const val KEY_HOME: Int = 0xF00D
            const val KEY_END: Int = 0xF00E
            const val KEY_PGUP: Int = 0xF00F
            const val KEY_PGDN: Int = 0xF010
            const val KEY_SUPER: Int = 0xF011
            const val KEY_DELETE: Int = 0xF012
            const val KEY_INSERT: Int = 0xF013
            const val KEY_VOL_UP: Int = 0xF014
            const val KEY_VOL_DOWN: Int = 0xF015
            const val KEY_VOL_MUTE: Int = 0xF016

            // Macro keys (0xF020 .. 0xF027)
            const val KEY_MACRO_0: Int = 0xF020
            const val KEY_MACRO_1: Int = 0xF021
            const val KEY_MACRO_2: Int = 0xF022
            const val KEY_MACRO_3: Int = 0xF023
            const val KEY_MACRO_4: Int = 0xF024
            const val KEY_MACRO_5: Int = 0xF025
            const val KEY_MACRO_6: Int = 0xF026
            const val KEY_MACRO_7: Int = 0xF027
        }

        /** Output bitmasks for decoding packed event values. */
        object OutputMasks {
            const val CHAR_MASK: Int = 0xFFFF
            const val MOD_SHIFT: Int = 1 shl 16
            const val MOD_CTRL: Int = 1 shl 17
            const val MOD_ALT: Int = 1 shl 18
            const val MOD_SUPER: Int = 1 shl 20

            const val LAYER_EVENT_MASK: Int = 0xE000
            const val PAGE_TOGGLE_EVENT_MASK: Int = 0xD000
        }

        /** Analog stick deadzone radii and squared distance limits. */
        object Deadzones {
            const val ENGAGE: Float = 0.42f
            const val ENGAGE_SQ: Float = ENGAGE * ENGAGE
            const val RELEASE: Float = 0.25f
            const val RELEASE_SQ: Float = RELEASE * RELEASE
        }

        /** Left-stick modifier and analog trigger activation thresholds. */
        object Thresholds {
            const val LEFT_STICK_ENGAGE: Float = 0.35f
            const val LEFT_STICK_RELEASE: Float = 0.20f
            const val TRIGGER_ENGAGEMENT_THRESHOLD: Float = 0.5f
            const val TRIGGER_RELEASE_THRESHOLD: Float = 0.25f
        }

        // --- Backward-Compatible Top-Level Companion Constants ---
        const val FLAG_R2_HOLD: Int = Flags.R2_HOLD
        const val FLAG_SHIFT: Int = Flags.SHIFT
        const val FLAG_CTRL: Int = Flags.CTRL
        const val FLAG_ALT: Int = Flags.ALT
        const val FLAG_CAPS_LOCK: Int = Flags.CAPS_LOCK
        const val FLAG_SUPER: Int = Flags.SUPER

        const val KEY_F1: Int = SpecialKeys.KEY_F1
        const val KEY_F2: Int = SpecialKeys.KEY_F2
        const val KEY_F3: Int = SpecialKeys.KEY_F3
        const val KEY_F4: Int = SpecialKeys.KEY_F4
        const val KEY_F5: Int = SpecialKeys.KEY_F5
        const val KEY_F6: Int = SpecialKeys.KEY_F6
        const val KEY_F7: Int = SpecialKeys.KEY_F7
        const val KEY_F8: Int = SpecialKeys.KEY_F8
        const val KEY_F9: Int = SpecialKeys.KEY_F9
        const val KEY_F10: Int = SpecialKeys.KEY_F10
        const val KEY_F11: Int = SpecialKeys.KEY_F11
        const val KEY_F12: Int = SpecialKeys.KEY_F12
        const val KEY_HOME: Int = SpecialKeys.KEY_HOME
        const val KEY_END: Int = SpecialKeys.KEY_END
        const val KEY_PGUP: Int = SpecialKeys.KEY_PGUP
        const val KEY_PGDN: Int = SpecialKeys.KEY_PGDN
        const val KEY_SUPER: Int = SpecialKeys.KEY_SUPER
        const val KEY_DELETE: Int = SpecialKeys.KEY_DELETE
        const val KEY_INSERT: Int = SpecialKeys.KEY_INSERT
        const val KEY_VOL_UP: Int = SpecialKeys.KEY_VOL_UP
        const val KEY_VOL_DOWN: Int = SpecialKeys.KEY_VOL_DOWN
        const val KEY_VOL_MUTE: Int = SpecialKeys.KEY_VOL_MUTE

        const val KEY_MACRO_0: Int = SpecialKeys.KEY_MACRO_0
        const val KEY_MACRO_1: Int = SpecialKeys.KEY_MACRO_1
        const val KEY_MACRO_2: Int = SpecialKeys.KEY_MACRO_2
        const val KEY_MACRO_3: Int = SpecialKeys.KEY_MACRO_3
        const val KEY_MACRO_4: Int = SpecialKeys.KEY_MACRO_4
        const val KEY_MACRO_5: Int = SpecialKeys.KEY_MACRO_5
        const val KEY_MACRO_6: Int = SpecialKeys.KEY_MACRO_6
        const val KEY_MACRO_7: Int = SpecialKeys.KEY_MACRO_7

        const val LAYER_EVENT_MASK: Int = OutputMasks.LAYER_EVENT_MASK
        const val PAGE_TOGGLE_EVENT_MASK: Int = OutputMasks.PAGE_TOGGLE_EVENT_MASK

        const val CHAR_MASK: Int = OutputMasks.CHAR_MASK
        const val MOD_SHIFT: Int = OutputMasks.MOD_SHIFT
        const val MOD_CTRL: Int = OutputMasks.MOD_CTRL
        const val MOD_ALT: Int = OutputMasks.MOD_ALT
        const val MOD_SUPER: Int = OutputMasks.MOD_SUPER

        const val TRIGGER_ENGAGEMENT_THRESHOLD: Float = Thresholds.TRIGGER_ENGAGEMENT_THRESHOLD
        const val TRIGGER_RELEASE_THRESHOLD: Float = Thresholds.TRIGGER_RELEASE_THRESHOLD
        const val LEFT_STICK_ENGAGE: Float = Thresholds.LEFT_STICK_ENGAGE
        const val LEFT_STICK_RELEASE: Float = Thresholds.LEFT_STICK_RELEASE

        const val DEADZONE_ENGAGE: Float = Deadzones.ENGAGE
        const val DEADZONE_ENGAGE_SQ: Float = Deadzones.ENGAGE_SQ
        const val DEADZONE_RELEASE: Float = Deadzones.RELEASE
        const val DEADZONE_RELEASE_SQ: Float = Deadzones.RELEASE_SQ

        val LAYOUT_A_H = arrayOf('a', 'b', 'c', 'd', 'e', 'f', 'g', 'h')
        val LAYOUT_MORE_SYM_1 = arrayOf('\'', '=', '.', ';', '\\', '/', ',', '-')
        val LAYOUT_MORE_SYM_2 = arrayOf('`', '\u0000', ']', '\u0000', '\u0000', '\u0000', '[', '\u0000')
        val LAYOUT_I_P = arrayOf('i', 'j', 'k', 'l', 'm', 'n', 'o', 'p')
        val LAYOUT_FN_1_8 = arrayOf(KEY_F1, KEY_F2, KEY_F3, KEY_F4, KEY_F5, KEY_F6, KEY_F7, KEY_F8)
        val LAYOUT_FN_9_12 = arrayOf(KEY_F9, KEY_F10, KEY_F11, KEY_F12, 0, 0, 0, 0)
        val LAYOUT_Q_Z_1 = arrayOf('q', 'r', 's', 't', 'u', 'v', 'w', 'x')
        val LAYOUT_Q_Z_2 = arrayOf('y', 'z', '\u0000', '\u0000', '\u0000', '\u0000', '\u0000', '\u0000')
        val LAYOUT_MACRO = arrayOf(
            KEY_MACRO_0, KEY_MACRO_1, KEY_MACRO_2, KEY_MACRO_3,
            KEY_MACRO_4, KEY_MACRO_5, KEY_MACRO_6, KEY_MACRO_7
        )
        val LAYOUT_NUM_1_8 = arrayOf('1', '2', '3', '4', '5', '6', '7', '8')
        val LAYOUT_NUM_9_0 = arrayOf('9', '0', '\u0000', '\u0000', '\u0000', '\u0000', '\u0000', '\u0000')
        val LAYOUT_SYS = arrayOf(
            KEY_PGUP, KEY_VOL_UP, KEY_END, KEY_DELETE, KEY_PGDN, KEY_INSERT, KEY_HOME, KEY_VOL_DOWN
        )

        /**
         * Returns the shifted symbol character corresponding to standard US QWERTY keyboard symbols.
         */
        @JvmStatic
        fun shiftSymbol(c: Char): Char {
            return when (c) {
                '1' -> '!'
                '2' -> '@'
                '3' -> '#'
                '4' -> '$'
                '5' -> '%'
                '6' -> '^'
                '7' -> '&'
                '8' -> '*'
                '9' -> '('
                '0' -> ')'
                '-' -> '_'
                '=' -> '+'
                '[' -> '{'
                ']' -> '}'
                '\\' -> '|'
                ';' -> ':'
                '\'' -> '"'
                ',' -> '<'
                '.' -> '>'
                '/' -> '?'
                '`' -> '~'
                else -> c
            }
        }

        /**
         * Reads an analog trigger axis value with graceful fallback for gamepads reporting
         * triggers as alternate axes (e.g. BRAKE/GAS vs LTRIGGER/RTRIGGER).
         */
        @JvmStatic
        fun readTriggerAxis(event: MotionEvent, primaryAxis: Int, fallbackAxis: Int): Float {
            val dev = event.device
            return if (dev?.getMotionRange(primaryAxis) != null) {
                event.getAxisValue(primaryAxis)
            } else if (dev?.getMotionRange(fallbackAxis) != null) {
                event.getAxisValue(fallbackAxis)
            } else {
                event.getAxisValue(primaryAxis)
            }
        }

        /**
         * Resolves left analog stick (X, Y) deflection from standard AXIS_X and AXIS_Y.
         */
        @JvmStatic
        fun readLeftStick(event: MotionEvent): Pair<Float, Float> {
            val lx = event.getAxisValue(MotionEvent.AXIS_X)
            val ly = event.getAxisValue(MotionEvent.AXIS_Y)
            return Pair(lx, ly)
        }

        /**
         * Resolves right analog stick (X, Y) deflection, supporting controllers that map
         * the right stick to Z/RZ or RX/RY.
         */
        @JvmStatic
        fun readRightStick(event: MotionEvent): Pair<Float, Float> {
            val rawZ = event.getAxisValue(MotionEvent.AXIS_Z)
            val rawRZ = event.getAxisValue(MotionEvent.AXIS_RZ)
            val rawRX = event.getAxisValue(MotionEvent.AXIS_RX)
            val rawRY = event.getAxisValue(MotionEvent.AXIS_RY)
            val rx = if (rawZ != 0f || rawRZ != 0f) rawZ else rawRX
            val ry = if (rawZ != 0f || rawRZ != 0f) rawRZ else rawRY
            return Pair(rx, ry)
        }

        /**
         * Evaluates a hysteresis threshold check.
         *
         * When [inverted] is false:
         *   If currently active, requires `value >= release` to stay active.
         *   If currently inactive, requires `value >= engage` to engage.
         *
         * When [inverted] is true (for negative stick deflection):
         *   If currently active, requires `value <= -release` to stay active.
         *   If currently inactive, requires `value <= -engage` to engage.
         */
        @JvmStatic
        fun hysteresis(
            active: Boolean,
            value: Float,
            engage: Float,
            release: Float,
            inverted: Boolean = false
        ): Boolean {
            return if (inverted) {
                if (active) value <= -release else value <= -engage
            } else {
                if (active) value >= release else value >= engage
            }
        }

        /**
         * Decodes a raw integer emitted by the native Zig engine or Kotlin fallback into
         * a structured [ProcessedEvent].
         *
         * Returns `null` if the raw event represents an internal layer navigation or page toggle.
         */
        @JvmStatic
        fun decode(rawValue: Int): ProcessedEvent? {
            if (rawValue == 0) return null
            if ((rawValue and 0xF000) == LAYER_EVENT_MASK) return null
            if ((rawValue and 0xF000) == PAGE_TOGGLE_EVENT_MASK) return null
            val code = rawValue and CHAR_MASK
            return ProcessedEvent(
                rawValue = rawValue,
                charCode = code,
                char = code.toChar(),
                isShift = (rawValue and MOD_SHIFT) != 0,
                isCtrl = (rawValue and MOD_CTRL) != 0,
                isAlt = (rawValue and MOD_ALT) != 0,
                isSuper = (rawValue and MOD_SUPER) != 0
            )
        }
    }

    // Bare-metal JNI methods
    external fun processInput(x: Float, y: Float, buttonMask: Int): Int
    external fun select(stateFlags: Int): Int
    external fun goBack(): Int
    external fun backToBase()
    external fun getCurrentLayer(): Int
    external fun setCurrentLayer(layerId: Int)
    external fun isSecondPage(): Boolean
    external fun setSecondPage(secondPage: Boolean)
    external fun toggleSecondPage(): Boolean
    external fun resetState()
    external fun setSymmetricSlices(symmetric: Boolean)

    // Current bitmask accumulated from controller button & trigger events
    var currentButtonMask: Int = 0

    private var fallbackLayer: Layer = Layer.BASE

    var currentLayer: Layer
        get() {
            if (isNativeLoaded) {
                try {
                    return Layer.fromId(getCurrentLayer())
                } catch (_: UnsatisfiedLinkError) {}
            }
            return fallbackLayer
        }
        set(value) {
            fallbackLayer = value
            if (isNativeLoaded) {
                try {
                    setCurrentLayer(value.id)
                } catch (_: UnsatisfiedLinkError) {}
            }
        }

    private var fallbackSecondPage: Boolean = false

    var isSecondLayerActive: Boolean
        get() {
            if (isNativeLoaded) {
                try {
                    return isSecondPage()
                } catch (_: UnsatisfiedLinkError) {}
            }
            return fallbackSecondPage
        }
        set(value) {
            fallbackSecondPage = value
            if (isNativeLoaded) {
                try {
                    setSecondPage(value)
                } catch (_: UnsatisfiedLinkError) {}
            }
        }

    val isMouseLayerActive: Boolean
        get() = isR2ButtonDown || r2TriggerActive

    var lastStickX: Float = 0f
        private set
    var lastStickY: Float = 0f
        private set

    var aimedSlice: Int = 0
        private set
    var isDeflected: Boolean = false
        private set

    // Tracking for Left Stick flick-release tap for standalone Super (Windows) key
    private var leftStickSuperEngaged: Boolean = false
    private var hasTypedDuringSuperEngaged: Boolean = false

    // Hardware button states for triggers and bumpers
    private var isL2ButtonDown: Boolean = false
    private var isR2ButtonDown: Boolean = false
    private var isL1ButtonDown: Boolean = false

    var lastLeftStickX: Float = 0f
        private set
    var lastLeftStickY: Float = 0f
        private set

    // Left Stick modifier states with hysteresis
    private var leftStickShiftActive: Boolean = false
    private var leftStickCtrlActive: Boolean = false
    private var leftStickAltActive: Boolean = false
    private var leftStickSuperActive: Boolean = false

    val isLeftStickShiftActive: Boolean get() = leftStickShiftActive
    val isLeftStickCtrlActive: Boolean get() = leftStickCtrlActive
    val isLeftStickAltActive: Boolean get() = leftStickAltActive
    val isLeftStickSuperActive: Boolean get() = leftStickSuperActive

    val isShiftActive: Boolean get() = (currentButtonMask and FLAG_SHIFT) != 0
    val isCtrlActive: Boolean get() = (currentButtonMask and FLAG_CTRL) != 0
    val isAltActive: Boolean get() = (currentButtonMask and FLAG_ALT) != 0
    val isSuperActive: Boolean get() = (currentButtonMask and FLAG_SUPER) != 0

    // Analog triggers
    private var l2TriggerActive: Boolean = false
    private var r2TriggerActive: Boolean = false

    var useSymmetricSlices: Boolean = true
        set(value) {
            field = value
            if (isNativeLoaded) {
                try {
                    setSymmetricSlices(value)
                } catch (_: UnsatisfiedLinkError) {}
            }
        }

    /**
     * Represents a fully resolved and decoded input action ready for system dispatch.
     *
     * @property rawValue The raw 32-bit packed representation from native Zig or fallback engine.
     * @property charCode The isolated character code (Unicode or SpecialKey constant).
     * @property char The printable char representation (or 0 for non-printable keys).
     * @property isShift Whether Shift modifier is active.
     * @property isCtrl Whether Ctrl modifier is active.
     * @property isAlt Whether Alt modifier is active.
     * @property isSuper Whether Super / Windows key modifier is active.
     */
    data class ProcessedEvent(
        val rawValue: Int,
        val charCode: Int,
        val char: Char,
        val isShift: Boolean = false,
        val isCtrl: Boolean = false,
        val isAlt: Boolean = false,
        val isSuper: Boolean = false
    )

    /**
     * Calculates the sector index (0..7) based on analog stick (X, Y) coordinates.
     *
     * Angles are measured clockwise from North (12 o'clock, 0°).
     *
     * In [useSymmetricSlices] mode:
     * - Each sector spans exactly 45° (North: 337.5° to 22.5°).
     *
     * In asymmetric mode:
     * - Cardinals (North, East, South, West) span 60°.
     * - Diagonals span 30°.
     */
    fun calculateSlice(x: Float, y: Float): Int {
        val rad = atan2(x.toDouble(), -y.toDouble())
        var deg = Math.toDegrees(rad).toFloat()
        if (deg < 0f) deg += 360f

        return if (useSymmetricSlices) {
            when {
                deg !in 22.5f..<337.5f -> 0
                deg < 67.5f -> 1
                deg < 112.5f -> 2
                deg < 157.5f -> 3
                deg < 202.5f -> 4
                deg < 247.5f -> 5
                deg < 292.5f -> 6
                else -> 7
            }
        } else {
            when {
                deg !in 30f..<330f -> 0
                deg < 60f -> 1
                deg < 120f -> 2
                deg < 150f -> 3
                deg < 210f -> 4
                deg < 240f -> 5
                deg < 300f -> 6
                else -> 7
            }
        }
    }

    /**
     * Goes back one level in the layer hierarchy (Left Bumper L1):
     * Page 2 -> Page 1 -> Base.
     */
    fun goBackLayer(): ProcessedEvent? {
        if (isNativeLoaded) {
            try {
                val raw = goBack()
                return decode(raw)
            } catch (_: UnsatisfiedLinkError) {}
        }
        if (isSecondLayerActive) {
            isSecondLayerActive = false
        } else if (currentLayer != Layer.BASE) {
            backToBaseLayer()
        }
        return null
    }

    /**
     * Immediately returns to the [Layer.BASE] layer and clears second page state.
     */
    fun backToBaseLayer() {
        currentLayer = Layer.BASE
        isSecondLayerActive = false
        if (isNativeLoaded) {
            try { backToBase() } catch (_: UnsatisfiedLinkError) {}
        }
    }

    /**
     * Toggles between secondary and primary sublayer pages.
     */
    fun toggleSecondLayer(): Boolean {
        if (isNativeLoaded) {
            try {
                val newSecond = toggleSecondPage()
                isSecondLayerActive = newSecond
                return newSecond
            } catch (_: UnsatisfiedLinkError) {}
        }
        isSecondLayerActive = !isSecondLayerActive
        return isSecondLayerActive
    }

    /**
     * Explicitly forces active layer to [layer] and resets second page state.
     */
    fun setLayer(layer: Layer) {
        currentLayer = layer
        isSecondLayerActive = false
    }

    /**
     * Completely resets all internal engine states, returning to [Layer.BASE].
     */
    fun resetAll() {
        backToBaseLayer()
        isDeflected = false
        aimedSlice = 0
        resetTracking()
        if (isNativeLoaded) {
            try {
                resetState()
            } catch (_: UnsatisfiedLinkError) {}
        }
    }

    /**
     * Performs selection (invoked when Right Bumper R1 is pressed).
     * On BASE: transitions to targeted layer.
     * In Layer:
     *   - Center deadzone: Vol Mute in SYS, Page toggle in Q-Z, SYM, NUM, FN.
     *   - Slices: emits targeted character/macro.
     */
    fun onSelect(): ProcessedEvent? {
        hasTypedDuringSuperEngaged = true

        if (isNativeLoaded) {
            try {
                val rawResult = select(currentButtonMask)
                return decode(rawResult)
            } catch (_: UnsatisfiedLinkError) {}
        }

        // Pure Kotlin fallback
        if (currentLayer == Layer.BASE) {
            if (!isDeflected) return null
            currentLayer = when (aimedSlice) {
                0 -> Layer.A_H
                1 -> Layer.MORE_SYM
                2 -> Layer.I_P
                3 -> Layer.FN
                4 -> Layer.Q_Z
                5 -> Layer.MACRO
                6 -> Layer.NUM_SYM
                7 -> Layer.SYS
                else -> Layer.BASE
            }
            isSecondLayerActive = false
            return null
        }

        // Inside active layer:
        if (!isDeflected) {
            if (currentLayer == Layer.SYS) {
                return ProcessedEvent(
                    rawValue = KEY_VOL_MUTE,
                    charCode = KEY_VOL_MUTE,
                    char = 0.toChar()
                )
            }
            if (currentLayer in listOf(Layer.Q_Z, Layer.MORE_SYM, Layer.NUM_SYM, Layer.FN)) {
                toggleSecondLayer()
                return null
            }
            return null
        }

        val isSecond = isSecondLayerActive
        val isShift = ((currentButtonMask and FLAG_SHIFT) != 0) xor ((currentButtonMask and FLAG_CAPS_LOCK) != 0)
        val isCtrl = (currentButtonMask and FLAG_CTRL) != 0
        val isAlt = (currentButtonMask and FLAG_ALT) != 0
        val isSuper = (currentButtonMask and FLAG_SUPER) != 0

        var charCode = when (currentLayer) {
            Layer.A_H -> LAYOUT_A_H[aimedSlice].code
            Layer.I_P -> LAYOUT_I_P[aimedSlice].code
            Layer.Q_Z -> (if (isSecond) LAYOUT_Q_Z_2[aimedSlice] else LAYOUT_Q_Z_1[aimedSlice]).code
            Layer.MACRO -> LAYOUT_MACRO[aimedSlice]
            Layer.MORE_SYM -> (if (isSecond) LAYOUT_MORE_SYM_2[aimedSlice] else LAYOUT_MORE_SYM_1[aimedSlice]).code
            Layer.NUM_SYM -> (if (isSecond) LAYOUT_NUM_9_0[aimedSlice] else LAYOUT_NUM_1_8[aimedSlice]).code
            Layer.FN -> if (isSecond) LAYOUT_FN_9_12[aimedSlice] else LAYOUT_FN_1_8[aimedSlice]
            Layer.SYS -> LAYOUT_SYS[aimedSlice]
            Layer.BASE -> 0
        }

        if (charCode == 0) return null

        if (currentLayer == Layer.MACRO) {
            return ProcessedEvent(charCode, charCode, charCode.toChar())
        }

        var charVal = charCode.toChar()
        if (isShift) {
            if (charVal in 'a'..'z') {
                charVal = charVal.uppercaseChar()
                charCode = charVal.code
            } else {
                charVal = shiftSymbol(charVal)
                charCode = charVal.code
            }
        }

        var raw = charCode
        if (isShift) raw = raw or MOD_SHIFT
        if (isCtrl) raw = raw or MOD_CTRL
        if (isAlt) raw = raw or MOD_ALT
        if (isSuper) raw = raw or MOD_SUPER

        return ProcessedEvent(
            rawValue = raw,
            charCode = charCode,
            char = charVal,
            isShift = isShift,
            isCtrl = isCtrl,
            isAlt = isAlt,
            isSuper = isSuper
        )
    }

    /**
     * Process analog right stick motion.
     */
    fun onMotion(x: Float, y: Float): ProcessedEvent? {
        lastStickX = x
        lastStickY = y

        val rSq = (x * x) + (y * y)
        if (isDeflected) {
            if (rSq < DEADZONE_RELEASE_SQ) {
                isDeflected = false
            } else {
                aimedSlice = calculateSlice(x, y)
            }
        } else {
            if (rSq >= DEADZONE_ENGAGE_SQ) {
                isDeflected = true
                aimedSlice = calculateSlice(x, y)
            }
        }

        if (isNativeLoaded) {
            try {
                val rawResult = processInput(x, y, currentButtonMask)
                return decode(rawResult)
            } catch (_: UnsatisfiedLinkError) {}
        }

        return null
    }

    /**
     * Evaluates Left Stick deflection for modifier keys and flick-release standalone Super key.
     *
     * - Tilt Up: Shift
     * - Tilt Down: Ctrl
     * - Tilt Left: Alt
     * - Tilt Right: Super (Windows)
     * - Flick Right & Release: Standalone Super (Windows) key
     *
     * @param lx Left stick horizontal axis (-1.0f .. 1.0f).
     * @param ly Left stick vertical axis (-1.0f .. 1.0f).
     * @return Standalone [ProcessedEvent] if a flick-and-release Super gesture completed, null otherwise.
     */
    fun onLeftStickMotion(lx: Float, ly: Float): ProcessedEvent? {
        lastLeftStickX = lx
        lastLeftStickY = ly

        val prevShift = leftStickShiftActive
        val prevCtrl = leftStickCtrlActive
        val prevAlt = leftStickAltActive
        val prevSuper = leftStickSuperActive

        leftStickShiftActive = hysteresis(leftStickShiftActive, ly, LEFT_STICK_ENGAGE, LEFT_STICK_RELEASE, inverted = true)
        leftStickCtrlActive = hysteresis(leftStickCtrlActive, ly, LEFT_STICK_ENGAGE, LEFT_STICK_RELEASE)
        leftStickAltActive = hysteresis(leftStickAltActive, lx, LEFT_STICK_ENGAGE, LEFT_STICK_RELEASE, inverted = true)
        leftStickSuperActive = hysteresis(leftStickSuperActive, lx, LEFT_STICK_ENGAGE, LEFT_STICK_RELEASE)

        // When Super engages on a new tilt, clear the typing flag so flick-release can trigger
        if (!prevSuper && leftStickSuperActive) {
            leftStickSuperEngaged = true
            hasTypedDuringSuperEngaged = false
        }

        val shiftActive = isL2ButtonDown || l2TriggerActive || leftStickShiftActive
        currentButtonMask = if (shiftActive) currentButtonMask or FLAG_SHIFT else currentButtonMask and FLAG_SHIFT.inv()
        currentButtonMask = if (leftStickCtrlActive) currentButtonMask or FLAG_CTRL else currentButtonMask and FLAG_CTRL.inv()
        currentButtonMask = if (leftStickAltActive) currentButtonMask or FLAG_ALT else currentButtonMask and FLAG_ALT.inv()
        currentButtonMask = if (leftStickSuperActive) currentButtonMask or FLAG_SUPER else currentButtonMask and FLAG_SUPER.inv()

        val changed = (prevShift != leftStickShiftActive) ||
                (prevCtrl != leftStickCtrlActive) ||
                (prevAlt != leftStickAltActive) ||
                (prevSuper != leftStickSuperActive)

        if (changed && isNativeLoaded) {
            try { processInput(lastStickX, lastStickY, currentButtonMask) } catch (_: UnsatisfiedLinkError) {}
        }

        // Check for flick-and-release tap on Left Stick for standalone Super (Windows) key
        var standaloneSuperEvent: ProcessedEvent? = null
        if (!leftStickSuperActive && leftStickSuperEngaged) {
            val lRsq = (lx * lx) + (ly * ly)
            if (lRsq < LEFT_STICK_RELEASE * LEFT_STICK_RELEASE) {
                if (!hasTypedDuringSuperEngaged) {
                    standaloneSuperEvent = ProcessedEvent(
                        rawValue = KEY_SUPER or MOD_SUPER,
                        charCode = KEY_SUPER,
                        char = 0.toChar(),
                        isShift = false,
                        isCtrl = false,
                        isAlt = false,
                        isSuper = true
                    )
                }
                leftStickSuperEngaged = false
                hasTypedDuringSuperEngaged = false
            }
        }

        return standaloneSuperEvent
    }

    /**
     * Handles gamepad analog stick movements and analog trigger pressures.
     * Motion alone NEVER emits a character.
     */
    fun onMotionEvent(event: MotionEvent): ProcessedEvent? {
        // 1. Read Left Stick as the Modifier & Utility Joystick:
        val (lx, ly) = readLeftStick(event)
        val standaloneSuperEvent = onLeftStickMotion(lx, ly)

        // 2. Analog triggers: Left Trigger (L2) is Shift, Right Trigger (R2) is Mouse Layer
        val l2Pressure = readTriggerAxis(event, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE)
        val r2Pressure = readTriggerAxis(event, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)

        l2TriggerActive = hysteresis(l2TriggerActive, l2Pressure, TRIGGER_ENGAGEMENT_THRESHOLD, TRIGGER_RELEASE_THRESHOLD)
        r2TriggerActive = hysteresis(r2TriggerActive, r2Pressure, TRIGGER_ENGAGEMENT_THRESHOLD, TRIGGER_RELEASE_THRESHOLD)

        val shiftActive = isL2ButtonDown || l2TriggerActive || leftStickShiftActive
        currentButtonMask = if (shiftActive) currentButtonMask or FLAG_SHIFT else currentButtonMask and FLAG_SHIFT.inv()

        val r2Active = isR2ButtonDown || r2TriggerActive
        currentButtonMask = if (r2Active) currentButtonMask or FLAG_R2_HOLD else currentButtonMask and FLAG_R2_HOLD.inv()

        // 3. Read Right Stick (Radial Dial):
        val (rx, ry) = readRightStick(event)

        lastStickX = rx
        lastStickY = ry

        val rSq = (rx * rx) + (ry * ry)
        if (isDeflected) {
            if (rSq < DEADZONE_RELEASE_SQ) {
                isDeflected = false
            } else {
                aimedSlice = calculateSlice(rx, ry)
            }
        } else {
            if (rSq >= DEADZONE_ENGAGE_SQ) {
                isDeflected = true
                aimedSlice = calculateSlice(rx, ry)
            }
        }

        if (isNativeLoaded) {
            try {
                processInput(rx, ry, currentButtonMask)
            } catch (_: UnsatisfiedLinkError) {}
        }

        return standaloneSuperEvent
    }

    /**
     * Handles hardware button presses that control modifiers and layer traversal.
     *
     * - [KeyEvent.KEYCODE_BUTTON_THUMBL] (L3): Toggles Caps Lock.
     * - [KeyEvent.KEYCODE_BUTTON_L1] (L1): Returns to previous layer tier or Base.
     * - [KeyEvent.KEYCODE_BUTTON_R2] (R2): Engages Mouse Layer hold.
     * - [KeyEvent.KEYCODE_BUTTON_L2] (L2): Engages Shift hold.
     *
     * @return `true` if the event was intercepted and consumed by the engine.
     */
    fun onKeyEvent(keyCode: Int, isDown: Boolean): Boolean {
        if (isDown) {
            hasTypedDuringSuperEngaged = true
        }

        // L3: Toggle Caps Lock
        if (keyCode == KeyEvent.KEYCODE_BUTTON_THUMBL) {
            if (isDown) {
                val isCaps = (currentButtonMask and FLAG_CAPS_LOCK) != 0
                currentButtonMask = if (!isCaps) currentButtonMask or FLAG_CAPS_LOCK else currentButtonMask and FLAG_CAPS_LOCK.inv()
                if (isNativeLoaded) {
                    try { processInput(lastStickX, lastStickY, currentButtonMask) } catch (_: UnsatisfiedLinkError) {}
                }
            }
            return true
        }

        // L1: Go back one layer level
        if (keyCode == KeyEvent.KEYCODE_BUTTON_L1) {
            isL1ButtonDown = isDown
            if (isDown) {
                goBackLayer()
            }
            return true
        }

        // R2: Hold for Mouse Layer
        if (keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            isR2ButtonDown = isDown
            if (!isDown) {
                r2TriggerActive = false
            }
            val r2Active = isR2ButtonDown
            currentButtonMask = if (r2Active) currentButtonMask or FLAG_R2_HOLD else currentButtonMask and FLAG_R2_HOLD.inv()
            if (isNativeLoaded) {
                try { processInput(lastStickX, lastStickY, currentButtonMask) } catch (_: UnsatisfiedLinkError) {}
            }
            return true
        }

        // L2: Shift
        if (keyCode == KeyEvent.KEYCODE_BUTTON_L2) {
            isL2ButtonDown = isDown
            val shiftActive = isL2ButtonDown || l2TriggerActive || leftStickShiftActive
            currentButtonMask = if (shiftActive) currentButtonMask or FLAG_SHIFT else currentButtonMask and FLAG_SHIFT.inv()
            if (isNativeLoaded) {
                try { processInput(lastStickX, lastStickY, currentButtonMask) } catch (_: UnsatisfiedLinkError) {}
            }
            return true
        }

        return false
    }

    /**
     * Dispatches a resolved [ProcessedEvent] to the system input method framework via [ic].
     *
     * Correctly formats:
     * - Standalone Super / Windows Key clicks.
     * - Win+key combo shortcuts.
     * - Alt/Meta VT100 ANSI sequences.
     * - Ctrl shortcuts (Copy, Cut, Paste, Undo, Select All) and ASCII control characters.
     * - Function keys (F1..F12), navigation keys (Home, End, PgUp, PgDn, Insert), and Volume controls.
     * - Custom Macro slots (0..7) via [MacroManager].
     * - Standard Unicode characters.
     */
    fun dispatchEvent(ic: InputConnection?, event: ProcessedEvent) {
        if (ic == null) return

        when {
            // Standalone Super key
            event.charCode == KEY_SUPER -> {
                var metaState = KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
                if (event.isShift) metaState = metaState or KeyEvent.META_SHIFT_ON
                if (event.isCtrl) metaState = metaState or KeyEvent.META_CTRL_ON
                if (event.isAlt) metaState = metaState or KeyEvent.META_ALT_ON

                ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_META_LEFT, 0, metaState))
                ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_META_LEFT, 0, metaState))
            }

            // Super modifier combinations
            event.isSuper -> {
                val keyCode = when (event.char.lowercaseChar()) {
                    in 'a'..'z' -> KeyEvent.KEYCODE_A + (event.char.lowercaseChar() - 'a')
                    in '0'..'9' -> KeyEvent.KEYCODE_0 + (event.char - '0')
                    ' ' -> KeyEvent.KEYCODE_SPACE
                    '\t' -> KeyEvent.KEYCODE_TAB
                    '\n' -> KeyEvent.KEYCODE_ENTER
                    '.' -> KeyEvent.KEYCODE_PERIOD
                    ',' -> KeyEvent.KEYCODE_COMMA
                    '/' -> KeyEvent.KEYCODE_SLASH
                    '\\' -> KeyEvent.KEYCODE_BACKSLASH
                    '-' -> KeyEvent.KEYCODE_MINUS
                    '=' -> KeyEvent.KEYCODE_EQUALS
                    '[' -> KeyEvent.KEYCODE_LEFT_BRACKET
                    ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET
                    ';' -> KeyEvent.KEYCODE_SEMICOLON
                    '\'' -> KeyEvent.KEYCODE_APOSTROPHE
                    '`' -> KeyEvent.KEYCODE_GRAVE
                    else -> KeyEvent.KEYCODE_UNKNOWN
                }
                if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                    var metaState = KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
                    if (event.isShift) metaState = metaState or KeyEvent.META_SHIFT_ON
                    if (event.isCtrl) metaState = metaState or KeyEvent.META_CTRL_ON
                    if (event.isAlt) metaState = metaState or KeyEvent.META_ALT_ON
                    ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
                    ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, metaState))
                } else {
                    ic.commitText(event.char.toString(), 1)
                }
            }

            // Alt / Meta modifier: Sends standard Alt+key or VT100 ANSI ESC sequence (\u001b + char)
            event.isAlt -> {
                val keyCode = when (event.char.lowercaseChar()) {
                    in 'a'..'z' -> KeyEvent.KEYCODE_A + (event.char.lowercaseChar() - 'a')
                    in '0'..'9' -> KeyEvent.KEYCODE_0 + (event.char - '0')
                    ' ' -> KeyEvent.KEYCODE_SPACE
                    '\t' -> KeyEvent.KEYCODE_TAB
                    '\n' -> KeyEvent.KEYCODE_ENTER
                    else -> KeyEvent.KEYCODE_UNKNOWN
                }
                if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                    var metaState = KeyEvent.META_ALT_ON
                    if (event.isShift) metaState = metaState or KeyEvent.META_SHIFT_ON
                    if (event.isCtrl) metaState = metaState or KeyEvent.META_CTRL_ON
                    ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
                    ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, metaState))
                } else {
                    ic.commitText("\u001b${event.char}", 1)
                }
            }

            // Ctrl modifier: Supports standard editing shortcuts (A/C/X/V/Z) and meta key events
            event.isCtrl -> {
                val isCharA = event.charCode == 1 || event.char == 'a' || event.char == 'A'
                val isCharC = event.charCode == 3 || event.char == 'c' || event.char == 'C'
                val isCharX = event.charCode == 24 || event.char == 'x' || event.char == 'X'
                val isCharV = event.charCode == 22 || event.char == 'v' || event.char == 'V'
                val isCharZ = event.charCode == 26 || event.char == 'z' || event.char == 'Z'

                when {
                    isCharA -> ic.performContextMenuAction(android.R.id.selectAll)
                    isCharC -> ic.performContextMenuAction(android.R.id.copy)
                    isCharX -> ic.performContextMenuAction(android.R.id.cut)
                    isCharV -> ic.performContextMenuAction(android.R.id.paste)
                    isCharZ -> ic.performContextMenuAction(android.R.id.undo)
                    else -> {
                        val keyCode = when {
                            event.charCode in 1..26 -> KeyEvent.KEYCODE_A + (event.charCode - 1)
                            event.char.lowercaseChar() in 'a'..'z' -> KeyEvent.KEYCODE_A + (event.char.lowercaseChar() - 'a')
                            event.char in '0'..'9' -> KeyEvent.KEYCODE_0 + (event.char - '0')
                            event.char == ' ' -> KeyEvent.KEYCODE_SPACE
                            event.char == '\t' -> KeyEvent.KEYCODE_TAB
                            event.char == '\n' -> KeyEvent.KEYCODE_ENTER
                            else -> KeyEvent.KEYCODE_UNKNOWN
                        }
                        if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                            var metaState = KeyEvent.META_CTRL_ON
                            if (event.isShift) metaState = metaState or KeyEvent.META_SHIFT_ON
                            ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
                            ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, metaState))
                        } else {
                            val ctrlPayload = if (event.char in 'a'..'z') {
                                (event.char.code - 'a'.code + 1).toChar().toString()
                            } else {
                                event.char.toString()
                            }
                            ic.commitText(ctrlPayload, 1)
                        }
                    }
                }
            }

            // Function Keys (F1 through F12)
            event.charCode in KEY_F1..KEY_F12 -> {
                val fNum = event.charCode - KEY_F1 + 1
                val keyCode = KeyEvent.KEYCODE_F1 + (fNum - 1)
                ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, 0))
                ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, 0))
            }

            // Navigation Keys (Home, End, PgUp, PgDn, Insert)
            event.charCode in KEY_HOME..KEY_INSERT -> {
                val keyCode = when (event.charCode) {
                    KEY_HOME -> KeyEvent.KEYCODE_MOVE_HOME
                    KEY_END -> KeyEvent.KEYCODE_MOVE_END
                    KEY_PGUP -> KeyEvent.KEYCODE_PAGE_UP
                    KEY_PGDN -> KeyEvent.KEYCODE_PAGE_DOWN
                    KEY_INSERT -> KeyEvent.KEYCODE_INSERT
                    else -> KeyEvent.KEYCODE_UNKNOWN
                }
                if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                    ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, 0))
                    ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, 0))
                }
            }

            // Volume Controls (Vol+, Vol-, Vol Mute)
            event.charCode in KEY_VOL_UP..KEY_VOL_MUTE -> {
                val keyCode = when (event.charCode) {
                    KEY_VOL_UP -> KeyEvent.KEYCODE_VOLUME_UP
                    KEY_VOL_DOWN -> KeyEvent.KEYCODE_VOLUME_DOWN
                    else -> KeyEvent.KEYCODE_VOLUME_MUTE
                }
                ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, 0))
                ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, 0))
            }

            // Macro Keys (KEY_MACRO_0 through KEY_MACRO_7)
            event.charCode in KEY_MACRO_0..KEY_MACRO_7 -> {
                val slot = event.charCode - KEY_MACRO_0
                MacroManager.execute(ic, slot)
            }

            // Regular character
            else -> {
                ic.commitText(event.char.toString(), 1)
            }
        }
    }

    /**
     * Resets all internal button, trigger, and deflection tracking variables.
     */
    fun resetTracking() {
        leftStickSuperEngaged = false
        hasTypedDuringSuperEngaged = false
        isL2ButtonDown = false
        isR2ButtonDown = false
        isL1ButtonDown = false
        leftStickShiftActive = false
        leftStickCtrlActive = false
        leftStickAltActive = false
        leftStickSuperActive = false
        lastLeftStickX = 0f
        lastLeftStickY = 0f
        lastStickX = 0f
        lastStickY = 0f
        l2TriggerActive = false
        r2TriggerActive = false
        isDeflected = false
    }
}
