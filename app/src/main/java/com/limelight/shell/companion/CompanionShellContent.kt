package com.limelight.shell.companion

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.Unbounded
import com.limelight.shell.ui.hsl
import com.limelight.shell.ui.px
import com.limelight.shell.ui.textResources
import com.limelight.shell.ui.tpx

/**
 * The game focused on the main panel as the companion shows it (home screen), with its details and the big buttons (docs/DUAL_SCREEN.md, "The game card").
 * Wrapped as a page ([GamePage]) so the window chooses between it, the settings help and the pages of the other screens ([chooseShellPage]).
 *
 * One card that never scrolls: the heading, the banner (the game's image, or its placeholder colors, with its name on it) that takes the height the
 * other blocks leave, the genre chips, one muted line, the three impact chips and the buttons ([CompanionGame.buttons], at least 64 px high). What
 * does not fit is cut by [GameFit]: genres are counted ("+3 genres"), the summary is cut at a sentence, and [FitColumn] drops the optional lines
 * before it would let anything overflow.
 */
internal data class GamePage(val game: CompanionGame) : CompanionPage {
    override val key: String get() = "game/${game.key}"
    override val priority: Int get() = PagePriority.GAME

    @Composable
    override fun Content() = GameDetails(game)
}

@Composable
private fun GameDetails(game: CompanionGame) {
    val colors = LocalDelosColors.current
    val res = textResources()
    PageCard { fit ->
        val b = fit.budget
        val state = stringResource(if (game.running) R.string.companion_game_running else R.string.companion_game_focused)
        val pc = game.pcName?.takeIf { it.isNotBlank() }
        // The name in the display face when it fits one line; else the state is the title and the PC the line under it (the banner has the whole name)
        val title = CompanionFit.title(game.name, state, b.innerWidth - HelpFit.HEADING_CHROME, fit.metrics)
        PageHeading(Symbols.PLAY_ARROW, title, if (title == game.name) listOfNotNull(state, pc).joinToString(" \u00b7 ") else pc)

        Banner(game, Modifier.fills(minHeight = BANNER_MIN.px))

        val facts = listOfNotNull(
            game.completionSeconds?.let { stringResource(R.string.home_chip_hltb, completionText(res, it)) },
            when (game.hdr) {
                NativeHdr.HDR -> stringResource(R.string.companion_hdr_native)
                NativeHdr.SDR -> "SDR" // l10n-ignore
                NativeHdr.UNKNOWN -> stringResource(R.string.metadata_hdr_unverified)
                null -> null
            },
        ).joinToString(" \u00b7 ")
        val text = GameFit.text(game.genres, game.summary, facts, b, fit.metrics) { res.getQuantityString(R.plurals.companion_genres_more, it, it) }
        if (text.genres.isNotEmpty()) ChipRow(text.genres, Modifier.optional(50))
        val running = game.otherRunning?.let { stringResource(R.string.companion_host_running, it) }?.takeIf { fit.metrics.lines(it, b.innerWidth) <= 1 }
        when {
            running != null -> BodyText(running, Modifier.optional(60), color = colors.accent2)
            text.body != null -> BodyText(text.body, Modifier.optional(40), color = colors.text2, maxLines = GameFit.PROSE_LINES)
        }

        ImpactChips(game.impact)

        WrappingActionRow(
            game.buttons.mapIndexed { i, button ->
                PageAction(
                    label = when (button) {
                        ShellButton.START -> stringResource(R.string.home_menu_start)
                        ShellButton.RESUME -> stringResource(R.string.home_menu_resume)
                        ShellButton.RESUME_OTHER -> stringResource(R.string.companion_game_resume_other)
                        ShellButton.QUIT_AND_START -> stringResource(R.string.companion_game_quit_start)
                        ShellButton.GAME_SETTINGS -> stringResource(R.string.home_menu_settings)
                    },
                    onClick = { game.onAction(button) },
                    style = when {
                        i == 0 -> ButtonStyle.PRIMARY
                        button == ShellButton.QUIT_AND_START -> ButtonStyle.DANGER
                        button == ShellButton.GAME_SETTINGS -> ButtonStyle.NORMAL
                        else -> ButtonStyle.SECONDARY
                    },
                )
            },
        )
    }
}

/** The banner's least height, in stage px: below it the page drops its optional lines first. */
private const val BANNER_MIN = 120

/** The banner: the game's art (else its placeholder colors) with a scrim, and the name in the display face on it, as large as the banner allows. */
@Composable
private fun Banner(game: CompanionGame, modifier: Modifier) {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    val shape = RoundedCornerShape(28.px)
    BoxWithConstraints(modifier.clip(shape).border(1.px, colors.line, shape).background(placeholder(game))) {
        game.image?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.Center, modifier = Modifier.fillMaxSize())
        }
        // Legibility: the bottom fades into the page color
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, .45f to Color.Transparent, 1f to colors.bg.copy(alpha = .92f))))
        val inset = BANNER_INSET
        val width = (maxWidth.value / stage.dpPerPx - 2 * inset) / stage.textScale
        val height = (maxHeight.value / stage.dpPerPx - 2 * inset) / stage.textScale
        val size = remember(game.name, width, height) { GameFit.nameSize(game.name, width, height) }
        BasicText(
            game.name,
            Modifier.align(Alignment.BottomStart).padding(horizontal = inset.px, vertical = (inset * .75f).px),
            style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = size.tpx, letterSpacing = (-.01).em, lineHeight = 1.15.em),
            maxLines = 2,
            overflow = TextOverflow.Clip,
        )
    }
}

private const val BANNER_INSET = 28f

/** The colors behind a game without art (the same hues the dashboard's placeholder uses). */
@Composable
private fun placeholder(game: CompanionGame): Brush =
    Brush.linearGradient(listOf(hsl(game.hue, 45f, 22f), hsl(game.hue2, 55f, 12f)))
