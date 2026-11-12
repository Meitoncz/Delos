package com.limelight.shell.companion

import android.os.SystemClock
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.preference.PreferenceManager
import com.limelight.R
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalReducedMotion
import com.limelight.shell.ui.LogoMark
import com.limelight.shell.ui.Motion
import com.limelight.shell.ui.Unbounded
import com.limelight.shell.ui.px
import com.limelight.shell.ui.tpx
import kotlinx.coroutines.delay

/** True only inside the real companion window ([CompanionActivity]): a preview drawn in the shell's own window must not tell the main screen that its description is shown here. */
internal val LocalInWindow = compositionLocalOf { false }

/**
 * The single place the content of the companion screen is decided (docs/DUAL_SCREEN.md): in the shell the ONE page of the highest priority that the
 * shell's screens have published, else the fallback or the welcome card ([CompanionState.Shell.page]); in a stream the statistics and the stream menu
 * as touch tiles, or the keyboard; while the stream is still starting the loader page ([CompanionState.Stream.loaderPage]) instead. It runs inside the companion's own `Stage`, so `LocalStage` is planned from that display's size, and inside the
 * Delos colors. [startTyping] opens a stream on the keyboard (debug previews). [inWindow] is true only for the real companion window.
 *
 * Nothing here scrolls, ever: every page fits the panel ([CompanionFit], [FitColumn]).
 *
 * Burn-in (docs/DUAL_SCREEN.md): everything drifts by a few pixels every two minutes ([CompanionBurnIn.shiftAt]), always; in the shell state the screen
 * dims to [CompanionBurnIn.DIM_ALPHA] after the delay of the user's setting ([CompanionDim], Settings > Display) without a touch, unless the page is
 * one that is waited at ([CompanionPage.keepsAwake]) or dimming is off, and the touch that wakes it is not passed on. A new page counts like a touch.
 */
@Composable
internal fun CompanionContent(state: CompanionState, startTyping: Boolean = false, startSheet: String? = null, inWindow: Boolean = false) {
    val colors = LocalDelosColors.current
    val reducedMotion = LocalReducedMotion.current
    // The shell's page; a stream that is still starting shows the loader page (an update of the shell's loader, not a new page) until it runs
    val page = when (state) {
        is CompanionState.Shell -> state.page
        is CompanionState.Stream -> state.loaderPage()
    }
    val policy = rememberDimPolicy()
    var lastTouch by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var dimmed by remember { mutableStateOf(false) }
    // Armed from the last touch; a touch restarts it, so no polling. A stream is never dimmed: someone is looking at the menu
    val dimDelay = if (page == null || page.keepsAwake) null else policy.delayMs
    LaunchedEffect(lastTouch, dimDelay, page?.key) {
        dimmed = false
        delay(dimDelay ?: return@LaunchedEffect)
        dimmed = true
    }
    var shift by remember { mutableStateOf(CompanionBurnIn.Shift(0, 0)) }
    LaunchedEffect(Unit) {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            shift = CompanionBurnIn.shiftAt(SystemClock.elapsedRealtime() - start)
            delay(CompanionBurnIn.SHIFT_PERIOD_MS)
        }
    }
    val alpha by animateFloatAsState(if (dimmed) CompanionBurnIn.DIM_ALPHA else 1f, if (reducedMotion) snap() else tween(1200), label = "companionDim")
    CompositionLocalProvider(LocalInWindow provides inWindow) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.bg)
                // Every touch counts, whoever consumes it
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        lastTouch = SystemClock.elapsedRealtime()
                    }
                },
        ) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    translationX = shift.x * CompanionBurnIn.SHIFT_STEP_DP * density
                    translationY = shift.y * CompanionBurnIn.SHIFT_STEP_DP * density
                    this.alpha = alpha
                },
            ) {
                Column(Modifier.fillMaxSize()) {
                    Header(
                        when (state) {
                            is CompanionState.Shell -> state.pcName ?: if (state.context?.welcome == true) stringResource(R.string.companion_no_pc) else null
                            is CompanionState.Stream -> state.pcName
                        },
                    )
                    Box(Modifier.fillMaxSize()) {
                        if (page != null) PageHost(page) else if (state is CompanionState.Stream) StreamContent(state, startTyping, startSheet)
                    }
                }
            }
            if (dimmed) {
                // Dimmed: the first touch only wakes the screen (it must never press a button it cannot be seen to press)
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false).consume()
                            dimmed = false
                            lastTouch = SystemClock.elapsedRealtime()
                            waitForUpOrCancellation()
                        }
                    },
                )
            }
        }
    }
}

/** The idle dimming setting, live: it follows the preference while the window is up. */
@Composable
internal fun rememberDimPolicy(): CompanionDim.Policy {
    val context = LocalContext.current.applicationContext
    val prefs = remember(context) { PreferenceManager.getDefaultSharedPreferences(context) }
    var policy by remember { mutableStateOf(CompanionDim.read(prefs)) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == CompanionDim.KEY_ENABLED || key == CompanionDim.KEY_MINUTES) policy = CompanionDim.read(prefs)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return policy
}

/**
 * Shows [page] and fades to the next one when the page's key changes (a different row, game, screen: [Motion.FADE], snapped with reduced motion);
 * a page with the same key is the same page updated and does not fade. The page that fades out keeps the data it last had.
 */
@Composable
private fun PageHost(page: CompanionPage) {
    val reduced = LocalReducedMotion.current
    val seen = remember { LastPages() }
    seen[page.key] = page
    // One window, one registration: the page that fades out must not tell the main screen the help is gone while the next help is fading in
    val window = LocalActivity.current
    if (LocalInWindow.current && window != null && page is SettingsHelpPage) {
        DisposableEffect(window) {
            CompanionSession.helpShown(window)
            onDispose { CompanionSession.helpGone(window) }
        }
    }
    Crossfade(
        page.key, Modifier.fillMaxSize(),
        animationSpec = if (reduced) snap() else tween(Motion.FADE, easing = Motion.Ease), label = "companionPage",
    ) { key -> (if (key == page.key) page else seen[key] ?: page).Content() }
}

/** The last few pages: the access order is the recency, the oldest goes first (the one fading out is always the newest but one). */
private class LastPages : LinkedHashMap<String, CompanionPage>(16, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CompanionPage>?): Boolean = size > KEEP

    private companion object {
        const val KEEP = 8
    }
}

/** The strip at the top (`.stage-header`): the brand on the left, the PC on the right; 64 stage px with its rule. */
@Composable
private fun Header(pcName: String?) {
    val colors = LocalDelosColors.current
    Row(
        Modifier.fillMaxWidth().height((CompanionFit.HEADER - 1).px).padding(horizontal = CompanionFit.MARGIN.px),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.px), verticalAlignment = Alignment.CenterVertically) {
            LogoMark(size = 34f)
            BasicText(
                "DELOS",
                style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = MIN_TEXT.tpx, letterSpacing = .08.em),
            )
        }
        if (!pcName.isNullOrBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.px), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.px).clip(CircleShape).background(colors.accent2))
                BasicText(
                    pcName,
                    style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = MIN_TEXT.tpx),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.px).background(colors.line))
}
