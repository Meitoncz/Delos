package com.limelight.shell.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.limelight.shell.LocalShell
import com.limelight.shell.Screen
import com.limelight.shell.ui.AnimatedPlayback
import com.limelight.shell.ui.AnimationTicker
import com.limelight.shell.ui.HeroMotion
import com.limelight.shell.ui.LocalReducedMotion

/**
 * The animated artwork of the home screen: which game tiles are on screen, whether anything may play now, and the one
 * [ticker] every playing tile reports its frames to. A tile plays while [plays] is true for its game.
 */
@Stable
internal class DashboardArt(val ticker: AnimationTicker, private val motion: State<Boolean>, private val visible: State<Set<Int>>) {
    /** Whether anything may play at all now (for views that are always on screen, like the focused game's logo). */
    val motionOn: Boolean get() = motion.value

    fun plays(appId: Int): Boolean = motion.value && appId in visible.value
}

internal val LocalDashboardArt = staticCompositionLocalOf<DashboardArt?> { null }

/**
 * Whether animated artwork plays on the screen that [onScreen] recognizes ([AnimatedPlayback.shouldPlay]): the screen is
 * the one shown and nothing covers it (a menu, a dialog, the launch loader, the stream exit or its result), no stream is
 * being started, and motion is allowed (reduced motion and the system's animations off stop it). A resumed window is
 * required by the decoders themselves (`rememberAnimatedDrawable` only runs while the lifecycle is resumed). Read as a
 * state, so only the views that play recompose when it changes.
 */
@Composable
internal fun rememberArtworkMotion(onScreen: (Screen) -> Boolean): State<Boolean> {
    val shell = LocalShell.current
    val context = LocalContext.current
    val animatorsOff = remember(context) { HeroMotion.animatorsOff(context) }
    val reduced = LocalReducedMotion.current
    val current by rememberUpdatedState(onScreen)
    val reducedNow by rememberUpdatedState(reduced)
    return remember(shell) {
        derivedStateOf {
            AnimatedPlayback.shouldPlay(
                visible = current(shell.screen) && shell.layer == null && shell.portal == null && !shell.exitCover && shell.result == null,
                foreground = true, streaming = shell.launch != null, reducedMotion = reducedNow, animatorsOff = animatorsOff,
            )
        }
    }
}
