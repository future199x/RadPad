package com.radpad.app

import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
import android.hardware.input.InputManager
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import java.util.Locale

/**
 * Diagnostic controller detector, live radial dial telemetry tester, and setup configurator.
 *
 * ## Responsibilities
 * - **Controller Detection**: Monitors gamepad hardware attachments via [InputManager.InputDeviceListener].
 * - **Live Telemetry**: Real-time interactive preview of [KinematicRadialHUDView] reflecting stick aiming and layers.
 * - **Guided Setup**: 3-step setup flow for IME enablement, keyboard selection, and floating HUD overlay.
 * - **System Customization**: Configures radial symmetry, color scheme, button actions, and macro mappings.
 * - **Cheatsheet Reference**: Collapsible reference accordion for all controller button mappings.
 */
class MainActivity : AppCompatActivity(), InputManager.InputDeviceListener, ThemeManager.ThemeListener, FloatingHUDManager.Listener {

    private lateinit var tvStatus: TextView
    private lateinit var tvDetail: TextView
    private lateinit var tvLiveInput: TextView
    private lateinit var btnFloatingHud: Button
    private var radialHUD: KinematicRadialHUDView? = null
    private var inputManager: InputManager? = null

    private val localEngine = InputEngine()
    private val engine: InputEngine
        get() = FloatingHUDManager.activeEngine ?: localEngine

    private var currentX: Float = 0f
    private var currentY: Float = 0f
    private var currentLayer: InputEngine.Layer = InputEngine.Layer.BASE
    private var isSecondLayer: Boolean = false
    private var isShift: Boolean = false
    private var isCtrl: Boolean = false
    private var isAlt: Boolean = false
    private var isSuper: Boolean = false
    private var isCaps: Boolean = false
    private var isL2ButtonDown: Boolean = false
    private var isR2ButtonDown: Boolean = false
    private var l2TriggerActive: Boolean = false
    private var r2TriggerActive: Boolean = false
    private var leftStickShiftActive: Boolean = false
    private var leftStickCtrlActive: Boolean = false
    private var leftStickAltActive: Boolean = false
    private var leftStickSuperActive: Boolean = false
    private var lastHatX: Float = 0f
    private var lastHatY: Float = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tv_controller_status)
        tvDetail = findViewById(R.id.tv_controller_detail)
        tvLiveInput = findViewById(R.id.tv_live_input)
        radialHUD = findViewById(R.id.main_radial_hud_view)

        val btnEnable = findViewById<Button>(R.id.btn_enable_ime)
        val btnSelect = findViewById<Button>(R.id.btn_select_ime)
        btnFloatingHud = findViewById(R.id.btn_floating_hud)
        updateFloatingButtonState(FloatingHUDService.isRunning)

        btnEnable.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        btnSelect.setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showInputMethodPicker()
        }

        btnFloatingHud.setOnClickListener {
            toggleFloatingHUD()
        }

        // Collapsible Button Mapping Accordion
        findViewById<View>(R.id.btn_toggle_button_map)?.setOnClickListener {
            val container = findViewById<View>(R.id.container_button_map)
            val chevron = findViewById<TextView>(R.id.icon_chevron)
            if (container?.visibility == View.VISIBLE) {
                container.visibility = View.GONE
                chevron?.setText(R.string.chevron_down)
            } else {
                container?.visibility = View.VISIBLE
                chevron?.setText(R.string.chevron_up)
            }
        }

        // Slices Symmetry Setting Switch
        val swSymmetric = findViewById<SwitchCompat>(R.id.sw_symmetric_slices)
        val prefs = getSharedPreferences("radpad_prefs", MODE_PRIVATE)
        val isSymmetric = prefs.getBoolean("is_symmetric_slices", true)
        swSymmetric.isChecked = isSymmetric
        radialHUD?.isSymmetric = isSymmetric
        engine.setSymmetricSlices(isSymmetric)

        swSymmetric.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("is_symmetric_slices", isChecked) }
            radialHUD?.isSymmetric = isChecked
            engine.setSymmetricSlices(isChecked)
        }

        // Button Action Spinners (Select and Start)
        val buttonActions = arrayOf(
            getString(R.string.action_name_escape),
            getString(R.string.action_name_hide_keyboard),
            getString(R.string.action_name_toggle_hud),
            getString(R.string.action_name_toggle_hud_hide_keyboard)
        )
        val buttonActionKeys = arrayOf("escape", "hide_keyboard", "toggle_hud", "toggle_hud_hide_keyboard")

        val actionAdapter = ArrayAdapter(this, R.layout.item_spinner, buttonActions).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }

        // Select / Share Button Spinner
        val spnSelectAction = findViewById<Spinner>(R.id.spn_select_action)
        spnSelectAction.adapter = actionAdapter
        val currentSelectAction = prefs.getString("select_button_action", "toggle_hud")
        val selectIdx = buttonActionKeys.indexOf(currentSelectAction).coerceAtLeast(0)
        spnSelectAction.setSelection(selectIdx)

        spnSelectAction.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val chosenKey = buttonActionKeys[position]
                prefs.edit { putString("select_button_action", chosenKey) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Start / Options Button Spinner
        val spnStartAction = findViewById<Spinner>(R.id.spn_start_action)
        spnStartAction.adapter = actionAdapter
        val currentStartAction = prefs.getString("start_button_action", "escape")
        val startIdx = buttonActionKeys.indexOf(currentStartAction).coerceAtLeast(0)
        spnStartAction.setSelection(startIdx)

        spnStartAction.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val chosenKey = buttonActionKeys[position]
                prefs.edit { putString("start_button_action", chosenKey) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        ThemeManager.init(this)
        ThemeManager.register(this)

        val spnTheme = findViewById<Spinner>(R.id.spn_color_scheme)
        val themes = ThemeManager.ColorScheme.entries
        val themeNames = themes.map { it.displayName }
        val adapter = ArrayAdapter(this, R.layout.item_spinner, themeNames).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        spnTheme.adapter = adapter
        val currentIdx = themes.indexOf(ThemeManager.currentTheme).coerceAtLeast(0)
        spnTheme.setSelection(currentIdx)

        spnTheme.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedTheme = themes[position]
                if (selectedTheme != ThemeManager.currentTheme) {
                    ThemeManager.setTheme(this@MainActivity, selectedTheme)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Macro Layer Spinners & Edit Buttons (Slots 0 to 7)
        MacroManager.init(this)
        setupMacroControls()

        inputManager = getSystemService(INPUT_SERVICE) as? InputManager
        inputManager?.registerInputDeviceListener(this, null)
        FloatingHUDManager.register(this)

        findViewById<Button>(R.id.btn_enable_accessibility)?.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        detectConnectedControllers()
        refreshRadialHUD()
    }

    override fun onStateUpdated(
        x: Float,
        y: Float,
        layer: InputEngine.Layer,
        secondLayer: Boolean,
        shift: Boolean,
        ctrl: Boolean,
        alt: Boolean,
        caps: Boolean,
        superKey: Boolean
    ) {
        runOnUiThread {
            currentX = x
            currentY = y
            currentLayer = layer
            isSecondLayer = secondLayer
            isShift = shift
            isCtrl = ctrl
            isAlt = alt
            isCaps = caps
            isSuper = superKey
            radialHUD?.updateState(x, y, layer, secondLayer, shift, ctrl, alt, caps, superKey)
        }
    }

    override fun onFloaterStateChanged(isRunning: Boolean) {
        runOnUiThread {
            updateFloatingButtonState(isRunning)
        }
    }

    override fun onThemeChanged(theme: ThemeManager.ColorScheme) {
        radialHUD?.applyColorScheme(theme)
        findViewById<View>(R.id.banner_controller_status)?.backgroundTintList = ColorStateList.valueOf(theme.cardBackground)
        findViewById<TextView>(R.id.tv_controller_detail)?.setTextColor(theme.inactiveText)
        refreshRadialHUD()
    }


    private fun setupMacroControls() {
        val macroSpinnerIds = intArrayOf(
            R.id.spn_macro_0, R.id.spn_macro_1, R.id.spn_macro_2, R.id.spn_macro_3,
            R.id.spn_macro_4, R.id.spn_macro_5, R.id.spn_macro_6, R.id.spn_macro_7
        )
        val macroCustomButtonIds = intArrayOf(
            R.id.btn_macro_custom_0, R.id.btn_macro_custom_1, R.id.btn_macro_custom_2, R.id.btn_macro_custom_3,
            R.id.btn_macro_custom_4, R.id.btn_macro_custom_5, R.id.btn_macro_custom_6, R.id.btn_macro_custom_7
        )

        for (slot in 0..7) {
            val spn = findViewById<Spinner>(macroSpinnerIds[slot]) ?: continue
            val btn = findViewById<Button>(macroCustomButtonIds[slot])

            updateSpinnerForSlot(slot, spn)

            btn?.setOnClickListener {
                showCustomMacroDialog(slot)
            }
        }
    }

    private fun updateSpinnerForSlot(slot: Int, spn: Spinner) {
        val currentMacro = MacroManager.getMacro(slot)
        val options = MacroManager.PRESETS.map { it.displayName }.toMutableList()
        val customOption = if (currentMacro.id.startsWith("custom")) {
            getString(R.string.macro_custom_option_format, currentMacro.displayName, currentMacro.shortHudLabel)
        } else {
            getString(R.string.macro_custom_option_default)
        }
        options.add(customOption)

        val spinnerAdapter = ArrayAdapter(this, R.layout.item_spinner, options).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        spn.adapter = spinnerAdapter

        val selectedPos = if (currentMacro.id.startsWith("custom")) {
            options.size - 1
        } else {
            val idx = MacroManager.PRESETS.indexOfFirst { it.id == currentMacro.id }
            if (idx >= 0) idx else 0
        }
        spn.setSelection(selectedPos)

        spn.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == options.size - 1) {
                    if (!currentMacro.id.startsWith("custom")) {
                        showCustomMacroDialog(slot)
                    }
                } else {
                    val preset = MacroManager.PRESETS[position]
                    MacroManager.setMacro(slot, preset.id)
                    refreshRadialHUD()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun refreshMacroSpinners() {
        val macroSpinnerIds = intArrayOf(
            R.id.spn_macro_0, R.id.spn_macro_1, R.id.spn_macro_2, R.id.spn_macro_3,
            R.id.spn_macro_4, R.id.spn_macro_5, R.id.spn_macro_6, R.id.spn_macro_7
        )
        for (slot in 0..7) {
            val spn = findViewById<Spinner>(macroSpinnerIds[slot]) ?: continue
            updateSpinnerForSlot(slot, spn)
        }
    }

    private fun showCustomMacroDialog(slot: Int) {
        val current = MacroManager.getMacro(slot)
        val directions = arrayOf(
            getString(R.string.macro_dir_north),
            getString(R.string.macro_dir_northeast),
            getString(R.string.macro_dir_east),
            getString(R.string.macro_dir_southeast),
            getString(R.string.macro_dir_south),
            getString(R.string.macro_dir_southwest),
            getString(R.string.macro_dir_west),
            getString(R.string.macro_dir_northwest)
        )
        val slotDir = directions.getOrElse(slot) { getString(R.string.macro_slot_fallback, slot + 1) }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
        }

        val tvInfo = TextView(this).apply {
            setText(R.string.macro_info_text)
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvInfo)

        val etCombo = EditText(this).apply {
            setHint(R.string.macro_combo_hint)
            setText(if (current.type == MacroManager.MacroType.TEXT) "text:${current.textPayload}" else current.displayName)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_input_field)
            setPadding(30, 24, 30, 24)
        }
        layout.addView(etCombo)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 20)
        }
        layout.addView(spacer)

        val etLabel = EditText(this).apply {
            setHint(R.string.macro_label_hint)
            setText(current.shortHudLabel)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_input_field)
            setPadding(30, 24, 30, 24)
        }
        layout.addView(etLabel)

        val tvStatus = TextView(this).apply {
            setText(R.string.macro_status_validating)
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setPadding(0, 16, 0, 0)
        }
        layout.addView(tvStatus)

        var validatedItem: MacroManager.MacroItem? = null

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.macro_dialog_title, slot + 1, slotDir))
            .setView(layout)
            .setPositiveButton(R.string.btn_save, null)
            .setNegativeButton(R.string.btn_cancel) { _, _ ->
                refreshMacroSpinners()
            }
            .create()

        fun validate() {
            val comboText = etCombo.text.toString()
            val labelText = etLabel.text.toString()
            when (val result = MacroManager.parse(comboText, labelText)) {
                is MacroManager.ValidationResult.Valid -> {
                    validatedItem = result.item
                    tvStatus.text = getString(R.string.macro_status_valid, result.description)
                    tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_success))
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled = true
                }
                is MacroManager.ValidationResult.Invalid -> {
                    validatedItem = null
                    tvStatus.text = getString(R.string.macro_status_invalid, result.errorMessage)
                    tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_danger))
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled = false
                }
            }
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { validate() }
            override fun afterTextChanged(s: Editable?) {}
        }
        etCombo.addTextChangedListener(watcher)
        etLabel.addTextChangedListener(watcher)

        dialog.setOnShowListener {
            validate()
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
                val item = validatedItem
                if (item != null) {
                    MacroManager.setCustomMacro(slot, item)
                    refreshMacroSpinners()
                    refreshRadialHUD()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        updateFloatingButtonState()
        refreshRadialHUD()
        refreshMacroSpinners()

        val tvAccessibility = findViewById<TextView>(R.id.tv_accessibility_status)
        val btnAccessibility = findViewById<Button>(R.id.btn_enable_accessibility)
        if (RadPadAccessibilityService.isEnabled) {
            tvAccessibility?.setText(R.string.accessibility_status_enabled)
            tvAccessibility?.setTextColor(ContextCompat.getColor(this, R.color.accent_success))
            btnAccessibility?.setText(R.string.btn_clicks_configured)
        } else {
            tvAccessibility?.setText(R.string.accessibility_status_disabled)
            tvAccessibility?.setTextColor(ContextCompat.getColor(this, R.color.accent_danger))
            btnAccessibility?.setText(R.string.btn_clicks_enable)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        inputManager?.unregisterInputDeviceListener(this)
        ThemeManager.unregister(this)
        FloatingHUDManager.unregister(this)
    }

    private fun executeSystemButtonAction(buttonName: String, actionKey: String) {
        when (actionKey) {
            "escape" -> {
                tvLiveInput.text = getString(R.string.action_escape_desc, buttonName)
            }
            "hide_keyboard" -> {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
                tvLiveInput.text = getString(R.string.action_hide_keyboard_desc, buttonName)
            }
            "toggle_hud_hide_keyboard" -> {
                toggleFloatingHUD()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
                tvLiveInput.text = getString(R.string.action_toggle_hud_hide_desc, buttonName)
            }
            else -> { // "toggle_hud"
                toggleFloatingHUD()
                tvLiveInput.text = getString(R.string.action_toggle_hud_desc, buttonName)
            }
        }
    }

    private fun toggleFloatingHUD() {
        val willRun = FloatingHUDManager.toggleFloater(this)
        updateFloatingButtonState(willRun)
    }

    private fun updateFloatingButtonState(isRunning: Boolean = FloatingHUDService.isRunning) {
        if (isRunning) {
            btnFloatingHud.setText(R.string.btn_close_floating_hud)
            btnFloatingHud.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.button_danger)
            )
        } else {
            btnFloatingHud.setText(R.string.btn_launch_floating_hud)
            btnFloatingHud.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.button_launch)
            )
        }
    }

    private fun detectConnectedControllers() {
        val allDevices = mutableListOf<String>()
        val gamepadNames = mutableListOf<String>()

        for (id in InputDevice.getDeviceIds()) {
            val dev = InputDevice.getDevice(id) ?: continue
            val sources = dev.sources
            allDevices.add("${dev.name} (id=$id)")

            val hasJoystick = (sources and InputDevice.SOURCE_JOYSTICK) != 0
            val hasGamepad = (sources and InputDevice.SOURCE_GAMEPAD) != 0
            val hasDpad = (sources and InputDevice.SOURCE_DPAD) != 0

            if (hasJoystick || hasGamepad || hasDpad ||
                dev.name.contains("pad", ignoreCase = true) ||
                dev.name.contains("controller", ignoreCase = true) ||
                dev.name.contains("sony", ignoreCase = true)) {
                gamepadNames.add(dev.name)
            }
        }

        if (gamepadNames.isNotEmpty()) {
            tvStatus.text = getString(R.string.status_gamepad_ready, gamepadNames.joinToString(", "))
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected))
            tvDetail.setText(R.string.detail_gamepad_ready)
        } else {
            tvStatus.setText(R.string.status_no_gamepad)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected))
            tvDetail.text = getString(R.string.detail_android_sees, allDevices.joinToString(", "))
        }
    }

    private fun refreshRadialHUD() {
        currentLayer = engine.currentLayer
        isSecondLayer = engine.isSecondLayerActive

        radialHUD?.updateState(currentX, currentY, currentLayer, isSecondLayer, isShift, isCtrl, isAlt, isCaps, isSuper)
        FloatingHUDManager.updateInput(currentX, currentY, currentLayer, isSecondLayer, isShift, isCtrl, isAlt, isCaps, isSuper)
    }

    private fun onDpadAction(dir: String) {
        tvStatus.text = getString(R.string.status_dpad_nav, dir)
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_primary))
        tvLiveInput.text = getString(R.string.live_dpad_nav, dir)
    }

    /**
     * Dispatches analog stick deflection and trigger pressure updates into [InputEngine]
     * and [VirtualMouseManager], updating the live status view and radial dial preview.
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val (lx, ly) = InputEngine.readLeftStick(event)
        val (rx, ry) = InputEngine.readRightStick(event)
        currentX = rx
        currentY = ry

        val standaloneSuper = engine.onMotionEvent(event)

        leftStickShiftActive = engine.isLeftStickShiftActive
        leftStickCtrlActive = engine.isLeftStickCtrlActive
        leftStickAltActive = engine.isLeftStickAltActive
        leftStickSuperActive = engine.isLeftStickSuperActive

        isCtrl = engine.isCtrlActive
        isAlt = engine.isAltActive
        isSuper = engine.isSuperActive

        val l2Val = InputEngine.readTriggerAxis(event, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE)
        val r2Val = InputEngine.readTriggerAxis(event, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)

        l2TriggerActive = InputEngine.hysteresis(l2TriggerActive, l2Val, InputEngine.TRIGGER_ENGAGEMENT_THRESHOLD, InputEngine.TRIGGER_RELEASE_THRESHOLD)
        r2TriggerActive = InputEngine.hysteresis(r2TriggerActive, r2Val, InputEngine.TRIGGER_ENGAGEMENT_THRESHOLD, InputEngine.TRIGGER_RELEASE_THRESHOLD)

        isShift = engine.isShiftActive
        isSecondLayer = engine.isSecondLayerActive

        val isMouseActive = isR2ButtonDown || r2TriggerActive
        val wasMouseActive = VirtualMouseManager.isMouseLayerActive
        if (wasMouseActive != isMouseActive) {
            VirtualMouseManager.setMouseLayerActive(this, isMouseActive)
        }

        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

        if (isMouseActive) {
            VirtualMouseManager.updateStick(currentX, currentY)

            if (hatX != lastHatX) {
                if (hatX < -0.5f) {
                    VirtualMouseManager.onMouseButton(VirtualMouseManager.MouseButton.LEFT, isDown = true)
                    tvLiveInput.setText(R.string.live_mouse_left_click)
                } else if (lastHatX < -0.5f) {
                    VirtualMouseManager.onMouseButton(VirtualMouseManager.MouseButton.LEFT, isDown = false)
                }

                if (hatX > 0.5f) {
                    VirtualMouseManager.onMouseButton(VirtualMouseManager.MouseButton.RIGHT, isDown = true)
                    tvLiveInput.setText(R.string.live_mouse_right_click)
                } else if (lastHatX > 0.5f) {
                    VirtualMouseManager.onMouseButton(VirtualMouseManager.MouseButton.RIGHT, isDown = false)
                }
                lastHatX = hatX
            }

            if (hatY != lastHatY) {
                if (hatY < -0.5f) {
                    VirtualMouseManager.scrollUp()
                    tvLiveInput.setText(R.string.live_mouse_scroll_up)
                } else if (hatY > 0.5f) {
                    VirtualMouseManager.scrollDown()
                    tvLiveInput.setText(R.string.live_mouse_scroll_down)
                }
                lastHatY = hatY
            }

            tvStatus.setText(R.string.status_mouse_layer_active)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_info))
            tvLiveInput.text = String.format(Locale.US, getString(R.string.live_mouse_cursor_format), VirtualMouseManager.cursorX, VirtualMouseManager.cursorY, currentX, currentY)
            refreshRadialHUD()
            return true
        }

        // Standard D-Pad Hat Navigation (when Mouse Layer is inactive)
        if (hatX != lastHatX) {
            if (hatX < -0.5f) onDpadAction(getString(R.string.dpad_left))
            else if (hatX > 0.5f) onDpadAction(getString(R.string.dpad_right))
            lastHatX = hatX
        }

        if (hatY != lastHatY) {
            if (hatY < -0.5f) onDpadAction(getString(R.string.dpad_up))
            else if (hatY > 0.5f) onDpadAction(getString(R.string.dpad_down))
            lastHatY = hatY
        }

        val activeMods = mutableListOf<String>()
        if (isCaps) activeMods.add("CAPS")
        if (isShift) activeMods.add("SHIFT")
        if (isCtrl) activeMods.add("CTRL")
        if (isAlt) activeMods.add("ALT")
        if (isSuper) activeMods.add("WIN")
        if (isSecondLayer) activeMods.add("2ND")
        val modSummary = if (activeMods.isEmpty()) "NONE" else activeMods.joinToString("+")

        val layerName = if (isSecondLayer) {
            getString(R.string.layer_second_suffix, engine.currentLayer.displayName)
        } else {
            engine.currentLayer.displayName
        }
        tvStatus.text = getString(R.string.status_controller_signal_layer, layerName)
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected))

        if (standaloneSuper != null) {
            tvStatus.setText(R.string.status_left_stick_flick)
            tvLiveInput.setText(R.string.live_left_stick_flick)
        } else {
            tvLiveInput.text = String.format(Locale.US, getString(R.string.live_telemetry_format), lx, ly, currentX, currentY, modSummary, l2Val, r2Val)
        }

        refreshRadialHUD()
        return true
    }

    /**
     * Intercepts gamepad button presses for testing selection, navigation, and system shortcuts in the test suite.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        var handled = true
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                // Right Bumper: Select
                val result = engine.onSelect()
                val emitted = result?.let {
                    when (it.charCode) {
                        in InputEngine.KEY_MACRO_0..InputEngine.KEY_MACRO_7 -> {
                            val slot = it.charCode - InputEngine.KEY_MACRO_0
                            getString(R.string.hud_macro_format, MacroManager.getMacro(slot).displayName)
                        }
                        InputEngine.KEY_VOL_MUTE -> "VOL MUTE"
                        InputEngine.KEY_VOL_UP -> "VOL+"
                        InputEngine.KEY_VOL_DOWN -> "VOL-"
                        InputEngine.KEY_DELETE -> "DEL"
                        in InputEngine.KEY_F1..InputEngine.KEY_F12 -> "F${it.charCode - InputEngine.KEY_F1 + 1}"
                        else -> it.char.toString()
                    }
                } ?: getString(R.string.live_selected_layer, engine.currentLayer.displayName)
                tvLiveInput.text = getString(R.string.live_r1_select, emitted)
                handled = true
            }
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                // Left Bumper: Go back one layer level (Page 2 -> Page 1 -> Base)
                engine.goBackLayer()
                tvLiveInput.text = getString(R.string.live_l1_back, engine.currentLayer.displayName)
                handled = true
            }
            KeyEvent.KEYCODE_BUTTON_L2 -> {
                isL2ButtonDown = true
                engine.onKeyEvent(keyCode, isDown = true)
                isShift = engine.isShiftActive
                handled = true
            }
            KeyEvent.KEYCODE_BUTTON_R2 -> {
                isR2ButtonDown = true
                engine.onKeyEvent(keyCode, isDown = true)
                VirtualMouseManager.setMouseLayerActive(this, true)
                tvStatus.setText(R.string.status_mouse_layer_active)
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_info))
                tvLiveInput.setText(R.string.live_mouse_layer_activated)
                handled = true
            }
            KeyEvent.KEYCODE_BUTTON_THUMBL -> {
                engine.onKeyEvent(keyCode, isDown = true)
                isCaps = (engine.currentButtonMask and InputEngine.FLAG_CAPS_LOCK) != 0
                handled = true
            }
            KeyEvent.KEYCODE_BUTTON_THUMBR -> {
                if (isR2ButtonDown || r2TriggerActive || VirtualMouseManager.isMouseLayerActive) {
                    VirtualMouseManager.onMouseButton(VirtualMouseManager.MouseButton.MIDDLE, isDown = true)
                    tvLiveInput.setText(R.string.live_mouse_middle_click)
                }
                handled = true
            }
            KeyEvent.KEYCODE_BUTTON_MODE -> isSuper = true
            KeyEvent.KEYCODE_BUTTON_SELECT -> {
                val prefs = getSharedPreferences("radpad_prefs", MODE_PRIVATE)
                val action = prefs.getString("select_button_action", "toggle_hud") ?: "toggle_hud"
                executeSystemButtonAction("Select", action)
                handled = true
            }
            KeyEvent.KEYCODE_BUTTON_START -> {
                val prefs = getSharedPreferences("radpad_prefs", MODE_PRIVATE)
                val action = prefs.getString("start_button_action", "escape") ?: "escape"
                executeSystemButtonAction("Start", action)
                handled = true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isR2ButtonDown || r2TriggerActive) {
                    VirtualMouseManager.onMouseButton(VirtualMouseManager.MouseButton.LEFT, isDown = true)
                    tvLiveInput.setText(R.string.live_mouse_left_click)
                    handled = true
                } else {
                    onDpadAction(getString(R.string.dpad_left))
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isR2ButtonDown || r2TriggerActive) {
                    VirtualMouseManager.onMouseButton(VirtualMouseManager.MouseButton.RIGHT, isDown = true)
                    tvLiveInput.setText(R.string.live_mouse_right_click)
                    handled = true
                } else {
                    onDpadAction(getString(R.string.dpad_right))
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isR2ButtonDown || r2TriggerActive || VirtualMouseManager.isMouseLayerActive) {
                    VirtualMouseManager.scrollUp()
                    tvLiveInput.setText(R.string.live_mouse_scroll_up)
                    handled = true
                } else {
                    onDpadAction(getString(R.string.dpad_up))
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isR2ButtonDown || r2TriggerActive || VirtualMouseManager.isMouseLayerActive) {
                    VirtualMouseManager.scrollDown()
                    tvLiveInput.setText(R.string.live_mouse_scroll_down)
                    handled = true
                } else {
                    onDpadAction(getString(R.string.dpad_down))
                }
            }
            KeyEvent.KEYCODE_BUTTON_X -> {
                tvLiveInput.setText(if (isShift) R.string.live_square_del else R.string.live_square_backspace)
                handled = true
            }
            KeyEvent.KEYCODE_BUTTON_A -> {
                if (isR2ButtonDown || r2TriggerActive) {
                    VirtualMouseManager.onMouseButton(VirtualMouseManager.MouseButton.LEFT, isDown = true)
                    tvLiveInput.setText(R.string.live_mouse_left_click)
                    handled = true
                } else {
                    tvLiveInput.setText(R.string.live_cross_space)
                    handled = true
                }
            }
            KeyEvent.KEYCODE_BUTTON_Y -> {
                tvLiveInput.setText(R.string.live_triangle_enter)
                handled = true
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                if (isR2ButtonDown || r2TriggerActive) {
                    VirtualMouseManager.onMouseButton(VirtualMouseManager.MouseButton.RIGHT, isDown = true)
                    tvLiveInput.setText(R.string.live_mouse_right_click)
                    handled = true
                } else {
                    tvLiveInput.setText(R.string.live_circle_tab)
                    handled = true
                }
            }
            else -> handled = false
        }

        val btnName = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_R1 -> getString(R.string.btn_r1_select)
            KeyEvent.KEYCODE_BUTTON_L1 -> getString(R.string.btn_l1_base)
            KeyEvent.KEYCODE_BUTTON_X -> getString(if (isShift) R.string.live_square_del else R.string.live_square_backspace)
            KeyEvent.KEYCODE_BUTTON_A -> getString(R.string.btn_cross_space)
            KeyEvent.KEYCODE_BUTTON_Y -> getString(R.string.btn_triangle_enter)
            KeyEvent.KEYCODE_BUTTON_B -> getString(R.string.btn_circle_tab)
            else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
        }
        tvStatus.text = getString(R.string.status_controller_signal_detected, btnName)
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected))

        if (handled) {
            refreshRadialHUD()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Intercepts gamepad button releases to clear active modifiers and deactivate mouse mode.
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        var handled = true
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> {
                isL2ButtonDown = false
                engine.onKeyEvent(keyCode, isDown = false)
                isShift = engine.isShiftActive
            }
            KeyEvent.KEYCODE_BUTTON_R2 -> {
                isR2ButtonDown = false
                r2TriggerActive = false
                engine.onKeyEvent(keyCode, isDown = false)
                VirtualMouseManager.setMouseLayerActive(this, false)
                tvLiveInput.setText(R.string.live_mouse_layer_deactivated)
                handled = true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isR2ButtonDown || r2TriggerActive || VirtualMouseManager.isMouseLayerActive) {
                    handled = true
                } else {
                    isCtrl = false
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isR2ButtonDown || r2TriggerActive || VirtualMouseManager.isMouseLayerActive) {
                    VirtualMouseManager.onMouseButton(VirtualMouseManager.MouseButton.LEFT, isDown = false)
                    handled = true
                } else {
                    isAlt = false
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isR2ButtonDown || r2TriggerActive || VirtualMouseManager.isMouseLayerActive) {
                    VirtualMouseManager.onMouseButton(VirtualMouseManager.MouseButton.RIGHT, isDown = false)
                    handled = true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isR2ButtonDown || r2TriggerActive || VirtualMouseManager.isMouseLayerActive) {
                    handled = true
                }
            }
            KeyEvent.KEYCODE_BUTTON_THUMBR -> {
                if (isR2ButtonDown || r2TriggerActive || VirtualMouseManager.isMouseLayerActive) {
                    VirtualMouseManager.onMouseButton(VirtualMouseManager.MouseButton.MIDDLE, isDown = false)
                }
                handled = true
            }
            KeyEvent.KEYCODE_BUTTON_MODE -> isSuper = false
            KeyEvent.KEYCODE_BUTTON_SELECT -> {
                val prefs = getSharedPreferences("radpad_prefs", MODE_PRIVATE)
                if (prefs.getString("select_button_action", "paste") == "super") {
                    isSuper = false
                }
                handled = true
            }
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                handled = true
            }
            KeyEvent.KEYCODE_BUTTON_A -> {
                if (isR2ButtonDown || r2TriggerActive) {
                    VirtualMouseManager.onMouseButton(VirtualMouseManager.MouseButton.LEFT, isDown = false)
                    handled = true
                } else {
                    handled = false
                }
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                if (isR2ButtonDown || r2TriggerActive) {
                    VirtualMouseManager.onMouseButton(VirtualMouseManager.MouseButton.RIGHT, isDown = false)
                    handled = true
                } else {
                    handled = false
                }
            }
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y -> {
                handled = false
            }
            else -> handled = false
        }
        if (handled) {
            refreshRadialHUD()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onInputDeviceAdded(deviceId: Int) { detectConnectedControllers() }
    override fun onInputDeviceRemoved(deviceId: Int) { detectConnectedControllers() }
    override fun onInputDeviceChanged(deviceId: Int) { detectConnectedControllers() }
}
