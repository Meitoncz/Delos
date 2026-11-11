package com.limelight.shell

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.limelight.shell.companion.CompanionContent
import com.limelight.shell.companion.CompanionGame
import com.limelight.shell.companion.CompanionState
import com.limelight.shell.companion.StreamCommands
import com.limelight.shell.data.NativeHdr
import com.limelight.shell.settings.ImpactLevel
import com.limelight.shell.settings.SettingImpact

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

    private fun game(running: Boolean, other: String?) = CompanionGame(
        key = "preview/1", name = "Asterfall", running = running, otherRunning = other, image = null, hue = 190f, hue2 = 255f,
        summary = "Trace a lost signal through a world that changes with every return.",
        genres = listOf("Action", "Roguelike"), completionSeconds = 19 * 3600L, hdr = NativeHdr.HDR,
        impact = SettingImpact(ImpactLevel.MEDIUM, ImpactLevel.LOW, ImpactLevel.MEDIUM),
        onAction = { android.util.Log.i("CompanionPreview", "button $it") },
    )

    private fun companionState(name: String): CompanionState = when (name) {
        "shell_running" -> CompanionState.Shell("GAMING-PC", game(true, null))
        "shell_other" -> CompanionState.Shell("GAMING-PC", game(false, "Hades"))
        "idle" -> CompanionState.Shell("GAMING-PC")
        "stream", "typing" -> CompanionState.Stream(
            "GAMING-PC", "Asterfall",
            StreamStats(1920, 1080, 119.9f, 120f, 119.9f, "HEVC", true, "c2.qti.hevc.decoder", null, 0f, 5, 1, 2.1f, 0f, -1f, -1f, -1f, 4400f),
            StreamCommands(FakeStreamMenuHost()) { code, down ->
                android.util.Log.i("CompanionPreview", (if (down) "down " else "up   ") + KeyEvent.keyCodeToString(code))
            },
        )
        else -> CompanionState.Shell("GAMING-PC", game(false, null))
    }
}
