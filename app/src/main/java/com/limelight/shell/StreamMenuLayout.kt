package com.limelight.shell

import com.limelight.shell.settings.SettingsHelp
import com.limelight.shell.settings.StreamMenuHelp
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The decisions of the stream menu that need no Compose: which circle of the bar a page belongs to, where
 * the focus goes when a popover closes, where the bar, its circles and the popover with its caret sit, and
 * how much room the help texts have. Sizes are stage pixels (the 1920 x 1080 stage of docs/GUI.md).
 */

/** The circles of the bottom bar, in the order they appear. */
internal enum class BarItem { RESUME, KEYBOARD, OVERLAY, BRIGHTNESS, BITRATE, UPSCALER, MORE, DISCONNECT, QUIT }

/** What the popover above the bar shows; [MAIN] is no popover (the bar alone). */
internal enum class MenuPage { MAIN, MORE, KEYS, COMMANDS, BRIGHTNESS, BITRATE, UPSCALER, MOUSE }

/** The page B goes back to: the bar for the pages of a circle, More for the pages that More opens. */
internal fun MenuPage.parent(): MenuPage? = when (this) {
    MenuPage.MAIN -> null
    MenuPage.KEYS, MenuPage.COMMANDS, MenuPage.MOUSE -> MenuPage.MORE
    else -> MenuPage.MAIN
}

/** The circle that opens this page, directly or through More. */
internal fun MenuPage.barItem(): BarItem? = when (this) {
    MenuPage.MAIN -> null
    MenuPage.MORE, MenuPage.KEYS, MenuPage.COMMANDS, MenuPage.MOUSE -> BarItem.MORE
    MenuPage.BRIGHTNESS -> BarItem.BRIGHTNESS
    MenuPage.BITRATE -> BarItem.BITRATE
    MenuPage.UPSCALER -> BarItem.UPSCALER
}

/** The labels of the rows of More that open a page of their own (the focus returns to them). */
internal object MoreRow {
    const val KEYS = "Send keys"
    const val COMMANDS = "Commands"
    const val MOUSE = "Mouse mode"
}

/** The row of the parent page that opened this page (by its label), or null when the bar's circle did. */
internal fun MenuPage.openedByRow(): String? = when (this) {
    MenuPage.KEYS -> MoreRow.KEYS
    MenuPage.COMMANDS -> MoreRow.COMMANDS
    MenuPage.MOUSE -> MoreRow.MOUSE
    else -> null
}

/** Where the menu goes after a page closes: the [page] to show and what gets the focus there. */
internal data class PageExit(val page: MenuPage, val focusCircle: BarItem?, val focusRow: String?)

/**
 * B (or the back chevron) on [page]: one level up, to the row that opened it or to its circle on the bar.
 * Null on the bar itself: there B resumes the stream.
 */
internal fun pageAfterBack(page: MenuPage): PageExit? {
    val parent = page.parent() ?: return null
    return if (parent == MenuPage.MAIN) PageExit(parent, page.barItem(), null) else PageExit(parent, null, page.openedByRow())
}

/** A tap outside the popover: the whole popover closes and its circle gets the focus. Null when none is open. */
internal fun pageAfterDismiss(page: MenuPage): PageExit? =
    if (page == MenuPage.MAIN) null else PageExit(MenuPage.MAIN, page.barItem(), null)

/** A rectangle in stage pixels. */
internal data class StageRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

internal object StreamMenuLayout {
    /** Nothing of the menu comes closer to a screen edge than this (TV overscan). */
    const val SAFE_MARGIN = 48f

    /** The largest interface scale of Settings -> Appearance: text grows with it, its boxes do not. */
    const val MAX_TEXT_SCALE = 1.3f

    /** The share of its font size that an average character of Figtree is wide (the model of the help fit tests). */
    const val AVERAGE_CHAR_WIDTH = .5f

    // The bar: the game's name and the hints on the left, one row of nine circles, the help area under them
    const val BAR_MAX_WIDTH = 1700f
    const val BAR_CORNER = 40f
    const val BAR_PADDING_X = 40f
    const val BAR_PADDING_TOP = 28f
    const val TITLE_WIDTH = 250f
    const val SEPARATOR_GAP = 28f
    const val CIRCLE_SIZE = 108f
    const val CIRCLE_LABEL_SIZE = 22f
    const val CIRCLE_STATE_SIZE = 18f
    const val CIRCLE_ROW_HEIGHT = 188f
    const val HINT_GLYPH_SIZE = 44

    /** The ring that glides between the circles: its gap and width around the lifted circle. */
    const val RING_GAP = 5f
    const val RING_WIDTH = 4f
    const val FOCUS_LIFT = 1.08f

    // The help area of the bar: HELP_LINES lines of HELP_TEXT_SIZE at the largest text scale, never scrolling
    const val HELP_LINES = 2
    const val HELP_TEXT_SIZE = 22f
    const val HELP_LINE_HEIGHT = 1.4f
    const val HELP_GAP = 14f
    const val HELP_PADDING_Y = 12f

    /** A little more than [HELP_LINES] lines at the largest text scale (rounding of line heights). */
    const val HELP_TEXT_HEIGHT = 86f
    const val BAR_HEIGHT = BAR_PADDING_TOP + CIRCLE_ROW_HEIGHT + HELP_GAP + 1f + HELP_PADDING_Y * 2 + HELP_TEXT_HEIGHT

    // The popover: a card with a caret on its bottom edge, pointing at the circle that opened it
    const val POPOVER_MIN_WIDTH = 700f
    const val POPOVER_CORNER = 32f
    const val POPOVER_PADDING_X = 36f
    const val POPOVER_PADDING_TOP = 20f
    const val POPOVER_HEADER_HEIGHT = 72f
    const val ROW_HEIGHT = 68f
    const val ROW_SPACING = 14f
    const val CARET_WIDTH = 28f
    const val CARET_HEIGHT = 14f

    /** The caret never comes closer to a corner of the card than this (center of the caret). */
    const val CARET_MIN_INSET = 40f

    /** The space between the caret's tip and the top of the bar. */
    const val POPOVER_GAP = 12f
    const val FOOTER_TEXT_SIZE = 20f
    const val FOOTER_LINE_HEIGHT = 1.35f

    /** The soft gradient under the bar: its height and its alpha at the top, middle and bottom. */
    const val GRADIENT_HEIGHT = 380f
    const val GRADIENT_MID_ALPHA = .17f
    const val GRADIENT_BOTTOM_ALPHA = .55f

    /** The translucent tint of every glass surface of the menu (see `menuGlass`). */
    const val GLASS_ALPHA = .88f
}

/** The bar: centered, at most [StreamMenuLayout.BAR_MAX_WIDTH] wide, [StreamMenuLayout.SAFE_MARGIN] from the sides and the bottom. */
internal fun barFrame(areaWidth: Float, areaHeight: Float): StageRect {
    val width = minOf(StreamMenuLayout.BAR_MAX_WIDTH, (areaWidth - 2 * StreamMenuLayout.SAFE_MARGIN).coerceAtLeast(0f))
    val left = (areaWidth - width) / 2f
    val bottom = areaHeight - StreamMenuLayout.SAFE_MARGIN
    return StageRect(left, bottom - StreamMenuLayout.BAR_HEIGHT, left + width, bottom)
}

/** Where the nine equal cells of the circles start and end (left edge of the first, right edge of the last), in stage pixels. */
internal fun circlesSpan(bar: StageRect): Pair<Float, Float> {
    val start = bar.left + StreamMenuLayout.BAR_PADDING_X + StreamMenuLayout.TITLE_WIDTH + 2 * StreamMenuLayout.SEPARATOR_GAP + 1f
    return start to (bar.right - StreamMenuLayout.BAR_PADDING_X)
}

/** The horizontal center of [item]'s circle: the middle of its equal cell. */
internal fun circleCenterX(bar: StageRect, item: BarItem): Float {
    val (start, end) = circlesSpan(bar)
    return start + (end - start) / BarItem.entries.size * (item.ordinal + .5f)
}

/** The vertical center of the circles, in stage pixels. */
internal fun circleCenterY(bar: StageRect): Float =
    bar.top + StreamMenuLayout.BAR_PADDING_TOP + StreamMenuLayout.CIRCLE_SIZE / 2f

/** The popover's width: wide enough for its rows, never narrower than 700 unless the bar is, never wider than the bar. */
internal fun popoverWidth(page: MenuPage, bar: StageRect): Float {
    val wanted = when (page) {
        MenuPage.MAIN -> 0f
        MenuPage.BRIGHTNESS -> 780f
        // The pages of More (and More itself) end at the bar's right end when centered on the More circle
        MenuPage.BITRATE, MenuPage.MORE, MenuPage.KEYS, MenuPage.COMMANDS, MenuPage.MOUSE -> 800f
        MenuPage.UPSCALER -> 860f
    }
    return minOf(wanted, bar.width)
}

/**
 * The card's left edge: centered on [anchorX] (the center of the circle that opened it) but kept inside the bar's
 * horizontal bounds and [StreamMenuLayout.SAFE_MARGIN] from the sides of the [areaWidth]-wide screen.
 */
internal fun popoverLeft(anchorX: Float, width: Float, bar: StageRect, areaWidth: Float): Float {
    val minLeft = maxOf(bar.left, StreamMenuLayout.SAFE_MARGIN)
    val maxLeft = minOf(bar.right, areaWidth - StreamMenuLayout.SAFE_MARGIN) - width
    return if (maxLeft < minLeft) minLeft else (anchorX - width / 2f).coerceIn(minLeft, maxLeft)
}

/** The caret's center: [anchorX] kept at least [StreamMenuLayout.CARET_MIN_INSET] from both corners of the card. */
internal fun caretCenterX(anchorX: Float, left: Float, width: Float): Float {
    val inset = minOf(StreamMenuLayout.CARET_MIN_INSET, width / 2f)
    return anchorX.coerceIn(left + inset, left + width - inset)
}

/** The tallest popover including its caret: what fits above the bar's gap, keeping the safe margin from the top. */
internal fun popoverMaxHeight(bar: StageRect): Float =
    (bar.top - StreamMenuLayout.POPOVER_GAP - StreamMenuLayout.SAFE_MARGIN).coerceAtLeast(0f)

/** Where the popover of [page] sits: its frame (the card and the caret below it) and the caret's center, in stage pixels. */
internal data class PopoverPlacement(val left: Float, val width: Float, val caretX: Float)

internal fun popoverPlacement(page: MenuPage, bar: StageRect, areaWidth: Float): PopoverPlacement? {
    val item = page.barItem() ?: return null
    val anchor = circleCenterX(bar, item)
    val width = popoverWidth(page, bar)
    val left = popoverLeft(anchor, width, bar, areaWidth)
    return PopoverPlacement(left, width, caretCenterX(anchor, left, width))
}

/** The footer of a popover: as many lines of help as the page has room for (the Upscaler page is the tallest). */
internal fun popoverFooterLines(page: MenuPage): Int = if (page == MenuPage.UPSCALER) 2 else 3

/** The height of the footer's text box for [lines] lines at the largest text scale, stage pixels. */
internal fun popoverFooterTextHeight(lines: Int): Float =
    ceil(lines * StreamMenuLayout.FOOTER_TEXT_SIZE * StreamMenuLayout.MAX_TEXT_SCALE * StreamMenuLayout.FOOTER_LINE_HEIGHT) + 4f

/** How many characters of [textSize] (stage pixels) fit a line of [width] stage pixels at the largest text scale. */
internal fun helpLineChars(width: Float, textSize: Float): Int =
    floor(width / (textSize * StreamMenuLayout.MAX_TEXT_SCALE * StreamMenuLayout.AVERAGE_CHAR_WIDTH)).toInt()

/** The width of the text box of the bar's help area, and the characters of a line in it. */
internal fun barHelpTextWidth(barWidth: Float): Float {
    val (start, end) = circlesSpan(StageRect(0f, 0f, barWidth, 0f))
    return end - start
}

internal fun barHelpLineChars(barWidth: Float = StreamMenuLayout.BAR_MAX_WIDTH): Int =
    helpLineChars(barHelpTextWidth(barWidth), StreamMenuLayout.HELP_TEXT_SIZE)

/** The width of the footer's text in the popover of [page], and the characters of a line in it. */
internal fun popoverFooterTextWidth(page: MenuPage, bar: StageRect): Float =
    popoverWidth(page, bar) - 2 * StreamMenuLayout.POPOVER_PADDING_X

internal fun popoverFooterLineChars(page: MenuPage, bar: StageRect): Int =
    helpLineChars(popoverFooterTextWidth(page, bar), StreamMenuLayout.FOOTER_TEXT_SIZE)

/** The lines [text] needs with greedy word wrapping at [lineChars] characters per line. */
internal fun wrappedLineCount(text: String, lineChars: Int): Int {
    var lines = 1
    var used = 0
    for (word in text.split(' ', '\n').filter { it.isNotEmpty() }) {
        if (used == 0) used = word.length
        else if (used + 1 + word.length <= lineChars) used += 1 + word.length
        else { lines++; used = word.length }
    }
    return lines
}

/** The heading of a popover. */
internal fun pageTitle(page: MenuPage, supersampling: Boolean): String = when (page) {
    MenuPage.MAIN -> ""
    MenuPage.MORE -> "More"
    MenuPage.KEYS -> "Send keys"
    MenuPage.COMMANDS -> "Server commands"
    MenuPage.BRIGHTNESS -> "Brightness"
    MenuPage.BITRATE -> "Stream bitrate"
    MenuPage.UPSCALER -> if (supersampling) "Supersampling" else "Snapdragon Game Super Resolution"
    MenuPage.MOUSE -> "Mouse and touch"
}

/** The help key of the circle (or the page's first row) that shows before anything inside a page is focused. */
internal fun pageHelpKey(page: MenuPage): String = when (page) {
    MenuPage.MAIN -> "Resume"
    MenuPage.MORE -> "More"
    MenuPage.KEYS -> "Send keys"
    MenuPage.COMMANDS -> "Commands"
    MenuPage.BRIGHTNESS -> "Screen brightness"
    MenuPage.BITRATE -> "Bitrate"
    MenuPage.UPSCALER -> "Upscaling"
    MenuPage.MOUSE -> "Touchscreen mode"
}

/** The help key of a circle: the upscaler's circle is called Supersampling when the stream is supersampled. */
internal fun circleHelpKey(item: BarItem, supersampling: Boolean): String = when (item) {
    BarItem.RESUME -> "Resume"
    BarItem.KEYBOARD -> "Keyboard"
    BarItem.OVERLAY -> "Overlay"
    BarItem.BRIGHTNESS -> "Brightness"
    BarItem.BITRATE -> "Bitrate"
    BarItem.UPSCALER -> if (supersampling) "Supersampling" else "Upscaler"
    BarItem.MORE -> "More"
    BarItem.DISCONNECT -> "Disconnect"
    BarItem.QUIT -> "Quit game"
}

private val upscalerModeValues = listOf("standard", "high", "temporal", "temporal-quality")

/**
 * What the help of [key] says. The Mouse mode row follows the active mode, the Mode row the selected method and
 * the upscaler's toggles start with the renderer's status.
 */
internal fun streamMenuHelp(key: String, upscalerStatus: String, upscalerMode: Int, mouseMode: Int, rememberMouseMode: Boolean): String = when (key) {
    "Touchscreen mode" -> SettingsHelp.choice("mouse_mode_list", mouseMode.toString()) +
        "\n\n" + if (rememberMouseMode) "Applies now and is saved." else "Applies now; this stream only."
    "Mode" -> SettingsHelp.choice("list_sgsr_quality", upscalerModeValues[upscalerMode.coerceIn(0, upscalerModeValues.lastIndex)]) +
        "\n\n" + StreamMenuHelp.description(key)
    "Upscaler", "Upscaling", "Supersampling", "Supersampling on" -> "$upscalerStatus\n\n${StreamMenuHelp.description(key)}"
    else -> StreamMenuHelp.description(key)
}

/**
 * The few words that fit under the upscaler's circle, from the renderer's status ("On: SGSR 2 Video" -> "On",
 * "Unavailable; direct rendering is active" -> "Unavailable"). The full status stays in the help.
 */
internal fun upscalerCircleState(status: String): String =
    status.substringBefore(':').substringBefore(';').trim()

/** The help's text: its paragraphs as one flowing text, each joined to the next with a space or a middle dot. */
internal fun helpLineText(text: String): String {
    val paragraphs = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    return paragraphs.fold("") { line, next ->
        when {
            line.isEmpty() -> next
            line.last() in ".!?" -> "$line $next"
            else -> "$line \u00B7 $next"
        }
    }
}
