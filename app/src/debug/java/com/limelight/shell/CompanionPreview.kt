package com.limelight.shell

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.limelight.shell.companion.CompanionContent
import com.limelight.shell.companion.CompanionHint
import com.limelight.shell.companion.CompanionHostLine
import com.limelight.shell.companion.CompanionPage
import com.limelight.shell.companion.GamePage
import com.limelight.shell.companion.SettingsHelpPage
import com.limelight.shell.companion.ShellContext
import com.limelight.shell.companion.CompanionGame
import com.limelight.shell.companion.CompanionHelp
import com.limelight.shell.companion.HelpBackdrop
import com.limelight.shell.companion.HelpSwatch
import com.limelight.shell.companion.helpSource
import androidx.compose.ui.graphics.Color
import com.limelight.shell.ui.Symbols
import com.limelight.shell.companion.CompanionState
import com.limelight.shell.companion.StreamCommands
import com.limelight.shell.data.NativeHdr
import com.limelight.shell.settings.Appearance
import com.limelight.shell.settings.ImpactLevel
import com.limelight.shell.settings.SettingImpact
import kotlinx.coroutines.flow.StateFlow

/**
 * Debug builds only: the companion screen of a dual-screen device with made-up data, started with
 * `adb shell am start -n io.github.meitoncz.delos.debug/com.limelight.shell.ShellActivity --es preview_screen companion --es preview_state <state>`
 * where `<state>` is `shell` (a game focused, nothing runs; the default), `shell_running` (it runs), `shell_other` (another game runs),
 * `worst_genres` (ten genres), the settings help `help`, `help_long`, `help_short`, `help_game`, `help_unavailable` (with a value), `help_debug` (eight
 * lines of details), `help_palette`, `help_background` and `worst_help` (the six-sentence help), `idle` (no game focused), `stream` (statistics and
 * the tiles; its fake menu has 30 special keys and 12 commands, so the sheets page), `typing` (the keyboard); add
 * `--es preview_sheet <brightness|bitrate|upscaler|more|keys|commands|mouse>` to open a page over the tiles. Combine with
 * `--es preview_shape <W>x<H>@<dpi>` for the panel's own shape (the companion plans its stage from its own display; docs/DUAL_SCREEN.md).
 * Nothing reaches a PC, a game or a stream: the keys go to a list in the log (`adb logcat -s CompanionPreview`).
 */
internal object CompanionPreview {
    fun from(intent: Intent): (@Composable () -> Unit)? {
        if (intent.getStringExtra("preview_screen") != "companion") return null
        val state = intent.getStringExtra("preview_state") ?: "shell"
        val sheet = intent.getStringExtra("preview_sheet")
        return {
            val fake = remember(state) { companionState(state) }
            CompanionContent(fake, startTyping = state == "typing", startSheet = sheet)
        }
    }

    /**
     * The state a real companion window shows instead of the owner's, for testing on a real second display with the shell on the main
     * one: `--es companion_fake <state>` on the launch of `ShellActivity` (the states of `preview_state`; null without the extra).
     */
    fun fakeState(intent: Intent): CompanionState? = intent.getStringExtra("companion_fake")?.let { companionState(it) }

    /**
     * The shell of the screen preview that is running (`preview_screen settings` and the others run the real [Shell][com.limelight.shell.Shell]
     * on a [PreviewWorld] with a shell state of its own): the companion window shows what that shell publishes, so a real second display can
     * be tested with the settings screens of the previews. Null without a screen preview.
     */
    var liveShell: ShellState? by mutableStateOf(null)

    /** The appearance of that same preview: the window must be drawn in the theme and palette the previewed screen is, as the real shell's is. */
    var liveAppearance: StateFlow<Appearance>? by mutableStateOf(null)

    /** The prototype's worst case: ten genres. */
    private val WORST_GENRES = listOf("Action", "Adventure", "Role-playing", "Strategy", "Simulation", "Puzzle", "Platformer", "Racing", "Sport", "Arcade")

    private fun game(running: Boolean, other: String?, genres: List<String> = listOf("Action", "Roguelike")) = CompanionGame(
        key = "preview/1", name = "Asterfall", running = running, otherRunning = other, image = null, hue = 190f, hue2 = 255f,
        summary = "Trace a lost signal through a changing world.",
        genres = genres, completionSeconds = 19 * 3600L, hdr = NativeHdr.HDR,
        impact = COST,
        onAction = { android.util.Log.i("CompanionPreview", "button $it") },
        pcName = "GAMING-PC",
    )

    /** The prototype's worst case: a six-sentence help. */
    private const val WORST_HELP = "Requests a larger picture from the host. More pixels need more network bandwidth. A higher resolution can preserve fine detail. " +
        "The host must also render the requested picture. Lower the value when the connection is unstable. The profile value stays available when this game has an override."

    private val COST = SettingImpact(ImpactLevel.MEDIUM, ImpactLevel.LOW, ImpactLevel.MEDIUM)

    private fun profileSource(custom: Boolean, title: String, sentence: String, strong: String) =
        helpSource(custom, if (custom) Symbols.TUNE else Symbols.ACCOUNT_CIRCLE, title, sentence, "%s", strong)

    private fun help(name: String): CompanionHelp = when (name) {
        "help_short" -> CompanionHelp("preview/short", Symbols.SETTINGS, "Language", "The language of the app.", section = "Settings · Display")
        "help_game" -> CompanionHelp(
            "preview/game", Symbols.SETTINGS, "Resolution",
            "Overrides the profile for Asterfall.\nPress X on the main display to restore the profile value. Saved automatically for the next stream.",
            short = "Overrides the profile for this game.", section = "Game settings",
            source = profileSource(true, "Custom for this game", "Profile: %s.", "1080p"), impact = COST,
        )
        "worst_help" -> CompanionHelp(
            "preview/worst", Symbols.SETTINGS, "Resolution", WORST_HELP, short = "Requests the host picture size.", section = "Game settings",
            source = profileSource(true, "Custom for this game", "Profile: %s.", "1080p"), impact = COST, savedNote = true,
        )
        "help_unavailable" -> CompanionHelp(
            "preview/unavailable", Symbols.SETTINGS, "Native HDR",
            "Unavailable with this device or its current settings. Use an HDR-capable codec and display before enabling HDR.",
            short = "Unavailable in the current configuration.", section = "Settings · Display", unavailable = true, value = "Off",
            source = profileSource(false, "Profile", "%s. Currently unavailable.", "Off"), impact = COST,
        )
        "help_debug" -> CompanionHelp(
            "preview/debug", Symbols.INFO, "Displays", "These are the panels reported by Android. A built-in companion is not a stream target.",
            short = "Shows the device's two panels.", section = "Debug info",
            source = profileSource(false, "Device information", "Read only.", ""),
            details = "id 8 · 1240 x 1080 · 420 dpi · 60.0 Hz\nstate ON\nflags PRESENTATION, SHOULD_SHOW_SYSTEM_DECORATIONS, TRUSTED\ntype text: VIRTUAL\nproduct info: none\npanel: companion\nmain: 1920 x 1080\nrefresh: 60.0 Hz",
        )
        "help_palette" -> CompanionHelp(
            "preview/palette", Symbols.SETTINGS, "Color palette", "Uses three accents with neutral text and surfaces. Change the palette on the main display.",
            short = "Sets the interface's three accents.", section = "Settings · Display",
            source = profileSource(false, "Profile", "%s palette.", "Island"),
            swatches = listOf(HelpSwatch(Color(0xFFF6B73C), "Gold"), HelpSwatch(Color(0xFF4FD1E8), "Sea glass"), HelpSwatch(Color(0xFFB49EF3), "Violet")),
        )
        "help_background" -> CompanionHelp(
            "preview/background", Symbols.SETTINGS, "Background blur", "Softens only the game background. Text and controls stay sharp. Reduced motion keeps the background still.",
            short = "Softens only the game background.", section = "Settings · Display",
            source = profileSource(false, "Profile", "%s blur.", "15 px"),
            backdrop = HelpBackdrop("Asterfall · background preview", true, null, 190f, 255f, Symbols.SETTINGS, .6f),
        )
        "help" -> CompanionHelp(
            "preview/help", Symbols.SETTINGS, "Resolution",
            "Sets the picture requested from the host. More pixels can preserve detail but need more bandwidth. Use a lower value if the connection is unstable.",
            short = "Requests the host picture size.", section = "Settings · Display",
            source = profileSource(false, "Profile value", "%s.", "1080p"), impact = COST,
        )
        else -> CompanionHelp(
            "preview/long", Symbols.SETTINGS, "Frame pacing",
            "Controls when decoded frames reach the display. Balanced pacing absorbs uneven delivery with a small buffer. Lowest latency favors immediate response. " +
                "Match the source frame rate to your display when possible. Network jitter and decoding stalls have different causes. Read the stream statistics before changing this setting.",
            short = "Controls when frames reach the display.", section = "Settings · Display",
            source = profileSource(false, "Profile", "%s.", "Balanced"), impact = SettingImpact(ImpactLevel.LOW, ImpactLevel.MEDIUM, ImpactLevel.HIGH),
        )
    }

    /** What the fallback card of a preview knows of the main screen (a Settings screen with nothing focused on a connected PC that runs a game). */
    private val context = ShellContext(
        title = "Settings", purpose = "Select a setting on the main display.",
        hints = listOf(
            CompanionHint("L1", null, "Previous tab"), CompanionHint("R1", null, "Next tab"), CompanionHint("Y", null, "Search"),
            CompanionHint("A", null, "Change"), CompanionHint("B", null, "Back"),
        ),
        host = CompanionHostLine("GAMING-PC", "Online", "Asterfall"), welcome = false,
    )

    private fun shell(vararg pages: CompanionPage, welcome: Boolean = false): CompanionState =
        CompanionState.Shell(if (welcome) null else "GAMING-PC", pages.toList(), context.copy(welcome = welcome))

    private fun companionState(name: String): CompanionState = CompanionFakesPc.state(name) ?: CompanionFakesContent.state(name) ?: CompanionFakesFlow.state(name) ?: when (name) {
        "help", "help_long", "help_short", "help_game", "help_unavailable", "help_debug", "help_palette", "help_background", "worst_help" ->
            shell(GamePage(game(false, null)), SettingsHelpPage(help(name)))
        "shell" -> shell(GamePage(game(false, null)))
        "shell_running" -> shell(GamePage(game(true, null)))
        "shell_other" -> shell(GamePage(game(false, "Hades")))
        "worst_genres" -> shell(GamePage(game(false, null, WORST_GENRES)))
        "idle", "fallback" -> shell()
        "welcome" -> shell(welcome = true)
        "stream", "typing" -> CompanionState.Stream(
            "GAMING-PC", "Asterfall",
            StreamStats(1920, 1080, 119.9f, 120f, 119.9f, "HEVC", true, "c2.qti.hevc.decoder", null, 0f, 5, 1, 2.1f, 0f, -1f, -1f, -1f, 4400f),
            // Long lists on purpose (30 special keys, 12 commands): the sheets page them with Previous / Next instead of scrolling
            StreamCommands(FakeStreamMenuHost(longLists = true)) { code, down ->
                android.util.Log.i("CompanionPreview", (if (down) "down " else "up   ") + KeyEvent.keyCodeToString(code))
            },
        )
        else -> shell(GamePage(game(false, null)))
    }
}
