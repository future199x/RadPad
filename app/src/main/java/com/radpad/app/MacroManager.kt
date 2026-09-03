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

    // Pre-defined built-in macro presets
    val PRESETS = listOf(
        MacroItem("copy", "Copy (Ctrl+C)", "COPY", MacroType.ACTION, KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON),
        MacroItem("paste", "Paste (Ctrl+V)", "PASTE", MacroType.ACTION, KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON),
        MacroItem("cut", "Cut (Ctrl+X)", "CUT", MacroType.ACTION, KeyEvent.KEYCODE_X, KeyEvent.META_CTRL_ON),
        MacroItem("undo", "Undo (Ctrl+Z)", "UNDO", MacroType.ACTION, KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON),
        MacroItem("redo", "Redo (Ctrl+Y)", "REDO", MacroType.KEY_COMBO, KeyEvent.KEYCODE_Y, KeyEvent.META_CTRL_ON),
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
        "copy",       // Slot 0 (North / 0°)
        "paste",      // Slot 1 (NorthEast / 45°)
        "cut",        // Slot 2 (East / 90°)
        "undo",       // Slot 3 (SouthEast / 135°)
        "select_all", // Slot 4 (South / 180°)
        "save",       // Slot 5 (SouthWest / 225°)
        "redo",       // Slot 6 (West / 270°)
        "desktop"     // Slot 7 (NorthWest / 315°)
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
            val item = PRESETS.firstOrNull { it.id == presetId } ?: PRESETS[0]
            currentSlots[i] = item
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

    fun getMacroLabels(): Array<String> {
        return Array(8) { i -> currentSlots[i].shortHudLabel }
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
