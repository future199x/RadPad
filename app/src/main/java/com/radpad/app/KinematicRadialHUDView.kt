package com.radpad.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * KinematicRadialHUDView: Hardware-accelerated radial controller dial.
 */
class KinematicRadialHUDView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val DEADZONE_ENGAGE: Float = 0.42f
        const val DEADZONE_ENGAGE_SQ: Float = DEADZONE_ENGAGE * DEADZONE_ENGAGE

        const val DEADZONE_RELEASE: Float = 0.25f
        const val DEADZONE_RELEASE_SQ: Float = DEADZONE_RELEASE * DEADZONE_RELEASE

        val BASE_PREVIEW_LABELS = arrayOf("A-H", "SYM", "I-P", "FN", "Q-Z", "MACRO", "NUM", "SYS")

        val SYMMETRIC_START_ANGLES = floatArrayOf(247.5f, 292.5f, 337.5f, 22.5f, 67.5f, 112.5f, 157.5f, 202.5f)
        val SYMMETRIC_SWEEP_ANGLES = floatArrayOf(45f, 45f, 45f, 45f, 45f, 45f, 45f, 45f)

        val ASYMMETRIC_START_ANGLES = floatArrayOf(240f, 300f, 330f, 30f, 60f, 120f, 150f, 210f)
        val ASYMMETRIC_SWEEP_ANGLES = floatArrayOf(60f, 30f, 60f, 30f, 60f, 30f, 60f, 30f)

        val SLICE_CENTER_AZIMUTHS = floatArrayOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f)

        fun shiftNumberSymbol(c: String): String {
            return when (c) {
                "1" -> "!"
                "2" -> "@"
                "3" -> "#"
                "4" -> "$"
                "5" -> "%"
                "6" -> "^"
                "7" -> "&"
                "8" -> "*"
                "9" -> "("
                "0" -> ")"
                "-" -> "_"
                "=" -> "+"
                "[" -> "{"
                "]" -> "}"
                ";" -> ":"
                "'" -> "\""
                "," -> "<"
                "." -> ">"
                "/" -> "?"
                "\\" -> "|"
                "`" -> "~"
                else -> c
            }
        }
    }

    var isSymmetric: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    // State properties
    private var stickX: Float = 0f
    private var stickY: Float = 0f
    var currentLayer: InputEngine.Layer = InputEngine.Layer.BASE
        private set
    private var isSecondLayer: Boolean = false
    private var isShift: Boolean = false
    private var isCtrl: Boolean = false
    private var isAlt: Boolean = false
    private var isCaps: Boolean = false
    private var isSuper: Boolean = false
    private var isDeflected: Boolean = false
    private var targetedSlice: Int = -1 // -1 when in deadzone, 0..7 otherwise

    // Reusable drawing objects
    private val outerRect = RectF()
    private val innerDeadzoneRect = RectF()
    private val arcPath = Path()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF141724.toInt()
    }

    private val sliceDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFF282C40.toInt()
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xFF353B55.toInt()
    }

    private val activeSliceFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x550084FF.toInt()
    }

    private val activeSliceStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = 0xFF00C8FF.toInt()
    }

    private val deadzonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF1B1E2E.toInt()
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 36f
        color = 0xFFBAC2DE.toInt()
        isFakeBoldText = true
    }

    private val activeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 46f
        color = 0xFFFFFFFF.toInt()
        isFakeBoldText = true
    }

    private val centerInfoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 22f
        color = 0xFF7AA2F7.toInt()
        isFakeBoldText = true
    }

    private val centerSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 14f
        color = 0xFF9AA5CE.toInt()
    }

    private val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF00F0FF.toInt()
    }

    private val reticleRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xAA00F0FF.toInt()
    }

    init {
        ThemeManager.init(context)
        applyColorScheme(ThemeManager.currentTheme)
        MacroManager.init(context)
        MacroManager.registerListener(object : MacroManager.Listener {
            override fun onMacrosChanged() {
                invalidate()
            }
        })
    }

    fun applyColorScheme(theme: ThemeManager.ColorScheme) {
        bgPaint.color = theme.dialBackground
        deadzonePaint.color = theme.deadzoneBackground
        ringPaint.color = theme.ringStroke
        sliceDividerPaint.color = theme.sliceDivider
        activeSliceFillPaint.color = theme.activeSliceFill
        activeSliceStrokePaint.color = theme.activeSliceStroke
        textPaint.color = theme.inactiveText
        activeTextPaint.color = theme.activeText
        centerInfoPaint.color = theme.centerInfoText
        centerSubPaint.color = theme.inactiveText
        reticlePaint.color = theme.reticleDot
        reticleRingPaint.color = theme.reticleRing
        invalidate()
    }

    fun updateState(
        x: Float,
        y: Float,
        layer: InputEngine.Layer,
        secondLayer: Boolean,
        shift: Boolean,
        ctrl: Boolean,
        alt: Boolean,
        caps: Boolean = false,
        superKey: Boolean = false
    ) {
        stickX = x
        stickY = y
        currentLayer = layer
        isSecondLayer = secondLayer
        isShift = shift
        isCtrl = ctrl
        isAlt = alt
        isCaps = caps
        isSuper = superKey

        val rSq = (x * x) + (y * y)

        if (isDeflected) {
            if (rSq < DEADZONE_RELEASE_SQ) {
                isDeflected = false
                targetedSlice = -1
            } else {
                targetedSlice = calculateSlice(x, y)
            }
        } else {
            if (rSq >= DEADZONE_ENGAGE_SQ) {
                isDeflected = true
                targetedSlice = calculateSlice(x, y)
            } else {
                targetedSlice = -1
            }
        }

        invalidate()
    }

    private fun calculateSlice(x: Float, y: Float): Int {
        val rad = atan2(x.toDouble(), -y.toDouble())
        var deg = Math.toDegrees(rad).toFloat()
        if (deg < 0f) deg += 360f

        return if (isSymmetric) {
            when {
                deg >= 337.5f || deg < 22.5f -> 0
                deg < 67.5f -> 1
                deg < 112.5f -> 2
                deg < 157.5f -> 3
                deg < 202.5f -> 4
                deg < 247.5f -> 5
                deg < 292.5f -> 6
                else -> 7
            }
        } else {
            when {
                deg >= 330f || deg < 30f -> 0
                deg < 60f -> 1
                deg < 120f -> 2
                deg < 150f -> 3
                deg < 210f -> 4
                deg < 240f -> 5
                deg < 300f -> 6
                else -> 7
            }
        }
    }

    private fun getSliceLabels(effectiveShift: Boolean): Array<String> {
        return when (currentLayer) {
            InputEngine.Layer.BASE -> BASE_PREVIEW_LABELS

            InputEngine.Layer.A_H -> {
                val base = arrayOf("a", "b", "c", "d", "e", "f", "g", "h")
                if (effectiveShift) base.map { it.uppercase() }.toTypedArray() else base
            }

            InputEngine.Layer.I_P -> {
                val base = arrayOf("i", "j", "k", "l", "m", "n", "o", "p")
                if (effectiveShift) base.map { it.uppercase() }.toTypedArray() else base
            }

            InputEngine.Layer.Q_Z -> {
                if (isSecondLayer) {
                    val base = arrayOf("y", "z", "", "", "", "", "", "")
                    if (effectiveShift) base.map { if (it.isNotEmpty()) it.uppercase() else "" }.toTypedArray() else base
                } else {
                    val base = arrayOf("q", "r", "s", "t", "u", "v", "w", "x")
                    if (effectiveShift) base.map { it.uppercase() }.toTypedArray() else base
                }
            }

            InputEngine.Layer.MACRO -> {
                MacroManager.getMacroLabels()
            }

            InputEngine.Layer.MORE_SYM -> {
                if (isSecondLayer) {
                    // SYM 2: North='`', East=']', West='['
                    if (effectiveShift) arrayOf("~", "", "}", "", "", "", "{", "")
                    else arrayOf("`", "", "]", "", "", "", "[", "")
                } else {
                    // SYM 1: North=''', NE='=', East='.', SE=';', South='\', SW='/', West=',', NW='-'
                    if (effectiveShift) arrayOf("\"", "+", ">", ":", "|", "?", "<", "_")
                    else arrayOf("'", "=", ".", ";", "\\", "/", ",", "-")
                }
            }

            InputEngine.Layer.NUM_SYM -> {
                if (isSecondLayer) {
                    // 9-0: North='9', NE='0'
                    if (effectiveShift) arrayOf("(", ")", "", "", "", "", "", "")
                    else arrayOf("9", "0", "", "", "", "", "", "")
                } else {
                    // 1-8
                    if (effectiveShift) arrayOf("!", "@", "#", "$", "%", "^", "&", "*")
                    else arrayOf("1", "2", "3", "4", "5", "6", "7", "8")
                }
            }

            InputEngine.Layer.FN -> {
                if (isSecondLayer) {
                    arrayOf("F9", "F10", "F11", "F12", "", "", "", "")
                } else {
                    arrayOf("F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8")
                }
            }

            InputEngine.Layer.SYS -> {
                arrayOf("PgUp", "Vol+", "End", "Del", "PgDn", "Ins", "Home", "Vol-")
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val centerX = w / 2f
        val centerY = h / 2f
        val radius = (Math.min(w, h) / 2f) * 0.95f
        val deadzoneRadius = radius * DEADZONE_ENGAGE

        outerRect.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        innerDeadzoneRect.set(
            centerX - deadzoneRadius,
            centerY - deadzoneRadius,
            centerX + deadzoneRadius,
            centerY + deadzoneRadius
        )

        val startAngles = if (isSymmetric) SYMMETRIC_START_ANGLES else ASYMMETRIC_START_ANGLES
        val sweepAngles = if (isSymmetric) SYMMETRIC_SWEEP_ANGLES else ASYMMETRIC_SWEEP_ANGLES

        // 1. Draw outer circle background
        canvas.drawCircle(centerX, centerY, radius, bgPaint)

        // 2. Draw active targeted slice highlight
        if (targetedSlice in 0..7) {
            val startAngle = startAngles[targetedSlice]
            val sweepAngle = sweepAngles[targetedSlice]

            arcPath.reset()
            arcPath.moveTo(centerX, centerY)
            arcPath.arcTo(outerRect, startAngle, sweepAngle)
            arcPath.close()

            canvas.drawPath(arcPath, activeSliceFillPaint)
            canvas.drawPath(arcPath, activeSliceStrokePaint)
        }

        // 3. Draw slice divider radial lines
        for (i in 0..7) {
            val dividerCanvasAngle = Math.toRadians(startAngles[i].toDouble())
            val edgeX = centerX + (radius * cos(dividerCanvasAngle)).toFloat()
            val edgeY = centerY + (radius * sin(dividerCanvasAngle)).toFloat()
            canvas.drawLine(centerX, centerY, edgeX, edgeY, sliceDividerPaint)
        }

        // 4. Draw outer border ring
        canvas.drawCircle(centerX, centerY, radius, ringPaint)

        // 5. Draw center deadzone circle
        val isSysDeadzoneTargeted = (currentLayer == InputEngine.Layer.SYS && targetedSlice == -1)
        val isPageToggleTargeted = (targetedSlice == -1 && currentLayer in listOf(
            InputEngine.Layer.Q_Z, InputEngine.Layer.MORE_SYM, InputEngine.Layer.NUM_SYM, InputEngine.Layer.FN
        ))

        if (isSysDeadzoneTargeted || isPageToggleTargeted) {
            canvas.drawCircle(centerX, centerY, deadzoneRadius, activeSliceFillPaint)
            canvas.drawCircle(centerX, centerY, deadzoneRadius, activeSliceStrokePaint)
        } else {
            canvas.drawCircle(centerX, centerY, deadzoneRadius, deadzonePaint)
            canvas.drawCircle(centerX, centerY, deadzoneRadius, ringPaint)
        }

        // 6. Draw sector labels
        val effectiveShift = isShift != isCaps
        val labels = getSliceLabels(effectiveShift)
        val labelRadius = radius * 0.68f

        for (i in 0..7) {
            val charText = labels[i]
            if (charText.isEmpty()) continue

            val azimuthRad = Math.toRadians((SLICE_CENTER_AZIMUTHS[i] - 90f).toDouble())
            val textX = centerX + (labelRadius * cos(azimuthRad)).toFloat()
            val textY = centerY + (labelRadius * sin(azimuthRad)).toFloat()

            val paint = if (i == targetedSlice) activeTextPaint else textPaint
            if (charText.length >= 3) {
                paint.textSize = if (i == targetedSlice) radius * 0.20f else radius * 0.16f
            } else {
                paint.textSize = if (i == targetedSlice) radius * 0.32f else radius * 0.25f
            }

            val verticalOffset = (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(charText, textX, textY - verticalOffset, paint)
        }

        // 7. Draw center status indicator & layer telemetry
        centerInfoPaint.textSize = radius * 0.13f
        centerSubPaint.textSize = radius * 0.08f

        if (VirtualMouseManager.isMouseLayerActive) {
            canvas.drawText("🐭 MOUSE", centerX, centerY - (radius * 0.04f), activeTextPaint.apply { textSize = radius * 0.12f })
            canvas.drawText("D-PAD: CLICKS", centerX, centerY + (radius * 0.12f), centerSubPaint)
        } else when (currentLayer) {
            InputEngine.Layer.BASE -> {
                val hoverPreview = when (targetedSlice) {
                    0 -> "A - H"
                    1 -> "MORE SYM"
                    2 -> "I - P"
                    3 -> "FN (1-12)"
                    4 -> "Q - Z"
                    5 -> "MACRO"
                    6 -> "NUMBERS"
                    7 -> "SYSTEM"
                    else -> "RADPAD"
                }
                val subText = if (targetedSlice != -1) "R1: SELECT" else "AIM + R1"
                canvas.drawText(hoverPreview, centerX, centerY - (radius * 0.04f), centerInfoPaint)
                canvas.drawText(subText, centerX, centerY + (radius * 0.12f), centerSubPaint)
            }

            InputEngine.Layer.SYS -> {
                val title = if (isSysDeadzoneTargeted) "▶ MUTE ◀" else "VOL TOGGLE"
                val sub = if (isSysDeadzoneTargeted) "R1: TOGGLE" else "L1: BASE"
                canvas.drawText(title, centerX, centerY - (radius * 0.04f), if (isSysDeadzoneTargeted) activeTextPaint.apply { textSize = radius * 0.12f } else centerInfoPaint)
                canvas.drawText(sub, centerX, centerY + (radius * 0.12f), centerSubPaint)
            }

            InputEngine.Layer.Q_Z -> {
                val title = if (isPageToggleTargeted) (if (isSecondLayer) "▶ Q - X ◀" else "▶ Y - Z ◀") else (if (isSecondLayer) "Y - Z (2/2)" else "Q - X (1/2)")
                val sub = if (isPageToggleTargeted) "R1: TOGGLE" else (if (isSecondLayer) "L1: PAGE 1" else "L1: BASE")
                canvas.drawText(title, centerX, centerY - (radius * 0.04f), if (isPageToggleTargeted) activeTextPaint.apply { textSize = radius * 0.12f } else centerInfoPaint)
                canvas.drawText(sub, centerX, centerY + (radius * 0.12f), centerSubPaint)
            }

            InputEngine.Layer.MORE_SYM -> {
                val title = if (isPageToggleTargeted) (if (isSecondLayer) "▶ SYM 1 ◀" else "▶ SYM 2 ◀") else (if (isSecondLayer) "SYM 2 (2/2)" else "SYM 1 (1/2)")
                val sub = if (isPageToggleTargeted) "R1: TOGGLE" else (if (isSecondLayer) "L1: PAGE 1" else "L1: BASE")
                canvas.drawText(title, centerX, centerY - (radius * 0.04f), if (isPageToggleTargeted) activeTextPaint.apply { textSize = radius * 0.12f } else centerInfoPaint)
                canvas.drawText(sub, centerX, centerY + (radius * 0.12f), centerSubPaint)
            }

            InputEngine.Layer.NUM_SYM -> {
                val title = if (isPageToggleTargeted) (if (isSecondLayer) "▶ 1 - 8 ◀" else "▶ 9 - 0 ◀") else (if (isSecondLayer) "9 - 0 (2/2)" else "1 - 8 (1/2)")
                val sub = if (isPageToggleTargeted) "R1: TOGGLE" else (if (isSecondLayer) "L1: PAGE 1" else "L1: BASE")
                canvas.drawText(title, centerX, centerY - (radius * 0.04f), if (isPageToggleTargeted) activeTextPaint.apply { textSize = radius * 0.12f } else centerInfoPaint)
                canvas.drawText(sub, centerX, centerY + (radius * 0.12f), centerSubPaint)
            }

            InputEngine.Layer.FN -> {
                val title = if (isPageToggleTargeted) (if (isSecondLayer) "▶ FN 1-8 ◀" else "▶ FN 9-12 ◀") else (if (isSecondLayer) "FN 9-12 (2/2)" else "FN 1-8 (1/2)")
                val sub = if (isPageToggleTargeted) "R1: TOGGLE" else (if (isSecondLayer) "L1: PAGE 1" else "L1: BASE")
                canvas.drawText(title, centerX, centerY - (radius * 0.04f), if (isPageToggleTargeted) activeTextPaint.apply { textSize = radius * 0.12f } else centerInfoPaint)
                canvas.drawText(sub, centerX, centerY + (radius * 0.12f), centerSubPaint)
            }

            InputEngine.Layer.MACRO -> {
                canvas.drawText("MACROS", centerX, centerY - (radius * 0.04f), centerInfoPaint)
                canvas.drawText("L1: BASE", centerX, centerY + (radius * 0.12f), centerSubPaint)
            }

            else -> {
                canvas.drawText(currentLayer.displayName, centerX, centerY - (radius * 0.04f), centerInfoPaint)
                canvas.drawText("L1: BASE", centerX, centerY + (radius * 0.12f), centerSubPaint)
            }
        }

        // 8. Draw physical analog stick position dot (targeting reticle)
        val stickPixelX = centerX + (stickX * radius * 0.85f)
        val stickPixelY = centerY + (stickY * radius * 0.85f)

        canvas.drawCircle(stickPixelX, stickPixelY, 14f, reticleRingPaint)
        canvas.drawCircle(stickPixelX, stickPixelY, 7f, reticlePaint)
    }
}
