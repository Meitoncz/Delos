package com.limelight.shell.ui

/** How much of a hint a compact hint bar shows: only its button, its short label, or its full label. Ordered by width. */
enum class HintDetail { BARE, SHORT, FULL }

/**
 * One hint as the fit sees it: how important it is ([rank], 0 most) and how wide it is at each detail, in pixels. [short] is null when the
 * hint has no shorter label (or the shorter one is not narrower). Hints with the same non-null [group] always get the same detail (the
 * two tab hints are either both worded or both buttons).
 */
class HintFitItem(val rank: Int, val bare: Int, val short: Int?, val full: Int, val group: Int? = null) {
    fun width(detail: HintDetail): Int = when (detail) {
        HintDetail.BARE -> bare
        HintDetail.SHORT -> short ?: full
        HintDetail.FULL -> full
    }
}

/** The result of [fitHintBar]: which note variant is drawn ([note] is an index into the offered widths, null for no note) and each hint's detail. */
class HintBarFit(val note: Int?, val details: List<HintDetail>)

/**
 * How important a hint is, judged by its button: the primary action A first, then Back (B), then the screen's own actions (X, Y, adjust),
 * then the tab hints (L1, R1), then the menu buttons. Lower is more important.
 */
fun hintRank(glyph: Glyph): Int = when (glyph) {
    Glyph.A -> 0
    Glyph.B -> 1
    Glyph.L1, Glyph.R1 -> 3
    Glyph.MENU_LEFT, Glyph.MENU_RIGHT -> 4
    else -> 2
}

/** The tab hints belong together, every other hint stands alone ([HintFitItem.group]). */
fun hintGroup(glyph: Glyph): Int? = if (glyph == Glyph.L1 || glyph == Glyph.R1) hintRank(glyph) else null

/**
 * Chooses how much of each hint fits in [available] pixels, [gap] apart. The most important hint gets the most text: in order of rank
 * (the list order breaks ties) every hint (or group of hints) takes the fullest label that still fits when all the hints after it are
 * only buttons. So the labels of the least important hints go first, a shorter label saves its hint before its label is dropped, and
 * the buttons alone are the last resort (also when even they do not fit, so the bar never grows past what the buttons need).
 */
fun fitHints(items: List<HintFitItem>, gap: Int, available: Int): List<HintDetail> {
    val details = MutableList(items.size) { HintDetail.BARE }
    fun total() = items.indices.sumOf { items[it].width(details[it]) } + gap * (items.size - 1).coerceAtLeast(0)
    val byImportance = items.indices.sortedWith(compareBy({ items[it].rank }, { it }))
    val done = mutableSetOf<Int>()
    for (i in byImportance) {
        if (i in done) continue
        val members = items.indices.filter { it == i || (items[i].group != null && items[it].group == items[i].group) }
        done += members
        for (detail in listOf(HintDetail.FULL, HintDetail.SHORT)) {
            if (detail == HintDetail.SHORT && members.all { items[it].short == null }) continue
            members.forEach { details[it] = detail }
            if (total() <= available) break
            members.forEach { details[it] = HintDetail.BARE }
        }
    }
    return details
}

/**
 * The whole bar: a note ([noteWidths], widest, fullest variant first) at the left of the hints. A note is kept only while every hint keeps
 * a label next to its button (the hints are the bar); otherwise the next variant is tried and finally the hints get the whole width.
 */
fun fitHintBar(noteWidths: List<Int>, noteGap: Int, items: List<HintFitItem>, hintGap: Int, available: Int): HintBarFit {
    for ((i, width) in noteWidths.withIndex()) {
        val room = available - width - if (items.isEmpty()) 0 else noteGap
        val details = fitHints(items, hintGap, room)
        if (room >= 0 && details.none { it == HintDetail.BARE }) return HintBarFit(i, details)
    }
    return HintBarFit(null, fitHints(items, hintGap, available))
}
