package com.limelight.shell.companion

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import com.limelight.shell.settings.SettingImpact

/*
 * What a settings screen hands to the companion window (docs/DUAL_SCREEN.md, "Settings help"): the description of the focused row, as
 * immutable data. The panel on the right of the main screen (or the dock under its list) is drawn from the same pieces; on a dual-screen
 * device the second panel shows them large instead and the main screen gives the room to the list.
 */

/**
 * The description of the row focused on a settings screen (Settings, a game's settings, Debug info). [identity] names the row (a new one
 * crossfades, an update of the same row, like a changed value, does not); the other fields are the pieces the main screen's panel shows,
 * each optional where only some screens or rows have it.
 */
@Immutable
internal data class CompanionHelp(
    val identity: String,
    val icon: String,
    val title: String,
    /** The help text: the value's own detail (for example "720p" and what it requests) and the explanation of the setting. */
    val body: String,
    /** Per-game settings: where the focused value comes from. */
    val source: HelpSource? = null,
    /** The row cannot be changed on this device ("Not available here"). */
    val unavailable: Boolean = false,
    /** A text row's current text (a name, a path), set apart from the help in the accent color. */
    val value: String? = null,
    /** The row's impact chips; null for a row without them. The legend and its sentence follow. */
    val impact: SettingImpact? = null,
    /** The palette row: the three colors with their names. */
    val swatches: List<HelpSwatch> = emptyList(),
    /** The rows that change the game background: how it looks now. */
    val backdrop: HelpBackdrop? = null,
    /** Debug info: the technical details under the description (monospace). */
    val details: String? = null,
    /** Per-game settings: "Saved automatically". */
    val savedNote: Boolean = false,
)

/** Where a per-game value comes from: the marker (custom for this game or from the profile) and the sentence with its [strong] part in bold. */
@Immutable
internal data class HelpSource(val custom: Boolean, val icon: String, val title: String, val before: String, val strong: String, val after: String)

@Immutable
internal data class HelpSwatch(val color: Color, val label: String)

/**
 * The game background as the settings rows change it: [active] while a game background is on and a game's art is there (the
 * [image], or the placeholder hues with [icon] while it loads), [opacity] as set; [title] is the name on the preview.
 */
@Immutable
internal data class HelpBackdrop(
    val title: String,
    val active: Boolean,
    val image: ImageBitmap?,
    val hue: Float,
    val hue2: Float,
    val icon: String,
    val opacity: Float,
)

/**
 * Whether the main screen leaves out its description panel (the 520 px panel, the compact dock) and gives the list the room: only when a
 * companion window has actually taken the help ([CompanionSession.showsHelp]), never just because a second display exists. [help] is what
 * the screen publishes; with nothing published there is nothing to show elsewhere either.
 */
internal fun hidesDescription(windowShowsHelp: Boolean, help: CompanionHelp?): Boolean = windowShowsHelp && help != null

/**
 * The text of a [HelpSource] body: the sentence formatted around a marker that [strong] then replaces, so it can be set in bold on both
 * screens. A sentence without the marker is plain text.
 */
internal fun helpSource(custom: Boolean, icon: String, title: String, sentence: String, marker: String, strong: String): HelpSource {
    val at = sentence.indexOf(marker)
    return if (marker.isEmpty() || at < 0) HelpSource(custom, icon, title, sentence, "", "")
    else HelpSource(custom, icon, title, sentence.substring(0, at), strong, sentence.substring(at + marker.length))
}
