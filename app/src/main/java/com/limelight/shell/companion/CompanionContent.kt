package com.limelight.shell.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.limelight.R
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LogoMark
import com.limelight.shell.ui.px
import com.limelight.shell.ui.tpx

/**
 * What the owner of the companion window wants it to show (docs/DUAL_SCREEN.md). The owning activity builds one; the
 * [CompanionHost] reads it inside composition, so a state held in Compose snapshot state updates the window live.
 */
sealed interface CompanionState {
    /** The shell: the PC whose games are open (null on the start screen, in settings before a PC is chosen). */
    data class Shell(val pcName: String?) : CompanionState

    /** A stream: the PC it runs on. */
    data class Stream(val pcName: String?) : CompanionState
}

/**
 * The single place the content of the companion screen is decided. Step 4 of issue #76 replaces this placeholder (variant A,
 * "Details and actions": game details and actions in the shell, statistics and the stream-menu tiles in a stream, the keyboard
 * when typing) without touching [CompanionHost]. It runs inside the companion's own `Stage`, so `LocalStage` is planned from
 * that display's size, and inside the Delos colors.
 */
@Composable
fun CompanionContent(state: CompanionState) {
    val colors = LocalDelosColors.current
    Box(Modifier.fillMaxSize().background(colors.bg), contentAlignment = Alignment.Center) {
        Column(Modifier.alpha(DIM), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.px)) {
            // The padding keeps the moon, which sits outside the ring's box, inside the dimmed layer (a layer clips to its bounds)
            Box(Modifier.padding(14.px)) { LogoMark(size = 96f) }
            val line = when (state) {
                is CompanionState.Shell -> state.pcName
                is CompanionState.Stream -> stringResource(R.string.companion_streaming)
            }
            if (!line.isNullOrBlank()) {
                BasicText(
                    line,
                    style = TextStyle(color = colors.text2, fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 30.tpx, textAlign = TextAlign.Center),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The placeholder is dim on purpose: an AMOLED panel that stays on all day must not burn in (the policy is an open point of step 4). */
private const val DIM = 0.45f
