package com.radpad.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingHUDPositionTest {

    @Test
    fun testDefaultPositionConstants() {
        assertEquals(80, FloatingHUDManager.DEFAULT_HUD_X)
        assertEquals(160, FloatingHUDManager.DEFAULT_HUD_Y)
        assertEquals("radpad_prefs", FloatingHUDManager.PREFS_NAME)
        assertEquals("floating_hud_x", FloatingHUDManager.PREF_HUD_X)
        assertEquals("floating_hud_y", FloatingHUDManager.PREF_HUD_Y)
    }

    @Test
    fun testHudDragDeltaAndClamping() {
        val screenW = 1080
        val screenH = 2400
        val hudW = 400
        val hudH = 500

        val maxW = (screenW - hudW).coerceAtLeast(0) // 680
        val maxH = (screenH - hudH).coerceAtLeast(0) // 1900

        val dragStartHudX = 100
        val dragStartHudY = 200
        val dragStartCursorX = 150f
        val dragStartCursorY = 250f

        // 1. Move cursor right by 50px, down by 100px
        val currentCursorX = 200f
        val currentCursorY = 350f
        val dx = (currentCursorX - dragStartCursorX).toInt() // +50
        val dy = (currentCursorY - dragStartCursorY).toInt() // +100

        val newX = (dragStartHudX + dx).coerceIn(0, maxW)
        val newY = (dragStartHudY + dy).coerceIn(0, maxH)

        assertEquals(150, newX)
        assertEquals(300, newY)

        // 2. Drag off the top-left edge: clamped to (0, 0)
        val farLeftCursorX = -500f
        val farTopCursorY = -500f
        val clampedMinX = (dragStartHudX + (farLeftCursorX - dragStartCursorX).toInt()).coerceIn(0, maxW)
        val clampedMinY = (dragStartHudY + (farTopCursorY - dragStartCursorY).toInt()).coerceIn(0, maxH)
        assertEquals(0, clampedMinX)
        assertEquals(0, clampedMinY)

        // 3. Drag off the bottom-right edge: clamped to (maxW, maxH)
        val farRightCursorX = 5000f
        val farBottomCursorY = 5000f
        val clampedMaxX = (dragStartHudX + (farRightCursorX - dragStartCursorX).toInt()).coerceIn(0, maxW)
        val clampedMaxY = (dragStartHudY + (farBottomCursorY - dragStartCursorY).toInt()).coerceIn(0, maxH)
        assertEquals(maxW, clampedMaxX)
        assertEquals(maxH, clampedMaxY)
    }

    @Test
    fun testHitTestCalculations() {
        val hudX = 100
        val hudY = 200
        val hudW = 300
        val hudH = 400

        fun isOverHud(cx: Float, cy: Float): Boolean {
            return cx >= hudX && cx <= (hudX + hudW) && cy >= hudY && cy <= (hudY + hudH)
        }

        // Inside
        assertTrue(isOverHud(100f, 200f))
        assertTrue(isOverHud(250f, 350f))
        assertTrue(isOverHud(400f, 600f))

        // Outside
        assertFalse(isOverHud(99f, 200f))
        assertFalse(isOverHud(100f, 199f))
        assertFalse(isOverHud(401f, 350f))
        assertFalse(isOverHud(250f, 601f))
    }

    @Test
    fun testVirtualMouseOverlayListenerConsumption() {
        var buttonStateReceived = false
        var lastButton: VirtualMouseManager.MouseButton? = null
        var lastIsDown = false

        val testListener = object : VirtualMouseManager.MouseStateListener {
            override fun onMouseLayerChanged(active: Boolean) {}
            override fun onCursorMoved(x: Float, y: Float) {}
            override fun onMouseButtonState(
                button: VirtualMouseManager.MouseButton,
                isDown: Boolean,
                x: Float,
                y: Float
            ): Boolean {
                buttonStateReceived = true
                lastButton = button
                lastIsDown = isDown
                return true // Consume event
            }
        }

        VirtualMouseManager.register(testListener)
        try {
            val consumed = VirtualMouseManager.onMouseButton(VirtualMouseManager.MouseButton.LEFT, isDown = true)
            assertTrue(consumed)
            assertTrue(buttonStateReceived)
            assertEquals(VirtualMouseManager.MouseButton.LEFT, lastButton)
            assertTrue(lastIsDown)

            val released = VirtualMouseManager.onMouseButton(VirtualMouseManager.MouseButton.LEFT, isDown = false)
            assertTrue(released)
            assertFalse(lastIsDown)
        } finally {
            VirtualMouseManager.unregister(testListener)
        }
    }

    @Test
    fun testVirtualMouseOverlayGeometryNoClipping() {
        val sizeDp = VirtualMouseManager.CURSOR_SIZE_DP
        val tipOffsetDp = VirtualMouseManager.CURSOR_TIP_OFFSET_DP
        val maxRadiusDp = VirtualMouseManager.MAX_PULSE_RADIUS_DP

        // Left and top margins from circle edge to window boundary
        val leftMargin = tipOffsetDp - maxRadiusDp
        val topMargin = tipOffsetDp - maxRadiusDp
        assertTrue("Left margin must be positive to avoid clipping (was $leftMargin)", leftMargin > 15f)
        assertTrue("Top margin must be positive to avoid clipping (was $topMargin)", topMargin > 15f)

        // Right and bottom margins from circle edge to window boundary
        val rightMargin = (sizeDp - tipOffsetDp) - maxRadiusDp
        val bottomMargin = (sizeDp - tipOffsetDp) - maxRadiusDp
        assertTrue("Right margin must be positive to avoid clipping (was $rightMargin)", rightMargin > 15f)
        assertTrue("Bottom margin must be positive to avoid clipping (was $bottomMargin)", bottomMargin > 15f)

        // Ensure pointer arrow plus drop shadow fits within the window bounds
        val arrowWidthDp = 16f
        val arrowHeightDp = 25f
        val shadowAllowanceDp = 6f
        assertTrue(tipOffsetDp + arrowWidthDp + shadowAllowanceDp <= sizeDp)
        assertTrue(tipOffsetDp + arrowHeightDp + shadowAllowanceDp <= sizeDp)
    }

    @Test
    fun testVirtualMouseMiddleClickEvents() {
        var lastButton: VirtualMouseManager.MouseButton? = null
        var lastIsDown = false

        val testListener = object : VirtualMouseManager.MouseStateListener {
            override fun onMouseLayerChanged(active: Boolean) {}
            override fun onCursorMoved(x: Float, y: Float) {}
            override fun onMouseButtonState(
                button: VirtualMouseManager.MouseButton,
                isDown: Boolean,
                x: Float,
                y: Float
            ): Boolean {
                lastButton = button
                lastIsDown = isDown
                return true
            }
        }

        VirtualMouseManager.register(testListener)
        try {
            val consumedDown = VirtualMouseManager.onMouseButton(VirtualMouseManager.MouseButton.MIDDLE, isDown = true)
            assertTrue(consumedDown)
            assertEquals(VirtualMouseManager.MouseButton.MIDDLE, lastButton)
            assertTrue(lastIsDown)

            val consumedUp = VirtualMouseManager.onMouseButton(VirtualMouseManager.MouseButton.MIDDLE, isDown = false)
            assertTrue(consumedUp)
            assertEquals(VirtualMouseManager.MouseButton.MIDDLE, lastButton)
            assertFalse(lastIsDown)
        } finally {
            VirtualMouseManager.unregister(testListener)
        }
    }

    @Test
    fun testVirtualMouseScrollEvents() {
        var lastScrollUp: Boolean? = null
        var scrollCount = 0

        val testListener = object : VirtualMouseManager.MouseStateListener {
            override fun onMouseLayerChanged(active: Boolean) {}
            override fun onCursorMoved(x: Float, y: Float) {}
            override fun onMouseScroll(scrollUp: Boolean, x: Float, y: Float): Boolean {
                lastScrollUp = scrollUp
                scrollCount++
                return true
            }
        }

        VirtualMouseManager.register(testListener)
        try {
            val upHandled = VirtualMouseManager.scrollUp()
            assertTrue(upHandled)
            assertEquals(true, lastScrollUp)
            assertEquals(1, scrollCount)

            val downHandled = VirtualMouseManager.scrollDown()
            assertTrue(downHandled)
            assertEquals(false, lastScrollUp)
            assertEquals(2, scrollCount)
        } finally {
            VirtualMouseManager.unregister(testListener)
        }
    }
}
