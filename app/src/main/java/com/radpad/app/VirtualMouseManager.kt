package com.radpad.app

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
 * VirtualMouseManager: Coordinates on-screen cursor position, smooth analog velocity integration,
 * overlay pointer rendering, and click dispatching via RadPadAccessibilityService.
 */
object VirtualMouseManager {

    interface MouseStateListener {
        fun onMouseLayerChanged(active: Boolean)
        fun onCursorMoved(x: Float, y: Float)
        fun onMouseClicked(button: MouseButton, x: Float, y: Float)
    }

    enum class MouseButton {
        LEFT, RIGHT, MIDDLE
    }

    private val listeners = CopyOnWriteArrayList<MouseStateListener>()

    var isMouseLayerActive: Boolean = false
        private set

    var cursorX: Float = 500f
        private set
    var cursorY: Float = 800f
        private set

    private var screenWidth: Int = 1080
    private var screenHeight: Int = 2400

    private var stickX: Float = 0f
    private var stickY: Float = 0f

    private var windowManager: WindowManager? = null
    private var cursorView: CursorPointerView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private val mainHandler = Handler(Looper.getMainLooper())
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) return

        val wm = windowManager ?: return

        cursorView = CursorPointerView(context.applicationContext)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val density = context.resources.displayMetrics.density
        val tipOffset = 10f * density
        val sizePx = (44 * density).toInt()

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

            mainHandler.postDelayed(this, 16)
        }
    }

    private fun startKinematicLoop() {
        if (isLoopRunning) return
        isLoopRunning = true
        lastFrameTime = SystemClock.uptimeMillis()
        mainHandler.post(frameRunnable)
    }

    private fun stopKinematicLoop() {
        isLoopRunning = false
        mainHandler.removeCallbacks(frameRunnable)
    }

    private fun updateCursorPosition() {
        val view = cursorView ?: return
        val params = layoutParams ?: return
        val wm = windowManager ?: return

        val tipOffset = 10f * view.context.resources.displayMetrics.density
        params.x = (cursorX - tipOffset).toInt()
        params.y = (cursorY - tipOffset).toInt()
        try {
            wm.updateViewLayout(view, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun performLeftClick(): Boolean {
        cursorView?.triggerClickPulse(MouseButton.LEFT)
        for (l in listeners) {
            l.onMouseClicked(MouseButton.LEFT, cursorX, cursorY)
        }
        return RadPadAccessibilityService.dispatchTap(cursorX, cursorY, 50L)
    }

    fun performRightClick(): Boolean {
        cursorView?.triggerClickPulse(MouseButton.RIGHT)
        for (l in listeners) {
            l.onMouseClicked(MouseButton.RIGHT, cursorX, cursorY)
        }
        // Long press for context menu, or fallback to global back
        val tapped = RadPadAccessibilityService.dispatchTap(cursorX, cursorY, 550L)
        if (!tapped) {
            return RadPadAccessibilityService.performBack()
        }
        return true
    }

    fun performMiddleClick(): Boolean {
        cursorView?.triggerClickPulse(MouseButton.MIDDLE)
        for (l in listeners) {
            l.onMouseClicked(MouseButton.MIDDLE, cursorX, cursorY)
        }
        val tapped = RadPadAccessibilityService.dispatchTap(cursorX, cursorY, 100L)
        if (!tapped) {
            return RadPadAccessibilityService.performRecents()
        }
        return true
    }

    /**
     * Custom overlay view rendering a crisp pointer arrow with drop shadow and click ripple.
     */
    private class CursorPointerView(context: Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val tipX = 10f * density
        private val tipY = 10f * density
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
            strokeWidth = 3f * density
        }

        private var pulseRadius = 0f
        private var pulseAlpha = 0
        private var pulseColor = 0xFFA6E3A1.toInt()

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
            pulseColor = when (button) {
                MouseButton.LEFT -> 0xFFA6E3A1.toInt()    // Soft green
                MouseButton.RIGHT -> 0xFF89B4FA.toInt()   // Soft blue
                MouseButton.MIDDLE -> 0xFFF9E2AF.toInt()  // Soft gold
            }
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 250
                interpolator = DecelerateInterpolator()
                addUpdateListener { va ->
                    val fraction = va.animatedFraction
                    pulseRadius = fraction * 20f * density
                    pulseAlpha = ((1f - fraction) * 255).toInt()
                    invalidate()
                }
            }
            anim.start()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // Draw ripple pulse centered precisely at arrow tip
            if (pulseAlpha > 0) {
                pulsePaint.color = pulseColor
                pulsePaint.alpha = pulseAlpha
                canvas.drawCircle(tipX, tipY, pulseRadius, pulsePaint)
            }

            // Draw arrow pointer
            canvas.drawPath(arrowPath, arrowFillPaint)
            canvas.drawPath(arrowPath, arrowStrokePaint)
        }
    }
}
