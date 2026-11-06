package com.limelight.shell

import com.limelight.shell.settings.SettingsHelp
import com.limelight.shell.settings.StreamMenuHelp
import kotlin.math.floor

/**
 * The decisions of the stream menu that need no Compose: which tile of the dock a page belongs to, where the
 * focus goes when a popover closes, where the tiles, the ring and the popover with its caret sit, and how much
 * room the help texts have. Sizes are stage pixels (the 1920 x 1080 stage of docs/GUI.md); the numbers are the
 * CSS variables of prototype B (docs/prototype/delos-gui.html, `#quickmenu`), one for one.
 */

/** The tiles of the dock, in the order they appear. */
internal enum class BarItem { RESUME, KEYBOARD, OVERLAY, BRIGHTNESS, BITRATE, UPSCALER, MORE, DISCONNECT, QUIT }

/**
 * What the popover above the dock shows; [MAIN] is no popover (the dock alone).
 * [RUMBLE] is the live rumble feel selection, opened from the More page.
 */
internal enum class MenuPage { MAIN, MORE, KEYS, COMMANDS, BRIGHTNESS, BITRATE, UPSCALER, MOUSE, RUMBLE }

/** The page B goes back to: the dock for the pages of a tile, More for the pages that More opens. */
internal fun MenuPage.parent(): MenuPage? = when (this) {
    MenuPage.MAIN -> null
    MenuPage.KEYS, MenuPage.COMMANDS, MenuPage.MOUSE, MenuPage.RUMBLE -> MenuPage.MORE
    else -> MenuPage.MAIN
}

/** The tile that opens this page, directly or through More. */
internal fun MenuPage.barItem(): BarItem? = when (this) {
    MenuPage.MAIN -> null
    MenuPage.MORE, MenuPage.KEYS, MenuPage.COMMANDS, MenuPage.MOUSE, MenuPage.RUMBLE -> BarItem.MORE
    MenuPage.BRIGHTNESS -> BarItem.BRIGHTNESS
    MenuPage.BITRATE -> BarItem.BITRATE
    MenuPage.UPSCALER -> BarItem.UPSCALER
}

/** The labels of the rows of More that open a page of their own (the focus returns to them). */
internal object MoreRow {
    const val KEYS = "Send keys"
    const val COMMANDS = "Commands"
    const val MOUSE = "Mouse mode"

    /** The More row that opens Android's system keyboard (the Keyboard tile is the Delos keyboard). */
    const val ANDROID_KEYBOARD = "Open Android keyboard"

    /** The More row that opens the live rumble feel page. */
    const val RUMBLE = "Rumble feel"
}

/** The row of the parent page that opened this page (by its label), or null when the dock's tile did. */
internal fun MenuPage.openedByRow(): String? = when (this) {
    MenuPage.KEYS -> MoreRow.KEYS
    MenuPage.COMMANDS -> MoreRow.COMMANDS
    MenuPage.MOUSE -> MoreRow.MOUSE
    MenuPage.RUMBLE -> MoreRow.RUMBLE
    else -> null
}

/** Where the menu goes after a page closes: the [page] to show and what gets the focus there. */
internal data class PageExit(val page: MenuPage, val focusCircle: BarItem?, val focusRow: String?)

/**
 * B (or the back chevron) on [page]: one level up, to the row that opened it or to its tile on the dock.
 * Null on the dock itself: there B resumes the stream.
 */
internal fun pageAfterBack(page: MenuPage): PageExit? {
    val parent = page.parent() ?: return null
    return if (parent == MenuPage.MAIN) PageExit(parent, page.barItem(), null) else PageExit(parent, null, page.openedByRow())
}

/** A tap outside the popover: the whole popover closes and its tile gets the focus. Null when none is open. */
internal fun pageAfterDismiss(page: MenuPage): PageExit? =
    if (page == MenuPage.MAIN) null else PageExit(MenuPage.MAIN, page.barItem(), null)

internal object StreamMenuLayout {
    /** The stage every number below is measured on. */
    const val STAGE_WIDTH = 1920f
    const val STAGE_HEIGHT = 1080f

    /** The largest interface scale of Settings -> Appearance: text grows with it, its boxes do not. */
    const val MAX_TEXT_SCALE = 1.3f

    /** The share of its font size that an average character of Figtree is wide (the model of the help fit tests). */
    const val AVERAGE_CHAR_WIDTH = .5f

    // The tiles: 9 x 150 + 8 x 40 = 1670, 125 left and right
    const val TILE = 150f
    const val TILE_RADIUS = 42f
    const val TILE_GAP = 40f
    const val TILE_ICON = 64f
    const val TILES_WIDTH = 9 * TILE + 8 * TILE_GAP
    const val TILES_LEFT = (STAGE_WIDTH - TILES_WIDTH) / 2f
    const val FOCUS_SCALE = 1.12f
    const val ICON_FOCUS_SCALE = 1.04f
    const val PRESS_SCALE = .97f

    /** The gold ring around the focused tile: a 186 box (the tile at 1.12 is 168, plus 9 on each side). */
    const val RING = 186f
    const val RING_RADIUS = 56f
    const val RING_WIDTH = 4f

    // Labels, pills and the caption
    const val LABEL_SIZE = 24f
    const val LABEL_LINE_HEIGHT = 1.2f
    const val LABEL_GAP = 24f
    const val LABEL_FOCUS_SHIFT = 8f
    const val PILL_HEIGHT = 34f
    const val PILL_SIZE = 18f
    const val PILL_PADDING_X = 15f
    const val PILL_RIGHT = -12f
    const val PILL_TOP = -14f
    const val CAPTION_SIZE = 28f
    const val CAPTION_LINE_HEIGHT = 1.4f
    const val CAPTION_WIDTH = 1100f
    const val CAPTION_GAP = 26f

    /** The caption's box is this many lines tall at the current text scale: it never grows, nothing scrolls. */
    const val CAPTION_LINES = 3

    // The dock: the title block above the tiles, the tiles, the caption; it sinks while a popover is open
    const val DOCK_TOP = 628f
    const val DOCK_SHIFT = 160f
    const val HEAD_BOTTOM = 46f
    const val HEAD_GAP = 22f
    const val HINTS_GAP = 12f
    const val TITLE_SIZE = 36f
    const val TITLE_LINE_HEIGHT = 1.15f
    const val HINT_SIZE = 24f
    const val HINT_GLYPH_SIZE = 44

    /** A long game name stops here (one line, ellipsis) instead of running over the HUD. */
    const val TITLE_MAX_WIDTH = 900f

    // The popover: a frosted card with a caret on its bottom edge, pointing at the tile that opened it
    const val POPOVER_RADIUS = 44f
    const val POPOVER_PADDING_X = 40f
    const val POPOVER_PADDING_Y = 36f
    const val POPOVER_PADDING_BOTTOM = 28f
    const val POPOVER_MAX_HEIGHT = 686f
    const val POPOVER_HEAD_HEIGHT = 64f
    const val POPOVER_HEAD_GAP = 20f
    const val POPOVER_GAP_AFTER_HEAD = 16f
    const val POPOVER_HELP_GAP = 22f
    const val POPOVER_TITLE_SIZE = 30f
    const val CARET_WIDTH = 60f
    const val CARET_HEIGHT = 24f

    /** The popover's box ends here above the bottom of the stage: card bottom 734, caret tip about 13 above the sunk tile's ring. */
    const val POPOVER_BOTTOM = 322f

    /** The card keeps this far from the stage's sides, and its caret this far from the card's corners. */
    const val POPOVER_MIN_LEFT = 110f
    const val POPOVER_MAX_RIGHT = 1810f
    const val CARET_MIN_INSET = 72f
    const val POPOVER_HIDDEN_SCALE = .86f

    // Rows of a page
    const val ROW_HEIGHT = 72f
    const val ROW_RADIUS = 28f
    const val ROW_GAP = 8f
    const val ROW_SIZE = 24f
    const val ROW_ICON = 32f
    const val ROW_ICON_COLUMN = 56f
    const val ROW_COLUMN_GAP = 14f
    const val ROW_PADDING_START = 22f
    const val ROW_PADDING_END = 28f
    const val ROW_RING = 3f
    const val CHOICE_VALUE_MIN_WIDTH = 170f
    const val NUMBER_MIN_WIDTH = 104f
    const val SWITCH_WIDTH = 78f
    const val SWITCH_HEIGHT = 44f
    const val SLIDER_WIDTH = 240f
    const val SLIDER_HEIGHT = 12f
    const val KNOB = 32f
    const val HELP_SIZE = 22f
    const val HELP_LINE_HEIGHT = 1.4f

    /** The soft tint under the tiles and the extra one under an open popover. */
    const val SCRIM_POPOVER_ALPHA = .22f

    /**
     * The stream is another window (a `SurfaceView`): the menu cannot blur it, as the prototype's frosted glass does.
     * These dark undercoats stand in for the missing blur so the glass stays readable on a bright picture; the
     * prototype's own tint and white gradients are drawn over them unchanged.
     */
    const val TILE_BACKING_ALPHA = .28f
    const val CARD_BACKING_ALPHA = .30f
}

/** The left edge of [item]'s tile, stage pixels. */
internal fun tileLeft(item: BarItem): Float = StreamMenuLayout.TILES_LEFT + item.ordinal * (StreamMenuLayout.TILE + StreamMenuLayout.TILE_GAP)

/** The horizontal center of [item]'s tile. */
internal fun tileCenterX(item: BarItem): Float = tileLeft(item) + StreamMenuLayout.TILE / 2f

/** The left edge of the ring's box around [item]'s tile. */
internal fun ringLeft(item: BarItem): Float = tileCenterX(item) - StreamMenuLayout.RING / 2f

/** The ring's top edge relative to the tiles' top edge (negative: it reaches above the tile). */
internal const val RING_TOP = (StreamMenuLayout.TILE - StreamMenuLayout.RING) / 2f

/** The popover's width: wide enough for its rows. More and the pages it opens share one width. */
internal fun popoverWidth(page: MenuPage): Float = when (page) {
    MenuPage.MAIN -> 0f
    MenuPage.BRIGHTNESS -> 780f
    MenuPage.BITRATE -> 860f
    MenuPage.UPSCALER -> 980f
    MenuPage.MORE, MenuPage.KEYS, MenuPage.COMMANDS, MenuPage.MOUSE, MenuPage.RUMBLE -> 920f
}

/** The card's left edge: centered on [anchorX] (the center of the tile that opened it), [StreamMenuLayout.POPOVER_MIN_LEFT] to [StreamMenuLayout.POPOVER_MAX_RIGHT]. */
internal fun popoverLeft(anchorX: Float, width: Float): Float =
    (anchorX - width / 2f).coerceIn(StreamMenuLayout.POPOVER_MIN_LEFT, (StreamMenuLayout.POPOVER_MAX_RIGHT - width).coerceAtLeast(StreamMenuLayout.POPOVER_MIN_LEFT))

/** The caret's center from the card's left edge: the anchor kept [StreamMenuLayout.CARET_MIN_INSET] from both corners. */
internal fun caretCenterX(anchorX: Float, left: Float, width: Float): Float =
    (anchorX - left).coerceIn(StreamMenuLayout.CARET_MIN_INSET, (width - StreamMenuLayout.CARET_MIN_INSET).coerceAtLeast(StreamMenuLayout.CARET_MIN_INSET))

/** Where the popover of a page sits, stage pixels: the card's left edge, its width, and the caret's center from the left edge. */
internal data class PopoverPlacement(val left: Float, val width: Float, val caretX: Float)

internal fun popoverPlacement(page: MenuPage): PopoverPlacement? {
    val item = page.barItem() ?: return null
    val anchor = tileCenterX(item)
    val width = popoverWidth(page)
    val left = popoverLeft(anchor, width)
    return PopoverPlacement(left, width, caretCenterX(anchor, left, width))
}

/** The lines the popover's help text reserves: 2 at the normal text scale, 3 from 1.15 up. */
internal fun helpLines(textScale: Float): Int = if (textScale > 1.15f) 3 else 2

/** The height of the popover's help box at [textScale]. */
internal fun popoverHelpHeight(textScale: Float): Float =
    StreamMenuLayout.HELP_SIZE * textScale * StreamMenuLayout.HELP_LINE_HEIGHT * helpLines(textScale)

/** The height of [rows] rows with the gaps between them. */
internal fun rowsHeight(rows: Int): Float =
    if (rows <= 0) 0f else rows * StreamMenuLayout.ROW_HEIGHT + (rows - 1) * StreamMenuLayout.ROW_GAP

/** The card's height with [rows] rows at [textScale] when nothing scrolls, caret excluded; capped at [StreamMenuLayout.POPOVER_MAX_HEIGHT]. */
internal fun popoverCardHeight(rows: Int, textScale: Float): Float {
    val c = StreamMenuLayout
    val natural = c.POPOVER_PADDING_Y + c.POPOVER_HEAD_HEIGHT + c.POPOVER_GAP_AFTER_HEAD + rowsHeight(rows) + c.POPOVER_HELP_GAP + popoverHelpHeight(textScale) + c.POPOVER_PADDING_BOTTOM
    return minOf(natural, c.POPOVER_MAX_HEIGHT)
}

/** True when a page of [rows] rows needs no scrolling at [textScale]. */
internal fun popoverFits(rows: Int, textScale: Float): Boolean {
    val c = StreamMenuLayout
    return c.POPOVER_PADDING_Y + c.POPOVER_HEAD_HEIGHT + c.POPOVER_GAP_AFTER_HEAD + rowsHeight(rows) + c.POPOVER_HELP_GAP + popoverHelpHeight(textScale) + c.POPOVER_PADDING_BOTTOM <= c.POPOVER_MAX_HEIGHT
}

/** The height of the caption's box at [textScale]. */
internal fun captionHeight(textScale: Float): Float =
    StreamMenuLayout.CAPTION_SIZE * textScale * StreamMenuLayout.CAPTION_LINE_HEIGHT * StreamMenuLayout.CAPTION_LINES

/** The caption's top edge below the tiles' top edge at [textScale] (below the label, its gap and 8). */
internal fun captionTop(textScale: Float): Float {
    val c = StreamMenuLayout
    return c.TILE + c.LABEL_GAP + c.LABEL_SIZE * textScale * c.LABEL_LINE_HEIGHT + c.CAPTION_GAP + 8f
}

/** How many characters of [textSize] (stage pixels) fit a line of [width] stage pixels at the largest text scale. */
internal fun helpLineChars(width: Float, textSize: Float, textScale: Float = StreamMenuLayout.MAX_TEXT_SCALE): Int =
    floor(width / (textSize * textScale * StreamMenuLayout.AVERAGE_CHAR_WIDTH)).toInt()

/** The characters of a caption line at [textScale]. */
internal fun captionLineChars(textScale: Float = StreamMenuLayout.MAX_TEXT_SCALE): Int =
    helpLineChars(StreamMenuLayout.CAPTION_WIDTH, StreamMenuLayout.CAPTION_SIZE, textScale)

/** The width of the help text in the popover of [page]. */
internal fun popoverHelpWidth(page: MenuPage): Float = popoverWidth(page) - 2 * StreamMenuLayout.POPOVER_PADDING_X

/** The characters of a help line in the popover of [page] at [textScale]. */
internal fun popoverHelpLineChars(page: MenuPage, textScale: Float = StreamMenuLayout.MAX_TEXT_SCALE): Int =
    helpLineChars(popoverHelpWidth(page), StreamMenuLayout.HELP_SIZE, textScale)

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
    MenuPage.UPSCALER -> if (supersampling) "Supersampling" else "Upscaler"
    MenuPage.MOUSE -> "Mouse and touch"
    MenuPage.RUMBLE -> "Rumble feel"
}

/** The help key of the tile (or the page's first row) that shows before anything inside a page is focused. */
internal fun pageHelpKey(page: MenuPage): String = when (page) {
    MenuPage.MAIN -> "Resume"
    MenuPage.MORE -> "Send keys"
    MenuPage.KEYS -> "Send keys"
    MenuPage.COMMANDS -> "Commands"
    MenuPage.BRIGHTNESS -> "Screen brightness"
    MenuPage.BITRATE -> "Bitrate"
    MenuPage.UPSCALER -> "Upscaling"
    MenuPage.MOUSE -> "Touchscreen mode"
    MenuPage.RUMBLE -> "Rumble feel"
}

/** The help key of a tile: the upscaler's tile is called Supersampling when the stream is supersampled. */
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
internal fun streamMenuHelp(
    key: String,
    upscalerStatus: String,
    upscalerMode: Int,
    mouseMode: Int,
    rememberMouseMode: Boolean,
    bitrateFailure: String? = null,
): String = when (key) {
    // A failed Apply explains itself where the help is
    "Apply bitrate" -> bitrateFailure ?: StreamMenuHelp.description(key)
    "Touchscreen mode" -> SettingsHelp.choice("mouse_mode_list", mouseMode.toString()) +
        "\n\n" + if (rememberMouseMode) "Applies now and is saved." else "Applies now; this stream only."
    "Mode" -> SettingsHelp.choice("list_sgsr_quality", upscalerModeValues[upscalerMode.coerceIn(0, upscalerModeValues.lastIndex)]) +
        "\n\n" + StreamMenuHelp.description(key)
    "Upscaler", "Upscaling", "Supersampling", "Supersampling on" -> "$upscalerStatus\n\n${StreamMenuHelp.description(key)}"
    else -> StreamMenuHelp.description(key)
}

/** What the Apply row of the Bitrate page says on its right: null before anything was applied. */
internal fun bitrateStatusText(busy: Boolean, result: BitrateResult?): String? = when {
    busy -> "Applying\u2026"
    result is BitrateResult.Accepted -> "Accepted ${result.kbps / 1000} Mbps"
    result is BitrateResult.Limited -> "Limited by the host to ${result.kbps / 1000} Mbps"
    result is BitrateResult.Failed -> "Failed"
    else -> null
}

/** The reasons a bitrate change can fail, as the help says them (all short enough for it). */
internal object BitrateFailure {
    const val NOT_CONNECTED = "The stream is not connected, so the bitrate cannot be changed."
    const val UNSUPPORTED = "The host does not support live bitrate changes, or the stream has ended."
    const val DENIED = "The host did not allow this change."
    const val UNCONFIRMED = "The bitrate was not confirmed. Check the connection and try again."

    /** The host answered with an error of its own; its [detail] is cut so the sentence still fits. */
    fun rejected(detail: String?): String {
        val clean = detail?.trim().orEmpty().replace('\n', ' ')
        return "The host could not apply the change" + if (clean.isEmpty()) "." else ": " + (if (clean.length > 60) clean.take(59) + "\u2026" else clean)
    }
}

/**
 * The few words that fit in the pill of the upscaler's tile, from the renderer's status ("On: SGSR 2 Video" -> "On",
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
