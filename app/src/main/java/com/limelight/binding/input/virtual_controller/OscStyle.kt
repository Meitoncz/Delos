package com.limelight.binding.input.virtual_controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.limelight.R
import com.limelight.shell.data.ProfileStore
import com.limelight.shell.settings.AppearanceStore
import com.limelight.shell.ui.delosColors
import com.limelight.shell.ui.systemAccentHue
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min

/**
 * The look of the on-screen controls in the Delos style: thin light outlines over a faint dark glass,
 * labels in Figtree, and the profile's main color filling whatever is pressed (with its contrasting color
 * for the label). [opacity] is the element's opacity (the on-screen controls opacity setting).
 */
object OscStyle {
    private var accent = 0xFFF5B83D.toInt()
    private var onAccent = 0xFF1A1405.toInt()
    private var typeface: Typeface = Typeface.DEFAULT_BOLD

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val path = Path()
    private val vertical = Path()
    private val horizontal = Path()
    private val cross = Path()
    private val pressed = Path()

    private const val GLASS = 0x0B1220
    private const val GLASS_ALPHA = .34f
    private const val LINE_ALPHA = .72f

    /** Takes the current profile's main color and the font; call when the controls are (re)created. */
    @JvmStatic
    fun refresh(context: Context) {
        runCatching {
            val appearance = AppearanceStore(context) { ProfileStore.currentSettings(context) }.appearance.value
            val colors = delosColors(appearance.copy(dark = true), systemAccentHue(context))
            accent = colors.accent.toArgb()
            onAccent = colors.onAccent.toArgb()
        }
        runCatching {
            val figtree = ResourcesCompat.getFont(context, R.font.figtree)
            typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(figtree, 600, false) else Typeface.create(figtree, Typeface.BOLD)
        }
    }

    private fun alpha(color: Int, a: Float) = ColorUtils.setAlphaComponent(color, (a.coerceIn(0f, 1f) * 255).toInt())

    private fun stroke(density: Float) = 1f * density

    /** Whatever is pressed shows clearly, however transparent the controls are set. */
    private fun pressedAlpha(opacity: Float) = max(opacity, .92f)

    /**
     * A button: a circle when it is about square (face buttons, L3 / R3), else a pill (shoulders, triggers,
     * Back / Start), or a rounded square ([square], the keyboard's square keys). Pressed: filled with the
     * main color.
     */
    @JvmStatic
    @JvmOverloads
    fun drawButton(canvas: Canvas, width: Float, height: Float, label: String?, pressed: Boolean, opacity: Float, density: Float, square: Boolean = false) {
        val s = stroke(density)
        rect.set(s, s, width - s, height - s)
        val round = abs(width - height) < max(width, height) * .15f && !square
        val radius = when {
            square -> min(rect.width(), rect.height()) * .24f
            round -> min(rect.width(), rect.height()) / 2f
            else -> rect.height() / 2f
        }
        if (pressed) {
            // A soft halo of the main color around the pressed button
            line.strokeWidth = s * 3f
            line.color = alpha(accent, .28f * opacity)
            canvas.drawRoundRect(rect, radius, radius, line)
        }
        fill.color = if (pressed) alpha(accent, pressedAlpha(opacity)) else alpha(GLASS or 0xFF000000.toInt(), GLASS_ALPHA * opacity)
        canvas.drawRoundRect(rect, radius, radius, fill)
        line.strokeWidth = s
        line.color = if (pressed) alpha(accent, pressedAlpha(opacity)) else alpha(Color.WHITE, LINE_ALPHA * opacity)
        canvas.drawRoundRect(rect, radius, radius, line)
        if (!label.isNullOrEmpty()) {
            text.typeface = typeface
            text.color = if (pressed) alpha(onAccent, pressedAlpha(opacity)) else alpha(Color.WHITE, .92f * opacity)
            // Letters about a third of a circle; longer labels smaller, fitted to the pill's width
            text.textSize = if (round || label.length <= 2) min(rect.width(), rect.height()) * .32f else rect.height() * .26f
            val fit = rect.width() * .64f
            val measured = text.measureText(label)
            if (measured > fit) text.textSize *= fit / measured
            val baseline = rect.centerY() - (text.descent() + text.ascent()) / 2f
            canvas.drawText(label, rect.centerX(), baseline, text)
        }
    }

    /** The D-pad: a cross of four arms, the pressed ones (one or two) filled with the main color. */
    @JvmStatic
    fun drawDpad(canvas: Canvas, width: Float, height: Float, up: Boolean, down: Boolean, left: Boolean, right: Boolean, opacity: Float, density: Float) {
        val s = stroke(density)
        val size = min(width, height) - s * 2f
        val cx = width / 2f
        val cy = height / 2f
        val arm = size * .34f
        val half = size / 2f
        val r = arm * .28f
        // The cross outline: the union of two rounded bars, so no lines cross the middle
        vertical.reset()
        vertical.addRoundRect(RectF(cx - arm / 2f, cy - half, cx + arm / 2f, cy + half), r, r, Path.Direction.CW)
        horizontal.reset()
        horizontal.addRoundRect(RectF(cx - half, cy - arm / 2f, cx + half, cy + arm / 2f), r, r, Path.Direction.CW)
        cross.reset()
        cross.op(vertical, horizontal, Path.Op.UNION)
        fill.color = alpha(GLASS or 0xFF000000.toInt(), GLASS_ALPHA * opacity)
        canvas.drawPath(cross, fill)
        // Pressed arms, as one shape (a diagonal presses two)
        pressed.reset()
        fun arm(l: Float, t: Float, rr: Float, b: Float) {
            vertical.reset()
            vertical.addRoundRect(RectF(l, t, rr, b), r, r, Path.Direction.CW)
            pressed.op(vertical, Path.Op.UNION)
        }
        if (up) arm(cx - arm / 2f, cy - half, cx + arm / 2f, cy - arm / 2f + r)
        if (down) arm(cx - arm / 2f, cy + arm / 2f - r, cx + arm / 2f, cy + half)
        if (left) arm(cx - half, cy - arm / 2f, cx - arm / 2f + r, cy + arm / 2f)
        if (right) arm(cx + arm / 2f - r, cy - arm / 2f, cx + half, cy + arm / 2f)
        fill.color = alpha(accent, pressedAlpha(opacity))
        canvas.drawPath(pressed, fill)
        line.strokeWidth = s
        line.color = alpha(Color.WHITE, LINE_ALPHA * opacity)
        canvas.drawPath(cross, line)
        // A small chevron in each arm
        val c = arm * .2f
        val d = half - arm * .42f
        line.strokeWidth = s * 1.1f
        fun chevron(x: Float, y: Float, dx: Float, dy: Float, on: Boolean) {
            line.color = if (on) alpha(onAccent, pressedAlpha(opacity)) else alpha(Color.WHITE, .8f * opacity)
            path.reset()
            path.moveTo(x - dy * c - dx * c * .6f, y - dx * c - dy * c * .6f)
            path.lineTo(x + dx * c * .4f, y + dy * c * .4f)
            path.lineTo(x + dy * c - dx * c * .6f, y + dx * c - dy * c * .6f)
            canvas.drawPath(path, line)
        }
        chevron(cx, cy - d, 0f, -1f, up)
        chevron(cx, cy + d, 0f, 1f, down)
        chevron(cx - d, cy, -1f, 0f, left)
        chevron(cx + d, cy, 1f, 0f, right)
    }

    /**
     * An analog stick: a thin ring for its reach, a faint ring for the dead zone and the knob at
     * ([knobX], [knobY]); the knob takes the main color while the stick is moved ([active]), the ring while
     * it is clicked ([clicked], L3 / R3).
     */
    @JvmStatic
    fun drawStick(
        canvas: Canvas, cx: Float, cy: Float, outer: Float, deadZone: Float, knobX: Float, knobY: Float, knob: Float,
        active: Boolean, clicked: Boolean, opacity: Float, density: Float,
    ) {
        val s = stroke(density)
        fill.color = alpha(GLASS or 0xFF000000.toInt(), GLASS_ALPHA * .8f * opacity)
        canvas.drawCircle(cx, cy, outer - s, fill)
        line.strokeWidth = s
        line.color = if (clicked) alpha(accent, pressedAlpha(opacity)) else alpha(Color.WHITE, LINE_ALPHA * opacity)
        canvas.drawCircle(cx, cy, outer - s, line)
        if (deadZone > s * 2f) {
            line.color = alpha(Color.WHITE, .22f * opacity)
            canvas.drawCircle(cx, cy, deadZone, line)
        }
        if (active) {
            line.strokeWidth = s * 3f
            line.color = alpha(accent, .28f * opacity)
            canvas.drawCircle(knobX, knobY, knob, line)
        }
        fill.color = if (active) alpha(accent, pressedAlpha(opacity)) else alpha(GLASS or 0xFF000000.toInt(), .5f * opacity)
        canvas.drawCircle(knobX, knobY, knob, fill)
        line.strokeWidth = s
        line.color = if (active) alpha(accent, pressedAlpha(opacity)) else alpha(Color.WHITE, .85f * opacity)
        canvas.drawCircle(knobX, knobY, knob, line)
    }

    private fun abs(v: Float) = kotlin.math.abs(v)
}
