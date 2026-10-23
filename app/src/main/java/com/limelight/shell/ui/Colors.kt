package com.limelight.shell.ui

import android.content.Context
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.limelight.shell.settings.Appearance

/**
 * Color tokens of the new UI. Same formulas as applyTheme() in the prototype: everything is derived
 * from three hues, so Material You only has to supply one seed hue.
 */
@Immutable
data class DelosColors(
    val dark: Boolean,
    val materialYou: Boolean,
    val baseHue: Float,
    val accentHue: Float,
    val accent2Hue: Float,
    val bg: Color,
    val bg2: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val line: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val accent: Color,
    val accent2: Color,
    val onAccent: Color,
    val glow: Color,
    val scrim: Color,
)

/** CSS-style hsl(): hue in degrees (any value), saturation and lightness in percent. */
fun hsl(h: Float, s: Float, l: Float, a: Float = 1f): Color =
    Color.hsl(((h % 360f) + 360f) % 360f, (s / 100f).coerceIn(0f, 1f), (l / 100f).coerceIn(0f, 1f), a.coerceIn(0f, 1f))

/** Shortest-path hue interpolation. */
fun lerpHue(a: Float, b: Float, k: Float): Float {
    val d = ((b - a + 540f) % 360f) - 180f
    return (a + d * k + 360f) % 360f
}

/** Hue of the system (wallpaper) accent on Android 12+, null when there is no Material You palette. */
fun systemAccentHue(context: Context): Float? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(context.getColor(android.R.color.system_accent1_500), hsv)
    return hsv[0]
}

/** Material You on a device without a wallpaper palette (Android 11 and older): Lagoon's hue. */
private const val FALLBACK_SYSTEM_HUE = 176f

fun delosColors(appearance: Appearance, systemHue: Float?): DelosColors {
    val my = appearance.materialYou
    val palette = appearance.palette
    var base = palette.base
    var acc = palette.accent
    var acc2 = palette.accent2
    if (my) {
        // Material You: only the system's (wallpaper) colors
        val h = systemHue ?: FALLBACK_SYSTEM_HUE
        base = h; acc = h; acc2 = (h + 52f) % 360f
    }
    return if (appearance.dark) {
        val sat = if (my) 30f else 38f
        DelosColors(
            dark = true, materialYou = my, baseHue = base, accentHue = acc, accent2Hue = acc2,
            bg = hsl(base, sat, 7f), bg2 = hsl(base, sat - 4f, 11f),
            surface = hsl(base, sat - 10f, 14f, .72f), surface2 = hsl(base, sat - 12f, 19f, .84f), surface3 = hsl(base, sat - 14f, 23f, .94f),
            line = hsl(base, 35f, 70f, .14f), text = hsl(base, 30f, 96f), text2 = hsl(base, 16f, 76f), text3 = hsl(base, 12f, 58f),
            accent = if (my) hsl(acc, 78f, 72f) else hsl(acc, 95f, 62f),
            accent2 = if (my) hsl(acc2, 70f, 66f) else hsl(acc2, 85f, 62f),
            onAccent = hsl(acc, 55f, 12f),
            glow = if (my) hsl(acc, 80f, 60f, .42f) else hsl(acc, 95f, 58f, .45f),
            scrim = hsl(base, 50f, 3f, .62f),
        )
    } else {
        val sat = if (my) 45f else 40f
        DelosColors(
            dark = false, materialYou = my, baseHue = base, accentHue = acc, accent2Hue = acc2,
            bg = hsl(base, sat, 95f), bg2 = hsl(base, sat - 6f, 90f),
            surface = hsl(base, 60f, 99f, .74f), surface2 = hsl(base, 50f, 99f, .9f), surface3 = hsl(base, 40f, 98f, .97f),
            line = hsl(base, 30f, 30f, .13f), text = hsl(base, 40f, 12f), text2 = hsl(base, 18f, 32f), text3 = hsl(base, 12f, 46f),
            accent = if (my) hsl(acc, 58f, 40f) else hsl(acc - 6f, 92f, 52f),
            accent2 = if (my) hsl(acc2, 55f, 40f) else hsl(acc2, 80f, 40f),
            onAccent = if (my) Color.White else hsl(acc, 60f, 12f),
            glow = hsl(acc, 85f, 50f, .34f),
            scrim = hsl(base, 30f, 20f, .35f),
        )
    }
}

val LocalDelosColors = staticCompositionLocalOf<DelosColors> { error("DelosColors not provided") }

/** Cross-fades every token when the theme changes (the prototype's 0.6 s color transition). */
@Composable
fun animateDelosColors(target: DelosColors): DelosColors {
    @Composable
    fun anim(c: Color) = animateColorAsState(c, tween(600), label = "theme").value
    return target.copy(
        bg = anim(target.bg), bg2 = anim(target.bg2), surface = anim(target.surface), surface2 = anim(target.surface2),
        surface3 = anim(target.surface3), line = anim(target.line), text = anim(target.text), text2 = anim(target.text2),
        text3 = anim(target.text3), accent = anim(target.accent), accent2 = anim(target.accent2),
        onAccent = anim(target.onAccent), glow = anim(target.glow), scrim = anim(target.scrim),
    )
}
