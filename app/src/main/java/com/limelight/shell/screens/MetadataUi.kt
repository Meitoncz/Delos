package com.limelight.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import com.limelight.shell.ContextMenu
import com.limelight.shell.DialogField
import com.limelight.shell.DialogSpec
import com.limelight.shell.FocusTracker
import com.limelight.shell.Layer
import com.limelight.shell.LocalShell
import com.limelight.shell.MenuItem
import com.limelight.shell.ShellState
import com.limelight.shell.data.App
import com.limelight.shell.data.BundledArtwork
import com.limelight.shell.data.GameMetadataStore
import com.limelight.shell.data.LocalGameMetadata
import com.limelight.shell.data.MetadataFilters
import com.limelight.shell.data.Pc
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.RowValue
import com.limelight.shell.ui.SettingRow
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.Symbols
import androidx.compose.foundation.layout.widthIn
import com.limelight.shell.ui.px
import com.limelight.shell.ui.tpx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The native HDR badge's gradient stops: cyan to indigo to fuchsia, the middle one also tints the glow. */
private val HdrCyan = Color(0xFF22D3EE)
private val HdrIndigo = Color(0xFF6366F1)
private val HdrFuchsia = Color(0xFFD946EF)

/** A light dark layer under the gradient, so the white text keeps its contrast on the cyan end. */
private const val HDR_SCRIM = .12f

@Composable
internal fun NativeHdrChip() {
    val k = LocalStage.current.dpPerPx * LocalDensity.current.density
    val gradient = remember { Brush.linearGradient(listOf(HdrCyan, HdrIndigo, HdrFuchsia)) }
    Row(
        Modifier
            // A very soft glow in the gradient's middle color, behind the pill
            .dropShadow(CircleShape) {
                radius = 8f * k
                color = HdrIndigo
                alpha = .35f
            }
            .clip(CircleShape).background(gradient).background(Color.Black.copy(alpha = HDR_SCRIM))
            .border(1.px, Color.White.copy(alpha = .35f), CircleShape).padding(horizontal = 16.px, vertical = 6.px),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.px),
    ) {
        Symbol(Symbols.ANIMATION, 22.tpx, Color.White)
        BasicText("HDR", style = TextStyle(color = Color.White, fontFamily = Figtree, fontSize = 19.tpx))
    }
}

@Composable
internal fun FilteredLibraryEmpty() {
    val metadata = LocalGameMetadata.current
    val shell = LocalShell.current
    val colors = LocalDelosColors.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { shell.hero = null }
    LaunchedEffect(shell.layer) {
        if (shell.layer == null) first.requestFocus()
    }
    DisposableEffect(shell) {
        shell.restoreScreenFocus = { first.requestFocus() }
        onDispose { shell.restoreScreenFocus = null }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 900.px), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.px)) {
            BasicText("No games match these filters", style = TextStyle(color = colors.text, fontFamily = Figtree, fontSize = 38.tpx))
            BasicText("Press Y to change the filters, or reset them below.", style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 24.tpx))
            SettingRow(
                Symbols.REFRESH, "Reset library filters", RowValue.Action { metadata.updateFilters(MetadataFilters()) },
                Modifier.focusRequester(first), compact = true,
            )
        }
    }
}

internal fun openMetadataMenu(scope: CoroutineScope, shell: ShellState, tracker: FocusTracker, store: GameMetadataStore, pc: Pc, app: App) {
    if (BundledArtwork.kind(app) != null) return
    fun operation(success: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                val state = store.entry(pc, app)
                val error = state.error
                // A finished refresh/reset that found no IGDB match is not a success.
                if (error == null && state.data?.candidate == null) {
                    shell.showToast("No IGDB match found. Use Search IGDB / change match.", Symbols.SEARCH)
                } else {
                    shell.showToast(error ?: success, if (error == null) Symbols.CHECK_CIRCLE else Symbols.ERROR)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                shell.showToast("Metadata could not be updated", Symbols.ERROR)
            }
        }
    }
    shell.openLayer(
        Layer.Context(
            ContextMenu("Metadata · ${app.name}", listOf(
                MenuItem(Symbols.SEARCH, "Search IGDB / change match") { searchMetadata(scope, shell, tracker, store, pc, app, app.name) },
                MenuItem(Symbols.REFRESH, "Refresh metadata") { operation("Metadata refreshed") { store.ensure(pc, app, force = true) } },
                MenuItem(Symbols.REFRESH, "Reset metadata match") { operation("Metadata match reset") { store.reset(pc, app) } },
            )), tracker.bounds(),
        ), tracker,
    )
}

private fun searchMetadata(scope: CoroutineScope, shell: ShellState, tracker: FocusTracker, store: GameMetadataStore, pc: Pc, app: App, term: String) {
    scope.launch {
        shell.showToast("Searching IGDB for $term…", Symbols.SEARCH)
        val found = try {
            store.search(term)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            shell.showToast("IGDB search is unavailable", Symbols.ERROR)
            return@launch
        }
        fun showPage(page: Int) {
        val items = found.drop(page * 6).take(6).map { candidate ->
            MenuItem(Symbols.SPORTS_ESPORTS, candidate.name + (candidate.year?.let { " ($it)" } ?: "")) {
                scope.launch {
                    try {
                        store.select(pc, app, candidate)
                        val error = store.entry(pc, app).error
                        shell.showToast(error ?: "Metadata matched with ${candidate.name}", if (error == null) Symbols.CHECK_CIRCLE else Symbols.ERROR)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        shell.showToast("Metadata match could not be saved", Symbols.ERROR)
                    }
                }
            }
        } + buildList {
            if (page > 0) add(MenuItem(Symbols.SEARCH, "Previous matches") { showPage(page - 1) })
            if ((page + 1) * 6 < found.size) add(MenuItem(Symbols.SEARCH, "More matches") { showPage(page + 1) })
        } + MenuItem(Symbols.EDIT, "Search for another name") {
            shell.openLayer(
                Layer.Dialog(DialogSpec(
                    "Search IGDB", "Choose metadata independently of SteamGridDB artwork.",
                    listOf(DialogField(Symbols.SEARCH, term, "Game name", maxLength = 100)), "Search",
                ) { values ->
                    val next = values.firstOrNull()?.trim().orEmpty()
                    if (next.isEmpty()) false else {
                        searchMetadata(scope, shell, tracker, store, pc, app, next)
                        true
                    }
                }), tracker,
            )
        }
        shell.openLayer(Layer.Context(ContextMenu(if (found.isEmpty()) "No IGDB match for $term" else "Choose metadata match", items), tracker.bounds()), tracker)
        }
        showPage(0)
    }
}
