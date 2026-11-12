package com.pulsarnav.app.ui

import android.graphics.Color
import com.pulsarnav.app.data.AppSettings

data class CockpitTheme(
    val id: String,
    val name: String,
    val displayName: String,
    val isLight: Boolean,
    val backgroundColor: Int,
    val surfaceColor: Int,
    val cardColor: Int,
    val cardBorderColor: Int,
    val textPrimaryColor: Int,
    val textSecondaryColor: Int,
    val textTertiaryColor: Int,
    val primaryColor: Int,
    val onPrimaryColor: Int,
    val accentColor: Int,
    val subtleColor: Int,
    val borderColor: Int
)

private data class SurfacePalette(
    val backgroundColor: Int,
    val surfaceColor: Int,
    val cardColor: Int,
    val cardBorderColor: Int,
    val textPrimaryColor: Int,
    val textSecondaryColor: Int,
    val textTertiaryColor: Int
)

private data class AccentSpec(
    val id: String,
    val name: String,
    val primaryHex: String,
    val onPrimary: Int,
    val accentHex: String,
    val subtleHex: String,
    val borderHex: String
)

object CockpitThemePalette {

    private val LIGHT_SURFACE = SurfacePalette(
        backgroundColor = Color.parseColor("#F4F5F8"),
        surfaceColor = Color.parseColor("#FFFFFF"),
        cardColor = Color.parseColor("#FFFFFF"),
        cardBorderColor = Color.parseColor("#E2E8F0"),
        textPrimaryColor = Color.parseColor("#0F172A"),
        textSecondaryColor = Color.parseColor("#475569"),
        textTertiaryColor = Color.parseColor("#64748B")
    )

    private val DARK_SURFACE = SurfacePalette(
        backgroundColor = Color.parseColor("#131215"),
        surfaceColor = Color.parseColor("#1C1B20"),
        cardColor = Color.parseColor("#222128"),
        cardBorderColor = Color.parseColor("#32303B"),
        textPrimaryColor = Color.parseColor("#F7F7F8"),
        textSecondaryColor = Color.parseColor("#A5A4AF"),
        textTertiaryColor = Color.parseColor("#72707D")
    )

    private val AMOLED_DARK_SURFACE = SurfacePalette(
        backgroundColor = Color.parseColor("#000000"),
        surfaceColor = Color.parseColor("#000000"),
        cardColor = Color.parseColor("#0B0B0E"),
        cardBorderColor = Color.parseColor("#262630"),
        textPrimaryColor = Color.parseColor("#F8FAFC"),
        textSecondaryColor = Color.parseColor("#A5A4AF"),
        textTertiaryColor = Color.parseColor("#72707D")
    )

    private val LIGHT_SPECS = mapOf(
        AppSettings.THEME_AMOLED to AccentSpec(AppSettings.THEME_AMOLED, "Pearl Minimal", "#0F172A", Color.WHITE, "#0F172A", "#F1F5F9", "#CBD5E1"),
        AppSettings.THEME_STEALTH to AccentSpec(AppSettings.THEME_STEALTH, "Stealth Platinum", "#334155", Color.WHITE, "#334155", "#F1F5F9", "#CBD5E1"),
        AppSettings.THEME_AMBER to AccentSpec(AppSettings.THEME_AMBER, "Neon Amber", "#D97706", Color.WHITE, "#B45309", "#FEF3C7", "#FDE68A"),
        AppSettings.THEME_MINT to AccentSpec(AppSettings.THEME_MINT, "Hyper Mint", "#059669", Color.WHITE, "#047857", "#ECFDF5", "#A7F3D0"),
        AppSettings.THEME_BLUE to AccentSpec(AppSettings.THEME_BLUE, "Liquid Blue", "#0284C7", Color.WHITE, "#0369A1", "#F0F9FF", "#BAE6FD"),
        AppSettings.THEME_WINE_RED to AccentSpec(AppSettings.THEME_WINE_RED, "Wine Red", "#9E1B32", Color.WHITE, "#9E1B32", "#FDF2F4", "#F8D7DA")
    )

    private val DARK_SPECS = mapOf(
        AppSettings.THEME_AMOLED to AccentSpec(AppSettings.THEME_AMOLED, "AMOLED Black", "#FFFFFF", Color.BLACK, "#FFFFFF", "#141418", "#383844"),
        AppSettings.THEME_STEALTH to AccentSpec(AppSettings.THEME_STEALTH, "Stealth Mono", "#475569", Color.WHITE, "#94A3B8", "#1E293B", "#334155"),
        AppSettings.THEME_AMBER to AccentSpec(AppSettings.THEME_AMBER, "Neon Amber", "#D97706", Color.WHITE, "#F59E0B", "#2E1E08", "#5C3D10"),
        AppSettings.THEME_MINT to AccentSpec(AppSettings.THEME_MINT, "Hyper Mint", "#059669", Color.WHITE, "#10B981", "#0D2E21", "#134E35"),
        AppSettings.THEME_BLUE to AccentSpec(AppSettings.THEME_BLUE, "Liquid Blue", "#0284C7", Color.WHITE, "#38BDF8", "#0C253B", "#144566"),
        AppSettings.THEME_WINE_RED to AccentSpec(AppSettings.THEME_WINE_RED, "Wine Red", "#9E1B32", Color.WHITE, "#C92A45", "#2C1218", "#541B26")
    )

    fun getThemeDefinition(
        themeKey: String,
        isLight: Boolean,
        customRed: Int = 158,
        customGreen: Int = 27,
        customBlue: Int = 50
    ): CockpitTheme {
        val surface = when {
            isLight -> LIGHT_SURFACE
            themeKey == AppSettings.THEME_AMOLED -> AMOLED_DARK_SURFACE
            else -> DARK_SURFACE
        }

        if (themeKey == AppSettings.THEME_CUSTOM) {
            return buildCustomTheme(surface, isLight, customRed, customGreen, customBlue)
        }

        val spec = (if (isLight) LIGHT_SPECS else DARK_SPECS)[themeKey]
            ?: (if (isLight) LIGHT_SPECS else DARK_SPECS).getValue(AppSettings.THEME_WINE_RED)

        return CockpitTheme(
            id = spec.id,
            name = spec.name,
            displayName = spec.name,
            isLight = isLight,
            backgroundColor = surface.backgroundColor,
            surfaceColor = surface.surfaceColor,
            cardColor = surface.cardColor,
            cardBorderColor = surface.cardBorderColor,
            textPrimaryColor = surface.textPrimaryColor,
            textSecondaryColor = surface.textSecondaryColor,
            textTertiaryColor = surface.textTertiaryColor,
            primaryColor = Color.parseColor(spec.primaryHex),
            onPrimaryColor = spec.onPrimary,
            accentColor = Color.parseColor(spec.accentHex),
            subtleColor = Color.parseColor(spec.subtleHex),
            borderColor = Color.parseColor(spec.borderHex)
        )
    }

    internal fun relativeLuminanceRgb(r: Int, g: Int, b: Int): Double {
        fun linearize(channel: Int): Double {
            val c = channel.coerceIn(0, 255) / 255.0
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linearize(r) + 0.7152 * linearize(g) + 0.0722 * linearize(b)
    }

    internal fun contrastRatioRgb(
        fgR: Int,
        fgG: Int,
        fgB: Int,
        bgR: Int,
        bgG: Int,
        bgB: Int
    ): Double {
        val lum1 = relativeLuminanceRgb(fgR, fgG, fgB)
        val lum2 = relativeLuminanceRgb(bgR, bgG, bgB)
        val hi = maxOf(lum1, lum2)
        val lo = minOf(lum1, lum2)
        return (hi + 0.05) / (lo + 0.05)
    }

    /**
     * Preserves the user's chosen hue while nudging luminance just enough to meet WCAG AA contrast
     * (4.5:1 by default) against the active card surface so custom RGB themes never render
     * illegible light-on-light or dark-on-dark accent text/icons.
     */
    internal fun adjustCustomAccentRgb(
        r: Int,
        g: Int,
        b: Int,
        surfaceR: Int,
        surfaceG: Int,
        surfaceB: Int,
        isLight: Boolean,
        minContrast: Double = 4.5
    ): Triple<Int, Int, Int> {
        val cr = r.coerceIn(0, 255)
        val cg = g.coerceIn(0, 255)
        val cb = b.coerceIn(0, 255)
        if (contrastRatioRgb(cr, cg, cb, surfaceR, surfaceG, surfaceB) >= minContrast) {
            return Triple(cr, cg, cb)
        }

        val target = if (isLight) 0 else 255
        var low = 0.0
        var high = 1.0
        for (i in 0 until 18) {
            val mid = (low + high) / 2.0
            val mr = (cr + (target - cr) * mid).toInt().coerceIn(0, 255)
            val mg = (cg + (target - cg) * mid).toInt().coerceIn(0, 255)
            val mb = (cb + (target - cb) * mid).toInt().coerceIn(0, 255)
            if (contrastRatioRgb(mr, mg, mb, surfaceR, surfaceG, surfaceB) >= minContrast) {
                high = mid
            } else {
                low = mid
            }
        }
        val fr = (cr + (target - cr) * high).toInt().coerceIn(0, 255)
        val fg = (cg + (target - cg) * high).toInt().coerceIn(0, 255)
        val fb = (cb + (target - cb) * high).toInt().coerceIn(0, 255)
        return Triple(fr, fg, fb)
    }

    internal fun shouldUseDarkForegroundOnPrimary(r: Int, g: Int, b: Int): Boolean {
        val contrastBlack = contrastRatioRgb(0, 0, 0, r, g, b)
        val contrastWhite = contrastRatioRgb(255, 255, 255, r, g, b)
        return contrastBlack > contrastWhite
    }

    private fun buildCustomTheme(
        surface: SurfacePalette,
        isLight: Boolean,
        r: Int,
        g: Int,
        b: Int
    ): CockpitTheme {
        val cr = r.coerceIn(0, 255)
        val cg = g.coerceIn(0, 255)
        val cb = b.coerceIn(0, 255)
        val customColor = Color.rgb(cr, cg, cb)
        val onPrimary = if (shouldUseDarkForegroundOnPrimary(cr, cg, cb)) Color.BLACK else Color.WHITE
        val surfaceR = (surface.cardColor shr 16) and 0xFF
        val surfaceG = (surface.cardColor shr 8) and 0xFF
        val surfaceB = surface.cardColor and 0xFF
        val (ar, ag, ab) = adjustCustomAccentRgb(cr, cg, cb, surfaceR, surfaceG, surfaceB, isLight)
        val accentColor = Color.rgb(ar, ag, ab)
        val subtle = if (isLight) {
            Color.rgb(
                (255 * 0.90 + cr * 0.10).toInt().coerceIn(0, 255),
                (255 * 0.90 + cg * 0.10).toInt().coerceIn(0, 255),
                (255 * 0.90 + cb * 0.10).toInt().coerceIn(0, 255)
            )
        } else {
            Color.rgb(
                (19 * 0.80 + cr * 0.20).toInt().coerceIn(0, 255),
                (18 * 0.80 + cg * 0.20).toInt().coerceIn(0, 255),
                (21 * 0.80 + cb * 0.20).toInt().coerceIn(0, 255)
            )
        }
        val border = if (isLight) {
            Color.rgb(
                (255 * 0.75 + cr * 0.25).toInt().coerceIn(0, 255),
                (255 * 0.75 + cg * 0.25).toInt().coerceIn(0, 255),
                (255 * 0.75 + cb * 0.25).toInt().coerceIn(0, 255)
            )
        } else {
            Color.rgb(
                (19 * 0.60 + cr * 0.40).toInt().coerceIn(0, 255),
                (18 * 0.60 + cg * 0.40).toInt().coerceIn(0, 255),
                (21 * 0.60 + cb * 0.40).toInt().coerceIn(0, 255)
            )
        }

        return CockpitTheme(
            id = AppSettings.THEME_CUSTOM,
            name = "Custom RGB",
            displayName = "Custom RGB",
            isLight = isLight,
            backgroundColor = surface.backgroundColor,
            surfaceColor = surface.surfaceColor,
            cardColor = surface.cardColor,
            cardBorderColor = surface.cardBorderColor,
            textPrimaryColor = surface.textPrimaryColor,
            textSecondaryColor = surface.textSecondaryColor,
            textTertiaryColor = surface.textTertiaryColor,
            primaryColor = customColor,
            onPrimaryColor = onPrimary,
            accentColor = accentColor,
            subtleColor = subtle,
            borderColor = border
        )
    }
}
