package com.limelight.shell.companion

import com.limelight.shell.ui.StagePlanner

/**
 * What the page-fit tests share: text measured at a flat 15 px per character at the body size (a little wider than Figtree's average) and 21 px in the title
 * face, and the five panels the companion is designed for, as the page sees them (the stage divided by the interface scale, [DEFAULT_SCALE] by default).
 */
internal object FakeMetrics : TextMetrics {
    private fun char(kind: FitText) = if (kind == FitText.BODY) 15f else 21f

    override fun lines(text: String, width: Float, kind: FitText): Int {
        // Greedy word wrap; a word wider than a line counts as impossible (a huge number)
        var lines = 1
        var current = 0f
        for (word in text.split(' ')) {
            val w = word.length * char(kind)
            if (w > width) return 1000
            val next = if (current == 0f) w else current + char(kind) + w
            if (next > width) {
                lines++
                current = w
            } else current = next
        }
        return lines
    }

    override fun width(text: String, kind: FitText): Float = text.length * char(kind)
}

/** The default interface scale: text is this much bigger than the stage px it is sized in. */
internal const val DEFAULT_SCALE = 1.15f

/** The panels the user named (px at dpi) by name: the stage the planner makes of each. */
private val SHAPES = listOf(
    Triple("1240x1080@420", 1240 to 1080, 420),
    Triple("960x720@300", 960 to 720, 300),
    Triple("1024x768@320", 1024 to 768, 320),
    Triple("720x720@255", 720 to 720, 255),
    Triple("960x640@330", 960 to 640, 330),
)

internal fun panels(scale: Float = DEFAULT_SCALE): List<Pair<String, Panel>> = SHAPES.map { (name, size, dpi) ->
    val plan = StagePlanner.plan(size.first / (dpi / 160f), size.second / (dpi / 160f))
    name to Panel(plan.stageWidth / scale, plan.stageHeight / scale)
}
