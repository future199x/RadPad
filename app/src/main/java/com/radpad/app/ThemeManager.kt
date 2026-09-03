package com.radpad.app

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ThemeManager: Central color scheme coordinator supporting:
 * - Tokyo Night (Default cyberpunk blue/purple)
 * - Gruvbox (Retro warm groove)
 * - Dracula (Gothic purple/pink/cyan)
 * - Black & White / Monochrome (High-contrast OLED black & white)
 */
object ThemeManager {

    enum class ColorScheme(
        val key: String,
        val displayName: String,
        val dialBackground: Int,
        val deadzoneBackground: Int,
        val ringStroke: Int,
        val sliceDivider: Int,
        val activeSliceFill: Int,
        val activeSliceStroke: Int,
        val inactiveText: Int,
        val activeText: Int,
        val centerInfoText: Int,
        val reticleDot: Int,
        val reticleRing: Int,
        val cardBackground: Int,
        val headerText: Int,
        val closeButtonColor: Int,
        val modeAbcColor: Int,
        val mode123Color: Int,
        val modeFnColor: Int,
        val badgeActiveCapsText: Int,
        val badgeActiveCapsBg: Int,
        val badgeActiveShiftText: Int,
        val badgeActiveShiftBg: Int,
        val badgeActiveCtrlText: Int,
        val badgeActiveCtrlBg: Int,
        val badgeActiveAltText: Int,
        val badgeActiveAltBg: Int,
        val badgeActiveSuperText: Int,
        val badgeActiveSuperBg: Int,
        val badgeInactiveText: Int,
        val badgeInactiveBg: Int
    ) {
        TOKYO_NIGHT(
            key = "tokyonight",
            displayName = "Tokyo Night",
            dialBackground = 0xFF1A1B26.toInt(),
            deadzoneBackground = 0xFF16161E.toInt(),
            ringStroke = 0xFF353B55.toInt(),
            sliceDivider = 0xFF282C40.toInt(),
            activeSliceFill = 0x550084FF.toInt(),
            activeSliceStroke = 0xFF00C8FF.toInt(),
            inactiveText = 0xFFBAC2DE.toInt(),
            activeText = 0xFFFFFFFF.toInt(),
            centerInfoText = 0xFF7AA2F7.toInt(),
            reticleDot = 0xFF00F0FF.toInt(),
            reticleRing = 0xAA00F0FF.toInt(),
            cardBackground = 0xF21A1B26.toInt(),
            headerText = 0xFF7AA2F7.toInt(),
            closeButtonColor = 0xFFF7768E.toInt(),
            modeAbcColor = 0xFFA6E3A1.toInt(),
            mode123Color = 0xFFF9E2AF.toInt(),
            modeFnColor = 0xFFFF9E3B.toInt(),
            badgeActiveCapsText = 0xFFBB9AF7.toInt(),
            badgeActiveCapsBg = 0xFF3B284C.toInt(),
            badgeActiveShiftText = 0xFF7DCFFF.toInt(),
            badgeActiveShiftBg = 0xFF1E3A52.toInt(),
            badgeActiveCtrlText = 0xFF7AA2F7.toInt(),
            badgeActiveCtrlBg = 0xFF1E2852.toInt(),
            badgeActiveAltText = 0xFFE0AF68.toInt(),
            badgeActiveAltBg = 0xFF423318.toInt(),
            badgeActiveSuperText = 0xFF7DCFFF.toInt(),
            badgeActiveSuperBg = 0xFF1E3A52.toInt(),
            badgeInactiveText = 0xFF565F89.toInt(),
            badgeInactiveBg = 0xFF24283B.toInt()
        ),

        GRUVBOX(
            key = "gruvbox",
            displayName = "Gruvbox",
            dialBackground = 0xFF282828.toInt(),
            deadzoneBackground = 0xFF1D2021.toInt(),
            ringStroke = 0xFF504945.toInt(),
            sliceDivider = 0xFF3C3836.toInt(),
            activeSliceFill = 0x55FE8019.toInt(),
            activeSliceStroke = 0xFFFABD2F.toInt(),
            inactiveText = 0xFFEBDBB2.toInt(),
            activeText = 0xFFFBF1C7.toInt(),
            centerInfoText = 0xFFFE8019.toInt(),
            reticleDot = 0xFFB8BB26.toInt(),
            reticleRing = 0xAAB8BB26.toInt(),
            cardBackground = 0xF21D2021.toInt(),
            headerText = 0xFFFE8019.toInt(),
            closeButtonColor = 0xFFFB4934.toInt(),
            modeAbcColor = 0xFFB8BB26.toInt(),
            mode123Color = 0xFFFABD2F.toInt(),
            modeFnColor = 0xFFFE8019.toInt(),
            badgeActiveCapsText = 0xFFD3869B.toInt(),
            badgeActiveCapsBg = 0xFF4A2B36.toInt(),
            badgeActiveShiftText = 0xFF8EC07C.toInt(),
            badgeActiveShiftBg = 0xFF283B2E.toInt(),
            badgeActiveCtrlText = 0xFF83A598.toInt(),
            badgeActiveCtrlBg = 0xFF22353B.toInt(),
            badgeActiveAltText = 0xFFFABD2F.toInt(),
            badgeActiveAltBg = 0xFF4D3D18.toInt(),
            badgeActiveSuperText = 0xFF8EC07C.toInt(),
            badgeActiveSuperBg = 0xFF283B2E.toInt(),
            badgeInactiveText = 0xFF928374.toInt(),
            badgeInactiveBg = 0xFF32302F.toInt()
        ),

        DRACULA(
            key = "dracula",
            displayName = "Dracula",
            dialBackground = 0xFF282A36.toInt(),
            deadzoneBackground = 0xFF21222C.toInt(),
            ringStroke = 0xFF6272A4.toInt(),
            sliceDivider = 0xFF44475A.toInt(),
            activeSliceFill = 0x55BD93F9.toInt(),
            activeSliceStroke = 0xFFFF79C6.toInt(),
            inactiveText = 0xFFF8F8F2.toInt(),
            activeText = 0xFFFFFFFF.toInt(),
            centerInfoText = 0xFFBD93F9.toInt(),
            reticleDot = 0xFF50FA7B.toInt(),
            reticleRing = 0xAA50FA7B.toInt(),
            cardBackground = 0xF221222C.toInt(),
            headerText = 0xFFBD93F9.toInt(),
            closeButtonColor = 0xFFFF5555.toInt(),
            modeAbcColor = 0xFF50FA7B.toInt(),
            mode123Color = 0xFFF1FA8C.toInt(),
            modeFnColor = 0xFFFF79C6.toInt(),
            badgeActiveCapsText = 0xFFFF79C6.toInt(),
            badgeActiveCapsBg = 0xFF4D203D.toInt(),
            badgeActiveShiftText = 0xFF8BE9FD.toInt(),
            badgeActiveShiftBg = 0xFF1D3B45.toInt(),
            badgeActiveCtrlText = 0xFFBD93F9.toInt(),
            badgeActiveCtrlBg = 0xFF35204C.toInt(),
            badgeActiveAltText = 0xFFF1FA8C.toInt(),
            badgeActiveAltBg = 0xFF45421A.toInt(),
            badgeActiveSuperText = 0xFFBD93F9.toInt(),
            badgeActiveSuperBg = 0xFF35204C.toInt(),
            badgeInactiveText = 0xFF6272A4.toInt(),
            badgeInactiveBg = 0xFF343746.toInt()
        ),

        MONOCHROME(
            key = "monochrome",
            displayName = "Black & White",
            dialBackground = 0xFF000000.toInt(),
            deadzoneBackground = 0xFF0A0A0A.toInt(),
            ringStroke = 0xFF444444.toInt(),
            sliceDivider = 0xFF222222.toInt(),
            activeSliceFill = 0x55FFFFFF.toInt(),
            activeSliceStroke = 0xFFFFFFFF.toInt(),
            inactiveText = 0xFF9E9E9E.toInt(),
            activeText = 0xFFFFFFFF.toInt(),
            centerInfoText = 0xFFE0E0E0.toInt(),
            reticleDot = 0xFFFFFFFF.toInt(),
            reticleRing = 0xAAFFFFFF.toInt(),
            cardBackground = 0xF8000000.toInt(),
            headerText = 0xFFFFFFFF.toInt(),
            closeButtonColor = 0xFF888888.toInt(),
            modeAbcColor = 0xFFFFFFFF.toInt(),
            mode123Color = 0xFFFFFFFF.toInt(),
            modeFnColor = 0xFFFFFFFF.toInt(),
            badgeActiveCapsText = 0xFF000000.toInt(),
            badgeActiveCapsBg = 0xFFFFFFFF.toInt(),
            badgeActiveShiftText = 0xFF000000.toInt(),
            badgeActiveShiftBg = 0xFFFFFFFF.toInt(),
            badgeActiveCtrlText = 0xFF000000.toInt(),
            badgeActiveCtrlBg = 0xFFFFFFFF.toInt(),
            badgeActiveAltText = 0xFF000000.toInt(),
            badgeActiveAltBg = 0xFFFFFFFF.toInt(),
            badgeActiveSuperText = 0xFF000000.toInt(),
            badgeActiveSuperBg = 0xFFFFFFFF.toInt(),
            badgeInactiveText = 0xFF777777.toInt(),
            badgeInactiveBg = 0xFF1C1C1C.toInt()
        );

        companion object {
            fun fromKey(key: String?): ColorScheme {
                return values().firstOrNull { it.key.equals(key, ignoreCase = true) } ?: TOKYO_NIGHT
            }
        }
    }

    interface ThemeListener {
        fun onThemeChanged(theme: ColorScheme)
    }

    private val listeners = CopyOnWriteArrayList<ThemeListener>()
    var currentTheme: ColorScheme = ColorScheme.TOKYO_NIGHT
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("radpad_prefs", Context.MODE_PRIVATE)
        val savedKey = prefs.getString("color_scheme", ColorScheme.TOKYO_NIGHT.key)
        currentTheme = ColorScheme.fromKey(savedKey)
    }

    fun setTheme(context: Context, theme: ColorScheme) {
        currentTheme = theme
        context.getSharedPreferences("radpad_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("color_scheme", theme.key)
            .apply()

        for (l in listeners) {
            l.onThemeChanged(theme)
        }
    }

    fun register(listener: ThemeListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
        listener.onThemeChanged(currentTheme)
    }

    fun unregister(listener: ThemeListener) {
        listeners.remove(listener)
    }
}
