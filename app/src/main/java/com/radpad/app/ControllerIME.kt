package com.radpad.app

import android.content.ClipboardManager
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * RadPad: High-performance gamepad InputMethodService for ultra-fast,
 * system-wide controller typing.
 *
 * Responsibilities:
 * 1. Intercepts hardware gamepad analog stick (MotionEvent) and button inputs (KeyEvent).
 * 2. Radial aiming via Right Stick; selection via Right Bumper (R1).
 * 3. Base Layer return via Left Bumper (L1); secondary layer hold via Right Trigger (R2).
 * 4. Injects emitted characters and modifier sequences into the OS via `currentInputConnection`.
 * 5. Displays real-time radial HUD showing base layer preview text, active layers, and targeting reticle.
 */
class ControllerIME : InputMethodService(), ThemeManager.ThemeListener {

    private val engine = InputEngine()

    private var rootView: View? = null
    private var hudStatus: TextView? = null
    private var hudModifiers: TextView? = null
    private var hudZone: TextView? = null
    private var radialHUD: KinematicRadialHUDView? = null

    private var lastStickX: Float = 0f
    private var lastStickY: Float = 0f
    private var lastHatX: Float = 0f
    private var lastHatY: Float = 0f

    override fun onCreate() {
        super.onCreate()
        window?.window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        FloatingHUDManager.activeEngine = engine
        ThemeManager.init(this)
        ThemeManager.register(this)
        MacroManager.init(this)
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.ime_view, null)
        rootView = view
        hudStatus = view.findViewById(R.id.tv_hud_status)
        hudModifiers = view.findViewById(R.id.tv_hud_modifiers)
        hudZone = view.findViewById(R.id.tv_hud_zone)
        radialHUD = view.findViewById(R.id.radial_hud_view)

        val prefs = getSharedPreferences("radpad_prefs", Context.MODE_PRIVATE)
        val isSymmetric = prefs.getBoolean("is_symmetric_slices", true)
        engine.setSymmetricSlices(isSymmetric)
        radialHUD?.isSymmetric = isSymmetric


        view.findViewById<TextView>(R.id.tv_ime_cheatsheet1)?.text = "R1: Select / Type | Center R1: 2nd Page | L1: Back"
        view.findViewById<TextView>(R.id.tv_ime_cheatsheet2)?.text = "A: Space | X: Backspace | Y: Enter | B: Tab"

        applyThemeToIME(ThemeManager.currentTheme)

        updateHud(lastEvent = null, x = 0f, y = 0f)
        return view
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val isGamepad = (event.source and InputDevice.SOURCE_JOYSTICK) != 0 ||
                (event.source and InputDevice.SOURCE_GAMEPAD) != 0

        if (!isGamepad) {
            return super.onGenericMotionEvent(event)
        }

        // Read Right Stick (Radial Dial):
        val rawZ = event.getAxisValue(MotionEvent.AXIS_Z)
        val rawRZ = event.getAxisValue(MotionEvent.AXIS_RZ)
        val rawRX = event.getAxisValue(MotionEvent.AXIS_RX)
        val rawRY = event.getAxisValue(MotionEvent.AXIS_RY)
        val rx = if (rawZ != 0f || rawRZ != 0f) rawZ else rawRX
        val ry = if (rawZ != 0f || rawRZ != 0f) rawRZ else rawRY

        lastStickX = rx
        lastStickY = ry

        // Check if R2 trigger engaged
        val wasMouseActive = VirtualMouseManager.isMouseLayerActive
        val isMouseActive = engine.isMouseLayerActive
        if (wasMouseActive != isMouseActive) {
            VirtualMouseManager.setMouseLayerActive(this, isMouseActive)
        }

        // D-Pad Hat Navigation
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

        if (engine.isMouseLayerActive) {
            VirtualMouseManager.updateStick(rx, ry)

            if (hatX != lastHatX) {
                if (hatX < -0.5f) {
                    VirtualMouseManager.performLeftClick()
                    hudStatus?.text = "Mouse: Left Click"
                } else if (hatX > 0.5f) {
                    VirtualMouseManager.performRightClick()
                    hudStatus?.text = "Mouse: Right Click"
                }
                lastHatX = hatX
            }

            if (hatY != lastHatY) {
                if (hatY < -0.5f) {
                    VirtualMouseManager.performMiddleClick()
                    hudStatus?.text = "Mouse: Middle Click"
                }
                lastHatY = hatY
            }

            updateHud(lastEvent = null, x = rx, y = ry)
            return true
        }

        if (hatX != lastHatX) {
            if (hatX < -0.5f) handleDpadNavigation(KeyEvent.KEYCODE_DPAD_LEFT)
            else if (hatX > 0.5f) handleDpadNavigation(KeyEvent.KEYCODE_DPAD_RIGHT)
            lastHatX = hatX
        }

        if (hatY != lastHatY) {
            if (hatY < -0.5f) handleDpadNavigation(KeyEvent.KEYCODE_DPAD_UP)
            else if (hatY > 0.5f) handleDpadNavigation(KeyEvent.KEYCODE_DPAD_DOWN)
            lastHatY = hatY
        }

        // Process motion event (motion alone never emits characters)
        val result = engine.onMotionEvent(event)
        if (result != null) {
            engine.dispatchEvent(currentInputConnection, result)
            updateHud(lastEvent = result, x = lastStickX, y = lastStickY)
            return true
        }

        updateHud(lastEvent = null, x = lastStickX, y = lastStickY)
        return true
    }

    private fun handleDpadNavigation(keyCode: Int) {
        val ic = currentInputConnection ?: return
        val mask = engine.currentButtonMask
        var metaState = 0
        if ((mask and InputEngine.FLAG_SHIFT) != 0) metaState = metaState or KeyEvent.META_SHIFT_ON
        if ((mask and InputEngine.FLAG_CTRL) != 0) metaState = metaState or KeyEvent.META_CTRL_ON
        if ((mask and InputEngine.FLAG_ALT) != 0) metaState = metaState or KeyEvent.META_ALT_ON
        if ((mask and InputEngine.FLAG_SUPER) != 0) metaState = metaState or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON

        ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
        ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, metaState))
        val dirName = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> "← Left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "→ Right"
            KeyEvent.KEYCODE_DPAD_UP -> "↑ Up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "↓ Down"
            else -> ""
        }
        hudStatus?.text = "Cursor: $dirName"
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 0. Mouse Layer check:
        if (keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            engine.onKeyEvent(keyCode, isDown = true)
            VirtualMouseManager.setMouseLayerActive(this, true)
            hudStatus?.text = "🐭 MOUSE LAYER (Aim Stick: Move | D-Pad: Clicks)"
            updateHud(lastEvent = null, x = lastStickX, y = lastStickY)
            return true
        }

        if (engine.isMouseLayerActive) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    VirtualMouseManager.performLeftClick()
                    hudStatus?.text = "Mouse: Left Click"
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    VirtualMouseManager.performRightClick()
                    hudStatus?.text = "Mouse: Right Click"
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    VirtualMouseManager.performMiddleClick()
                    hudStatus?.text = "Mouse: Middle Click"
                    return true
                }
            }
        }

        // 1. Right Bumper (R1): SELECTION
        if (keyCode == KeyEvent.KEYCODE_BUTTON_R1) {
            val result = engine.onSelect()
            if (result != null) {
                engine.dispatchEvent(currentInputConnection, result)
                updateHud(lastEvent = result, x = lastStickX, y = lastStickY)
            } else {
                updateHud(lastEvent = null, x = lastStickX, y = lastStickY)
            }
            return true
        }

        // 2. Left Bumper (L1): Go back one layer level (Page 2 -> Page 1 -> Base)
        if (keyCode == KeyEvent.KEYCODE_BUTTON_L1) {
            engine.goBackLayer()
            updateHud(lastEvent = null, x = lastStickX, y = lastStickY)
            return true
        }

        // 3. Modifier keys (L2 = Shift, R2 = 2nd layer hold, L3 = Caps)
        if (engine.onKeyEvent(keyCode, isDown = true)) {
            updateHud(lastEvent = null, x = lastStickX, y = lastStickY)
            return true
        }

        // 4. Primary Gamepad Text Navigation & Action Shortcuts:
        when (keyCode) {
            // Square (KEYCODE_BUTTON_X): Backspace / Forward Delete (Shift + Square)
            KeyEvent.KEYCODE_BUTTON_X -> {
                val mask = engine.currentButtonMask
                val isShift = (mask and InputEngine.FLAG_SHIFT) != 0
                val isCtrl = (mask and InputEngine.FLAG_CTRL) != 0
                val isAlt = (mask and InputEngine.FLAG_ALT) != 0
                val isSuper = (mask and InputEngine.FLAG_SUPER) != 0

                val targetKeyCode = if (isShift) KeyEvent.KEYCODE_FORWARD_DEL else KeyEvent.KEYCODE_DEL
                val actionName = if (isShift) "DEL (Forward)" else "BACKSPACE"

                if (isCtrl || isAlt || isSuper) {
                    var metaState = 0
                    if (isShift) metaState = metaState or KeyEvent.META_SHIFT_ON
                    if (isCtrl) metaState = metaState or KeyEvent.META_CTRL_ON
                    if (isAlt) metaState = metaState or KeyEvent.META_ALT_ON
                    if (isSuper) metaState = metaState or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
                    currentInputConnection?.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, targetKeyCode, 0, metaState))
                    currentInputConnection?.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, targetKeyCode, 0, metaState))
                } else if (isShift) {
                    val ic = currentInputConnection
                    if (ic?.deleteSurroundingText(0, 1) != true) {
                        sendDownUpKeyEvents(KeyEvent.KEYCODE_FORWARD_DEL)
                    }
                } else {
                    val ic = currentInputConnection
                    if (ic?.deleteSurroundingText(1, 0) != true) {
                        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                    }
                }
                hudStatus?.text = "Emitted: '$actionName'"
                updateHud(lastEvent = null, x = lastStickX, y = lastStickY)
                return true
            }

            // Cross (KEYCODE_BUTTON_A): Space
            KeyEvent.KEYCODE_BUTTON_A -> {
                val mask = engine.currentButtonMask
                if ((mask and (InputEngine.FLAG_CTRL or InputEngine.FLAG_ALT or InputEngine.FLAG_SUPER)) != 0) {
                    var metaState = 0
                    if ((mask and InputEngine.FLAG_SHIFT) != 0) metaState = metaState or KeyEvent.META_SHIFT_ON
                    if ((mask and InputEngine.FLAG_CTRL) != 0) metaState = metaState or KeyEvent.META_CTRL_ON
                    if ((mask and InputEngine.FLAG_ALT) != 0) metaState = metaState or KeyEvent.META_ALT_ON
                    if ((mask and InputEngine.FLAG_SUPER) != 0) metaState = metaState or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
                    currentInputConnection?.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, 0, metaState))
                    currentInputConnection?.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE, 0, metaState))
                } else {
                    currentInputConnection?.commitText(" ", 1)
                }
                hudStatus?.text = "Emitted: 'SPACE'"
                updateHud(lastEvent = null, x = lastStickX, y = lastStickY)
                return true
            }

            // Triangle (KEYCODE_BUTTON_Y): Enter / Newline
            KeyEvent.KEYCODE_BUTTON_Y -> {
                val mask = engine.currentButtonMask
                if ((mask and (InputEngine.FLAG_CTRL or InputEngine.FLAG_ALT or InputEngine.FLAG_SUPER)) != 0) {
                    var metaState = 0
                    if ((mask and InputEngine.FLAG_SHIFT) != 0) metaState = metaState or KeyEvent.META_SHIFT_ON
                    if ((mask and InputEngine.FLAG_CTRL) != 0) metaState = metaState or KeyEvent.META_CTRL_ON
                    if ((mask and InputEngine.FLAG_ALT) != 0) metaState = metaState or KeyEvent.META_ALT_ON
                    if ((mask and InputEngine.FLAG_SUPER) != 0) metaState = metaState or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
                    currentInputConnection?.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, metaState))
                    currentInputConnection?.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, metaState))
                } else {
                    sendKeyChar('\n')
                }
                hudStatus?.text = "Emitted: '\\n'"
                updateHud(lastEvent = null, x = lastStickX, y = lastStickY)
                return true
            }

            // Circle (KEYCODE_BUTTON_B): Tab
            KeyEvent.KEYCODE_BUTTON_B -> {
                val mask = engine.currentButtonMask
                if ((mask and (InputEngine.FLAG_CTRL or InputEngine.FLAG_ALT or InputEngine.FLAG_SUPER)) != 0) {
                    var metaState = 0
                    if ((mask and InputEngine.FLAG_SHIFT) != 0) metaState = metaState or KeyEvent.META_SHIFT_ON
                    if ((mask and InputEngine.FLAG_CTRL) != 0) metaState = metaState or KeyEvent.META_CTRL_ON
                    if ((mask and InputEngine.FLAG_ALT) != 0) metaState = metaState or KeyEvent.META_ALT_ON
                    if ((mask and InputEngine.FLAG_SUPER) != 0) metaState = metaState or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
                    currentInputConnection?.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0, metaState))
                    currentInputConnection?.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, 0, metaState))
                } else {
                    sendKeyChar('\t')
                }
                hudStatus?.text = "Emitted: '\\t'"
                updateHud(lastEvent = null, x = lastStickX, y = lastStickY)
                return true
            }

            // D-Pad Navigation
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                handleDpadNavigation(KeyEvent.KEYCODE_DPAD_LEFT)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                handleDpadNavigation(KeyEvent.KEYCODE_DPAD_RIGHT)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                handleDpadNavigation(KeyEvent.KEYCODE_DPAD_UP)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                handleDpadNavigation(KeyEvent.KEYCODE_DPAD_DOWN)
                return true
            }

            // R3: Escape
            KeyEvent.KEYCODE_BUTTON_THUMBR -> {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ESCAPE)
                return true
            }

            // Guide / Mode Button: Super
            KeyEvent.KEYCODE_BUTTON_MODE -> {
                val mask = engine.currentButtonMask
                var metaState = KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
                if ((mask and InputEngine.FLAG_SHIFT) != 0) metaState = metaState or KeyEvent.META_SHIFT_ON
                if ((mask and InputEngine.FLAG_CTRL) != 0) metaState = metaState or KeyEvent.META_CTRL_ON
                if ((mask and InputEngine.FLAG_ALT) != 0) metaState = metaState or KeyEvent.META_ALT_ON
                currentInputConnection?.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_META_LEFT, 0, metaState))
                currentInputConnection?.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_META_LEFT, 0, metaState))
                hudStatus?.text = "Emitted: 'WIN (Super)'"
                updateHud(lastEvent = null, x = lastStickX, y = lastStickY)
                return true
            }

            // Share / Select Button
            KeyEvent.KEYCODE_BUTTON_SELECT -> {
                val prefs = getSharedPreferences("radpad_prefs", Context.MODE_PRIVATE)
                val action = prefs.getString("select_button_action", "toggle_hud") ?: "toggle_hud"
                executeSystemButtonAction(action)
                updateHud(lastEvent = null, x = lastStickX, y = lastStickY)
                return true
            }

            // Options / Start: Close Keyboard / Toggle HUD
            KeyEvent.KEYCODE_BUTTON_START -> {
                val prefs = getSharedPreferences("radpad_prefs", Context.MODE_PRIVATE)
                val action = prefs.getString("start_button_action", "hide_keyboard") ?: "hide_keyboard"
                executeSystemButtonAction(action)
                updateHud(lastEvent = null, x = lastStickX, y = lastStickY)
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun executeSystemButtonAction(actionKey: String) {
        when (actionKey) {
            "hide_keyboard" -> {
                requestHideSelf(0)
            }
            "toggle_hud_hide_keyboard" -> {
                FloatingHUDManager.toggleFloater(this)
                requestHideSelf(0)
            }
            else -> { // "toggle_hud"
                val isShown = FloatingHUDManager.toggleFloater(this)
                hudStatus?.text = if (isShown) "Overlay: Visible" else "Overlay: Hidden"
            }
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            engine.onKeyEvent(keyCode, isDown = false)
            VirtualMouseManager.setMouseLayerActive(this, false)
            updateHud(lastEvent = null, x = lastStickX, y = lastStickY)
            return true
        }
        val handled = engine.onKeyEvent(keyCode, isDown = false)
        updateHud(lastEvent = null, x = lastStickX, y = lastStickY)
        if (handled) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        FloatingHUDManager.activeEngine = engine
        updateHud(lastEvent = null, x = lastStickX, y = lastStickY)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        engine.resetTracking()
        lastStickX = 0f
        lastStickY = 0f
        updateHud(lastEvent = null, x = 0f, y = 0f)
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.backToBaseLayer()
        engine.resetTracking()
        ThemeManager.unregister(this)
        if (FloatingHUDManager.activeEngine === engine) {
            FloatingHUDManager.activeEngine = null
        }
    }

    override fun onThemeChanged(theme: ThemeManager.ColorScheme) {
        applyThemeToIME(theme)
    }

    private fun applyThemeToIME(theme: ThemeManager.ColorScheme) {
        radialHUD?.applyColorScheme(theme)
        rootView?.setBackgroundColor(theme.cardBackground)
        hudZone?.setTextColor(theme.centerInfoText)
        hudStatus?.setTextColor(theme.headerText)
        hudModifiers?.setTextColor(theme.modeAbcColor)
        rootView?.findViewById<TextView>(R.id.tv_ime_cheatsheet1)?.setTextColor(theme.inactiveText)
        rootView?.findViewById<TextView>(R.id.tv_ime_cheatsheet2)?.setTextColor(theme.headerText)
    }

    private fun updateHud(lastEvent: InputEngine.ProcessedEvent? = null, x: Float, y: Float) {
        val mask = engine.currentButtonMask
        val layer = engine.currentLayer
        val isSecond = engine.isSecondLayerActive

        val isMouse = engine.isMouseLayerActive
        val zoneLabel = if (isMouse) {
            "🐭 MOUSE LAYER"
        } else when (layer) {
            InputEngine.Layer.BASE -> "BASE LAYER"
            InputEngine.Layer.MORE_SYM -> if (isSecond) "SYM 2" else "SYM 1"
            InputEngine.Layer.NUM_SYM -> if (isSecond) "NUM (9-0)" else "NUM (1-8)"
            InputEngine.Layer.FN -> if (isSecond) "FN (9-12)" else "FN (1-8)"
            InputEngine.Layer.Q_Z -> if (isSecond) "Q-Z (Y-Z)" else "Q-Z (Q-X)"
            InputEngine.Layer.MACRO -> "MACROS"
            else -> layer.displayName
        }
        hudZone?.text = zoneLabel

        val isShift = (mask and InputEngine.FLAG_SHIFT) != 0
        val isCtrl = (mask and InputEngine.FLAG_CTRL) != 0
        val isAlt = (mask and InputEngine.FLAG_ALT) != 0
        val isSuper = (mask and InputEngine.FLAG_SUPER) != 0
        val isCaps = (mask and InputEngine.FLAG_CAPS_LOCK) != 0

        val activeMods = mutableListOf<String>()
        if (isMouse) activeMods.add("MOUSE")
        if (isCaps) activeMods.add("CAPS")
        if (isShift) activeMods.add("SHIFT")
        if (isCtrl) activeMods.add("CTRL")
        if (isAlt) activeMods.add("ALT")
        if (isSuper) activeMods.add("WIN")
        if (isSecond) activeMods.add("2ND")

        val modText = if (activeMods.isEmpty()) "MODS: NONE" else "MODS: " + activeMods.joinToString("+")
        hudModifiers?.text = modText

        if (isMouse) {
            hudStatus?.text = "D-Pad: ← Left Click | → Right Click | ↑ Mid Click"
        } else if (lastEvent != null) {
            val displayChar = when {
                lastEvent.charCode in InputEngine.KEY_MACRO_0..InputEngine.KEY_MACRO_7 -> {
                    val slot = lastEvent.charCode - InputEngine.KEY_MACRO_0
                    "MACRO: " + MacroManager.getMacro(slot).displayName
                }
                lastEvent.charCode == InputEngine.KEY_SUPER -> "WIN (Super)"
                lastEvent.charCode == InputEngine.KEY_DELETE -> "DEL"
                lastEvent.charCode == InputEngine.KEY_VOL_UP -> "VOL+"
                lastEvent.charCode == InputEngine.KEY_VOL_DOWN -> "VOL-"
                lastEvent.charCode == InputEngine.KEY_VOL_MUTE -> "VOL MUTE"
                lastEvent.charCode in InputEngine.KEY_F1..InputEngine.KEY_F12 -> "F${lastEvent.charCode - InputEngine.KEY_F1 + 1}"
                lastEvent.charCode == InputEngine.KEY_HOME -> "HOME"
                lastEvent.charCode == InputEngine.KEY_END  -> "END"
                lastEvent.charCode == InputEngine.KEY_PGUP -> "PGUP"
                lastEvent.charCode == InputEngine.KEY_PGDN -> "PGDN"
                lastEvent.charCode == InputEngine.KEY_INSERT -> "INS"
                lastEvent.char == '\n' -> "\\n"
                lastEvent.char == '\t' -> "\\t"
                lastEvent.char == ' '  -> "SPACE"
                else -> lastEvent.char.toString()
            }
            hudStatus?.text = "Emitted: '$displayChar'"
        } else {
            hudStatus?.text = getString(R.string.hud_status_ready)
        }

        radialHUD?.updateState(x, y, layer, isSecond, isShift, isCtrl, isAlt, isCaps, isSuper)
        FloatingHUDManager.updateInput(x, y, layer, isSecond, isShift, isCtrl, isAlt, isCaps, isSuper)
    }
}
