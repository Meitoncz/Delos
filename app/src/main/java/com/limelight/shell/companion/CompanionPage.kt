package com.limelight.shell.companion

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import com.limelight.R
import com.limelight.shell.Screen

/*
 * What the shell's companion window shows (docs/DUAL_SCREEN.md, "Companion pages"): ONE page at a time, chosen from what the screens of the shell
 * have published. A page is immutable data plus the owner-side callbacks its touch buttons run (the same paths as the controller); it draws
 * itself ([Content]) inside the companion's own stage and never reaches into the shell. A screen that publishes nothing gets the fallback page
 * ([FallbackPage]): the companion is never empty.
 */

/** Which published page wins when a screen and a layer over it both publish (the loader over Home, the filter sheet over the library). */
internal object PagePriority {
    /** The focused game of Home: the screen's own details. */
    const val GAME = 50

    /** What a screen publishes about its focused item. */
    const val SCREEN = 100

    /** A sheet over the screen that changes what the screen shows (the filter sheets). */
    const val MODAL = 200

    /** A launch in progress, a stream result or a problem: what the user must see first. */
    const val OVERLAY = 300
}

/** One thing the companion window can show of the shell. */
internal interface CompanionPage {
    /**
     * The identity of the page: another key crossfades, the same key is an update of the same page (a value that changed, a clock) and does
     * not fade.
     */
    val key: String

    val priority: Int get() = PagePriority.SCREEN

    /** Stays bright however long nobody touches it (a launch that is still going): the page that is waited at. */
    val keepsAwake: Boolean get() = false

    @Composable
    fun Content()
}

/** A button of the main screen's hint bar as the companion lists it: the pad button's letter, or the symbol of a menu button, and the word next to it. */
@Immutable
internal data class CompanionHint(val key: String?, val symbol: String?, val label: String)

/** The PC the shell is connected to, for the fallback: its name, its status as a word, and the game that runs on it. */
@Immutable
internal data class CompanionHostLine(val name: String, val status: String, val runningGame: String?)

/**
 * What the fallback page and the idle card know of the main screen, published by the shell next to the pages: the title and the one-line purpose
 * of the screen, the entries of the main hint bar (the same list the bar draws), the connected PC, and whether any PC is paired at all.
 */
@Immutable
internal data class ShellContext(
    val title: String,
    val purpose: String,
    val hints: List<CompanionHint>,
    val host: CompanionHostLine?,
    /** No PC is paired: the welcome card instead of a status card. */
    val welcome: Boolean,
)

/** The page [state] shows: the published page of the highest priority, else the welcome card (no PC), else the fallback status card. */
internal fun chooseShellPage(published: Collection<CompanionPage>, context: ShellContext?): CompanionPage {
    published.maxByOrNull { it.priority }?.let { return it }
    return if (context?.welcome == true) WelcomePage(context) else FallbackPage(context ?: ShellContext("", "", emptyList(), null, false))
}

/** The kinds of content the companion has for a screen of the shell; [FALLBACK] is the status card every other screen gets. */
internal enum class ContentKind { PC, PAIR_HOST, PIN, GAME, HELP, ARTWORK, PROFILE, CREATE_PROFILE, BOOT, FALLBACK }

/**
 * Which content a route has. Exhaustive on purpose: a screen added to [Screen] does not compile until it is placed here, and a place of
 * [ContentKind.FALLBACK] is a valid one (the status card with its title, purpose and the hints of the main screen), so the companion is never
 * empty for a new route (CompanionRoutesTest).
 */
internal fun contentKind(screen: Screen): ContentKind = when (screen) {
    Screen.Home -> ContentKind.GAME
    Screen.Start, Screen.Devices -> ContentKind.PC
    is Screen.PairPc -> ContentKind.PAIR_HOST
    is Screen.Pin -> ContentKind.PIN
    Screen.Settings, is Screen.GameSettings, Screen.DebugInfo -> ContentKind.HELP
    is Screen.Artwork -> ContentKind.ARTWORK
    Screen.Profile -> ContentKind.PROFILE
    is Screen.CreateProfile -> ContentKind.CREATE_PROFILE
    is Screen.Boot -> ContentKind.BOOT
    Screen.SettingsSearch, Screen.OscPreview -> ContentKind.FALLBACK
}

/** The title of a screen for the fallback page (the name of the screen on the main panel). */
@StringRes
internal fun screenTitle(screen: Screen): Int = when (screen) {
    Screen.Home -> R.string.companion_screen_home
    Screen.Start -> R.string.companion_screen_start
    Screen.Devices -> R.string.devices_title
    is Screen.PairPc -> R.string.pairing_pc_title
    is Screen.Pin -> R.string.companion_screen_pairing
    Screen.Settings -> R.string.companion_screen_settings
    Screen.SettingsSearch -> R.string.companion_screen_settings_search
    is Screen.GameSettings -> R.string.companion_screen_game_settings
    Screen.DebugInfo -> R.string.companion_screen_debug
    Screen.OscPreview -> R.string.companion_screen_osc
    is Screen.Artwork -> R.string.companion_screen_artwork
    Screen.Profile -> R.string.companion_screen_profile
    is Screen.CreateProfile -> R.string.companion_screen_create_profile
    is Screen.Boot -> R.string.companion_screen_boot
}

/** The one-line purpose of a screen for the fallback page. */
@StringRes
internal fun screenPurpose(screen: Screen): Int = when (screen) {
    Screen.Home -> R.string.companion_purpose_home
    Screen.Start -> R.string.companion_purpose_start
    Screen.Devices -> R.string.companion_purpose_devices
    is Screen.PairPc -> R.string.companion_purpose_pair_pc
    is Screen.Pin -> R.string.companion_purpose_pin
    Screen.Settings -> R.string.companion_purpose_settings
    Screen.SettingsSearch -> R.string.companion_purpose_settings_search
    is Screen.GameSettings -> R.string.companion_purpose_game_settings
    Screen.DebugInfo -> R.string.companion_purpose_debug
    Screen.OscPreview -> R.string.companion_purpose_osc
    is Screen.Artwork -> R.string.companion_purpose_artwork
    Screen.Profile -> R.string.companion_purpose_profile
    is Screen.CreateProfile -> R.string.companion_purpose_create_profile
    is Screen.Boot -> R.string.companion_purpose_boot
}
