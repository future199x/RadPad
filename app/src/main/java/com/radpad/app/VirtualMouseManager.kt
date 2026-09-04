package com.radpad.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.hypot
import kotlin.math.pow

/**
 * Coordinates the on-screen mouse cursor position, analog velocity integration,
 * pointer rendering overlay, and synthetic click injection via [RadPadAccessibilityService].
 *
 * ## Cursor Dynamics
 * - **Deflection Deadzone**: 0.10f threshold below which stick inputs are ignored.
 * - **Response Curve**: Exponential response \(v \propto (\text{deflection})^{1.6}\) for pixel-precise fine aiming
 *   near center and fast screen traversal at edge deflections.
 * - **Frame Loop**: Smooth 60fps kinematic integration loop updating cursor position in real time.
 */
object VirtualMouseManager {

    /**
     * Listener interface for observing virtual mouse activation, position, and click events.
     */
    interface MouseStateListener {
        /** Called when mouse layer transitions between active and inactive. */
        fun onMouseLayerChanged(active: Boolean)
        /** Called whenever the cursor moves on screen. */
        fun onCursorMoved(x: Float, y: Float)
        /** Called when a click gesture is dispatched. Returns true if consumed by overlay. */
        fun onMouseClicked(button: MouseButton, x: Float, y: Float): Boolean {
            return false
        }
        /** Called when a mouse button transitions between down and up. Returns true if consumed by overlay. */
        fun onMouseButtonState(button: MouseButton, isDown: Boolean, x: Float, y: Float): Boolean {
            return false
        }
        /** Called when a scroll gesture is requested. Returns true if consumed by overlay. */
        fun onMouseScroll(scrollUp: Boolean, x: Float, y: Float): Boolean {
            return false
        }
    }

    /**
     * Mouse button identifiers mapped to controller face buttons or D-pad hats.
     */
    enum class MouseButton {
        LEFT, RIGHT, MIDDLE
    }

    /** Overlay window dimension in DP providing ample padding so ripple waves and drop shadows are never cut off. */
    const val CURSOR_SIZE_DP = 96f
    /** Anchor offset in DP from top-left of the overlay window to the cursor arrow tip. */
    const val CURSOR_TIP_OFFSET_DP = 48f
    /** Maximum radius in DP for the click ripple wave. */
    const val MAX_PULSE_RADIUS_DP = 28f

    private val listeners = CopyOnWriteArrayList<MouseStateListener>()

    /** Whether the virtual mouse overlay and input routing are currently active. */
    var isMouseLayerActive: Boolean = false
        private set

    /** Current cursor X screen coordinate in pixels. */
    var cursorX: Float = 500f
        private set
    /** Current cursor Y screen coordinate in pixels. */
    var cursorY: Float = 800f
        private set

    private var screenWidth: Int = 1080
    private var screenHeight: Int = 2400

    private var stickX: Float = 0f
    private var stickY: Float = 0f

    private var windowManager: WindowManager? = null
    private var cursorView: CursorPointerView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private val mainHandler: Handler? by lazy {
        try {
            Handler(Looper.getMainLooper())
        } catch (_: Throwable) {
            null
        }
    }
    private var isLoopRunning = false
    private var lastFrameTime = 0L

    // Base speed in pixels per second at full stick deflection
    var speedMultiplier: Float = 1.0f

    fun register(listener: MouseStateListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
        listener.onMouseLayerChanged(isMouseLayerActive)
        listener.onCursorMoved(cursorX, cursorY)
    }

    fun unregister(listener: MouseStateListener) {
        listeners.remove(listener)
    }

    fun init(context: Context) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }

        if (cursorX == 500f && cursorY == 800f) {
            cursorX = (screenWidth / 2).toFloat()
            cursorY = (screenHeight / 2).toFloat()
        }
    }

    fun setMouseLayerActive(context: Context, active: Boolean) {
        if (isMouseLayerActive == active) return
        isMouseLayerActive = active

        init(context)

        if (active) {
            ensureCursorView(context)
            showCursor(true)
            startKinematicLoop()
        } else {
            stickX = 0f
            stickY = 0f
            isLeftDown = false
            isRightDown = false
            isMiddleDown = false
            leftConsumedByOverlay = false
            rightConsumedByOverlay = false
            middleConsumedByOverlay = false
            stopKinematicLoop()
            // Keep cursor briefly visible or hide
            showCursor(false)
        }

        for (l in listeners) {
            l.onMouseLayerChanged(active)
        }
    }

    fun updateStick(rx: Float, ry: Float) {
        stickX = rx
        stickY = ry
    }

    private fun ensureCursorView(context: Context) {
        if (cursorView != null) return
        if (!Settings.canDrawOverlays(context)) return

        val wm = windowManager ?: return

        cursorView = CursorPointerView(context.applicationContext)

        val layoutType =
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val density = context.resources.displayMetrics.density
        val tipOffset = CURSOR_TIP_OFFSET_DP * density
        val sizePx = (CURSOR_SIZE_DP * density).toInt()

        layoutParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            x = (cursorX - tipOffset).toInt()
            y = (cursorY - tipOffset).toInt()
        }

        try {
            wm.addView(cursorView, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
            cursorView = null
        }
    }

    private fun showCursor(visible: Boolean) {
        cursorView?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (!isMouseLayerActive) {
                isLoopRunning = false
                return
            }

            val now = SystemClock.uptimeMillis()
            val dt = if (lastFrameTime == 0L) 0.016f else ((now - lastFrameTime).coerceIn(1, 50)) / 1000f
            lastFrameTime = now

            val mag = hypot(stickX, stickY)
            if (mag > 0.10f) {
                // Exponential response curve: deadzone 0.10, power 1.6
                val normMag = ((mag - 0.10f) / 0.90f).coerceIn(0f, 1f)
                val curve = normMag.pow(1.6f)
                val baseSpeed = 1600f * speedMultiplier // px per sec
                val speed = curve * baseSpeed

                val dirX = stickX / mag
                val dirY = stickY / mag

                cursorX = (cursorX + dirX * speed * dt).coerceIn(0f, screenWidth.toFloat())
                cursorY = (cursorY + dirY * speed * dt).coerceIn(0f, screenHeight.toFloat())

                updateCursorPosition()

                for (l in listeners) {
                    l.onCursorMoved(cursorX, cursorY)
                }
            }

            mainHandler?.postDelayed(this, 16)
        }
    }

    private fun startKinematicLoop() {
        if (isLoopRunning) return
        isLoopRunning = true
        lastFrameTime = SystemClock.uptimeMillis()
        mainHandler?.post(frameRunnable)
    }

    private fun stopKinematicLoop() {
        isLoopRunning = false
        mainHandler?.removeCallbacks(frameRunnable)
    }

    private fun updateCursorPosition() {
        val view = cursorView ?: return
        val params = layoutParams ?: return
        val wm = windowManager ?: return

        val tipOffset = CURSOR_TIP_OFFSET_DP * view.context.resources.displayMetrics.density
        params.x = (cursorX - tipOffset).toInt()
        params.y = (cursorY - tipOffset).toInt()
        try {
            wm.updateViewLayout(view, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private var isLeftDown = false
    private var isRightDown = false
    private var isMiddleDown = false
    private var leftConsumedByOverlay = false
    private var rightConsumedByOverlay = false
    private var middleConsumedByOverlay = false
    private var leftDownTime = 0L

    /**
     * Handles hardware button down/up transitions for virtual mouse clicks and dragging.
     * Returns true if consumed by an overlay or handled.
     */
    fun onMouseButton(button: MouseButton, isDown: Boolean): Boolean {
        when (button) {
            MouseButton.LEFT -> {
                if (isLeftDown == isDown) return leftConsumedByOverlay
                isLeftDown = isDown
                if (isDown) {
                    leftDownTime = SystemClock.uptimeMillis()
                    cursorView?.triggerClickPulse(MouseButton.LEFT)
                    var consumed = false
                    for (l in listeners) {
                        if (l.onMouseButtonState(MouseButton.LEFT, true, cursorX, cursorY)) {
                            consumed = true
                        }
                    }
                    leftConsumedByOverlay = consumed
                    return consumed
                } else {
                    var consumed = leftConsumedByOverlay
                    for (l in listeners) {
                        if (l.onMouseButtonState(MouseButton.LEFT, false, cursorX, cursorY)) {
                            consumed = true
                        }
                    }
                    val wasConsumed = leftConsumedByOverlay
                    leftConsumedByOverlay = false
                    if (wasConsumed || consumed) {
                        return true
                    }
                    val duration = (SystemClock.uptimeMillis() - leftDownTime).coerceIn(40L, 300L)
                    return RadPadAccessibilityService.dispatchTap(cursorX, cursorY, duration)
                }
            }
            MouseButton.RIGHT -> {
                if (isRightDown == isDown) return rightConsumedByOverlay
                isRightDown = isDown
                if (isDown) {
                    cursorView?.triggerClickPulse(MouseButton.RIGHT)
                    var consumed = false
                    for (l in listeners) {
                        if (l.onMouseButtonState(MouseButton.RIGHT, true, cursorX, cursorY)) {
                            consumed = true
                        }
                    }
                    rightConsumedByOverlay = consumed
                    return consumed
                } else {
                    var consumed = rightConsumedByOverlay
                    for (l in listeners) {
                        if (l.onMouseButtonState(MouseButton.RIGHT, false, cursorX, cursorY)) {
                            consumed = true
                        }
                    }
                    val wasConsumed = rightConsumedByOverlay
                    rightConsumedByOverlay = false
                    if (wasConsumed || consumed) {
                        return true
                    }
                    return performRightClick(triggerPulse = false)
                }
            }
            MouseButton.MIDDLE -> {
                if (isMiddleDown == isDown) return middleConsumedByOverlay
                isMiddleDown = isDown
                if (isDown) {
                    cursorView?.triggerClickPulse(MouseButton.MIDDLE)
                    var consumed = false
                    for (l in listeners) {
                        if (l.onMouseButtonState(MouseButton.MIDDLE, true, cursorX, cursorY)) {
                            consumed = true
                        }
                    }
                    middleConsumedByOverlay = consumed
                    return consumed
                } else {
                    var consumed = middleConsumedByOverlay
                    for (l in listeners) {
                        if (l.onMouseButtonState(MouseButton.MIDDLE, false, cursorX, cursorY)) {
                            consumed = true
                        }
                    }
                    val wasConsumed = middleConsumedByOverlay
                    middleConsumedByOverlay = false
                    if (wasConsumed || consumed) {
                        return true
                    }
                    return performMiddleClick(triggerPulse = false)
                }
            }
        }
    }

    /**
     * Injects a primary left-click tap at current pointer coordinates via [RadPadAccessibilityService.dispatchTap].
     */
    fun performLeftClick(): Boolean {
        cursorView?.triggerClickPulse(MouseButton.LEFT)
        var consumed = false
        for (l in listeners) {
            if (l.onMouseClicked(MouseButton.LEFT, cursorX, cursorY)) {
                consumed = true
            }
        }
        if (consumed) {
            return true
        }
        return RadPadAccessibilityService.dispatchTap(cursorX, cursorY, 50L)
    }

    /**
     * Injects a secondary right-click action (long-press tap for context menus, or global Back fallback).
     */
    fun performRightClick(triggerPulse: Boolean = true): Boolean {
        if (triggerPulse) {
            cursorView?.triggerClickPulse(MouseButton.RIGHT)
        }
        var consumed = false
        for (l in listeners) {
            if (l.onMouseClicked(MouseButton.RIGHT, cursorX, cursorY)) {
                consumed = true
            }
        }
        if (consumed) {
            return true
        }
        // Long press for context menu, or fallback to global back
        val tapped = RadPadAccessibilityService.dispatchTap(cursorX, cursorY, 550L)
        if (!tapped) {
            return RadPadAccessibilityService.performBack()
        }
        return true
    }

    /**
     * Injects a middle-click action (medium tap, or global Recents overview fallback).
     */
    fun performMiddleClick(triggerPulse: Boolean = true): Boolean {
        if (triggerPulse) {
            cursorView?.triggerClickPulse(MouseButton.MIDDLE)
        }
        var consumed = false
        for (l in listeners) {
            if (l.onMouseClicked(MouseButton.MIDDLE, cursorX, cursorY)) {
                consumed = true
            }
        }
        if (consumed) {
            return true
        }
        val tapped = RadPadAccessibilityService.dispatchTap(cursorX, cursorY, 100L)
        if (!tapped) {
            return RadPadAccessibilityService.performRecents()
        }
        return true
    }

    /**
     * Dispatches a synthetic scroll-up gesture at current pointer coordinates.
     */
    fun scrollUp(): Boolean {
        for (l in listeners) {
            if (l.onMouseScroll(scrollUp = true, cursorX, cursorY)) {
                return true
            }
        }
        return RadPadAccessibilityService.dispatchScroll(cursorX, cursorY, scrollUp = true)
    }

    /**
     * Dispatches a synthetic scroll-down gesture at current pointer coordinates.
     */
    fun scrollDown(): Boolean {
        for (l in listeners) {
            if (l.onMouseScroll(scrollUp = false, cursorX, cursorY)) {
                return true
            }
        }
        return RadPadAccessibilityService.dispatchScroll(cursorX, cursorY, scrollUp = false)
    }

    /**
     * Custom overlay view rendering a crisp pointer arrow with drop shadow and click ripple.
     */
    private class CursorPointerView(context: Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val tipX = CURSOR_TIP_OFFSET_DP * density
        private val tipY = CURSOR_TIP_OFFSET_DP * density
        private val arrowPath = Path()
        private val arrowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.FILL
            setShadowLayer(4f * density, 2f * density, 2f * density, 0x99000000.toInt())
        }
        private val arrowStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1E1E2E.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            strokeJoin = Paint.Join.ROUND
        }
        private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        private class RipplePulse(
            val color: Int,
            var radius: Float = 0f,
            var alpha: Int = 0,
            var strokeWidth: Float = 0f
        )

        private val activeRipples = mutableListOf<RipplePulse>()

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)

            val s = density
            // Modern precision cursor geometry with tip anchored at (tipX, tipY)
            arrowPath.moveTo(tipX, tipY)
            arrowPath.lineTo(tipX, tipY + 22f * s)
            arrowPath.lineTo(tipX + 5.5f * s, tipY + 16.5f * s)
            arrowPath.lineTo(tipX + 10f * s, tipY + 25f * s)
            arrowPath.lineTo(tipX + 13.5f * s, tipY + 23.5f * s)
            arrowPath.lineTo(tipX + 9f * s, tipY + 15f * s)
            arrowPath.lineTo(tipX + 16f * s, tipY + 15f * s)
            arrowPath.close()
        }

        fun triggerClickPulse(button: MouseButton) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                post { triggerClickPulse(button) }
                return
            }

            val color = when (button) {
                MouseButton.LEFT -> 0xFFA6E3A1.toInt()    // Soft green
                MouseButton.RIGHT -> 0xFF89B4FA.toInt()   // Soft blue
                MouseButton.MIDDLE -> 0xFFF9E2AF.toInt()  // Soft gold
            }

            // Cap simultaneous ripples to avoid excessive drawing
            if (activeRipples.size >= 4) {
                activeRipples.removeAt(0)
            }

            val ripple = RipplePulse(color)
            activeRipples.add(ripple)

            val maxRadius = MAX_PULSE_RADIUS_DP * density
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 280
                interpolator = DecelerateInterpolator(1.2f)
                addUpdateListener { va ->
                    val fraction = va.animatedFraction
                    ripple.radius = (3f * density) + fraction * (maxRadius - 3f * density)
                    val rawAlpha = if (fraction < 0.15f) {
                        (fraction / 0.15f) * 220f
                    } else {
                        (1f - (fraction - 0.15f) / 0.85f).toDouble().pow(1.5).toFloat() * 220f
                    }
                    ripple.alpha = rawAlpha.toInt().coerceIn(0, 255)
                    ripple.strokeWidth = (2.6f - fraction * 1.0f) * density
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        activeRipples.remove(ripple)
                        invalidate()
                    }
                })
            }
            anim.start()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // Draw ripple wave pulses centered precisely at arrow tip
            for (i in 0 until activeRipples.size) {
                val ripple = activeRipples.getOrNull(i) ?: continue
                if (ripple.alpha > 0) {
                    pulsePaint.color = ripple.color
                    pulsePaint.alpha = ripple.alpha
                    pulsePaint.strokeWidth = ripple.strokeWidth
                    canvas.drawCircle(tipX, tipY, ripple.radius, pulsePaint)
                }
            }

            // Draw arrow pointer
            canvas.drawPath(arrowPath, arrowFillPaint)
            canvas.drawPath(arrowPath, arrowStrokePaint)
        }
    }
}
