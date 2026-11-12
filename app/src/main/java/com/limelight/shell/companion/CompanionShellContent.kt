package com.limelight.shell.companion

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import com.limelight.R
import com.limelight.shell.data.NativeHdr
import com.limelight.shell.screens.completionText
import com.limelight.shell.settings.ImpactLevel
import com.limelight.shell.settings.SettingImpact
import com.limelight.shell.ui.IMPACT_AXES
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LogoMark
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.Unbounded
import com.limelight.shell.ui.hsl
import com.limelight.shell.ui.impactColor
import com.limelight.shell.ui.levels
import com.limelight.shell.ui.px
import com.limelight.shell.ui.textResources
import com.limelight.shell.ui.tpx

/**
 * The shell's companion: the description of the row focused on a settings screen, large (the help wins, [shellView]); else the game focused
 * on the main panel with its details and the big buttons; else a quiet mark. [inWindow] is false for a preview drawn inside the shell's own
 * window: only a real companion window tells the main screen that the description is shown here.
 */
@Composable
internal fun ShellContent(state: CompanionState.Shell, inWindow: Boolean = true) {
    when (shellView(state)) {
        ShellView.HELP -> HelpContent(checkNotNull(state.help), inWindow)
        ShellView.GAME -> GameDetails(checkNotNull(state.game))
        ShellView.IDLE -> IdleContent()
    }
}

@Composable
private fun IdleContent() {
    val colors = LocalDelosColors.current
    Box(Modifier.fillMaxSize().padding(horizontal = 48.px), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(26.px)) {
            // The padding keeps the moon, which sits outside the ring's box (about 18 px at this size), inside the dimmed layer
            Box(Modifier.padding(24.px)) { LogoMark(size = 96f) }
            Body(stringResource(R.string.companion_idle_hint), color = colors.text2, maxLines = 3, align = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GameDetails(game: CompanionGame) {
    val colors = LocalDelosColors.current
    val res = textResources()
    Column(Modifier.fillMaxSize().padding(start = 44.px, end = 44.px, top = 24.px, bottom = 30.px), verticalArrangement = Arrangement.spacedBy(18.px)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(stringResource(R.string.companion_focused_label), Modifier.weight(1f))
            if (game.running) Body(stringResource(R.string.home_header_running), color = colors.accent2, weight = FontWeight.SemiBold)
        }
        GameCard(game, Modifier.weight(1f).fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.px), verticalArrangement = Arrangement.spacedBy(12.px)) {
            game.genres.forEach { InfoPill(it) }
            game.completionSeconds?.let { InfoPill(stringResource(R.string.home_chip_hltb, completionText(res, it)), Symbols.COMPLETION_TIME) }
            when (game.hdr) {
                NativeHdr.HDR -> InfoPill(stringResource(R.string.companion_hdr_native), Symbols.HDR_ON, color = colors.accent2)
                NativeHdr.SDR -> InfoPill("SDR", Symbols.SDR)
                NativeHdr.UNKNOWN -> InfoPill(stringResource(R.string.metadata_hdr_unverified), Symbols.HDR_UNKNOWN)
                null -> Unit
            }
        }
        game.impact?.let { ImpactRow(it) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.px)) {
            val buttons = game.buttons
            buttons.forEachIndexed { i, button ->
                CompanionButton(
                    label = when (button) {
                        ShellButton.START -> stringResource(R.string.home_menu_start)
                        ShellButton.RESUME -> stringResource(R.string.home_menu_resume)
                        ShellButton.RESUME_OTHER -> stringResource(R.string.home_menu_resume_other, game.otherRunning.orEmpty())
                        ShellButton.QUIT_AND_START -> stringResource(R.string.home_menu_quit_and_start, game.otherRunning.orEmpty())
                        ShellButton.GAME_SETTINGS -> stringResource(R.string.home_menu_settings)
                    },
                    onClick = { game.onAction(button) },
                    modifier = Modifier.weight(1f).height(96.px),
                    style = when {
                        i == 0 -> ButtonStyle.PRIMARY
                        button == ShellButton.QUIT_AND_START -> ButtonStyle.DANGER
                        button == ShellButton.GAME_SETTINGS -> ButtonStyle.NORMAL
                        else -> ButtonStyle.SECONDARY
                    },
                    icon = when (button) {
                        ShellButton.START -> Symbols.PLAY_ARROW
                        ShellButton.RESUME, ShellButton.RESUME_OTHER -> Symbols.RESUME
                        ShellButton.QUIT_AND_START -> Symbols.SWAP_GAME
                        ShellButton.GAME_SETTINGS -> Symbols.SETTINGS
                    },
                    textSize = 30,
                    maxLines = 2,
                )
            }
        }
        Body(stringResource(R.string.companion_shell_hint), color = colors.text3, maxLines = 2)
    }
}

/** The banner: the game's art with a scrim, the name in the display face, the summary under it. */
@Composable
private fun GameCard(game: CompanionGame, modifier: Modifier) {
    val colors = LocalDelosColors.current
    val shape = RoundedCornerShape(28.px)
    Box(modifier.clip(shape).border(1.px, colors.line, shape).background(placeholder(game))) {
        game.image?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.Center, modifier = Modifier.fillMaxSize())
        }
        // Legibility: the bottom and left fade into the page color
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, .45f to Color.Transparent, 1f to colors.bg.copy(alpha = .92f))))
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 32.px, vertical = 26.px), verticalArrangement = Arrangement.spacedBy(10.px)) {
            val size = when {
                game.name.length > 26 -> 36
                game.name.length > 16 -> 46
                else -> 58
            }
            BasicText(
                game.name,
                style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = size.tpx, letterSpacing = (-.01).em),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            game.summary?.takeIf { it.isNotBlank() }?.let { Body(it, color = colors.text2, maxLines = 2) }
        }
    }
}

/** The colors behind a game without art (the same hues the dashboard's placeholder uses). */
@Composable
private fun placeholder(game: CompanionGame): Brush =
    Brush.linearGradient(listOf(hsl(game.hue, 45f, 22f), hsl(game.hue2, 55f, 12f)))

/** What the game costs: GPU, latency and network, each with its three-step bars. An estimate, never a measurement. */
@Composable
internal fun ImpactRow(impact: SettingImpact) {
    val colors = LocalDelosColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.px)) {
        IMPACT_AXES.forEachIndexed { i, (label, glyph) ->
            val level = impact.levels()[i]
            Row(
                Modifier.clip(CircleShape).background(colors.surface).border(1.px, colors.line, CircleShape).padding(horizontal = 22.px, vertical = 10.px),
                horizontalArrangement = Arrangement.spacedBy(12.px),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Symbol(glyph, 32.tpx, colors.text3)
                Body(stringResource(label), color = colors.text2)
                ImpactBars(level)
            }
        }
    }
}

/** One to three filled bars in the level's color, the rest faint: the shape of `ImpactChip`, a third larger for a thumb's reading distance. */
@Composable
private fun ImpactBars(level: ImpactLevel) {
    val colors = LocalDelosColors.current
    val fill = impactColor(level)
    Row(Modifier.height(30.px), horizontalArrangement = Arrangement.spacedBy(4.px), verticalAlignment = Alignment.Bottom) {
        for (step in 1..3) {
            Box(
                Modifier.size(width = 9.px, height = (12 + (step - 1) * 9).px).clip(RoundedCornerShape(3.px))
                    .background(if (step <= level.ordinal) fill else colors.text3.copy(alpha = .3f)),
            )
        }
    }
}
