package com.limelight.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import com.limelight.shell.ContextMenu
import com.limelight.shell.LocalShell
import com.limelight.shell.MenuItem
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.GameArt
import com.limelight.shell.ui.InitialFocus
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.PlaceholderArt
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.Unbounded
import com.limelight.shell.ui.focusEffects
import com.limelight.shell.ui.hsl
import com.limelight.shell.ui.px
import com.limelight.shell.ui.tpx

/** A game on the home screen. Placeholder data until the library is loaded from the PC (step 6.4). */
@Immutable
data class HomeGame(val art: GameArt, val title: String, val played: String, val tags: List<String>, val wide: Boolean = false, val sub: String? = null)

val PREVIEW_GAMES = listOf(
    HomeGame(GameArt("returnal", 176f, 150f, Symbols.PUBLIC), "Returnal", "Played yesterday", listOf("HDR", "AV1"), wide = true, sub = "Continue · Biome 3"),
    HomeGame(GameArt("animalwell", 300f, 330f, Symbols.EGG), "ANIMAL WELL", "Played 3 days ago", listOf("Controller")),
    HomeGame(GameArt("gris", 348f, 18f, Symbols.WATER_DROP), "GRIS", "Played last week", listOf("Controller")),
    HomeGame(GameArt("deathsdoor", 212f, 250f, Symbols.DOOR_FRONT), "Death's Door", "Played last week", listOf("Controller")),
    HomeGame(GameArt("davediver", 196f, 170f, Symbols.SCUBA_DIVING), "DAVE THE DIVER", "Played 2 weeks ago", listOf("Controller")),
)

fun previewGame(id: String?): HomeGame = PREVIEW_GAMES.firstOrNull { it.art.id == id } ?: PREVIEW_GAMES[0]

/**
 * Home screen preview of step 6.3 (shell): game info and one row of covers with the focus effects and
 * context menus. The real library, grid and row layouts come in step 6.4.
 */
@Composable
fun HomeScreen() {
    val shell = LocalShell.current
    val focused = previewGame(shell.homeGame)
    val requesters = remember { PREVIEW_GAMES.associate { it.art.id to FocusRequester() } }
    InitialFocus(requesters.getValue(focused.art.id))
    Column(Modifier.fillMaxSize().padding(start = 150.px, top = 116.px)) {
        GameInfo(focused)
        Row(
            Modifier.padding(top = 74.px).wrapContentWidth(Alignment.Start, unbounded = true),
            horizontalArrangement = Arrangement.spacedBy(28.px),
        ) {
            PREVIEW_GAMES.forEachIndexed { i, game ->
                Tile(game, badge = i == 0, modifier = Modifier.focusRequester(requesters.getValue(game.art.id))) { shell.homeGame = game.art.id }
            }
        }
    }
}

@Composable
private fun GameInfo(game: HomeGame) {
    val colors = LocalDelosColors.current
    BasicText(
        if (game == PREVIEW_GAMES[0]) "CONTINUE PLAYING" else "LIBRARY",
        style = TextStyle(color = colors.accent, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 20.tpx, letterSpacing = .14.em),
    )
    Row(Modifier.height(132.px).padding(top = 2.px), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(26.px)) {
        Symbol(game.art.icon, 104.tpx, hsl(game.art.hue2, 85f, 66f), filled = true, filledWeight = 500, opticalSize = 48f)
        val size = when {
            game.title.length > 22 -> 56
            game.title.length > 13 -> 68
            else -> 84
        }
        BasicText(
            game.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                brush = Brush.verticalGradient(.25f to colors.text, 1f to hsl(game.art.hue2, 70f, 58f)),
                fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = size.tpx, letterSpacing = (-.01).em,
            ),
        )
    }
    Row(Modifier.padding(top = 10.px), horizontalArrangement = Arrangement.spacedBy(12.px)) {
        Chip(Symbols.SCHEDULE, game.played)
        game.tags.forEach { tag ->
            Chip(
                when (tag) {
                    "HDR" -> Symbols.HDR_ON
                    "AV1" -> Symbols.HIGH_QUALITY
                    "Co-op" -> Symbols.GROUP
                    else -> Symbols.SPORTS_ESPORTS
                },
                tag,
            )
        }
    }
}

@Composable
private fun Chip(icon: String, label: String) {
    val colors = LocalDelosColors.current
    Row(
        Modifier
            .clip(CircleShape)
            .background(colors.surface)
            .border(1.px, colors.line, CircleShape)
            .padding(horizontal = 16.px, vertical = 6.px),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.px),
    ) {
        Symbol(icon, 22.tpx, colors.text2)
        BasicText(label, style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 19.tpx))
    }
}

@Composable
private fun Tile(game: HomeGame, badge: Boolean, modifier: Modifier = Modifier, onFocused: () -> Unit) {
    val colors = LocalDelosColors.current
    val shell = LocalShell.current
    val shape = RoundedCornerShape(26.px)
    val soon = { what: String -> shell.showToast("$what comes in a later step", Symbols.INFO) }
    val menu = ContextMenu(
        game.title,
        listOf(
            MenuItem(Symbols.PLAY_ARROW, "Start game") { soon("Streaming") },
            MenuItem(Symbols.DOWNLOAD, "Download covers") { soon("SteamGridDB") },
            MenuItem(Symbols.STAR, "Add to favorites") { soon("Favorites") },
            MenuItem(Symbols.VISIBILITY_OFF, "Hide game") { soon("Hiding games") },
            MenuItem(Symbols.INFO, "Game details") { soon("Game details") },
        ),
    )
    Box(
        modifier
            .focusEffects(
                onClick = { soon("Streaming") }, corner = 26f, sweep = true, dimWhenIdle = true, contextMenu = menu,
                onFocusChange = { if (it) onFocused() },
            )
            .size(if (game.wide) 963.px else 300.px, 450.px)
            .clip(shape)
            .background(colors.surface2),
    ) {
        PlaceholderArt(
            game.art.hue, game.art.hue2, game.art.icon,
            modifier = Modifier.fillMaxSize(),
            glyphSize = if (game.wide) 420f else 320f,
            glyphRight = if (game.wide) 40f else -36f,
            glyphTop = if (game.wide) .04f else .24f,
            title = game.title,
            titleSize = if (game.wide) 72f else 34f,
            titleInset = if (game.wide) 44f else 26f,
            titleBottom = if (game.wide) 40f else 26f,
            subtitle = if (game.wide) game.sub else null,
        )
        if (badge) {
            Row(
                Modifier
                    .padding(18.px)
                    .clip(CircleShape)
                    .background(Color(0x8C050A14))
                    .padding(horizontal = 14.px, vertical = 6.px),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.px),
            ) {
                Symbol(Symbols.HISTORY, 22.tpx, Color.White)
                BasicText("Last played", style = TextStyle(color = Color.White, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 18.tpx))
            }
        }
    }
}
