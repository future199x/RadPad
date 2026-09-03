package com.radpad.app

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputConnection
import kotlin.math.atan2

/**
 * InputEngine: Zero-latency radial input engine communicating directly with bare-metal Zig core
 * (`libzigengine.so`), with seamless pure-Kotlin fallback for host JVM testing.
 *
 * Implements:
 * - Two-stage radial selection architecture matching the architecture diagram.
 * - Stick movement aims/highlights sectors without flick emission.
 * - Right Bumper (R1) commits selection (enters layer from BASE, or emits character in layer).
 * - Left Bumper (L1) returns to BASE layer.
 * - Right Trigger (R2) holds secondary layer when in layers with two tiers (SYM 2, 9-0, FN 9-12).
 * - Left Trigger (L2) holds Shift.
 * - Full system key dispatch (Vol+, Vol-, Vol Mute/Toggle, PgUp, PgDn, Home, End, Del, Ins).
 */
class InputEngine {

    enum class Layer(val id: Int, val displayName: String) {
        BASE(0, "BASE"),
        A_H(1, "A-H"),
        MORE_SYM(2, "SYM"),
        I_P(3, "I-P"),
        FN(4, "FN"),
        Q_X(5, "Q-X"),
        Y_Z(6, "Y-Z"),
        NUM_SYM(7, "NUM"),
        SYS(8, "SYS");

        companion object {
            fun fromId(id: Int): Layer = values().firstOrNull { it.id == id } ?: BASE
        }
    }

    companion object {
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

        const val FLAG_R2_HOLD: Int = 1 shl 1   // Right Trigger (R2) -> Second Layer Hold
        const val FLAG_SHIFT: Int = 1 shl 2     // Shift (Left Trigger L2): Hold to uppercase
        const val FLAG_CTRL: Int = 1 shl 3      // Ctrl (Left Stick Down / Tilt)
        const val FLAG_ALT: Int = 1 shl 4       // Alt (Left Stick Left / Tilt)
        const val FLAG_CAPS_LOCK: Int = 1 shl 5 // Caps Lock (toggled via L3 click)
        const val FLAG_SUPER: Int = 1 shl 7     // Super / Windows Key (Left Stick Right / Tilt)
        const val FLAG_SELECT: Int = 1 shl 8    // Right Bumper (R1): Select Layer / Character
        const val FLAG_BACK: Int = 1 shl 9      // Left Bumper (L1): Return to Base Layer


        // Special Key Codes (Matching main.zig: 0xF001 .. 0xF016)
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

        const val LAYER_EVENT_MASK: Int = 0xE000

        // Output Bitmasks
        const val CHAR_MASK: Int = 0xFFFF
        const val MOD_SHIFT: Int = 1 shl 16
        const val MOD_CTRL: Int = 1 shl 17
        const val MOD_ALT: Int = 1 shl 18
        const val MOD_SUPER: Int = 1 shl 20

        const val TRIGGER_ENGAGEMENT_THRESHOLD: Float = 0.5f
        const val DEADZONE_ENGAGE: Float = 0.42f
        const val DEADZONE_ENGAGE_SQ: Float = DEADZONE_ENGAGE * DEADZONE_ENGAGE
        const val DEADZONE_RELEASE: Float = 0.25f
        const val DEADZONE_RELEASE_SQ: Float = DEADZONE_RELEASE * DEADZONE_RELEASE

        val LAYOUT_A_H = arrayOf('a', 'b', 'c', 'd', 'e', 'f', 'g', 'h')
        val LAYOUT_MORE_SYM_1 = arrayOf('\'', '=', '.', ';', '\\', '/', ',', '-')
        val LAYOUT_MORE_SYM_2 = arrayOf('`', '\u0000', ']', '\u0000', '\u0000', '\u0000', '[', '\u0000')
        val LAYOUT_I_P = arrayOf('i', 'j', 'k', 'l', 'm', 'n', 'o', 'p')
        val LAYOUT_FN_1_8 = arrayOf(KEY_F1, KEY_F2, KEY_F3, KEY_F4, KEY_F5, KEY_F6, KEY_F7, KEY_F8)
        val LAYOUT_FN_9_12 = arrayOf(KEY_F9, KEY_F10, KEY_F11, KEY_F12, 0, 0, 0, 0)
        val LAYOUT_Q_X = arrayOf('q', 'r', 's', 't', 'u', 'v', 'w', 'x')
        val LAYOUT_Y_Z = arrayOf('y', 'z', '\u0000', '\u0000', '\u0000', '\u0000', '\u0000', '\u0000')
        val LAYOUT_NUM_1_8 = arrayOf('1', '2', '3', '4', '5', '6', '7', '8')
        val LAYOUT_NUM_9_0 = arrayOf('9', '0', '\u0000', '\u0000', '\u0000', '\u0000', '\u0000', '\u0000')
        val LAYOUT_SYS = arrayOf(
            KEY_PGUP, KEY_VOL_UP, KEY_END, KEY_DELETE, KEY_PGDN, KEY_INSERT, KEY_HOME, KEY_VOL_DOWN
        )

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

        @JvmStatic
        fun decode(rawValue: Int): ProcessedEvent? {
            if (rawValue == 0) return null
            if ((rawValue and 0xF000) == LAYER_EVENT_MASK) return null
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
    external fun backToBase()
    external fun getCurrentLayer(): Int
    external fun setCurrentLayer(layerId: Int)
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

    var isSecondLayerActive: Boolean = false
        private set

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

    // Left Stick modifier states with hysteresis
    private var leftStickShiftActive: Boolean = false
    private var leftStickCtrlActive: Boolean = false
    private var leftStickAltActive: Boolean = false
    private var leftStickSuperActive: Boolean = false
    private var l2TriggerActive: Boolean = false
    private var r2TriggerActive: Boolean = false

    var useSymmetricSlices: Boolean = true

    data class ProcessedEvent(
        val rawValue: Int,
        val charCode: Int,
        val char: Char,
        val isShift: Boolean,
        val isCtrl: Boolean,
        val isAlt: Boolean,
        val isSuper: Boolean
    )

    fun calculateSlice(x: Float, y: Float): Int {
        val rad = atan2(x.toDouble(), -y.toDouble())
        var deg = Math.toDegrees(rad).toFloat()
        if (deg < 0f) deg += 360f

        return if (useSymmetricSlices) {
            when {
                deg >= 337.5f || deg < 22.5f -> 0
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
                deg >= 330f || deg < 30f -> 0
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

    fun backToBaseLayer() {
        currentLayer = Layer.BASE
    }

    fun setLayer(layer: Layer) {
        currentLayer = layer
    }

    fun resetAll() {
        currentLayer = Layer.BASE
        isDeflected = false
        aimedSlice = 0
        if (isNativeLoaded) {
            try {
                resetState()
            } catch (_: UnsatisfiedLinkError) {}
        }
    }

    /**
     * Performs selection (invoked when Right Bumper R1 is pressed).
     * On BASE: transitions to targeted layer.
     * In Layer: emits targeted character/key (or VOL MUTE if in SYS deadzone).
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
                4 -> Layer.Q_X
                5 -> Layer.Y_Z
                6 -> Layer.NUM_SYM
                7 -> Layer.SYS
                else -> Layer.BASE
            }
            return null
        }

        // Inside active layer:
        if (currentLayer == Layer.SYS && !isDeflected) {
            return ProcessedEvent(
                rawValue = KEY_VOL_MUTE,
                charCode = KEY_VOL_MUTE,
                char = 0.toChar(),
                isShift = false,
                isCtrl = false,
                isAlt = false,
                isSuper = false
            )
        }

        if (!isDeflected) return null

        val isShift = ((currentButtonMask and FLAG_SHIFT) != 0) xor ((currentButtonMask and FLAG_CAPS_LOCK) != 0)
        val isCtrl = (currentButtonMask and FLAG_CTRL) != 0
        val isAlt = (currentButtonMask and FLAG_ALT) != 0
        val isSuper = (currentButtonMask and FLAG_SUPER) != 0

        var charCode = when (currentLayer) {
            Layer.A_H -> LAYOUT_A_H[aimedSlice].code
            Layer.I_P -> LAYOUT_I_P[aimedSlice].code
            Layer.Q_X -> LAYOUT_Q_X[aimedSlice].code
            Layer.Y_Z -> LAYOUT_Y_Z[aimedSlice].code
            Layer.MORE_SYM -> if (isSecondLayerActive) LAYOUT_MORE_SYM_2[aimedSlice].code else LAYOUT_MORE_SYM_1[aimedSlice].code
            Layer.NUM_SYM -> if (isSecondLayerActive) LAYOUT_NUM_9_0[aimedSlice].code else LAYOUT_NUM_1_8[aimedSlice].code
            Layer.FN -> if (isSecondLayerActive) LAYOUT_FN_9_12[aimedSlice] else LAYOUT_FN_1_8[aimedSlice]
            Layer.SYS -> LAYOUT_SYS[aimedSlice]
            Layer.BASE -> 0
        }

        if (charCode == 0) return null

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
     * Handles gamepad analog stick movements and analog trigger pressures.
     * Motion alone NEVER emits a character.
     */
    fun onMotionEvent(event: MotionEvent): ProcessedEvent? {
        // 1. Read Left Stick as the Modifier & Utility Joystick:
        val lx = event.getAxisValue(MotionEvent.AXIS_X)
        val ly = event.getAxisValue(MotionEvent.AXIS_Y)

        val ENGAGE = 0.35f
        val RELEASE = 0.20f

        leftStickShiftActive = if (leftStickShiftActive) ly <= -RELEASE else ly <= -ENGAGE
        leftStickCtrlActive = if (leftStickCtrlActive) ly >= RELEASE else ly >= ENGAGE
        leftStickAltActive = if (leftStickAltActive) lx <= -RELEASE else lx <= -ENGAGE
        leftStickSuperActive = if (leftStickSuperActive) lx >= RELEASE else lx >= ENGAGE

        currentButtonMask = if (leftStickCtrlActive) currentButtonMask or FLAG_CTRL else currentButtonMask and FLAG_CTRL.inv()
        currentButtonMask = if (leftStickAltActive) currentButtonMask or FLAG_ALT else currentButtonMask and FLAG_ALT.inv()
        currentButtonMask = if (leftStickSuperActive) currentButtonMask or FLAG_SUPER else currentButtonMask and FLAG_SUPER.inv()

        // Track flick-and-release tap on Left Stick for standalone Super (Windows) key
        var standaloneSuperEvent: ProcessedEvent? = null
        if (leftStickSuperActive) {
            leftStickSuperEngaged = true
        } else {
            val lRsq = (lx * lx) + (ly * ly)
            if (leftStickSuperEngaged && lRsq < 0.20f * 0.20f) {
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

        // 2. Analog triggers: Left Trigger (L2) is Shift, Right Trigger (R2) is 2nd layer hold
        val l2Pressure = event.getAxisValue(MotionEvent.AXIS_LTRIGGER).let {
            if (it != 0.0f) it else event.getAxisValue(MotionEvent.AXIS_BRAKE)
        }
        val r2Pressure = event.getAxisValue(MotionEvent.AXIS_RTRIGGER).let {
            if (it != 0.0f) it else event.getAxisValue(MotionEvent.AXIS_GAS)
        }

        l2TriggerActive = if (l2TriggerActive) l2Pressure >= 0.25f else l2Pressure >= TRIGGER_ENGAGEMENT_THRESHOLD
        r2TriggerActive = if (r2TriggerActive) r2Pressure >= 0.25f else r2Pressure >= TRIGGER_ENGAGEMENT_THRESHOLD

        val shiftActive = isL2ButtonDown || l2TriggerActive || leftStickShiftActive
        currentButtonMask = if (shiftActive) currentButtonMask or FLAG_SHIFT else currentButtonMask and FLAG_SHIFT.inv()

        val r2Active = isR2ButtonDown || r2TriggerActive
        isSecondLayerActive = r2Active
        currentButtonMask = if (r2Active) currentButtonMask or FLAG_R2_HOLD else currentButtonMask and FLAG_R2_HOLD.inv()

        // 3. Read Right Stick (Radial Dial):
        val rawZ = event.getAxisValue(MotionEvent.AXIS_Z)
        val rawRZ = event.getAxisValue(MotionEvent.AXIS_RZ)
        val rawRX = event.getAxisValue(MotionEvent.AXIS_RX)
        val rawRY = event.getAxisValue(MotionEvent.AXIS_RY)

        val rx = if (rawZ != 0f || rawRZ != 0f) rawZ else rawRX
        val ry = if (rawZ != 0f || rawRZ != 0f) rawRZ else rawRY

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
        l2TriggerActive = false
        r2TriggerActive = false
        isSecondLayerActive = false
        isDeflected = false
    }

    /**
     * Handles hardware button state transitions (KeyDown / KeyUp).
     */
    fun onKeyEvent(keyCode: Int, isDown: Boolean): Boolean {
        if (isDown) {
            hasTypedDuringSuperEngaged = true
        }

        // L3: Toggle Caps Lock
        if (keyCode == KeyEvent.KEYCODE_BUTTON_THUMBL) {
            if (isDown) {
                currentButtonMask = currentButtonMask xor FLAG_CAPS_LOCK
                if (isNativeLoaded) {
                    try { processInput(lastStickX, lastStickY, currentButtonMask) } catch (_: UnsatisfiedLinkError) {}
                }
            }
            return true
        }

        // L1: Return to Base Layer
        if (keyCode == KeyEvent.KEYCODE_BUTTON_L1) {
            isL1ButtonDown = isDown
            if (isDown) {
                backToBaseLayer()
            }
            return true
        }

        // R2: Hold for 2nd layer
        if (keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            isR2ButtonDown = isDown
            isSecondLayerActive = isR2ButtonDown || r2TriggerActive
            currentButtonMask = if (isSecondLayerActive) currentButtonMask or FLAG_R2_HOLD else currentButtonMask and FLAG_R2_HOLD.inv()
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
     * Dispatches a decoded event to the active Android InputConnection.
     */
    fun dispatchEvent(ic: InputConnection?, event: ProcessedEvent) {
        if (ic == null) return

        when {
            // Standalone Super / Windows Key
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

            // Alt / Meta modifier: Sends VT100 ANSI ESC sequence (\u001b + char)
            event.isAlt -> {
                ic.commitText("\u001b${event.char}", 1)
            }

            // Ctrl modifier
            event.isCtrl -> {
                when (event.char.lowercaseChar()) {
                    'a' -> ic.performContextMenuAction(android.R.id.selectAll)
                    'c' -> ic.performContextMenuAction(android.R.id.copy)
                    'x' -> ic.performContextMenuAction(android.R.id.cut)
                    'v' -> ic.performContextMenuAction(android.R.id.paste)
                    'z' -> ic.performContextMenuAction(android.R.id.undo)
                    else -> {
                        val ctrlPayload = if (event.charCode in 1..26) {
                            event.charCode.toChar().toString()
                        } else if (event.char in 'a'..'z') {
                            (event.char.code - 'a'.code + 1).toChar().toString()
                        } else if (event.char in 'A'..'Z') {
                            (event.char.code - 'A'.code + 1).toChar().toString()
                        } else {
                            event.char.toString()
                        }
                        ic.commitText(ctrlPayload, 1)
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
                    KEY_VOL_MUTE -> KeyEvent.KEYCODE_VOLUME_MUTE
                    else -> KeyEvent.KEYCODE_UNKNOWN
                }
                if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                    ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, 0))
                    ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, 0))
                }
            }

            // Real Forward Delete Key
            event.charCode == KEY_DELETE -> {
                if (ic.deleteSurroundingText(0, 1) != true) {
                    ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_FORWARD_DEL, 0, 0))
                    ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_FORWARD_DEL, 0, 0))
                }
            }

            // Newline / Enter
            event.char == '\n' -> {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }

            // Tab
            event.char == '\t' -> {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB))
            }

            // Standard character
            else -> {
                ic.commitText(event.char.toString(), 1)
            }
        }
    }
}
