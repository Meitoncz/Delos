package com.limelight.shell.companion

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import com.limelight.shell.StreamMenuHost
import com.limelight.shell.StreamStats
import com.limelight.shell.data.NativeHdr
import com.limelight.shell.settings.SettingImpact

/**
 * What the owner of the companion window wants it to show (docs/DUAL_SCREEN.md). The owning activity builds one; the
 * [CompanionHost] reads it inside composition, so a state held in Compose snapshot state updates the window live. State goes in
 * as immutable values and commands come out through the small interfaces they carry ([CompanionGame.onAction],
 * [StreamCommands]): the companion never reaches into an activity.
 */
internal sealed interface CompanionState {
    /**
     * The shell: the PC whose games are open, what its screens have [published] (pages of different priorities, the highest wins: [chooseShellPage]) and
     * the [context] of the main screen the fallback page is made of. Nothing published is never empty: the fallback or the welcome card shows.
     */
    data class Shell(val pcName: String?, val published: List<CompanionPage> = emptyList(), val context: ShellContext? = null) : CompanionState {
        /** The page the window shows. */
        val page: CompanionPage get() = chooseShellPage(published, context)
    }

    /** A stream: the PC and game it runs, the latest statistics (null before the first) and the commands of the running stream (null until bound). */
    data class Stream(
        val pcName: String?,
        val gameName: String?,
        val stats: StreamStats?,
        val commands: StreamCommands?,
    ) : CompanionState
}

/** The buttons of the shell's companion: what the focused game offers, in the same cases as the game's context menu on the main panel. */
enum class ShellButton { START, RESUME, RESUME_OTHER, QUIT_AND_START, GAME_SETTINGS }

/**
 * The game focused on the main panel, with what the companion shows of it. Published by the shell's home screen
 * (`PublishCompanionGame`), cleared when the shell leaves the library. [onAction] runs the same paths as the controller
 * (`GameActions.start`, `quitAndStart`, the Game settings screen).
 */
@Immutable
data class CompanionGame(
    /** `pc uuid/app id`, the key of the focused game. */
    val key: String,
    val name: String,
    val running: Boolean,
    /** The name of another game that runs on the PC, null when none or when this one runs. */
    val otherRunning: String?,
    /** The game's hero, else its cover, else the PC's art; null while there is none (the placeholder colors are used). */
    val image: ImageBitmap?,
    /** The placeholder's hues while there is no [image]. */
    val hue: Float,
    val hue2: Float,
    val summary: String?,
    val genres: List<String>,
    /** HowLongToBeat's completion time in seconds, null when unknown. */
    val completionSeconds: Long?,
    val hdr: NativeHdr?,
    /** What the game's effective stream settings cost, null while unknown. */
    val impact: SettingImpact?,
    val onAction: (ShellButton) -> Unit,
    /** The name of the PC the library belongs to, for the line under the game's name; null when unknown. */
    val pcName: String? = null,
) {
    val buttons: List<ShellButton> get() = shellButtons(running, otherRunning != null)
}

/**
 * Which buttons show and in which order. The first is the primary one. Nothing runs: Start. This game runs: Resume. Another game
 * runs: Resume that one (the safe choice) and Quit and start, kept last of the launch buttons (the destructive one, no confirmation
 * as everywhere in Delos). Game settings always closes the row.
 */
fun shellButtons(running: Boolean, otherRunning: Boolean): List<ShellButton> = when {
    running -> listOf(ShellButton.RESUME, ShellButton.GAME_SETTINGS)
    otherRunning -> listOf(ShellButton.RESUME_OTHER, ShellButton.QUIT_AND_START, ShellButton.GAME_SETTINGS)
    else -> listOf(ShellButton.START, ShellButton.GAME_SETTINGS)
}

/**
 * What the stream's companion can do: the stream menu's own handlers ([menu], the same [StreamMenuHost] the menu on the main panel
 * is built on, so every tile does what its twin does there) and the one path keys take into the stream ([sendKey], the Full
 * keyboard's: `KeyBoardLayoutController.sendKeyEvent`). The menu's getters are not observable: the screen reads them again after
 * every command and once a second.
 */
internal class StreamCommands(val menu: StreamMenuHost, val sendKey: (code: Int, down: Boolean) -> Unit)
