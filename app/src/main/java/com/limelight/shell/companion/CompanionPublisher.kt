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
    SideEffect { shell.companionGame = game }
    DisposableEffect(shell) { onDispose { shell.companionGame = null } }
}

private const val IMPACT_DELAY_MS = 300L
