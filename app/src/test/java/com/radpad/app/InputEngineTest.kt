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
    fun testBaseLayerSelectionAndTyping() {
        val engine = InputEngine()
        assertEquals(InputEngine.Layer.BASE, engine.currentLayer)

        // Aim North (A-H)
        val sliceNorth = engine.calculateSlice(0.0f, -0.8f)
        assertEquals(0, sliceNorth)

        // Set A-H
        engine.setLayer(InputEngine.Layer.A_H)
        assertEquals(InputEngine.Layer.A_H, engine.currentLayer)

        // Press L1 to return to BASE
        engine.onKeyEvent(KeyEvent.KEYCODE_BUTTON_L1, isDown = true)
        assertEquals(InputEngine.Layer.BASE, engine.currentLayer)
    }

    @Test
    fun testQZLayerAndMacroLayerInEnum() {
        assertEquals(InputEngine.Layer.Q_Z, InputEngine.Layer.fromId(5))
        assertEquals(InputEngine.Layer.MACRO, InputEngine.Layer.fromId(6))
    }

    @Test
    fun testSecondaryLayerHold() {
        val engine = InputEngine()
        engine.setLayer(InputEngine.Layer.NUM_SYM)

        assertFalse(engine.isSecondLayerActive)

        // Hold R2
        engine.onKeyEvent(KeyEvent.KEYCODE_BUTTON_R2, isDown = true)
        assertTrue(engine.isSecondLayerActive)

        // Release R2
        engine.onKeyEvent(KeyEvent.KEYCODE_BUTTON_R2, isDown = false)
        assertFalse(engine.isSecondLayerActive)
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
}
