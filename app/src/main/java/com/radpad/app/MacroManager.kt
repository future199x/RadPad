package com.radpad.app

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import java.util.concurrent.CopyOnWriteArrayList

/**
 * MacroManager: Centralized repository, persistence, and execution engine
 * for user-configurable macros in the RadPad MACRO radial layer.
 */
object MacroManager {

    enum class MacroType {
        KEY_COMBO,
        TEXT,
        ACTION
    }

    data class MacroItem(
        val id: String,
        val displayName: String,
        val shortHudLabel: String,
        val type: MacroType,
        val keyCode: Int = 0,
        val metaModifiers: Int = 0,
        val textPayload: String = ""
    )

    sealed class ValidationResult {
        data class Valid(val item: MacroItem, val description: String) : ValidationResult()
        data class Invalid(val errorMessage: String) : ValidationResult()
    }

    // Pre-defined built-in macro presets
    val PRESETS = listOf(
        MacroItem("copy", "Copy (Ctrl+C)", "COPY", MacroType.ACTION, KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON),
        MacroItem("paste", "Paste (Ctrl+V)", "PASTE", MacroType.ACTION, KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON),
        MacroItem("cut", "Cut (Ctrl+X)", "CUT", MacroType.ACTION, KeyEvent.KEYCODE_X, KeyEvent.META_CTRL_ON),
        MacroItem("undo", "Undo (Ctrl+Z)", "UNDO", MacroType.ACTION, KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON),
        MacroItem("redo_shift_z", "Redo (Ctrl+Shift+Z)", "REDO", MacroType.KEY_COMBO, KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON),
        MacroItem("redo_ctrl_y", "Redo (Ctrl+Y)", "REDO", MacroType.KEY_COMBO, KeyEvent.KEYCODE_Y, KeyEvent.META_CTRL_ON),
        MacroItem("select_all", "Select All (Ctrl+A)", "ALL", MacroType.ACTION, KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON),
        MacroItem("save", "Save (Ctrl+S)", "SAVE", MacroType.KEY_COMBO, KeyEvent.KEYCODE_S, KeyEvent.META_CTRL_ON),
        MacroItem("find", "Find (Ctrl+F)", "FIND", MacroType.KEY_COMBO, KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON),
        MacroItem("desktop", "Show Desktop (Win+D)", "DESK", MacroType.KEY_COMBO, KeyEvent.KEYCODE_D, KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON),
        MacroItem("task_view", "Task View (Win+Tab)", "TASKS", MacroType.KEY_COMBO, KeyEvent.KEYCODE_TAB, KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON),
        MacroItem("alt_tab", "Switch App (Alt+Tab)", "ALT+TAB", MacroType.KEY_COMBO, KeyEvent.KEYCODE_TAB, KeyEvent.META_ALT_ON),
        MacroItem("close_win", "Close Window (Alt+F4)", "CLOSE", MacroType.KEY_COMBO, KeyEvent.KEYCODE_F4, KeyEvent.META_ALT_ON),
        MacroItem("terminal_break", "Terminal Break (^C)", "^C", MacroType.KEY_COMBO, KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON),
        MacroItem("escape", "Escape (Esc)", "ESC", MacroType.KEY_COMBO, KeyEvent.KEYCODE_ESCAPE, 0)
    )

    private val DEFAULT_SLOT_PRESET_IDS = arrayOf(
        "copy",          // Slot 0 (North / 0°)
        "paste",         // Slot 1 (NorthEast / 45°)
        "cut",           // Slot 2 (East / 90°)
        "undo",          // Slot 3 (SouthEast / 135°)
        "select_all",    // Slot 4 (South / 180°)
        "save",          // Slot 5 (SouthWest / 225°)
        "redo_shift_z",  // Slot 6 (West / 270°)
        "desktop"        // Slot 7 (NorthWest / 315°)
    )

    private val currentSlots: Array<MacroItem> = Array(8) { index ->
        PRESETS.firstOrNull { it.id == DEFAULT_SLOT_PRESET_IDS[index] } ?: PRESETS[0]
    }

    private var prefs: SharedPreferences? = null

    interface Listener {
        fun onMacrosChanged()
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun registerListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun unregisterListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences("radpad_macros", Context.MODE_PRIVATE)
            loadFromPrefs()
        }
    }

    private fun loadFromPrefs() {
        val p = prefs ?: return
        for (i in 0..7) {
            val presetId = p.getString("slot_$i", DEFAULT_SLOT_PRESET_IDS[i]) ?: DEFAULT_SLOT_PRESET_IDS[i]
            if (presetId == "custom") {
                val name = p.getString("slot_${i}_name", "Custom") ?: "Custom"
                val label = p.getString("slot_${i}_label", "CUST") ?: "CUST"
                val typeName = p.getString("slot_${i}_type", MacroType.KEY_COMBO.name)
                val type = try { MacroType.valueOf(typeName!!) } catch (_: Exception) { MacroType.KEY_COMBO }
                val code = p.getInt("slot_${i}_code", 0)
                val mods = p.getInt("slot_${i}_mods", 0)
                val text = p.getString("slot_${i}_text", "") ?: ""
                currentSlots[i] = MacroItem(
                    id = "custom_$i",
                    displayName = name,
                    shortHudLabel = label,
                    type = type,
                    keyCode = code,
                    metaModifiers = mods,
                    textPayload = text
                )
            } else {
                val item = PRESETS.firstOrNull { it.id == presetId } ?: PRESETS[0]
                currentSlots[i] = item
            }
        }
    }

    fun getMacro(slot: Int): MacroItem {
        if (slot in 0..7) return currentSlots[slot]
        return currentSlots[0]
    }

    fun setMacro(slot: Int, presetId: String) {
        if (slot !in 0..7) return
        val item = PRESETS.firstOrNull { it.id == presetId } ?: return
        currentSlots[slot] = item
        prefs?.edit()?.putString("slot_$slot", presetId)?.apply()
        for (l in listeners) l.onMacrosChanged()
    }

    fun setCustomMacro(slot: Int, item: MacroItem) {
        if (slot !in 0..7) return
        currentSlots[slot] = item
        prefs?.edit()?.apply {
            putString("slot_$slot", "custom")
            putString("slot_${slot}_name", item.displayName)
            putString("slot_${slot}_label", item.shortHudLabel)
            putString("slot_${slot}_type", item.type.name)
            putInt("slot_${slot}_code", item.keyCode)
            putInt("slot_${slot}_mods", item.metaModifiers)
            putString("slot_${slot}_text", item.textPayload)
            apply()
        }
        for (l in listeners) l.onMacrosChanged()
    }

    fun getMacroLabels(): Array<String> {
        return Array(8) { i -> currentSlots[i].shortHudLabel }
    }

    /**
     * Parses and validates a user-provided macro string (e.g. "ctrl shift z", "ctrl+shift+z", "win+d", "text:hello").
     */
    fun parse(rawInput: String, customLabel: String? = null): ValidationResult {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return ValidationResult.Invalid("Please enter a key combination (e.g. ctrl+shift+z)")
        }

        // Check for text payload prefix (e.g. "text:hello world")
        if (trimmed.startsWith("text:", ignoreCase = true)) {
            val text = trimmed.substring(5)
            if (text.isEmpty()) {
                return ValidationResult.Invalid("Text macro cannot be empty")
            }
            val label = customLabel?.trim()?.ifEmpty { null } ?: text.take(4).uppercase()
            val item = MacroItem(
                id = "custom_text",
                displayName = "Type: \"$text\"",
                shortHudLabel = label.take(6),
                type = MacroType.TEXT,
                textPayload = text
            )
            return ValidationResult.Valid(item, "Text input: '$text'")
        }

        // Tokenize by '+', '-', or whitespace
        val tokens = trimmed.split(Regex("[+\\-\\s]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) {
            return ValidationResult.Invalid("Please enter a key combination")
        }

        var metaModifiers = 0
        val modifierNames = mutableListOf<String>()
        var keyToken: String? = null

        for (token in tokens) {
            when (val lower = token.lowercase()) {
                "ctrl", "control" -> {
                    metaModifiers = metaModifiers or KeyEvent.META_CTRL_ON
                    if (!modifierNames.contains("Ctrl")) modifierNames.add("Ctrl")
                }
                "shift" -> {
                    metaModifiers = metaModifiers or KeyEvent.META_SHIFT_ON
                    if (!modifierNames.contains("Shift")) modifierNames.add("Shift")
                }
                "alt", "opt", "option" -> {
                    metaModifiers = metaModifiers or KeyEvent.META_ALT_ON
                    if (!modifierNames.contains("Alt")) modifierNames.add("Alt")
                }
                "win", "windows", "super", "meta", "cmd", "command" -> {
                    metaModifiers = metaModifiers or (KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON)
                    if (!modifierNames.contains("Win")) modifierNames.add("Win")
                }
                else -> {
                    if (keyToken != null) {
                        return ValidationResult.Invalid("Multiple keys specified ('$keyToken' and '$token'). Use only one non-modifier key.")
                    }
                    keyToken = token
                }
            }
        }

        if (keyToken == null) {
            return ValidationResult.Invalid("Missing key name (e.g. 'ctrl+shift+z' or 'win+d')")
        }

        val keyLower = keyToken.lowercase()
        val (keyCode, canonicalKeyName) = resolveKey(keyLower)
            ?: return ValidationResult.Invalid("Unknown key: '$keyToken'")

        val parts = modifierNames + canonicalKeyName
        val canonicalDisplayName = parts.joinToString("+")

        // Determine short HUD label
        val label = if (!customLabel.isNullOrBlank()) {
            customLabel.trim().take(6).uppercase()
        } else {
            generateDefaultHudLabel(modifierNames, canonicalKeyName)
        }

        val item = MacroItem(
            id = "custom_combo",
            displayName = canonicalDisplayName,
            shortHudLabel = label,
            type = MacroType.KEY_COMBO,
            keyCode = keyCode,
            metaModifiers = metaModifiers
        )

        return ValidationResult.Valid(item, "Key Combination: $canonicalDisplayName")
    }

    private fun resolveKey(keyLower: String): Pair<Int, String>? {
        if (keyLower.length == 1) {
            val c = keyLower[0]
            if (c in 'a'..'z') {
                return Pair(KeyEvent.KEYCODE_A + (c - 'a'), c.uppercase())
            }
            if (c in '0'..'9') {
                return Pair(KeyEvent.KEYCODE_0 + (c - '0'), c.toString())
            }
            return when (c) {
                '.' -> Pair(KeyEvent.KEYCODE_PERIOD, ".")
                ',' -> Pair(KeyEvent.KEYCODE_COMMA, ",")
                '/' -> Pair(KeyEvent.KEYCODE_SLASH, "/")
                '\\' -> Pair(KeyEvent.KEYCODE_BACKSLASH, "\\")
                ';' -> Pair(KeyEvent.KEYCODE_SEMICOLON, ";")
                '\'' -> Pair(KeyEvent.KEYCODE_APOSTROPHE, "'")
                '[' -> Pair(KeyEvent.KEYCODE_LEFT_BRACKET, "[")
                ']' -> Pair(KeyEvent.KEYCODE_RIGHT_BRACKET, "]")
                '-' -> Pair(KeyEvent.KEYCODE_MINUS, "-")
                '=' -> Pair(KeyEvent.KEYCODE_EQUALS, "=")
                '`' -> Pair(KeyEvent.KEYCODE_GRAVE, "`")
                ' ' -> Pair(KeyEvent.KEYCODE_SPACE, "Space")
                else -> null
            }
        }

        if (keyLower.startsWith("f") && keyLower.length in 2..3) {
            val num = keyLower.substring(1).toIntOrNull()
            if (num != null && num in 1..12) {
                return Pair(KeyEvent.KEYCODE_F1 + (num - 1), "F$num")
            }
        }

        return when (keyLower) {
            "enter", "return" -> Pair(KeyEvent.KEYCODE_ENTER, "Enter")
            "space", "spacebar" -> Pair(KeyEvent.KEYCODE_SPACE, "Space")
            "tab" -> Pair(KeyEvent.KEYCODE_TAB, "Tab")
            "esc", "escape" -> Pair(KeyEvent.KEYCODE_ESCAPE, "Esc")
            "backspace", "bksp" -> Pair(KeyEvent.KEYCODE_DEL, "Backspace")
            "delete", "del" -> Pair(KeyEvent.KEYCODE_FORWARD_DEL, "Del")
            "insert", "ins" -> Pair(KeyEvent.KEYCODE_INSERT, "Ins")
            "home" -> Pair(KeyEvent.KEYCODE_MOVE_HOME, "Home")
            "end" -> Pair(KeyEvent.KEYCODE_MOVE_END, "End")
            "pgup", "pageup" -> Pair(KeyEvent.KEYCODE_PAGE_UP, "PgUp")
            "pgdn", "pagedown" -> Pair(KeyEvent.KEYCODE_PAGE_DOWN, "PgDn")
            "up" -> Pair(KeyEvent.KEYCODE_DPAD_UP, "Up")
            "down" -> Pair(KeyEvent.KEYCODE_DPAD_DOWN, "Down")
            "left" -> Pair(KeyEvent.KEYCODE_DPAD_LEFT, "Left")
            "right" -> Pair(KeyEvent.KEYCODE_DPAD_RIGHT, "Right")
            else -> null
        }
    }

    private fun generateDefaultHudLabel(mods: List<String>, key: String): String {
        val comboLower = (mods + key).joinToString("+").lowercase()
        // Check common aliases
        if (comboLower in listOf("ctrl+shift+z", "ctrl+y")) return "REDO"
        if (comboLower == "ctrl+c") return "COPY"
        if (comboLower == "ctrl+v") return "PASTE"
        if (comboLower == "ctrl+x") return "CUT"
        if (comboLower == "ctrl+z") return "UNDO"
        if (comboLower == "ctrl+a") return "ALL"
        if (comboLower == "ctrl+s") return "SAVE"
        if (comboLower == "ctrl+f") return "FIND"
        if (comboLower == "win+d") return "DESK"
        if (comboLower == "alt+tab") return "ALT+TAB"
        if (comboLower == "alt+f4") return "CLOSE"

        // Build short abbreviation e.g. "C+S+Z" or "W+D"
        val prefix = mods.map { it.first() }.joinToString("+")
        val full = if (prefix.isNotEmpty()) "$prefix+$key" else key
        return full.take(6).uppercase()
    }

    /**
     * Executes the macro assigned to the given slot (0..7).
     */
    fun execute(ic: InputConnection?, slot: Int): String {
        if (slot !in 0..7) return ""
        val macro = currentSlots[slot]

        when (macro.type) {
            MacroType.ACTION -> {
                when (macro.id) {
                    "copy" -> {
                        if (ic?.performContextMenuAction(android.R.id.copy) != true) {
                            sendKeyCombo(ic, macro.keyCode, macro.metaModifiers)
                        }
                    }
                    "paste" -> {
                        if (ic?.performContextMenuAction(android.R.id.paste) != true) {
                            sendKeyCombo(ic, macro.keyCode, macro.metaModifiers)
                        }
                    }
                    "cut" -> {
                        if (ic?.performContextMenuAction(android.R.id.cut) != true) {
                            sendKeyCombo(ic, macro.keyCode, macro.metaModifiers)
                        }
                    }
                    "undo" -> {
                        if (ic?.performContextMenuAction(android.R.id.undo) != true) {
                            sendKeyCombo(ic, macro.keyCode, macro.metaModifiers)
                        }
                    }
                    "select_all" -> {
                        if (ic?.performContextMenuAction(android.R.id.selectAll) != true) {
                            sendKeyCombo(ic, macro.keyCode, macro.metaModifiers)
                        }
                    }
                    else -> sendKeyCombo(ic, macro.keyCode, macro.metaModifiers)
                }
            }
            MacroType.KEY_COMBO -> {
                sendKeyCombo(ic, macro.keyCode, macro.metaModifiers)
            }
            MacroType.TEXT -> {
                ic?.commitText(macro.textPayload, 1)
            }
        }
        return macro.displayName
    }

    private fun sendKeyCombo(ic: InputConnection?, keyCode: Int, metaState: Int) {
        if (ic == null || keyCode == 0) return
        ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
        ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, metaState))
    }
}
