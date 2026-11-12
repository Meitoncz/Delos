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
 * `idle` (no game focused), `stream` (statistics and the tiles), `typing` (the keyboard); add
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

    private fun game(running: Boolean, other: String?) = CompanionGame(
        key = "preview/1", name = "Asterfall", running = running, otherRunning = other, image = null, hue = 190f, hue2 = 255f,
        summary = "Trace a lost signal through a world that changes with every return.",
        genres = listOf("Action", "Roguelike"), completionSeconds = 19 * 3600L, hdr = NativeHdr.HDR,
        impact = SettingImpact(ImpactLevel.MEDIUM, ImpactLevel.LOW, ImpactLevel.MEDIUM),
        onAction = { android.util.Log.i("CompanionPreview", "button $it") },
    )

    private fun help(name: String): CompanionHelp = when (name) {
        "help_short" -> CompanionHelp("preview/short", Symbols.SETTINGS, "Language", "The language of the app.")
        "help_game" -> CompanionHelp(
            "preview/game", Symbols.SPEED, "Video bitrate",
            "Video bitrate in Mbps. Too high a value can stutter on a weak link; too low a value looks blocky. The PC's own line is the better guide.",
            source = helpSource(true, Symbols.TUNE, "Custom for this game", "Profile: %s. Press X to go back to it.", "%s", "20 Mbps"),
            impact = SettingImpact(ImpactLevel.LOW, ImpactLevel.MEDIUM, ImpactLevel.HIGH), savedNote = true,
        )
        "help_debug" -> CompanionHelp(
            "preview/debug", Symbols.INFO, "Emulator 2D Display", "A display of this device, with its role and the signals that gave it.",
            details = "id 8 · 1240 x 1080 · 420 dpi · 60.0 Hz\nstate ON\nflags PRESENTATION, SHOULD_SHOW_SYSTEM_DECORATIONS, TRUSTED\ntype text: VIRTUAL\nproduct info: none\npanel: companion",
        )
        "help_palette" -> CompanionHelp(
            "preview/palette", Symbols.SETTINGS, "Palette", "The colors of the app.",
            swatches = listOf(HelpSwatch(Color(0xFFFFB84D), "Main"), HelpSwatch(Color(0xFF45D6A8), "Secondary"), HelpSwatch(Color(0xFF9B8CFF), "Accent")),
        )
        "help_background" -> CompanionHelp(
            "preview/background", Symbols.SETTINGS, "Game background", "A blurred picture of the focused game behind every screen.",
            backdrop = HelpBackdrop("Game background", true, null, 190f, 255f, Symbols.SETTINGS, .6f),
        )
        else -> CompanionHelp(
            "preview/long", Symbols.SETTINGS, "Video bitrate on metered networks",
            "Bitrate on a metered network (mobile data, some hotspots). 0 uses a quarter of the normal bitrate.\n\nThe stream asks the host for this bitrate whenever the " +
                "connection is metered; on Wi-Fi without a data limit the normal bitrate is used. Lower it when a plan is small, raise it when the link is fast " +
                "and the plan is large. The change applies to the next stream, not to the one that runs.\n\nA higher bitrate costs more network and a little more " +
                "decoding time; the picture gets less blocky in fast scenes, in dark gradients and in foliage, where compression shows first.",
            impact = SettingImpact(ImpactLevel.LOW, ImpactLevel.MEDIUM, ImpactLevel.HIGH),
            source = helpSource(false, Symbols.ACCOUNT_CIRCLE, "From profile", "Currently %s. Change it to set a value for this game only.", "%s", "1 Mbps"),
            unavailable = true, savedNote = true,
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
        "help", "help_long", "help_short", "help_game", "help_debug", "help_palette", "help_background" -> shell(GamePage(game(false, null)), SettingsHelpPage(help(name)))
        "shell_running" -> shell(GamePage(game(true, null)))
        "shell_other" -> shell(GamePage(game(false, "Hades")))
        "idle", "fallback" -> shell()
        "welcome" -> shell(welcome = true)
        "stream", "typing" -> CompanionState.Stream(
            "GAMING-PC", "Asterfall",
            StreamStats(1920, 1080, 119.9f, 120f, 119.9f, "HEVC", true, "c2.qti.hevc.decoder", null, 0f, 5, 1, 2.1f, 0f, -1f, -1f, -1f, 4400f),
            StreamCommands(FakeStreamMenuHost()) { code, down ->
                android.util.Log.i("CompanionPreview", (if (down) "down " else "up   ") + KeyEvent.keyCodeToString(code))
            },
        )
        else -> shell(GamePage(game(false, null)))
    }
}
