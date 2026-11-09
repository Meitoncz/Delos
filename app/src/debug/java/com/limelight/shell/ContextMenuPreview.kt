package com.limelight.shell

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.limelight.shell.ui.ContextMenuLayer
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.PlaceholderArt
import com.limelight.shell.ui.Scrim
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.Unbounded
import com.limelight.shell.ui.px
import com.limelight.shell.ui.tpx
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay

/**
 * Debug builds only: the real context menu over a drawn dashboard (a hero, the game tiles and the PC card), started with
 * `adb shell am start -n io.github.meitoncz.delos.debug/com.limelight.shell.ShellActivity --es preview_screen context_menu`
 * and optionally `--es preview_state <idle|running|other|options|long|pc>` (idle: a game, nothing runs; running: the game runs;
 * other: another game runs; options: the Options submenu is open; long: the 12 SteamGridDB matches that scroll; pc: the PC
 * menu below the PC card). The menu opens 700 ms after the start with its entrance and stays; a tap outside closes it. The items are the home
 * game menu's (`gameMenu` in `HomeScreen.kt`); Options shows the submenu mechanism, which no real menu uses yet. Handled by
 * `ShellActivity` before the shell (docs/GUI.md, "Context menu").
 */
internal object ContextMenuPreview {
    fun from(intent: Intent): (@Composable () -> Unit)? {
        if (intent.getStringExtra("preview_screen") != "context_menu") return null
        val state = intent.getStringExtra("preview_state") ?: "idle"
        return { Preview(state) }
    }

    private class Tile(val name: String, val hue: Float, val hue2: Float, val icon: String)

    private val TILES = listOf(
        Tile("ANIMAL WELL", 300f, 330f, Symbols.EGG), Tile("GRIS", 348f, 18f, Symbols.SPORTS_ESPORTS),
        Tile("Death's Door", 212f, 250f, Symbols.SPORTS_ESPORTS), Tile("DAVE THE DIVER", 176f, 200f, Symbols.SPORTS_ESPORTS),
        Tile("Cairn", 30f, 50f, Symbols.PUBLIC),
    )

    private fun buildMenu(state: String): ContextMenu {
        val tile = if (state == "running") Tile("Returnal", 176f, 150f, Symbols.PUBLIC) else TILES[0]
        val cover = com.limelight.shell.ui.hsl(tile.hue, 70f, 52f)
        val common = listOf(
            MenuItem(Symbols.PHOTO_LIBRARY, "Artwork") {}, MenuItem(Symbols.SEARCH, "Metadata") {},
            MenuItem(Symbols.SETTINGS, "Game settings") {}, MenuItem(Symbols.VISIBILITY_OFF, "Hide game") {},
        )
        return when (state) {
            "running" -> ContextMenu(
                "Returnal", header = MenuHeader("Running", cover, running = true, meta = "Custom settings"),
                items = listOf(MenuItem(Symbols.RESUME, "Resume") {}, MenuItem(Symbols.POWER_SETTINGS_NEW, "Quit game", danger = true) {}) + common,
            )
            "other" -> ContextMenu(
                "ANIMAL WELL", header = MenuHeader("Game", cover, meta = "Returnal is running"),
                items = listOf(MenuItem(Symbols.SWAP_HORIZ, "Quit Returnal and start", danger = true) {}, MenuItem(Symbols.RESUME, "Resume Returnal") {}) + common,
            )
            "options" -> ContextMenu(
                "ANIMAL WELL", header = MenuHeader("Game", cover, meta = "Last played"),
                items = listOf(
                    MenuItem(Symbols.PLAY_ARROW, "Start game") {}, MenuItem(Symbols.SETTINGS, "Game settings") {},
                    MenuItem(Symbols.MORE_HORIZ, "Options", children = listOf(common[0], common[1], common[3])),
                ),
            )
            "long" -> ContextMenu(
                "Choose the game",
                items = listOf(
                    "Elden Ring (2022)", "Elden Ring Nightreign (2025)", "Elden Ring: Shadow of the Erdtree (2024)", "Ring of Elysium (2018)",
                    "Dark Souls III (2016)", "Sekiro: Shadows Die Twice (2019)", "Armored Core VI: Fires of Rubicon (2023)", "Lies of P (2023)",
                    "Nioh 2 (2020)", "Hollow Knight (2017)", "Bloodborne (2015)", "Salt and Sanctuary (2016)",
                ).map { MenuItem(Symbols.SPORTS_ESPORTS, it) {} } + MenuItem(Symbols.EDIT, "Search for another name") {},
            )
            "pc" -> ContextMenu(
                "Meiton-PC", below = true,
                items = listOf(
                    MenuItem(Symbols.LOGIN, "Open its games") {}, MenuItem(Symbols.EDIT, "Rename") {},
                    MenuItem(Symbols.STAR, "Don't start with this PC") {}, MenuItem(Symbols.LINK_OFF, "Unpair") {},
                    MenuItem(Symbols.DELETE, "Remove from Delos", danger = true) {},
                ),
            )
            else -> ContextMenu(
                "ANIMAL WELL", header = MenuHeader("Game", cover, meta = "Last played"),
                items = listOf(MenuItem(Symbols.PLAY_ARROW, "Start game") {}) + common,
            )
        }
    }

    @Composable
    private fun Preview(state: String) {
        val colors = LocalDelosColors.current
        val k = LocalStage.current.dpPerPx * LocalDensity.current.density
        val haze = rememberHazeState()
        val menu = remember(state) { buildMenu(state) }
        var anchor by remember { mutableStateOf<Rect?>(null) }
        var open by remember { mutableStateOf(false) }
        var path by remember { mutableStateOf(if (state == "options") listOf(2) else emptyList()) }
        LaunchedEffect(anchor != null) {
            if (anchor == null) return@LaunchedEffect
            delay(700)
            open = true
        }
        CompositionLocalProvider(LocalFocusTracker provides remember { FocusTracker() }) {
            Box(Modifier.fillMaxSize().background(colors.bg)) {
                Box(Modifier.fillMaxSize().hazeSource(haze)) {
                    // The hero: the game's art, fading into the background color
                    PlaceholderArt(300f, 330f, Symbols.EGG, Modifier.fillMaxSize().graphicsLayer { alpha = .7f }, glyphSize = 1050f, glyphRight = 80f, glyphTop = -.14f)
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(0f to Color.Transparent, .42f to Color.Transparent, 1f to colors.bg.copy(alpha = .9f)),
                        ),
                    )
                    val dim = if (open) .4f else 1f
                    BasicText(
                        TILES[0].name,
                        style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 84.tpx),
                        modifier = Modifier.offset(150.px, 150.px).graphicsLayer { alpha = dim },
                    )
                    BasicText(
                        "LAST PLAYED", style = TextStyle(color = colors.accent, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 20.tpx),
                        modifier = Modifier.offset(150.px, 118.px).graphicsLayer { alpha = dim },
                    )
                    if (state == "pc") {
                        // The PC card the menu belongs to
                        Box(
                            Modifier.offset(150.px, 236.px).size(380.px, 220.px).onGloballyPositioned { anchor = it.boundsInRoot() }
                                .clip(RoundedCornerShape(36.px)).background(Color.White.copy(alpha = .08f)),
                        )
                    } else TILES.forEachIndexed { i, tile ->
                        val focused = i == 0
                        Box(
                            Modifier.offset((150 + i * 340).px, 470.px).size(300.px, 450.px)
                                .then(if (focused) Modifier.onGloballyPositioned { anchor = it.boundsInRoot() } else Modifier)
                                .graphicsLayer { if (focused) { scaleX = 1.055f; scaleY = 1.055f } }
                                .drawWithContent {
                                    drawContent()
                                    if (focused) {
                                        val ring = 5f * k
                                        drawRoundRect(colors.accent, topLeft = androidx.compose.ui.geometry.Offset(-ring * 1.5f, -ring * 1.5f), size = Size(size.width + ring * 3f, size.height + ring * 3f), cornerRadius = CornerRadius(26f * k + ring * 1.5f), style = Stroke(ring))
                                    } else drawRect(Color.Black.copy(alpha = if (open) .35f else .08f))
                                }
                                .clip(RoundedCornerShape(26.px)),
                        ) {
                            PlaceholderArt(
                                tile.hue, tile.hue2, tile.icon, Modifier.fillMaxSize(), title = tile.name, titleSize = 34f,
                            )
                        }
                    }
                }
                Scrim(open, light = true, haze) { open = false }
                ContextMenuLayer(menu.takeIf { open }, anchor, path, { path = it }, haze) { open = false }
            }
        }
    }
}
