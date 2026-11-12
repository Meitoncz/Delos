package com.limelight.shell.companion

/*
 * Rule 0 of the companion screen (docs/DUAL_SCREEN.md, "No scrolling, ever"): every state fits completely in its panel, at every panel size. The
 * content adapts instead of the window scrolling: blocks are ranked, long texts are cut to a line budget at a SENTENCE boundary (never in the
 * middle of a sentence, never with an ellipsis), lists show the first entries and a count, and visuals shrink in ratio. This file is the one
 * place those budgets live: pure, no Compose, tested in CompanionFitTest with the worst-case data on every panel shape. The measuring of text is
 * the only thing it asks the caller for ([TextMetrics]).
 */

/** The companion's stage in stage pixels (the window divided by `dpPerPx`, and by the interface scale: text grows with it). */
internal data class Panel(val width: Float, val height: Float) {
    /** A tall panel (1240 x 1080 and squarer): room for the small caps lines, the longer texts and more list entries. The 4:3 and 3:2 ones are short. */
    val roomy: Boolean get() = height >= ROOMY_HEIGHT

    companion object {
        /** A stage this high or higher is tall. */
        const val ROOMY_HEIGHT = 900f
    }
}

/** What a kind of text measures with: the body face at the companion's minimum size, or a heading in the display face. */
internal enum class FitText { BODY, TITLE }

/** How the page measures text: the number of lines [text] needs in [width] stage px, and how wide it is on one line. */
internal interface TextMetrics {
    fun lines(text: String, width: Float, kind: FitText = FitText.BODY): Int
    fun width(text: String, kind: FitText = FitText.BODY): Float
}

/** The line and entry budgets of one panel (the prototype's `budget()`), in the units each block shows. */
internal data class FitBudget(
    val roomy: Boolean,
    /** Plain explanatory text (a pairing step, an empty library, a result). */
    val bodyLines: Int,
    /** The description of a setting. */
    val helpLines: Int,
    /** The source block of a per-game setting. */
    val sourceLines: Int,
    val genres: Int,
    /** Controller hints in the fallback and in the pairing steps. */
    val hints: Int,
    /** Facts of a PC card. */
    val facts: Int,
    /** The error text of a problem, and the recovery sentence under it. */
    val errorLines: Int,
    val recoveryLines: Int,
    /** The width a line of text has inside a card, in stage px. */
    val innerWidth: Float,
)

internal object CompanionFit {
    /** Outer margin of the card at every side. */
    const val MARGIN = 48f

    /** Padding of the card; a narrow panel (720 wide and less) takes a little less. */
    fun cardPadding(panel: Panel): Float = if (panel.width <= NARROW_WIDTH) 20f else 24f

    /** The gap between neighbouring blocks of a card. */
    fun gap(panel: Panel): Float = if (panel.width <= NARROW_WIDTH) 10f else 12f

    const val NARROW_WIDTH = 800f

    /** The header strip (brand and PC) and the strip kept free at the bottom (room for the pixel shift of the burn-in policy). */
    const val HEADER = 64f
    const val FOOTER = 32f

    fun budget(panel: Panel): FitBudget {
        val roomy = panel.roomy
        return FitBudget(
            roomy = roomy,
            bodyLines = if (roomy) 5 else 3,
            helpLines = if (roomy) 6 else 3,
            sourceLines = 2,
            genres = if (roomy) 4 else 2,
            hints = if (roomy) 5 else 2,
            facts = if (roomy) 6 else 4,
            errorLines = if (roomy) 5 else 2,
            recoveryLines = 2,
            innerWidth = panel.width - 2 * MARGIN - 2 * cardPadding(panel),
        )
    }

    /** The room a card has after the header, the footer and the margins. */
    fun cardHeight(panel: Panel): Float = panel.height - HEADER - FOOTER - 2 * MARGIN

    /**
     * The sentences of [text], each with its closing mark (and a closing quote after it). Line breaks end a sentence too; a text without a
     * mark is one sentence.
     */
    fun sentences(text: String): List<String> {
        val out = ArrayList<String>()
        for (paragraph in text.split('\n')) {
            val p = paragraph.trim()
            if (p.isEmpty()) continue
            var start = 0
            var i = 0
            while (i < p.length) {
                if (p[i] in ENDINGS) {
                    var end = i + 1
                    while (end < p.length && p[end] in ENDINGS) end++
                    if (end < p.length && p[end] in CLOSERS) end++
                    // A mark ends the sentence only when a space (or the end) follows it: "1.5x" and "v2.0.1" stay whole
                    if (end >= p.length || p[end].isWhitespace() || p[end - 1] in CJK_ENDINGS) {
                        out += p.substring(start, end).trim()
                        start = end
                    }
                    i = end
                } else i++
            }
            if (start < p.length) out += p.substring(start).trim()
        }
        return out.filter { it.isNotEmpty() }
    }

    /**
     * The longest run of complete leading sentences of [full] that fits [maxLines] in [width]; else [short] when that fits; else [last] (an
     * authored "see the main screen" sentence). Never cuts a sentence.
     */
    fun summary(full: String, short: String, maxLines: Int, width: Float, metrics: TextMetrics, last: String): String {
        var result = ""
        for (sentence in sentences(full)) {
            val candidate = if (result.isEmpty()) sentence else "$result $sentence"
            if (metrics.lines(candidate, width) <= maxLines) result = candidate else break
        }
        if (result.isNotEmpty()) return result
        if (short.isNotBlank() && metrics.lines(short, width) <= maxLines) return short
        return last
    }

    /**
     * The labels of a row of chips: at most [maxItems] of [items], then "+N more" ([more], which gets the remaining count) when some are left,
     * and fewer still while the row is wider than [width] (each chip adds [chipPadding] and the row [gap]).
     */
    fun chips(items: List<String>, maxItems: Int, width: Float, metrics: TextMetrics, more: (Int) -> String, chipPadding: Float = 26f, gap: Float = 8f): List<String> {
        val selected = items.take(maxItems).toMutableList()
        fun labels() = selected + (if (items.size > selected.size) listOf(more(items.size - selected.size)) else emptyList())
        fun rowWidth(labels: List<String>) = labels.sumOf { (metrics.width(it) + chipPadding).toDouble() }.toFloat() + gap * (labels.size - 1).coerceAtLeast(0)
        while (selected.isNotEmpty() && rowWidth(labels()) > width) selected.removeAt(selected.size - 1)
        return labels()
    }

    /** The first [max] of [items] and how many are left out. */
    fun <T> top(items: List<T>, max: Int): Pair<List<T>, Int> = items.take(max) to (items.size - minOf(max, items.size))

    /** [name] when it fits one line of [width] as a title, else the [alias] (an authored short name such as "PC · 7C2A"). */
    fun title(name: String, alias: String, width: Float, metrics: TextMetrics): String =
        if (name.isNotBlank() && metrics.lines(name, width, FitText.TITLE) <= 1) name else alias

    /** A short name for a PC whose own name does not fit: the last four characters of its id. */
    fun alias(prefix: String, uuid: String): String = "$prefix \u00b7 ${uuid.filter { it.isLetterOrDigit() }.takeLast(4).uppercase()}"

    private val ENDINGS = charArrayOf('.', '!', '?', '\u2026', '\u3002', '\uff01', '\uff1f')
    private val CLOSERS = charArrayOf('"', '\'', '\u201d', '\u2019', ')', '\u300d')
}

private val CJK_ENDINGS = charArrayOf('\u3002', '\uff01', '\uff1f')
