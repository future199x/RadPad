package com.radpad.app

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputEngineTest {

    @Test
    fun testDecodeZeroReturnsNull() {
        assertNull(InputEngine.decode(0))
    }

    @Test
    fun testDecodeLayerEventReturnsNull() {
        assertNull(InputEngine.decode(InputEngine.LAYER_EVENT_MASK or 1))
    }

    @Test
    fun testDecodePageToggleEventReturnsNull() {
        assertNull(InputEngine.decode(InputEngine.PAGE_TOGGLE_EVENT_MASK or 1))
        assertNull(InputEngine.decode(InputEngine.PAGE_TOGGLE_EVENT_MASK or 0))
    }

    @Test
    fun testDecodeSuperModifier() {
        val rawValue = 'd'.code or InputEngine.MOD_SUPER
        val event = InputEngine.decode(rawValue)

        assertNotNull(event)
        assertEquals('d', event!!.char)
        assertEquals('d'.code, event.charCode)
        assertTrue(event.isSuper)
        assertFalse(event.isCtrl)
        assertFalse(event.isAlt)
        assertFalse(event.isShift)
    }

    @Test
    fun testDecodeSuperPlusCtrlModifier() {
        val rawValue = 'd'.code or InputEngine.MOD_SUPER or InputEngine.MOD_CTRL
        val event = InputEngine.decode(rawValue)

        assertNotNull(event)
        assertTrue(event!!.isSuper)
        assertTrue(event.isCtrl)
        assertFalse(event.isAlt)
    }

    @Test
    fun testDecodeStandaloneSuperKey() {
        val rawValue = InputEngine.KEY_SUPER or InputEngine.MOD_SUPER
        val event = InputEngine.decode(rawValue)

        assertNotNull(event)
        assertEquals(InputEngine.KEY_SUPER, event!!.charCode)
        assertTrue(event.isSuper)
    }

    @Test
    fun testDecodeSystemKeys() {
        for (code in listOf(
            InputEngine.KEY_DELETE,
            InputEngine.KEY_INSERT,
            InputEngine.KEY_VOL_UP,
            InputEngine.KEY_VOL_DOWN,
            InputEngine.KEY_VOL_MUTE,
            InputEngine.KEY_PGUP,
            InputEngine.KEY_PGDN,
            InputEngine.KEY_HOME,
            InputEngine.KEY_END
        )) {
            val event = InputEngine.decode(code)
            assertNotNull(event)
            assertEquals(code, event!!.charCode)
        }
    }

    @Test
    fun testDecodeMacroKeys() {
        for (slot in 0..7) {
            val macroKey = InputEngine.KEY_MACRO_0 + slot
            val event = InputEngine.decode(macroKey)
            assertNotNull(event)
            assertEquals(macroKey, event!!.charCode)
        }
    }

    @Test
    fun testMacroManagerPresets() {
        val labels = MacroManager.getMacroLabels()
        assertEquals(8, labels.size)
        assertEquals("COPY", labels[0])
        assertEquals("PASTE", labels[1])
        assertEquals("CUT", labels[2])
        assertEquals("UNDO", labels[3])
        assertEquals("ALL", labels[4])
        assertEquals("SAVE", labels[5])
        assertEquals("REDO", labels[6])
        assertEquals("DESK", labels[7])
    }

    @Test
    fun testMacroParserValidCombos() {
        // Redo via Ctrl+Shift+Z
        val resRedo = MacroManager.parse("ctrl+shift+z")
        assertTrue(resRedo is MacroManager.ValidationResult.Valid)
        val redoItem = (resRedo as MacroManager.ValidationResult.Valid).item
        assertEquals(KeyEvent.KEYCODE_Z, redoItem.keyCode)
        assertTrue((redoItem.metaModifiers and KeyEvent.META_CTRL_ON) != 0)
        assertTrue((redoItem.metaModifiers and KeyEvent.META_SHIFT_ON) != 0)
        assertEquals("REDO", redoItem.shortHudLabel)

        // Alt+F4
        val resAltF4 = MacroManager.parse("alt+f4")
        assertTrue(resAltF4 is MacroManager.ValidationResult.Valid)
        val altF4Item = (resAltF4 as MacroManager.ValidationResult.Valid).item
        assertEquals(KeyEvent.KEYCODE_F4, altF4Item.keyCode)
        assertTrue((altF4Item.metaModifiers and KeyEvent.META_ALT_ON) != 0)
        assertEquals("CLOSE", altF4Item.shortHudLabel)

        // Win+D with custom label
        val resWinD = MacroManager.parse("win+d", "MYDESK")
        assertTrue(resWinD is MacroManager.ValidationResult.Valid)
        val winDItem = (resWinD as MacroManager.ValidationResult.Valid).item
        assertEquals(KeyEvent.KEYCODE_D, winDItem.keyCode)
        assertTrue((winDItem.metaModifiers and (KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON)) != 0)
        assertEquals("MYDESK", winDItem.shortHudLabel)

        // Space separated combo: "ctrl shift a"
        val resCtrlShiftA = MacroManager.parse("ctrl shift a")
        assertTrue(resCtrlShiftA is MacroManager.ValidationResult.Valid)
        val itemA = (resCtrlShiftA as MacroManager.ValidationResult.Valid).item
        assertEquals(KeyEvent.KEYCODE_A, itemA.keyCode)

        // Text macro: "text:hello world"
        val resText = MacroManager.parse("text:hello world")
        assertTrue(resText is MacroManager.ValidationResult.Valid)
        val textItem = (resText as MacroManager.ValidationResult.Valid).item
        assertEquals(MacroManager.MacroType.TEXT, textItem.type)
        assertEquals("hello world", textItem.textPayload)
    }

    @Test
    fun testMacroParserInvalidInputs() {
        // Empty
        val resEmpty = MacroManager.parse("")
        assertTrue(resEmpty is MacroManager.ValidationResult.Invalid)

        // Missing key
        val resNoKey = MacroManager.parse("ctrl+shift")
        assertTrue(resNoKey is MacroManager.ValidationResult.Invalid)

        // Unknown key
        val resUnknown = MacroManager.parse("ctrl+unknownkey")
        assertTrue(resUnknown is MacroManager.ValidationResult.Invalid)

        // Multiple keys
        val resMultiple = MacroManager.parse("ctrl+a+b")
        assertTrue(resMultiple is MacroManager.ValidationResult.Invalid)
    }

    @Test
    fun testBaseLayerSelectionAndTyping() {
        val engine = InputEngine()
        assertEquals(InputEngine.Layer.BASE, engine.currentLayer)

        // Aim North (A-H)
        val sliceNorth = engine.calculateSlice(0.0f, -0.8f)
        assertEquals(0, sliceNorth)

        // Set A-H
        engine.setLayer(InputEngine.Layer.A_H)
        assertEquals(InputEngine.Layer.A_H, engine.currentLayer)

        // Press L1 (goBackLayer): returns to BASE
        engine.goBackLayer()
        assertEquals(InputEngine.Layer.BASE, engine.currentLayer)
    }

    @Test
    fun testQZLayerAndMacroLayerInEnum() {
        assertEquals(InputEngine.Layer.Q_Z, InputEngine.Layer.fromId(5))
        assertEquals(InputEngine.Layer.MACRO, InputEngine.Layer.fromId(6))
    }

    @Test
    fun testMouseLayerHoldOnR2() {
        val engine = InputEngine()
        assertFalse(engine.isMouseLayerActive)

        // Hold R2
        engine.onKeyEvent(KeyEvent.KEYCODE_BUTTON_R2, isDown = true)
        assertTrue(engine.isMouseLayerActive)

        // Release R2
        engine.onKeyEvent(KeyEvent.KEYCODE_BUTTON_R2, isDown = false)
        assertFalse(engine.isMouseLayerActive)
    }

    @Test
    fun testCenterR1ToggleSecondPage() {
        val engine = InputEngine()
        engine.setLayer(InputEngine.Layer.Q_Z)
        assertFalse(engine.isSecondLayerActive)

        // Stick centered in deadzone: onSelect toggles to Page 2
        engine.onMotion(0.0f, 0.0f)
        engine.onSelect()
        assertTrue(engine.isSecondLayerActive)

        // onSelect again toggles back to Page 1
        engine.onSelect()
        assertFalse(engine.isSecondLayerActive)
    }

    @Test
    fun testHierarchicalL1GoBackLayer() {
        val engine = InputEngine()
        engine.setLayer(InputEngine.Layer.Q_Z)
        assertFalse(engine.isSecondLayerActive)

        // Toggle to Page 2
        engine.onMotion(0.0f, 0.0f)
        engine.onSelect()
        assertTrue(engine.isSecondLayerActive)
        assertEquals(InputEngine.Layer.Q_Z, engine.currentLayer)

        // L1 GoBack: Should step back to Page 1, NOT Base!
        engine.goBackLayer()
        assertFalse(engine.isSecondLayerActive)
        assertEquals(InputEngine.Layer.Q_Z, engine.currentLayer)

        // L1 GoBack again: From Page 1, steps back to Base!
        engine.goBackLayer()
        assertEquals(InputEngine.Layer.BASE, engine.currentLayer)

        // L1 GoBack on Base: Remains on Base!
        engine.goBackLayer()
        assertEquals(InputEngine.Layer.BASE, engine.currentLayer)
    }
}
