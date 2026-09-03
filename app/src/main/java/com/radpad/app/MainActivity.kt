package com.radpad.app

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.Button
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

/**
 * MainActivity: Diagnostic Controller Detector, Live Radial Dial Telemetry, and Setup.
 */
class MainActivity : Activity(), InputManager.InputDeviceListener, ThemeManager.ThemeListener, FloatingHUDManager.Listener {

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
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tv_controller_status)
        tvDetail = findViewById(R.id.tv_controller_detail)
        tvLiveInput = findViewById(R.id.tv_live_input)
        radialHUD = findViewById(R.id.main_radial_hud_view)

        val btnEnable = findViewById<Button>(R.id.btn_enable_ime)
        val btnSelect = findViewById<Button>(R.id.btn_select_ime)
        btnFloatingHud = findViewById(R.id.btn_floating_hud)

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

        // Slices Symmetry Setting Switch
        val swSymmetric = findViewById<Switch>(R.id.sw_symmetric_slices)
        val prefs = getSharedPreferences("radpad_prefs", Context.MODE_PRIVATE)
        val isSymmetric = prefs.getBoolean("is_symmetric_slices", true)
        swSymmetric.isChecked = isSymmetric
        radialHUD?.isSymmetric = isSymmetric
        engine.setSymmetricSlices(isSymmetric)

        swSymmetric.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("is_symmetric_slices", isChecked).apply()
            radialHUD?.isSymmetric = isChecked
            engine.setSymmetricSlices(isChecked)
        }


        // Button Action Spinners (Select and Start)
        val buttonActions = arrayOf("Toggle HUD", "Hide Keyboard", "Toggle HUD + Hide Keyboard")
        val buttonActionKeys = arrayOf("toggle_hud", "hide_keyboard", "toggle_hud_hide_keyboard")

        val actionAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, buttonActions).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
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
                prefs.edit().putString("select_button_action", chosenKey).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Start / Options Button Spinner
        val spnStartAction = findViewById<Spinner>(R.id.spn_start_action)
        spnStartAction.adapter = actionAdapter
        val currentStartAction = prefs.getString("start_button_action", "hide_keyboard")
        val startIdx = buttonActionKeys.indexOf(currentStartAction).coerceAtLeast(0)
        spnStartAction.setSelection(startIdx)

        spnStartAction.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val chosenKey = buttonActionKeys[position]
                prefs.edit().putString("start_button_action", chosenKey).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        ThemeManager.init(this)
        ThemeManager.register(this)

        val spnTheme = findViewById<Spinner>(R.id.spn_color_scheme)
        val themes = ThemeManager.ColorScheme.values()
        val themeNames = themes.map { it.displayName }
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, themeNames).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
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

        inputManager = getSystemService(Context.INPUT_SERVICE) as? InputManager
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

    override fun onThemeChanged(theme: ThemeManager.ColorScheme) {
        radialHUD?.applyColorScheme(theme)
        findViewById<View>(R.id.banner_controller_status)?.setBackgroundColor(theme.cardBackground)
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
            "✏️ ${currentMacro.displayName} [${currentMacro.shortHudLabel}]"
        } else {
            "✏️ Custom / Key Combo..."
        }
        options.add(customOption)

        val spinnerAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, options).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
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
            "North (0°)", "NorthEast (45°)", "East (90°)", "SouthEast (135°)",
            "South (180°)", "SouthWest (225°)", "West (270°)", "NorthWest (315°)"
        )
        val slotDir = directions.getOrElse(slot) { "Slot ${slot + 1}" }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
        }

        val tvInfo = TextView(this).apply {
            text = "Enter key combo (e.g. ctrl+shift+z, win+shift+s, alt+tab) or text (e.g. text:hello):"
            textSize = 12f
            setTextColor(0xFFBAC2DE.toInt())
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvInfo)

        val etCombo = EditText(this).apply {
            hint = "Key combo (e.g. ctrl+shift+z)"
            setText(if (current.type == MacroManager.MacroType.TEXT) "text:${current.textPayload}" else current.displayName)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF6C7086.toInt())
        }
        layout.addView(etCombo)

        val etLabel = EditText(this).apply {
            hint = "Short HUD Label (e.g. REDO, max 6 chars)"
            setText(current.shortHudLabel)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF6C7086.toInt())
        }
        layout.addView(etLabel)

        val tvStatus = TextView(this).apply {
            text = "Validating..."
            textSize = 12f
            setPadding(0, 16, 0, 0)
        }
        layout.addView(tvStatus)

        var validatedItem: MacroManager.MacroItem? = null

        val dialog = AlertDialog.Builder(this)
            .setTitle("Slot ${slot + 1}: $slotDir")
            .setView(layout)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel") { _, _ ->
                refreshMacroSpinners()
            }
            .create()

        fun validate() {
            val comboText = etCombo.text.toString()
            val labelText = etLabel.text.toString()
            val result = MacroManager.parse(comboText, labelText)
            when (result) {
                is MacroManager.ValidationResult.Valid -> {
                    validatedItem = result.item
                    tvStatus.text = "✓ ${result.description}"
                    tvStatus.setTextColor(0xFFA6E3A1.toInt())
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
                }
                is MacroManager.ValidationResult.Invalid -> {
                    validatedItem = null
                    tvStatus.text = "✗ ${result.errorMessage}"
                    tvStatus.setTextColor(0xFFF38BA8.toInt())
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
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
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
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
            tvAccessibility?.text = "✓ System Clicks: Enabled"
            tvAccessibility?.setTextColor(0xFFA6E3A1.toInt())
            btnAccessibility?.text = "Configured"
        } else {
            tvAccessibility?.text = "⚠️ System Clicks: Disabled"
            tvAccessibility?.setTextColor(0xFFF38BA8.toInt())
            btnAccessibility?.text = "Enable Clicks"
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
            "hide_keyboard" -> {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
                tvLiveInput.text = "$buttonName Button -> Action: Hide Keyboard"
            }
            "toggle_hud_hide_keyboard" -> {
                toggleFloatingHUD()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
                tvLiveInput.text = "$buttonName Button -> Action: Toggle HUD + Hide Keyboard"
            }
            else -> { // "toggle_hud"
                toggleFloatingHUD()
                tvLiveInput.text = "$buttonName Button -> Action: Toggle HUD"
            }
        }
    }

    private fun toggleFloatingHUD() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        val serviceIntent = Intent(this, FloatingHUDService::class.java)
        if (FloatingHUDService.isRunning) {
            stopService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        updateFloatingButtonState()
    }

    private fun updateFloatingButtonState() {
        if (FloatingHUDService.isRunning) {
            btnFloatingHud.text = "✕ Close Always-On Floating HUD"
            btnFloatingHud.setBackgroundColor(0xFFC53B53.toInt())
        } else {
            btnFloatingHud.text = "📌 Launch Always-On Floating HUD"
            btnFloatingHud.setBackgroundColor(0xFF2E7D32.toInt())
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
            tvStatus.text = "🎮 Gamepad Ready: ${gamepadNames.joinToString(", ")}"
            tvStatus.setTextColor(0xFF9ECE6A.toInt())
            tvDetail.text = "Aim Right Stick. Press R1 to Select. L1 returns to Base. Hold R2 for 2nd tier."
        } else {
            tvStatus.text = "⚠️ No Gamepad Detected in Android"
            tvStatus.setTextColor(0xFFF7768E.toInt())
            tvDetail.text = "Android sees: " + allDevices.joinToString(", ")
        }
    }

    private fun refreshRadialHUD() {
        currentLayer = engine.currentLayer
        isSecondLayer = engine.isSecondLayerActive

        radialHUD?.updateState(currentX, currentY, currentLayer, isSecondLayer, isShift, isCtrl, isAlt, isCaps, isSuper)
    }

    private fun onDpadAction(dir: String) {
        tvStatus.text = "🎮 D-Pad Navigation: $dir"
        tvStatus.setTextColor(0xFF7AA2F7.toInt())
        tvLiveInput.text = "D-Pad: $dir"
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val lx = event.getAxisValue(MotionEvent.AXIS_X)
        val ly = event.getAxisValue(MotionEvent.AXIS_Y)

        val ENGAGE = 0.35f
        val RELEASE = 0.20f

        leftStickShiftActive = if (leftStickShiftActive) ly <= -RELEASE else ly <= -ENGAGE
        leftStickCtrlActive = if (leftStickCtrlActive) ly >= RELEASE else ly >= ENGAGE
        leftStickAltActive = if (leftStickAltActive) lx <= -RELEASE else lx <= -ENGAGE
        leftStickSuperActive = if (leftStickSuperActive) lx >= RELEASE else lx >= ENGAGE

        isCtrl = leftStickCtrlActive
        isAlt = leftStickAltActive
        isSuper = leftStickSuperActive

        val rawZ = event.getAxisValue(MotionEvent.AXIS_Z)
        val rawRZ = event.getAxisValue(MotionEvent.AXIS_RZ)
        val rawRX = event.getAxisValue(MotionEvent.AXIS_RX)
        val rawRY = event.getAxisValue(MotionEvent.AXIS_RY)
        currentX = if (rawZ != 0f || rawRZ != 0f) rawZ else rawRX
        currentY = if (rawZ != 0f || rawRZ != 0f) rawRZ else rawRY

        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

        if (hatX != lastHatX) {
            if (hatX < -0.5f) onDpadAction("← Left")
            else if (hatX > 0.5f) onDpadAction("→ Right")
            lastHatX = hatX
        }

        if (hatY != lastHatY) {
            if (hatY < -0.5f) onDpadAction("↑ Up")
            else if (hatY > 0.5f) onDpadAction("↓ Down")
            lastHatY = hatY
        }

        val hasLTrigger = event.device?.getMotionRange(MotionEvent.AXIS_LTRIGGER) != null
        val l2Val = if (hasLTrigger) {
            event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
        } else if (event.device?.getMotionRange(MotionEvent.AXIS_BRAKE) != null) {
            event.getAxisValue(MotionEvent.AXIS_BRAKE)
        } else {
            event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
        }

        val hasRTrigger = event.device?.getMotionRange(MotionEvent.AXIS_RTRIGGER) != null
        val r2Val = if (hasRTrigger) {
            event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
        } else if (event.device?.getMotionRange(MotionEvent.AXIS_GAS) != null) {
            event.getAxisValue(MotionEvent.AXIS_GAS)
        } else {
            event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
        }

        l2TriggerActive = if (l2TriggerActive) l2Val >= 0.25f else l2Val >= 0.5f
        r2TriggerActive = if (r2TriggerActive) r2Val >= 0.25f else r2Val >= 0.5f

        isShift = isL2ButtonDown || l2TriggerActive || leftStickShiftActive
        isSecondLayer = engine.isSecondLayerActive

        engine.onMotionEvent(event)

        val isMouseActive = isR2ButtonDown || r2TriggerActive
        val wasMouseActive = VirtualMouseManager.isMouseLayerActive
        if (wasMouseActive != isMouseActive) {
            VirtualMouseManager.setMouseLayerActive(this, isMouseActive)
        }

        if (isMouseActive) {
            VirtualMouseManager.updateStick(currentX, currentY)

            if (hatX != lastHatX) {
                if (hatX < -0.5f) {
                    VirtualMouseManager.performLeftClick()
                    tvLiveInput.text = "Mouse: Left Click"
                } else if (hatX > 0.5f) {
                    VirtualMouseManager.performRightClick()
                    tvLiveInput.text = "Mouse: Right Click"
                }
                lastHatX = hatX
            }

            if (hatY != lastHatY) {
                if (hatY < -0.5f) {
                    VirtualMouseManager.performMiddleClick()
                    tvLiveInput.text = "Mouse: Middle Click"
                }
                lastHatY = hatY
            }

            tvStatus.text = "🐭 Mouse Layer Active (Right Stick: Move | D-Pad: Click)"
            tvStatus.setTextColor(0xFF89B4FA.toInt())
            tvLiveInput.text = String.format("Mouse Cursor: (%.0f, %.0f) | Stick: (%+.2f, %+.2f)", VirtualMouseManager.cursorX, VirtualMouseManager.cursorY, currentX, currentY)
            refreshRadialHUD()
            return true
        }

        if (hatX != lastHatX) {
            if (hatX < -0.5f) onDpadAction("← Left")
            else if (hatX > 0.5f) onDpadAction("→ Right")
            lastHatX = hatX
        }

        if (hatY != lastHatY) {
            if (hatY < -0.5f) onDpadAction("↑ Up")
            else if (hatY > 0.5f) onDpadAction("↓ Down")
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

        val layerName = if (isSecondLayer) "${engine.currentLayer.displayName} (2nd)" else engine.currentLayer.displayName
        tvStatus.text = "🎮 Controller Signal: Layer [$layerName]"
        tvStatus.setTextColor(0xFF9ECE6A.toInt())
        tvLiveInput.text = String.format("R-Stick: X:%+.2f Y:%+.2f | Mods: %s", currentX, currentY, modSummary)

        refreshRadialHUD()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        var handled = true
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                // Right Bumper: Select
                val result = engine.onSelect()
                val emitted = result?.let {
                    when {
                        it.charCode in InputEngine.KEY_MACRO_0..InputEngine.KEY_MACRO_7 -> {
                            val slot = it.charCode - InputEngine.KEY_MACRO_0
                            "MACRO: " + MacroManager.getMacro(slot).displayName
                        }
                        it.charCode == InputEngine.KEY_VOL_MUTE -> "VOL MUTE"
                        it.charCode == InputEngine.KEY_VOL_UP -> "VOL+"
                        it.charCode == InputEngine.KEY_VOL_DOWN -> "VOL-"
                        it.charCode == InputEngine.KEY_DELETE -> "DEL"
                        it.charCode in InputEngine.KEY_F1..InputEngine.KEY_F12 -> "F${it.charCode - InputEngine.KEY_F1 + 1}"
                        else -> it.char.toString()
                    }
                } ?: "Selected Layer: ${engine.currentLayer.displayName}"
                tvLiveInput.text = "R1 Select: $emitted"
                handled = true
            }
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                // Left Bumper: Go back one layer level (Page 2 -> Page 1 -> Base)
                engine.goBackLayer()
                tvLiveInput.text = "L1 Back: Layer [${engine.currentLayer.displayName}]"
                handled = true
            }
            KeyEvent.KEYCODE_BUTTON_L2 -> {
                isL2ButtonDown = true
                isShift = true
            }
            KeyEvent.KEYCODE_BUTTON_R2 -> {
                isR2ButtonDown = true
                VirtualMouseManager.setMouseLayerActive(this, true)
                tvStatus.text = "🐭 Mouse Layer Active (Right Stick: Move | D-Pad: Click)"
                tvStatus.setTextColor(0xFF89B4FA.toInt())
                tvLiveInput.text = "Mouse Layer Activated"
                handled = true
            }
            KeyEvent.KEYCODE_BUTTON_THUMBL -> isCaps = !isCaps
            KeyEvent.KEYCODE_BUTTON_MODE -> isSuper = true
            KeyEvent.KEYCODE_BUTTON_SELECT -> {
                val prefs = getSharedPreferences("radpad_prefs", Context.MODE_PRIVATE)
                val action = prefs.getString("select_button_action", "toggle_hud") ?: "toggle_hud"
                executeSystemButtonAction("Select", action)
                handled = true
            }
            KeyEvent.KEYCODE_BUTTON_START -> {
                val prefs = getSharedPreferences("radpad_prefs", Context.MODE_PRIVATE)
                val action = prefs.getString("start_button_action", "hide_keyboard") ?: "hide_keyboard"
                executeSystemButtonAction("Start", action)
                handled = true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isR2ButtonDown || r2TriggerActive) {
                    VirtualMouseManager.performLeftClick()
                    tvLiveInput.text = "Mouse: Left Click"
                    handled = true
                } else {
                    onDpadAction("← Left")
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isR2ButtonDown || r2TriggerActive) {
                    VirtualMouseManager.performRightClick()
                    tvLiveInput.text = "Mouse: Right Click"
                    handled = true
                } else {
                    onDpadAction("→ Right")
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isR2ButtonDown || r2TriggerActive) {
                    VirtualMouseManager.performMiddleClick()
                    tvLiveInput.text = "Mouse: Middle Click"
                    handled = true
                } else {
                    onDpadAction("↑ Up")
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> onDpadAction("↓ Down")
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y -> {
                handled = false
            }
            else -> handled = false
        }

        val btnName = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_R1 -> "R1 [SELECT]"
            KeyEvent.KEYCODE_BUTTON_L1 -> "L1 [BASE LAYER]"
            KeyEvent.KEYCODE_BUTTON_X -> if (isShift) "Square [DEL (Forward)]" else "Square [BACKSPACE]"
            else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
        }
        tvStatus.text = "🎮 Controller Signal Detected! (${event.device?.name ?: "Gamepad"})"
        tvStatus.setTextColor(0xFF9ECE6A.toInt())

        if (handled) {
            refreshRadialHUD()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        var handled = true
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> {
                isL2ButtonDown = false
                isShift = l2TriggerActive || leftStickShiftActive
            }
            KeyEvent.KEYCODE_BUTTON_R2 -> {
                isR2ButtonDown = false
                r2TriggerActive = false
                engine.onKeyEvent(keyCode, isDown = false)
                VirtualMouseManager.setMouseLayerActive(this, false)
                tvLiveInput.text = "Mouse Layer Deactivated"
                handled = true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> isCtrl = false
            KeyEvent.KEYCODE_DPAD_LEFT -> isAlt = false
            KeyEvent.KEYCODE_BUTTON_MODE -> isSuper = false
            KeyEvent.KEYCODE_BUTTON_SELECT -> {
                val prefs = getSharedPreferences("radpad_prefs", Context.MODE_PRIVATE)
                if (prefs.getString("select_button_action", "paste") == "super") {
                    isSuper = false
                }
                handled = true
            }
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                handled = true
            }
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_A,
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
