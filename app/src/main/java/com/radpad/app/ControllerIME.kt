package com.radpad.app

import android.annotation.SuppressLint
import android.inputmethodservice.InputMethodService
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.TextView

/**
 * RadPad: High-performance gamepad [InputMethodService] providing ultra-low latency,
 * system-wide controller typing.
 *
 * ## Responsibilities
 * 1. **Input Interception**: Intercepts gamepad analog stick motions ([MotionEvent]) and button presses ([KeyEvent]).
 * 2. **Radial Navigation**: Routes right-stick azimuths into [InputEngine] radial layers and handles selection via R1.
 * 3. **Layer Management**: Dispatches layer depth traversal via L1 (Back to Base) and R2 (Mouse Layer / Secondary Hold).
 * 4. **System Injection**: Emits resolved characters, control sequences, and meta-combinations via [InputConnection].
 * 5. **Real-time HUD**: Renders real-time visual telemetry, layer preview, and aim reticle in [KinematicRadialHUDView].
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

    private val prefs by lazy { getSharedPreferences("radpad_prefs", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        window?.window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        FloatingHUDManager.activeEngine = engine
        ThemeManager.init(this)
        ThemeManager.register(this)
        MacroManager.init(this)
    }

    @SuppressLint("InflateParams")
    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.ime_view, null)
        rootView = view
        hudStatus = view.findViewById(R.id.tv_hud_status)
        hudModifiers = view.findViewById(R.id.tv_hud_modifiers)
        hudZone = view.findViewById(R.id.tv_hud_zone)
        radialHUD = view.findViewById(R.id.radial_hud_view)

        val isSymmetric = prefs.getBoolean("is_symmetric_slices", true)
        engine.setSymmetricSlices(isSymmetric)
        radialHUD?.isSymmetric = isSymmetric

        view.findViewById<TextView>(R.id.tv_ime_cheatsheet1)?.setText(R.string.ime_cheatsheet1)
        view.findViewById<TextView>(R.id.tv_ime_cheatsheet2)?.setText(R.string.ime_cheatsheet2)

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
        val (rx, ry) = InputEngine.readRightStick(event)
        lastStickX = rx
        lastStickY = ry

        // 1. Process motion event first so triggers and stick state are updated in engine
        val result = engine.onMotionEvent(event)

        // 2. Check if Mouse Layer state changed
        val wasMouseActive = VirtualMouseManager.isMouseLayerActive
        val isMouseActive = engine.isMouseLayerActive
        if (wasMouseActive != isMouseActive) {
            VirtualMouseManager.setMouseLayerActive(this, isMouseActive)
        }

        // 3. D-Pad Hat Navigation
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

        if (isMouseActive) {
            VirtualMouseManager.updateStick(rx, ry)

            if (hatX != lastHatX) {
                if (hatX < -0.5f) {
                    VirtualMouseManager.onMouseButton(VirtualMouseManager.MouseButton.LEFT, isDown = true)
                    hudStatus?.setText(R.string.hud_mouse_left_click)
                } else if (lastHatX < -0.5f) {
                    VirtualMouseManager.onMouseButton(VirtualMouseManager.MouseButton.LEFT, isDown = false)
                }

                if (hatX > 0.5f) {
                    VirtualMouseManager.onMouseButton(VirtualMouseManager.MouseButton.RIGHT, isDown = true)
                    hudStatus?.setText(R.string.hud_mouse_right_click)
                } else if (lastHatX > 0.5f) {
                    VirtualMouseManager.onMouseButton(VirtualMouseManager.MouseButton.RIGHT, isDown = false)
                }
                lastHatX = hatX
            }

            if (hatY != lastHatY) {
                if (hatY < -0.5f) {
                    VirtualMouseManager.scrollUp()
                    hudStatus?.setText(R.string.hud_mouse_scroll_up)
                } else if (hatY > 0.5f) {
                    VirtualMouseManager.scrollDown()
                    hudStatus?.setText(R.string.hud_mouse_scroll_down)
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

        if (result != null) {
            engine.dispatchEvent(currentInputConnection, result)
            updateHud(lastEvent = result, x = lastStickX, y = lastStickY)
            return true
        }

        updateHud(lastEvent = null, x = lastStickX, y = lastStickY)
        return true
    }

    /**
     * Injects directional cursor motion key events via [InputConnection.sendKeyEvent]
     * preserving all currently engaged modifier keys.
     */
    private fun handleDpadNavigation(keyCode: Int) {
        val ic = currentInputConnection ?: return
        val mask = engine.currentButtonMask
        val metaState = buildMetaState(mask)

        ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
        ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, metaState))
        val dirRes = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> R.string.dpad_left
            KeyEvent.KEYCODE_DPAD_RIGHT -> R.string.dpad_right
            KeyEvent.KEYCODE_DPAD_UP -> R.string.dpad_up
            KeyEvent.KEYCODE_DPAD_DOWN -> R.string.dpad_down
            else -> null
        }
        val dirName = dirRes?.let { getString(it) }.orEmpty()
        hudStatus?.text = getString(R.string.hud_cursor_status, dirName)
    }

    /**
     * Handles hardware button presses for layer selection (R1), layer back (L1),
     * modifier holds, face button typing (A/B/X/Y), D-pad navigation, and system actions.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 0. Mouse Layer check:
        if (keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            engine.onKeyEvent(keyCode, isDown = true)
            VirtualMouseManager.setMouseLayerActive(this, true)
            hudStatus?.setText(R.string.hud_mouse_layer_status)
            updateHud(lastEvent = null, x = lastStickX, y = lastStickY)
            return true
        }

        if (engine.isMouseLayerActive) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_A -> {
                    if (event.repeatCount == 0) {
                        VirtualMouseManager.onMouseButton(VirtualMouseManager.MouseButton.LEFT, isDown = true)
                        hudStatus?.setText(R.string.hud_mouse_left_click)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_B -> {
                    if (event.repeatCount == 0) {
                        VirtualMouseManager.onMouseButton(VirtualMouseManager.MouseButton.RIGHT, isDown = true)
                        hudStatus?.setText(R.string.hud_mouse_right_click)
                    }
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_THUMBR -> {
                    if (event.repeatCount == 0) {
                        VirtualMouseManager.onMouseButton(VirtualMouseManager.MouseButton.MIDDLE, isDown = true)
                        hudStatus?.setText(R.string.hud_mouse_middle_click)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    VirtualMouseManager.scrollUp()
                    hudStatus?.setText(R.string.hud_mouse_scroll_up)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    VirtualMouseManager.scrollDown()
                    hudStatus?.setText(R.string.hud_mouse_scroll_down)
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
                val targetKeyCode = if (isShift) KeyEvent.KEYCODE_FORWARD_DEL else KeyEvent.KEYCODE_DEL
                val actionNameRes = if (isShift) R.string.action_del_forward else R.string.action_backspace

                if ((mask and (InputEngine.FLAG_CTRL or InputEngine.FLAG_ALT or InputEngine.FLAG_SUPER)) != 0) {
                    val metaState = buildMetaState(mask)
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
                hudStatus?.text = getString(R.string.hud_emitted_format, getString(actionNameRes))
                updateHud(lastEvent = null, x = lastStickX, y = lastStickY)
                return true
            }

            // Cross (KEYCODE_BUTTON_A): Space
            KeyEvent.KEYCODE_BUTTON_A -> {
                sendKeyOrCommit(KeyEvent.KEYCODE_SPACE, ' ', R.string.action_space)
                return true
            }

            // Triangle (KEYCODE_BUTTON_Y): Enter / Newline
            KeyEvent.KEYCODE_BUTTON_Y -> {
                sendKeyOrCommit(KeyEvent.KEYCODE_ENTER, '\n', R.string.action_enter)
                return true
            }

            // Circle (KEYCODE_BUTTON_B): Tab
            KeyEvent.KEYCODE_BUTTON_B -> {
                sendKeyOrCommit(KeyEvent.KEYCODE_TAB, '\t', R.string.action_tab)
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

            // R3: No-op when not in mouse mode
            KeyEvent.KEYCODE_BUTTON_THUMBR -> {
                return true
            }

            // Guide / Mode Button: Super
            KeyEvent.KEYCODE_BUTTON_MODE -> {
                val mask = engine.currentButtonMask
                val metaState = buildMetaState(mask) or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
                currentInputConnection?.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_META_LEFT, 0, metaState))
                currentInputConnection?.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_META_LEFT, 0, metaState))
                hudStatus?.text = getString(R.string.hud_emitted_format, getString(R.string.action_super))
                updateHud(lastEvent = null, x = lastStickX, y = lastStickY)
                return true
            }

            // Share / Select Button
            KeyEvent.KEYCODE_BUTTON_SELECT -> {
                val action = prefs.getString("select_button_action", "toggle_hud") ?: "toggle_hud"
                executeSystemButtonAction(action)
                updateHud(lastEvent = null, x = lastStickX, y = lastStickY)
                return true
            }

            // Options / Start: Escape (ESC) by default (or configured action)
            KeyEvent.KEYCODE_BUTTON_START -> {
                val action = prefs.getString("start_button_action", "escape") ?: "escape"
                executeSystemButtonAction(action)
                updateHud(lastEvent = null, x = lastStickX, y = lastStickY)
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    /**
     * Builds an Android [KeyEvent] metaState bitmask from active [InputEngine] modifier flags.
     */
    private fun buildMetaState(mask: Int): Int {
        var metaState = 0
        if ((mask and InputEngine.FLAG_SHIFT) != 0) metaState = metaState or KeyEvent.META_SHIFT_ON
        if ((mask and InputEngine.FLAG_CTRL) != 0) metaState = metaState or KeyEvent.META_CTRL_ON
        if ((mask and InputEngine.FLAG_ALT) != 0) metaState = metaState or KeyEvent.META_ALT_ON
        if ((mask and InputEngine.FLAG_SUPER) != 0) metaState = metaState or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        return metaState
    }

    /**
     * Dispatches a key event with metaState if modifier flags are held, or commits text / sends key char otherwise.
     */
    private fun sendKeyOrCommit(keyCode: Int, plainChar: Char?, actionNameRes: Int) {
        val mask = engine.currentButtonMask
        val hasModifiers = (mask and (InputEngine.FLAG_CTRL or InputEngine.FLAG_ALT or InputEngine.FLAG_SUPER)) != 0
        if (hasModifiers) {
            val metaState = buildMetaState(mask)
            currentInputConnection?.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
            currentInputConnection?.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, metaState))
        } else if (plainChar != null) {
            if (plainChar == ' ') {
                currentInputConnection?.commitText(" ", 1)
            } else {
                sendKeyChar(plainChar)
            }
        } else {
            sendDownUpKeyEvents(keyCode)
        }
        hudStatus?.text = getString(R.string.hud_emitted_format, getString(actionNameRes))
        updateHud(lastEvent = null, x = lastStickX, y = lastStickY)
    }

    /**
     * Executes user-configured action for Select/Share or Start/Options buttons.
     */
    private fun executeSystemButtonAction(actionKey: String) {
        when (actionKey) {
            "escape" -> {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ESCAPE)
                hudStatus?.text = getString(R.string.hud_emitted_format, "Esc")
            }
            "hide_keyboard" -> {
                requestHideSelf(0)
            }
            "toggle_hud_hide_keyboard" -> {
                FloatingHUDManager.toggleFloater(this)
                requestHideSelf(0)
            }
            else -> { // "toggle_hud"
                val isShown = FloatingHUDManager.toggleFloater(this)
                hudStatus?.setText(if (isShown) R.string.hud_overlay_visible else R.string.hud_overlay_hidden)
            }
        }
    }

    /**
     * Handles hardware button releases, clearing modifier states and mouse layer flags.
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            engine.onKeyEvent(keyCode, isDown = false)
            VirtualMouseManager.setMouseLayerActive(this, false)
            updateHud(lastEvent = null, x = lastStickX, y = lastStickY)
            return true
        }
        if (engine.isMouseLayerActive) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_A -> {
                    VirtualMouseManager.onMouseButton(VirtualMouseManager.MouseButton.LEFT, isDown = false)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_B -> {
                    VirtualMouseManager.onMouseButton(VirtualMouseManager.MouseButton.RIGHT, isDown = false)
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_THUMBR -> {
                    VirtualMouseManager.onMouseButton(VirtualMouseManager.MouseButton.MIDDLE, isDown = false)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    return true
                }
            }
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR) {
            return true
        }
        val handled = engine.onKeyEvent(keyCode, isDown = false)
        updateHud(lastEvent = null, x = lastStickX, y = lastStickY)
        if (handled) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        FloatingHUDManager.activeEngine = engine
        updateHud(lastEvent = null, x = lastStickX, y = lastStickY)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        engine.backToBaseLayer()
        engine.resetAll()
        lastStickX = 0f
        lastStickY = 0f
        updateHud(lastEvent = null, x = 0f, y = 0f)
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.backToBaseLayer()
        engine.resetAll()
        ThemeManager.unregister(this)
        if (FloatingHUDManager.activeEngine === engine) {
            FloatingHUDManager.activeEngine = null
        }
    }

    override fun onThemeChanged(theme: ThemeManager.ColorScheme) {
        applyThemeToIME(theme)
    }

    /**
     * Applies the selected [ThemeManager.ColorScheme] styling across all IME view components.
     */
    private fun applyThemeToIME(theme: ThemeManager.ColorScheme) {
        radialHUD?.applyColorScheme(theme)
        rootView?.setBackgroundColor(theme.cardBackground)
        hudZone?.setTextColor(theme.centerInfoText)
        hudStatus?.setTextColor(theme.headerText)
        hudModifiers?.setTextColor(theme.modeAbcColor)
        rootView?.findViewById<TextView>(R.id.tv_ime_cheatsheet1)?.setTextColor(theme.inactiveText)
        rootView?.findViewById<TextView>(R.id.tv_ime_cheatsheet2)?.setTextColor(theme.headerText)
    }

    /**
     * Updates the HUD visual telemetry (zone label, active modifiers, last emitted char/action,
     * and stick aiming reticle).
     */
    private fun updateHud(lastEvent: InputEngine.ProcessedEvent? = null, x: Float, y: Float) {
        val mask = engine.currentButtonMask
        val layer = engine.currentLayer
        val isSecond = engine.isSecondLayerActive

        val isMouse = engine.isMouseLayerActive
        val zoneLabel = if (isMouse) {
            getString(R.string.layer_mouse)
        } else when (layer) {
            InputEngine.Layer.BASE -> getString(R.string.layer_base)
            InputEngine.Layer.MORE_SYM -> getString(if (isSecond) R.string.layer_sym_2 else R.string.layer_sym_1)
            InputEngine.Layer.NUM_SYM -> getString(if (isSecond) R.string.layer_num_2 else R.string.layer_num_1)
            InputEngine.Layer.FN -> getString(if (isSecond) R.string.layer_fn_2 else R.string.layer_fn_1)
            InputEngine.Layer.Q_Z -> getString(if (isSecond) R.string.layer_qz_2 else R.string.layer_qz_1)
            InputEngine.Layer.MACRO -> getString(R.string.layer_macros)
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

        val modText = if (activeMods.isEmpty()) {
            getString(R.string.hud_mods_none)
        } else {
            getString(R.string.hud_mods_format, activeMods.joinToString("+"))
        }
        hudModifiers?.text = modText

        if (isMouse) {
            hudStatus?.setText(R.string.hud_mouse_dpad_help)
        } else if (lastEvent != null) {
            val displayChar = when {
                lastEvent.charCode in InputEngine.KEY_MACRO_0..InputEngine.KEY_MACRO_7 -> {
                    val slot = lastEvent.charCode - InputEngine.KEY_MACRO_0
                    getString(R.string.hud_macro_format, MacroManager.getMacro(slot).displayName)
                }
                lastEvent.charCode == InputEngine.KEY_SUPER -> getString(R.string.action_super)
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
                lastEvent.char == ' '  -> getString(R.string.action_space)
                lastEvent.isCtrl && lastEvent.charCode in 1..26 -> "Ctrl+" + ('A'.code + lastEvent.charCode - 1).toChar()
                lastEvent.isCtrl && lastEvent.char in 'a'..'z' -> "Ctrl+" + lastEvent.char.uppercaseChar()
                lastEvent.isCtrl && lastEvent.char in 'A'..'Z' -> "Ctrl+" + lastEvent.char
                lastEvent.isAlt && lastEvent.char in 'a'..'z' -> "Alt+" + lastEvent.char.uppercaseChar()
                lastEvent.isSuper && lastEvent.char in 'a'..'z' -> "Win+" + lastEvent.char.uppercaseChar()
                else -> lastEvent.char.toString()
            }
            hudStatus?.text = getString(R.string.hud_emitted_format, displayChar)
        } else {
            hudStatus?.text = getString(R.string.hud_status_ready)
        }

        radialHUD?.updateState(x, y, layer, isSecond, isShift, isCtrl, isAlt, isCaps, isSuper)
        FloatingHUDManager.updateInput(x, y, layer, isSecond, isShift, isCtrl, isAlt, isCaps, isSuper)
    }
}
