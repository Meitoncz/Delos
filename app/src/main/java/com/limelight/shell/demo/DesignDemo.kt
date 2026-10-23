package com.limelight.shell.demo

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import com.limelight.shell.settings.Appearance
import com.limelight.shell.settings.BackgroundKind
import com.limelight.shell.settings.Seed
import com.limelight.shell.ui.Backdrop
import com.limelight.shell.ui.Cue
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.GameArt
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalFeedback
import com.limelight.shell.ui.PlaceholderArt
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.Unbounded
import com.limelight.shell.ui.focusEffects
import com.limelight.shell.ui.hsl
import com.limelight.shell.ui.px
import com.limelight.shell.ui.tpx
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

private data class DemoGame(val art: GameArt, val title: String, val played: String, val tags: List<String>, val wide: Boolean = false, val sub: String? = null)

private val GAMES = listOf(
    DemoGame(GameArt("returnal", 176f, 150f, Symbols.PUBLIC), "Returnal", "Played yesterday", listOf("HDR", "AV1"), wide = true, sub = "Continue · Biome 3"),
    DemoGame(GameArt("animalwell", 300f, 330f, Symbols.EGG), "ANIMAL WELL", "Played 3 days ago", listOf("Controller")),
    DemoGame(GameArt("gris", 348f, 18f, Symbols.WATER_DROP), "GRIS", "Played last week", listOf("Controller")),
    DemoGame(GameArt("deathsdoor", 212f, 250f, Symbols.DOOR_FRONT), "Death's Door", "Played last week", listOf("Controller")),
    DemoGame(GameArt("davediver", 196f, 170f, Symbols.SCUBA_DIVING), "DAVE THE DIVER", "Played 2 weeks ago", listOf("Controller")),
)

/**
 * Step 6.2 test screen: the design system on one page (backgrounds, game background, colors, fonts,
 * icons, focus effects, sounds, backdrop blur), to compare with the prototype on the device.
 * Replaced by the real shell in step 6.3.
 */
@Composable
fun DesignDemo(appearance: Appearance, update: ((Appearance) -> Appearance) -> Unit) {
    val colors = LocalDelosColors.current
    val feedback = LocalFeedback.current
    var focusedGame by remember { mutableStateOf(GAMES[0]) }
    var overlay by remember { mutableStateOf(false) }
    val haze = rememberHazeState()
    val firstTile = remember { FocusRequester() }
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(Unit) {
        // Focus can only be requested once the window itself has focus
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        firstTile.requestFocus()
    }

    val overlayButton = remember { FocusRequester() }
    var overlayWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(overlay) {
        // Closing an overlay returns the focus to the element that opened it (as in the prototype)
        if (overlay) overlayWasOpen = true else if (overlayWasOpen) overlayButton.requestFocus()
    }
    BackHandler(enabled = overlay) {
        feedback.play(Cue.BACK)
        overlay = false
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().hazeSource(haze)) {
            Backdrop(appearance, colors, hero = focusedGame.art)
            Column(Modifier.padding(start = 150.px, top = 116.px)) {
                GameInfo(focusedGame)
                Row(Modifier.padding(top = 34.px), horizontalArrangement = Arrangement.spacedBy(14.px)) {
                    Pill(Symbols.ANIMATION, "Background · ${appearance.background.label}") {
                        update { it.copy(background = BackgroundKind.entries[(it.background.ordinal + 1) % BackgroundKind.entries.size]) }
                    }
                    Pill(Symbols.DARK_MODE, if (appearance.dark) "Dark" else "Light") { update { it.copy(dark = !it.dark) } }
                    Pill(Symbols.WALLPAPER, "Material You · ${if (appearance.materialYou) "On" else "Off"}") { update { it.copy(materialYou = !it.materialYou) } }
                    Pill(Symbols.PALETTE, "Colors · ${appearance.seed.label}") {
                        update { it.copy(seed = Seed.entries[(it.seed.ordinal + 1) % Seed.entries.size]) }
                    }
                }
                Row(Modifier.padding(top = 14.px), horizontalArrangement = Arrangement.spacedBy(14.px)) {
                    Pill(Symbols.IMAGE, "Game background · ${if (appearance.heroBackground) "On" else "Off"}") { update { it.copy(heroBackground = !it.heroBackground) } }
                    Pill(Symbols.BLUR_ON, "Blur · ${appearance.heroBlur} px") { update { it.copy(heroBlur = (it.heroBlur + 8) % 48) } }
                    Pill(Symbols.MUSIC_NOTE, "Sounds · ${if (appearance.sounds) "On" else "Off"}") { update { it.copy(sounds = !it.sounds) } }
                    Pill(Symbols.VIBRATION, "Haptics · ${if (appearance.haptics) "On" else "Off"}") { update { it.copy(haptics = !it.haptics) } }
                    Pill(Symbols.FILTER_NONE, "Overlay", modifier = Modifier.focusRequester(overlayButton), cue = Cue.OPEN) { overlay = true }
                }
                Row(
                    Modifier.padding(top = 50.px).wrapContentWidth(Alignment.Start, unbounded = true),
                    horizontalArrangement = Arrangement.spacedBy(28.px),
                ) {
                    GAMES.forEachIndexed { i, game ->
                        Tile(game, badge = i == 0, modifier = if (i == 0) Modifier.focusRequester(firstTile) else Modifier) { focusedGame = game }
                    }
                }
            }
        }

        AnimatedVisibility(overlay, enter = fadeIn(tween(450)), exit = fadeOut(tween(450))) {
            val blur = 24.px
            Box(
                Modifier
                    .fillMaxSize()
                    .hazeBlur(
                        HazeInput.Sources(haze),
                        style = HazeBlurStyle {
                            blurRadius(blur)
                            noiseFactor(0f)
                            colorEffects(listOf(HazeColorEffect.tint(colors.scrim.copy(alpha = .35f))))
                            fallbackColorEffect(HazeColorEffect.tint(colors.scrim.copy(alpha = .9f)))
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                OverlayCard { overlay = false }
            }
        }
    }
}

@Composable
private fun GameInfo(game: DemoGame) {
    val colors = LocalDelosColors.current
    BasicText(
        if (game == GAMES[0]) "CONTINUE PLAYING" else "LIBRARY",
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
private fun Pill(icon: String, label: String, modifier: Modifier = Modifier, cue: Cue = Cue.SELECT, onClick: () -> Unit) {
    val colors = LocalDelosColors.current
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .focusEffects(onClick = onClick, corner = 999f, focusScale = 1.06f, clickCue = cue, onFocusChange = { focused = it })
            .clip(CircleShape)
            .background(if (focused) colors.accent else colors.surface2)
            .height(66.px)
            .padding(horizontal = 26.px),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.px),
    ) {
        Symbol(icon, 30.tpx, if (focused) colors.onAccent else colors.text2, filled = focused)
        BasicText(label, style = TextStyle(color = if (focused) colors.onAccent else colors.text2, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 22.tpx))
    }
}

@Composable
private fun Tile(game: DemoGame, badge: Boolean, modifier: Modifier = Modifier, onFocused: () -> Unit) {
    val colors = LocalDelosColors.current
    val shape = RoundedCornerShape(26.px)
    Box(
        modifier
            .focusEffects(onClick = {}, corner = 26f, sweep = true, dimWhenIdle = true, onFocusChange = { if (it) onFocused() })
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

@Composable
private fun OverlayCard(onClose: () -> Unit) {
    val colors = LocalDelosColors.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        Modifier
            .width(760.px)
            .clip(RoundedCornerShape(40.px))
            .background(colors.surface3)
            .border(1.px, colors.line, RoundedCornerShape(40.px))
            .padding(52.px),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.px),
    ) {
        Symbol(Symbols.BLUR_ON, 100.tpx, colors.accent, filled = true, opticalSize = 48f)
        BasicText("Backdrop blur", style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 38.tpx))
        BasicText(
            "Everything behind this card is blurred live (Haze, Android 12+). Older Android versions get a dimmed layer instead.",
            style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 24.tpx, lineHeight = 1.4.em, textAlign = TextAlign.Center),
        )
        Pill(Symbols.CLOSE, "Close", modifier = Modifier.focusRequester(focus), cue = Cue.BACK, onClick = onClose)
    }
}
