package com.limelight.shell.companion

import kotlin.math.ceil

/*
 * What the game card and the settings help of the companion show for one panel (docs/DUAL_SCREEN.md, "No scrolling, ever"): pure functions on top of
 * CompanionFit's budgets, so what is kept, cut and counted is tested in CompanionGamePageTest and CompanionHelpTest with a fake TextMetrics. The pages
 * only draw what these decide; FitColumn is the net under them.
 */

/** The title and the small line under it of a settings help ([HelpFit.heading]). */
internal data class HelpHeadingText(val title: String, val subtitle: String?)

/** The source block's text after the fit: the bold [title], then the sentence around its bold [strong] part. */
internal data class SourceView(val title: String, val before: String, val strong: String, val after: String)

/** The debug details that fit: the lines to draw and how many were left out. */
internal data class DetailsView(val text: String, val hidden: Int)

internal object HelpFit {
    /** What the heading takes of the card's inner width besides the text: the 64 px disc and the gap after it, in fit px. */
    const val HEADING_CHROME = 100f

    /** What the source block takes of the width besides the text: its padding, the icon and the gap after it. */
    const val SOURCE_CHROME = 90f

    /** The width of one monospace character as a share of the font size. */
    private const val MONO_EM = .62f

    /**
     * The lines the description gets: the full budget, less where another block takes the room (the prototype's `helpPanel`): three on a tall panel
     * and one on a short one beside debug details or swatches, three and two beside the background preview, four and two beside a text value.
     */
    fun bodyLines(help: CompanionHelp, budget: FitBudget): Int {
        val limit = when {
            help.details != null || help.swatches.isNotEmpty() -> if (budget.roomy) 3 else 1
            help.backdrop != null -> if (budget.roomy) 3 else 2
            help.value != null -> if (budget.roomy) 4 else 2
            else -> budget.helpLines
        }
        return limit.coerceAtMost(budget.helpLines)
    }

    /**
     * The heading: the row's title when it fits one line in the display face, else the place it lives in becomes the title and the full title drops to
     * the small line (the body face is narrower), so nothing is cut with an ellipsis.
     */
    fun heading(title: String, section: String?, width: Float, metrics: TextMetrics): HelpHeadingText {
        val room = width - HEADING_CHROME
        if (metrics.lines(title, room, FitText.TITLE) <= 1) return HelpHeadingText(title, section)
        val place = section?.takeIf { it.isNotBlank() && metrics.lines(it, room, FitText.TITLE) <= 1 } ?: return HelpHeadingText(title, section)
        return HelpHeadingText(place, title.takeIf { metrics.lines(it, room) <= 1 })
    }

    /**
     * The source sentence cut to [maxLines] at a sentence boundary behind its bold title; the title itself is always kept (the page never loses where a
     * per-game value comes from).
     */
    fun source(source: HelpSource, maxLines: Int, width: Float, metrics: TextMetrics): SourceView {
        val room = width - SOURCE_CHROME
        val lead = source.before + source.strong
        val body = lead + source.after
        fun view(text: String): SourceView = when {
            text == body -> SourceView(source.title, source.before, source.strong, source.after)
            source.strong.isNotEmpty() && text.startsWith(lead) -> SourceView(source.title, source.before, source.strong, text.removePrefix(lead))
            else -> SourceView(source.title, text, "", "")
        }
        if (metrics.lines("${source.title} $body", room) <= maxLines) return view(body)
        var kept = ""
        for (sentence in CompanionFit.sentences(body)) {
            val candidate = if (kept.isEmpty()) sentence else "$kept $sentence"
            if (metrics.lines("${source.title} $candidate", room) <= maxLines) kept = candidate else break
        }
        return view(kept)
    }

    /**
     * A text row's current text (a name, a path) in [maxLines]: whole when it fits, else its END behind a leading ellipsis mark (the end is what tells
     * two paths or names apart). The only place an ellipsis stands for a cut, and for data, never for prose.
     */
    fun value(value: String, maxLines: Int, width: Float, metrics: TextMetrics): String {
        if (metrics.lines(value, width) <= maxLines) return value
        var low = 0
        var high = value.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (metrics.lines(ELLIPSIS + value.takeLast(mid), width) <= maxLines) low = mid else high = mid - 1
        }
        return ELLIPSIS + value.takeLast(low)
    }

    /** How many monospace characters a row of [width] holds at the companion's body size. */
    fun charsPerRow(width: Float): Int = (width / (MIN_TEXT * MONO_EM)).toInt().coerceAtLeast(8)

    /**
     * The debug details in [maxRows] rows of [charsPerRow] characters (a long line wraps and takes more than one): whole lines only, and when some are
     * left out the last row is the count ([DetailsView.hidden]).
     */
    fun details(text: String, maxRows: Int, charsPerRow: Int): DetailsView {
        val lines = text.lines().filter { it.isNotBlank() }
        fun rows(line: String) = ceil(line.length / charsPerRow.toFloat()).toInt().coerceAtLeast(1)
        if (lines.sumOf { rows(it) } <= maxRows) return DetailsView(lines.joinToString("\n"), 0)
        val shown = ArrayList<String>()
        var used = 0
        for (line in lines) {
            if (used + rows(line) > maxRows - 1) break
            shown += line
            used += rows(line)
        }
        return DetailsView(shown.joinToString("\n"), lines.size - shown.size)
    }

    /**
     * A description ready to be cut at sentence boundaries: every line (the catalog puts the value's name on a line of its own, "Balanced" before its
     * explanation) that ends in a letter or a digit gets a full stop, so [CompanionFit.sentences] sees it as one and the cut text reads "Balanced.
     * Smooths ...". A line that ends in any mark keeps it.
     */
    fun prepare(body: String): String = body.lines().joinToString("\n") { line ->
        val text = line.trimEnd()
        val last = text.lastOrNull()
        when {
            last == null || !last.isLetterOrDigit() -> text
            Character.UnicodeScript.of(last.code) in CJK_SCRIPTS -> text + "\u3002"
            else -> "$text."
        }
    }

    private val CJK_SCRIPTS = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)

    /**
     * Whether every swatch of [labels] fits one row of [width]: the dot, the name and the padding of each chip, and the gaps. Else the page stacks them.
     */
    fun swatchesInRow(labels: List<String>, width: Float, metrics: TextMetrics, chrome: Float = SWATCH_CHROME, gap: Float = 8f): Boolean =
        labels.sumOf { (metrics.width(it) + chrome).toDouble() }.toFloat() + gap * (labels.size - 1).coerceAtLeast(0) <= width

    /** The dot, its gap and the padding of one swatch chip, in fit px. */
    private const val SWATCH_CHROME = 70f
    private const val ELLIPSIS = "\u2026"
}

/** What the game card says in text for one panel: the genre chips (with the count of the rest) and the one muted line under them. */
internal data class GameText(val genres: List<String>, val body: String?)

internal object GameFit {
    /**
     * The chips and the muted line of a game card: the first genres of [genres] that fit one row and "+N genres" for the rest ([more]); the line under
     * them is the summary on a tall panel (cut to whole sentences in two lines) and the folded facts ([facts]: "HLTB 19 h · Native HDR") on a short one.
     * Either falls back to the other when it has nothing.
     */
    fun text(genres: List<String>, summary: String?, facts: String, budget: FitBudget, metrics: TextMetrics, more: (Int) -> String): GameText {
        val chips = CompanionFit.chips(genres, budget.genres, budget.innerWidth, metrics, more)
        val prose = summary?.takeIf { it.isNotBlank() }?.let { CompanionFit.summary(it, "", PROSE_LINES, budget.innerWidth, metrics, "") }.orEmpty()
        val line = if (budget.roomy) prose.ifEmpty { facts } else facts.ifEmpty { prose }
        return GameText(chips, line.ifBlank { null })
    }

    /** The lines the summary may take. */
    const val PROSE_LINES = 2

    /** The width of one character of the display face as a share of the font size (Unbounded is wide), and its line height. */
    private const val EM = .78f
    private const val LINE = 1.15f

    /**
     * The size, in fit px, of the game's name on its banner: the larger the shorter the name, never more than two lines in [width] and never taller
     * than the banner's [height], never below the companion's minimum text. [width] and [height] are the banner's inside, in fit px.
     */
    fun nameSize(name: String, width: Float, height: Float): Int {
        var size = when {
            name.length > 26 -> 36
            name.length > 16 -> 46
            else -> 58
        }
        while (size > MIN_TEXT) {
            val lines = ceil(name.length * size * EM / width.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
            if (lines <= 2 && lines * size * LINE <= height) break
            size -= 2
        }
        return size.coerceAtLeast(MIN_TEXT)
    }
}
