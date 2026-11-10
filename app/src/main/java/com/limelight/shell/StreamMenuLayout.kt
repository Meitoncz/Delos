package com.limelight.shell

import android.content.res.Resources
import com.limelight.R
import com.limelight.preferences.UpscalerMode
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

    /** The More slider row for the live gyro smoothing strength (shown where the Settings row is). */
    const val GYRO = "Gyro smoothing"

    /** The help key of [GYRO] while no gyroscope source is on: it says why the row does nothing. */
    const val GYRO_DISABLED = "Gyro smoothing (disabled)"
}

/**
 * The names the stream menu's tiles and rows are known by: the focus order, the help lookup ([StreamMenuHelp]) and the tests
 * use them. They are identifiers, not text: the words on screen come from the string resources.
 */
internal object MenuKey {
    const val RESUME = "Resume"
    const val KEYBOARD = "Keyboard"
    const val OVERLAY = "Overlay"
    const val BRIGHTNESS = "Brightness"
    const val BITRATE = "Bitrate"
    const val UPSCALER = "Upscaler"
    const val UPSCALING = "Upscaling"
    const val SUPERSAMPLING = "Supersampling"
    const val SUPERSAMPLING_ON = "Supersampling on"
    const val MORE = "More"
    const val DISCONNECT = "Disconnect"
    const val QUIT_GAME = "Quit game"
    const val SCREEN_BRIGHTNESS = "Screen brightness"
    const val TOUCHSCREEN_MODE = "Touchscreen mode"
    const val APPLY_BITRATE = "Apply bitrate"
    const val APPLY = "Apply"
    const val ENABLED = "Enabled"
    const val MODE = "Mode"
    const val SUPERSAMPLING_QUALITY = "Supersampling quality"
    const val SHARPNESS = "Sharpness"
    const val LOCAL_MOUSE_CURSOR = "Local mouse cursor"
    const val SEND_CLIPBOARD = "Send clipboard"
    const val GET_CLIPBOARD = "Get clipboard"
    const val TASK_MANAGER = "Task manager"
    const val ZOOM = "Zoom"
    const val ONSCREEN_CONTROLS = "On-screen controls"
    const val TOUCH_SENSITIVITY = "Touch sensitivity"
}

/** The More page's Gyro smoothing row: shown where the motion settings are, usable while a gyroscope source is on. */
internal fun gyroSmoothingShown(sdk: Int, hasDeviceSensors: Boolean): Boolean =
    com.limelight.shell.settings.SettingsCapabilities.motionHiddenKeys(sdk, hasDeviceSensors).let { "seekbar_gyro_smoothing" !in it }

/** The help key of the Gyro smoothing row. */
internal fun gyroSmoothingHelpKey(enabled: Boolean): String = if (enabled) MoreRow.GYRO else MoreRow.GYRO_DISABLED

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
    const val POPOVER_PADDING_Y = 30f
    const val POPOVER_PADDING_BOTTOM = 24f
    const val POPOVER_HEAD_HEIGHT = 64f
    // The card's height limit (POPOVER_MAX_HEIGHT) is derived below from the margin.
    const val POPOVER_HEAD_GAP = 20f
    const val POPOVER_GAP_AFTER_HEAD = 12f
    const val POPOVER_HELP_GAP = 18f
    const val POPOVER_TITLE_SIZE = 30f
    const val CARET_WIDTH = 60f
    const val CARET_HEIGHT = 24f

    /** The popover's box ends here above the bottom of the stage: card bottom 734, caret tip about 13 above the sunk tile's ring. */
    const val POPOVER_BOTTOM = 322f

    /** The caret keeps this far from the card's corners. */
    const val CARET_MIN_INSET = 72f
    const val POPOVER_HIDDEN_SCALE = .86f

    /**
     * The compact statistics line at the top center (`StreamStatsPanel`): its top gap, its vertical padding, its text size
     * (grows with the interface scale) and its line height. The popover must stay below its bottom edge at every scale.
     */
    const val STATS_COMPACT_TOP = 20f
    const val STATS_COMPACT_PADDING_Y = 8f
    const val STATS_COMPACT_TEXT_SIZE = 18f
    const val STATS_COMPACT_LINE_HEIGHT = 1.4f

    /** The least air the tests demand between the compact statistics line (at the largest text scale) and a full-height popover. */
    const val STATS_GAP = 11f

    /**
     * The ONE margin of the popover: the card keeps it from the stage's left, right and top edges. 120 is the gap
     * the rightmost popover (More, centered on its tile: its right edge is at 1800) already had to the stage's right
     * edge; the height limit gives a too-tall card the same gap to the top. It clears the compact statistics line
     * (bottom edge about 68.8 at the largest text scale) by about 51.
     */
    const val POPOVER_MARGIN = 120f
    const val POPOVER_MIN_LEFT = POPOVER_MARGIN
    const val POPOVER_MAX_RIGHT = STAGE_WIDTH - POPOVER_MARGIN

    /** The card's bottom edge (the caret excluded) and the tallest card: from there up to the [POPOVER_MARGIN] below the stage's top. */
    const val POPOVER_CARD_BOTTOM = STAGE_HEIGHT - POPOVER_BOTTOM - CARET_HEIGHT
    const val POPOVER_MAX_HEIGHT = POPOVER_CARD_BOTTOM - POPOVER_MARGIN

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

/** The card's height with [rows] rows at [textScale] when nothing scrolls, caret excluded; capped at [StreamMenuLayout.POPOVER_MAX_HEIGHT], the one height every page shares as its limit. */
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

/** The bottom edge of the compact statistics line at [textScale], stage pixels from the stage's top. */
internal fun compactStatsBottom(textScale: Float): Float {
    val c = StreamMenuLayout
    return c.STATS_COMPACT_TOP + 2 * c.STATS_COMPACT_PADDING_Y + c.STATS_COMPACT_TEXT_SIZE * textScale * c.STATS_COMPACT_LINE_HEIGHT
}

/** The gap between the top of a card at its height limit and the compact statistics line at [textScale]. */
internal fun popoverStatsGap(textScale: Float): Float =
    StreamMenuLayout.POPOVER_CARD_BOTTOM - StreamMenuLayout.POPOVER_MAX_HEIGHT - compactStatsBottom(textScale)

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
internal fun pageTitle(res: Resources, page: MenuPage, supersampling: Boolean): String = when (page) {
    MenuPage.MAIN -> ""
    MenuPage.MORE -> res.getString(R.string.stream_menu_title_more)
    MenuPage.KEYS -> res.getString(R.string.stream_menu_title_keys)
    MenuPage.COMMANDS -> res.getString(R.string.stream_menu_title_commands)
    MenuPage.BRIGHTNESS -> res.getString(R.string.stream_menu_title_brightness)
    MenuPage.BITRATE -> res.getString(R.string.stream_menu_title_bitrate)
    MenuPage.UPSCALER -> res.getString(if (supersampling) R.string.stream_menu_title_supersampling else R.string.stream_menu_title_upscaler)
    MenuPage.MOUSE -> res.getString(R.string.stream_menu_title_mouse)
    MenuPage.RUMBLE -> res.getString(R.string.stream_menu_title_rumble)
}

/** The help key of the tile (or the page's first row) that shows before anything inside a page is focused. */
internal fun pageHelpKey(page: MenuPage): String = when (page) {
    MenuPage.MAIN -> MenuKey.RESUME
    MenuPage.MORE -> MoreRow.KEYS
    MenuPage.KEYS -> MoreRow.KEYS
    MenuPage.COMMANDS -> MoreRow.COMMANDS
    MenuPage.BRIGHTNESS -> MenuKey.SCREEN_BRIGHTNESS
    MenuPage.BITRATE -> MenuKey.BITRATE
    MenuPage.UPSCALER -> MenuKey.UPSCALING
    MenuPage.MOUSE -> MenuKey.TOUCHSCREEN_MODE
    MenuPage.RUMBLE -> MoreRow.RUMBLE
}

/** The help key of a tile: the upscaler's tile is called Supersampling when the stream is supersampled. */
internal fun circleHelpKey(item: BarItem, supersampling: Boolean): String = when (item) {
    BarItem.RESUME -> MenuKey.RESUME
    BarItem.KEYBOARD -> MenuKey.KEYBOARD
    BarItem.OVERLAY -> MenuKey.OVERLAY
    BarItem.BRIGHTNESS -> MenuKey.BRIGHTNESS
    BarItem.BITRATE -> MenuKey.BITRATE
    BarItem.UPSCALER -> if (supersampling) MenuKey.SUPERSAMPLING else MenuKey.UPSCALER
    BarItem.MORE -> MenuKey.MORE
    BarItem.DISCONNECT -> MenuKey.DISCONNECT
    BarItem.QUIT -> MenuKey.QUIT_GAME
}

/** The mode the Mode row shows after a press in [direction] (-1 or +1): the list wraps around. */
internal fun nextUpscalerMode(mode: Int, direction: Int): Int =
    Math.floorMod(mode + direction, UpscalerMode.values().size)

/**
 * Whether the Upscaler page offers the supersampling quality row: only while the stream could be supersampled and
 * the mode scales at all. Sharpen only keeps the stream's size, so the quality means nothing for it.
 */
internal fun showsSupersamplingQuality(supersamplingMode: Boolean, mode: Int): Boolean =
    supersamplingMode && UpscalerMode.fromIndex(mode).scales()

/**
 * What the help of [key] says. The Mouse mode row follows the active mode, the Mode row the selected method and
 * the upscaler's toggles start with the renderer's status.
 */
internal fun streamMenuHelp(
    res: Resources,
    key: String,
    upscalerStatus: String,
    upscalerMode: Int,
    mouseMode: Int,
    rememberMouseMode: Boolean,
    bitrateFailure: String? = null,
): String = when (key) {
    // A failed Apply explains itself where the help is
    MenuKey.APPLY_BITRATE -> bitrateFailure ?: StreamMenuHelp.description(res, key)
    MenuKey.TOUCHSCREEN_MODE -> SettingsHelp.choice(res, "mouse_mode_list", mouseMode.toString()) +
        "\n\n" + res.getString(if (rememberMouseMode) R.string.stream_menu_mouse_applies_saved else R.string.stream_menu_mouse_applies_stream)
    MenuKey.MODE -> SettingsHelp.choice(res, "list_sgsr_quality", UpscalerMode.fromIndex(upscalerMode).value()) +
        "\n\n" + StreamMenuHelp.description(res, key)
    MenuKey.UPSCALER, MenuKey.UPSCALING, MenuKey.SUPERSAMPLING, MenuKey.SUPERSAMPLING_ON -> "$upscalerStatus\n\n${StreamMenuHelp.description(res, key)}"
    else -> StreamMenuHelp.description(res, key)
}

/** The Bitrate page's recommendation row (PyroWave): its focus key and help key; its label is the changing recommendation line. */
internal const val RECOMMENDED_ROW = "Recommended bitrate"

/** What the Apply row of the Bitrate page says on its right: null before anything was applied. */
internal fun bitrateStatusText(res: Resources, busy: Boolean, result: BitrateResult?): String? = when {
    busy -> res.getString(R.string.stream_menu_bitrate_applying)
    result is BitrateResult.Accepted -> res.getString(R.string.stream_menu_bitrate_accepted, result.kbps / 1000)
    result is BitrateResult.Limited -> res.getString(R.string.stream_menu_bitrate_limited, result.kbps / 1000)
    result is BitrateResult.Failed -> res.getString(R.string.stream_menu_bitrate_failed)
    else -> null
}

/** The reasons a bitrate change can fail, as the help says them (all short enough for it). */
internal class BitrateFailure private constructor(private val message: Int, private val detail: String? = null) {
    /** The sentence the help shows, in the language of [res]. */
    fun text(res: Resources): String = if (detail == null) res.getString(message) else res.getString(message, detail)

    companion object {
        val NOT_CONNECTED = BitrateFailure(R.string.stream_menu_bitrate_not_connected)
        val UNSUPPORTED = BitrateFailure(R.string.stream_menu_bitrate_unsupported)
        val DENIED = BitrateFailure(R.string.stream_menu_bitrate_denied)
        val UNCONFIRMED = BitrateFailure(R.string.stream_menu_bitrate_unconfirmed)

        /** The host answered with an error of its own; its [detail] is cut so the sentence still fits. */
        fun rejected(detail: String?): BitrateFailure {
            val clean = detail?.trim().orEmpty().replace('\n', ' ')
            if (clean.isEmpty()) return BitrateFailure(R.string.stream_menu_bitrate_rejected)
            return BitrateFailure(R.string.stream_menu_bitrate_rejected_detail, if (clean.length > 60) clean.take(59) + "\u2026" else clean)
        }
    }
}

/**
 * The few words that fit in the pill of the upscaler's tile, from the renderer's status ("On: SGSR2-DV (Normal)" -> "On",
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
            else -> "$line \u00B7 $next" // l10n-ignore
        }
    }
}
