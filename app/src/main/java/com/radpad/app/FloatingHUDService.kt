package com.radpad.app

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * System-wide, always-on-top draggable HUD overlay service.
 *
 * Renders [KinematicRadialHUDView] persistently over any application window using Android's
 * overlay window type ([WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]).
 *
 * Synchronizes with [FloatingHUDManager] to display live gamepad telemetry, active layers,
 * modifier statuses, and targeting feedback even when the soft keyboard is hidden.
 */
class FloatingHUDService : Service(), FloatingHUDManager.Listener, ThemeManager.ThemeListener {

    companion object {
        /**
         * Global running flag indicating whether the floating HUD service is currently active.
         */
        var isRunning: Boolean = false
            private set
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var floatingContainer: View? = null
    private var tvTitle: TextView? = null
    private var tvMode: TextView? = null
    private var closeBtn: TextView? = null
    private var badgeCaps: TextView? = null
    private var badgeShift: TextView? = null
    private var badgeCtrl: TextView? = null
    private var badgeAlt: TextView? = null
    private var badgeSuper: TextView? = null
    private var radialHUD: KinematicRadialHUDView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }

    private fun createPillDrawable(bgColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(4f)
            setColor(bgColor)
        }
    }

    /**
     * Updates a modifier indicator pill badge with active/inactive colors according to [isActive].
     */
    private fun applyBadgeState(
        badge: TextView?,
        isActive: Boolean,
        activeText: Int,
        activeBg: Int,
        theme: ThemeManager.ColorScheme
    ) {
        if (isActive) {
            badge?.setTextColor(activeText)
            badge?.background = createPillDrawable(activeBg)
        } else {
            badge?.setTextColor(theme.badgeInactiveText)
            badge?.background = createPillDrawable(theme.badgeInactiveBg)
        }
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        isRunning = true
        FloatingHUDManager.notifyFloaterStateChanged(true)

        ThemeManager.init(this)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        floatingView = inflater.inflate(R.layout.layout_floating_hud, null)

        floatingContainer = floatingView?.findViewById(R.id.floating_container)
        tvTitle = floatingView?.findViewById(R.id.tv_floating_title)
        tvMode = floatingView?.findViewById(R.id.btn_floating_mode)
        closeBtn = floatingView?.findViewById(R.id.btn_close_floating)
        badgeCaps = floatingView?.findViewById(R.id.hud_badge_caps)
        badgeShift = floatingView?.findViewById(R.id.hud_badge_shift)
        badgeCtrl = floatingView?.findViewById(R.id.hud_badge_ctrl)
        badgeAlt = floatingView?.findViewById(R.id.hud_badge_alt)
        badgeSuper = floatingView?.findViewById(R.id.hud_badge_super)
        radialHUD = floatingView?.findViewById(R.id.floating_radial_hud)
        val header = floatingView?.findViewById<View>(R.id.floating_header)

        MacroManager.init(this)

        VirtualMouseManager.register(mouseListener)

        closeBtn?.setOnClickListener {
            stopSelf()
        }

        tvMode?.setOnClickListener {
            val current = FloatingHUDManager.lastLayer
            val layers = InputEngine.Layer.entries
            val nextLayer = layers[(current.ordinal + 1) % layers.size]
            FloatingHUDManager.activeEngine?.setLayer(nextLayer)
            FloatingHUDManager.updateInput(
                FloatingHUDManager.lastX,
                FloatingHUDManager.lastY,
                nextLayer,
                false,
                FloatingHUDManager.isShift,
                FloatingHUDManager.isCtrl,
                FloatingHUDManager.isAlt,
                FloatingHUDManager.isCaps,
                FloatingHUDManager.isSuper
            )
        }

        val layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 160
        }

        header?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                val params = layoutParams ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        try {
                            windowManager?.updateViewLayout(floatingView, params)
                        } catch (_: Exception) {}
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        v?.performClick()
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager?.addView(floatingView, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
            return
        }

        ThemeManager.register(this)
        FloatingHUDManager.register(this)
    }

    /**
     * Re-applies theme colors to the floating card container, title, badges, and radial HUD.
     */
    override fun onThemeChanged(theme: ThemeManager.ColorScheme) {
        val updateAction = Runnable {
            val cardBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(20f)
                setColor(theme.cardBackground)
                setStroke(dpToPx(1.5f).toInt(), theme.ringStroke)
            }
            floatingContainer?.background = cardBg

            tvTitle?.setTextColor(theme.headerText)
            closeBtn?.setTextColor(theme.closeButtonColor)

            radialHUD?.applyColorScheme(theme)

            FloatingHUDManager.let {
                onStateUpdated(it.lastX, it.lastY, it.lastLayer, it.isSecondLayer, it.isShift, it.isCtrl, it.isAlt, it.isCaps, it.isSuper)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            updateAction.run()
        } else {
            mainHandler.post(updateAction)
        }
    }

    /**
     * Receives broadcasted input state updates from [FloatingHUDManager] and refreshes badges and dial.
     */
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
        val updateAction = Runnable {
            val theme = ThemeManager.currentTheme

            val isMouse = VirtualMouseManager.isMouseLayerActive
            if (isMouse) {
                tvMode?.visibility = View.GONE
                tvTitle?.setText(R.string.floating_title_mouse_mode)
            } else {
                tvMode?.visibility = View.VISIBLE
                tvTitle?.setText(R.string.floating_title_radpad)

                val modeLabel = when (layer) {
                    InputEngine.Layer.BASE -> getString(R.string.mode_base)
                    InputEngine.Layer.MORE_SYM -> if (secondLayer) getString(R.string.mode_sym_2) else getString(R.string.mode_sym_1)
                    InputEngine.Layer.NUM_SYM -> if (secondLayer) getString(R.string.mode_num_2) else getString(R.string.mode_num_1)
                    InputEngine.Layer.FN -> if (secondLayer) getString(R.string.mode_fn_2) else getString(R.string.mode_fn_1)
                    InputEngine.Layer.Q_Z -> if (secondLayer) getString(R.string.mode_qz_2) else getString(R.string.mode_qz_1)
                    InputEngine.Layer.MACRO -> getString(R.string.mode_macro)
                    else -> layer.displayName
                }
                val modeColor = when (layer) {
                    InputEngine.Layer.FN, InputEngine.Layer.SYS, InputEngine.Layer.MACRO -> theme.modeFnColor
                    InputEngine.Layer.NUM_SYM, InputEngine.Layer.MORE_SYM -> theme.mode123Color
                    else -> theme.modeAbcColor
                }
                tvMode?.text = modeLabel
                tvMode?.setTextColor(modeColor)
                tvMode?.background = createPillDrawable(theme.badgeInactiveBg)
            }

            // Update modifier badges via helper
            applyBadgeState(badgeCaps, caps, theme.badgeActiveCapsText, theme.badgeActiveCapsBg, theme)
            applyBadgeState(badgeShift, shift, theme.badgeActiveShiftText, theme.badgeActiveShiftBg, theme)
            applyBadgeState(badgeCtrl, ctrl, theme.badgeActiveCtrlText, theme.badgeActiveCtrlBg, theme)
            applyBadgeState(badgeAlt, alt, theme.badgeActiveAltText, theme.badgeActiveAltBg, theme)
            applyBadgeState(badgeSuper, superKey, theme.badgeActiveSuperText, theme.badgeActiveSuperBg, theme)

            radialHUD?.updateState(x, y, layer, secondLayer, shift, ctrl, alt, caps, superKey)
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            updateAction.run()
        } else {
            mainHandler.post(updateAction)
        }
    }

    private val mouseListener = object : VirtualMouseManager.MouseStateListener {
        override fun onMouseLayerChanged(active: Boolean) {
            mainHandler.post {
                if (active) {
                    tvMode?.visibility = View.GONE
                    tvTitle?.setText(R.string.floating_title_mouse_mode)
                } else {
                    tvMode?.visibility = View.VISIBLE
                    tvTitle?.setText(R.string.floating_title_radpad)
                }
                radialHUD?.invalidate()
            }
        }
        override fun onCursorMoved(x: Float, y: Float) {}
        override fun onMouseClicked(button: VirtualMouseManager.MouseButton, x: Float, y: Float) {}
    }

    /**
     * Cleans up floating view from WindowManager, unregisters listeners, and resets running status.
     */
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        FloatingHUDManager.notifyFloaterStateChanged(false)
        VirtualMouseManager.unregister(mouseListener)
        FloatingHUDManager.unregister(this)
        ThemeManager.unregister(this)
        if (floatingView != null) {
            try {
                windowManager?.removeView(floatingView)
            } catch (_: Exception) {}
            floatingView = null
        }
    }
}
