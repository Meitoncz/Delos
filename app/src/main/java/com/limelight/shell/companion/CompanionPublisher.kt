package com.limelight.shell.companion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.limelight.shell.LocalGameActions
import com.limelight.shell.LocalShell
import com.limelight.shell.Screen
import com.limelight.shell.data.App
import com.limelight.shell.data.LocalGameMetadata
import com.limelight.shell.data.Pc
import com.limelight.shell.data.ProfileStore
import com.limelight.shell.screens.appArt
import com.limelight.shell.screens.hdrBadgeKind
import com.limelight.shell.settings.GameSettingsStore
import com.limelight.shell.ui.topGenres
import com.limelight.shell.ui.topThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Publishes the game focused on the home screen to the companion screen ([ShellState.companionGame][com.limelight.shell.ShellState.companionGame]),
 * live: every focus move, a metadata or artwork update and a change of what runs on the PC makes a new [CompanionGame]; leaving the
 * library clears it. The buttons it carries run the same paths as the controller: `GameActions.start` (which starts or resumes),
 * `quitAndStart` and the game settings screen. Nothing here starts anything by itself.
 */
@Composable
internal fun PublishCompanionGame(pc: Pc, app: App, image: ImageBitmap?, running: App?, profiles: ProfileStore) {
    val shell = LocalShell.current
    val actions = LocalGameActions.current
    val metadata = LocalGameMetadata.current.entry(pc, app).data
    val profile by profiles.profiles.collectAsStateWithLifecycle()
    // Settled a moment after the focus stopped moving: reading the stream settings is not done for every game a fast scroll passes
    val impact by produceState<com.limelight.shell.settings.SettingImpact?>(null, pc.uuid, app.id, profile.currentId) {
        delay(IMPACT_DELAY_MS)
        value = withContext(Dispatchers.Default) {
            val defaults = profiles.catalog.all.mapNotNull { spec -> spec.default?.let { spec.key to it } }.toMap()
            gameImpact(defaults + GameSettingsStore(profiles.settings(), pc.uuid, app.id).preferences.all)
        }
    }
    val currentImage by rememberUpdatedState(image)
    val other = running?.takeIf { it.id != app.id }
    val onAction: (ShellButton) -> Unit = remember(shell, actions, pc, app, other) {
        { button ->
            when (button) {
                ShellButton.START, ShellButton.RESUME -> actions.start(pc, app, currentImage)
                ShellButton.RESUME_OTHER -> other?.let { actions.start(pc, it, null) }
                ShellButton.QUIT_AND_START -> other?.let { actions.quitAndStart(pc, it, app, currentImage) }
                ShellButton.GAME_SETTINGS -> shell.navigate(Screen.GameSettings(pc, app))
            }
        }
    }
    val art = appArt(pc, app)
    val genres = remember(metadata) {
        topGenres(metadata?.candidate?.genres.orEmpty().filter { it.name.isNotBlank() }, metadata?.steamTags.orEmpty()).map { it.name } +
            topThemes(metadata?.candidate?.themes.orEmpty().filter { it.name.isNotBlank() }, metadata?.steamTags.orEmpty()).map { it.name }
    }
    val game = remember(pc.uuid, app.id, app.name, app.running, other?.name, image, art.hue, art.hue2, metadata, genres, impact, onAction) {
        CompanionGame(
            key = "${pc.uuid}/${app.id}",
            name = app.name,
            running = app.running,
            otherRunning = other?.name.takeIf { !app.running },
            image = image,
            hue = art.hue,
            hue2 = art.hue2,
            summary = metadata?.candidate?.summary,
            genres = genres,
            completionSeconds = metadata?.completion?.seconds?.takeIf { it > 0 },
            hdr = hdrBadgeKind(metadata),
            impact = impact,
            onAction = onAction,
        )
    }
    PublishCompanionPage(remember(game) { GamePage(game) })
}

private const val IMPACT_DELAY_MS = 300L

/**
 * Publishes what a screen (or a layer over it) wants the companion window to show ([ShellState.companionPages][com.limelight.shell.ShellState.companionPages]):
 * [page] is kept live (every new value replaces the old), `null` publishes nothing, and leaving the composition removes it. Every publisher holds a
 * token of its own, so a screen that is being replaced (it stays for the length of the transition) clears only what IT published, never the next
 * screen's page. The window shows the page of the highest [CompanionPage.priority] ([chooseShellPage]).
 */
@Composable
internal fun PublishCompanionPage(page: CompanionPage?) {
    val shell = LocalShell.current
    val token = remember { Any() }
    SideEffect {
        if (page == null) shell.companionPages.remove(token)
        else if (shell.companionPages[token] != page) shell.companionPages[token] = page
    }
    DisposableEffect(shell, token) { onDispose { shell.companionPages.remove(token) } }
}

/**
 * Publishes the description of the row a settings screen has focused to the companion screen, live: every focus move or change of the row's value
 * makes a new [CompanionHelp]; leaving the screen clears it. Whether a window really shows it is [CompanionSession.showsHelp]: the screen draws its
 * own description unless one does.
 */
@Composable
internal fun PublishCompanionHelp(help: CompanionHelp) {
    PublishCompanionPage(remember(help) { SettingsHelpPage(help) })
}

/** True while a companion window shows the settings help: the settings screens then leave out their description and give the list the room. */
@Composable
internal fun companionShowsHelp(): Boolean = CompanionSession.showsHelp

/**
 * Publishes what the fallback card is made of ([ShellContext]): the title and purpose of the current screen, the entries of the hint bar (the very list
 * the main screen draws, so the two never disagree), the PC the shell is connected to with its status and the game that runs on it, and whether any PC is
 * paired at all. Called once by the shell.
 */
@Composable
internal fun PublishCompanionContext(hints: List<com.limelight.shell.ui.Hint>, library: com.limelight.shell.data.Library, pcs: List<Pc>) {
    val shell = LocalShell.current
    val res = com.limelight.shell.ui.textResources()
    val screen = shell.screen
    val pc = when (library) {
        is com.limelight.shell.data.Library.Ready -> library.pc
        is com.limelight.shell.data.Library.Offline -> library.pc
        else -> null
    }
    val running = (library as? com.limelight.shell.data.Library.Ready)?.apps?.firstOrNull { it.running }?.name
    val welcome = pcs.none { it.paired }
    val context = remember(res, screen, hints, pc?.name, pc?.online, pc?.canWake, running, welcome) {
        ShellContext(
            title = res.getString(screenTitle(screen)),
            purpose = res.getString(screenPurpose(screen)),
            hints = hints.map { hint ->
                val glyph = hint.glyph
                CompanionHint((glyph as? com.limelight.shell.ui.Glyph.Letter)?.text, (glyph as? com.limelight.shell.ui.Glyph.Icon)?.symbol, hint.label)
            },
            host = pc?.let { CompanionHostLine(it.name, pcStatusText(res, it), running) },
            welcome = welcome,
        )
    }
    SideEffect { shell.companionContext = context }
    DisposableEffect(shell) { onDispose { shell.companionContext = null } }
}

/** The status of a PC as a word, as the PC screens say it: online, asleep and waking up, or offline. */
internal fun pcStatusText(res: android.content.res.Resources, pc: Pc): String =
    res.getString(if (pc.online) com.limelight.R.string.devices_status_online else if (pc.canWake) com.limelight.R.string.devices_status_asleep_wakes else com.limelight.R.string.devices_status_offline)

