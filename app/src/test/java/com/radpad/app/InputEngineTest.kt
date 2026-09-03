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
    fun testBaseLayerSelectionAndTyping() {
        val engine = InputEngine()
        assertEquals(InputEngine.Layer.BASE, engine.currentLayer)

        // Aim North (A-H)
        val sliceNorth = engine.calculateSlice(0.0f, -0.8f)
        assertEquals(0, sliceNorth)

        // Simulate aiming North and pressing R1
        engine.setLayer(InputEngine.Layer.A_H)
        assertEquals(InputEngine.Layer.A_H, engine.currentLayer)

        // Press L1 to return to BASE
        engine.onKeyEvent(KeyEvent.KEYCODE_BUTTON_L1, isDown = true)
        assertEquals(InputEngine.Layer.BASE, engine.currentLayer)
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
}
