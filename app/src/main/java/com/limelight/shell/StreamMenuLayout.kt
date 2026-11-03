package com.limelight.shell

/**
 * The decisions of the stream menu that need no Compose: which circle of the bar a page belongs to, where
 * the focus goes when a popover closes, how big the bar and the popover may be and what the single help
 * line says. Sizes are stage pixels (the 1920 x 1080 stage of docs/GUI.md).
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
    const val BAR_WIDTH = 1520f
    const val BAR_HEIGHT = 224f
    const val POPOVER_WIDTH = 700f
    const val POPOVER_MAX_HEIGHT = 440f
    const val POPOVER_GAP = 16f

    /** The soft gradient under the bar: its height and its alpha at the top, middle and bottom. */
    const val GRADIENT_HEIGHT = 320f
    const val GRADIENT_MID_ALPHA = .18f
    const val GRADIENT_BOTTOM_ALPHA = .55f
}

/** The bar: centered, at most [StreamMenuLayout.BAR_WIDTH] wide, [StreamMenuLayout.SAFE_MARGIN] from the sides and the bottom. */
internal fun barFrame(areaWidth: Float, areaHeight: Float): StageRect {
    val width = minOf(StreamMenuLayout.BAR_WIDTH, (areaWidth - 2 * StreamMenuLayout.SAFE_MARGIN).coerceAtLeast(0f))
    val left = (areaWidth - width) / 2f
    val bottom = areaHeight - StreamMenuLayout.SAFE_MARGIN
    return StageRect(left, bottom - StreamMenuLayout.BAR_HEIGHT, left + width, bottom)
}

/**
 * The popover for [contentHeight] of content: as wide as it may be, ending at the bar's right end (so the
 * picture's centre stays clear), just above the bar, never taller than its maximum and never closer to the
 * top than the safe margin. Taller content scrolls inside.
 */
internal fun popoverFrame(bar: StageRect, contentHeight: Float): StageRect {
    val width = minOf(StreamMenuLayout.POPOVER_WIDTH, bar.width)
    val bottom = bar.top - StreamMenuLayout.POPOVER_GAP
    val room = (bottom - StreamMenuLayout.SAFE_MARGIN).coerceAtLeast(0f)
    val height = contentHeight.coerceIn(0f, minOf(StreamMenuLayout.POPOVER_MAX_HEIGHT, room))
    return StageRect(bar.right - width, bottom - height, bar.right, bottom)
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

/** The help key shown before anything inside a page is focused. */
internal fun pageHelpKey(page: MenuPage): String = when (page) {
    MenuPage.MAIN -> "Resume"
    MenuPage.MORE -> "More"
    MenuPage.KEYS -> "Send keys"
    MenuPage.COMMANDS -> "Commands"
    MenuPage.BRIGHTNESS -> "Screen brightness"
    MenuPage.BITRATE -> "Bitrate"
    MenuPage.UPSCALER -> "Upscaler"
    MenuPage.MOUSE -> "Touchscreen mode"
}

/**
 * The few words that fit under the upscaler's circle, from the renderer's status ("On: SGSR 2 Video" -> "On",
 * "Unavailable; direct rendering is active" -> "Unavailable"). The full status stays in the help.
 */
internal fun upscalerCircleState(status: String): String =
    status.substringBefore(':').substringBefore(';').trim()

/** The help line's text: its paragraphs on one line, each joined to the next with a space or a middle dot. */
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
