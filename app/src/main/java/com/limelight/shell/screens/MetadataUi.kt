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
import androidx.compose.ui.res.stringResource
import android.content.res.Resources
import androidx.annotation.StringRes
import com.limelight.R
import com.limelight.shell.data.MetadataProblem
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
        Symbol(Symbols.HDR_ON, 22.tpx, Color.White)
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
            BasicText(stringResource(R.string.metadata_filtered_title), style = TextStyle(color = colors.text, fontFamily = Figtree, fontSize = 38.tpx))
            BasicText(stringResource(R.string.metadata_filtered_note, "Y"), style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 24.tpx))
            SettingRow(
                Symbols.RESET, stringResource(R.string.metadata_filtered_reset), RowValue.Action { metadata.updateFilters(MetadataFilters()) },
                Modifier.focusRequester(first), compact = true,
            )
        }
    }
}

/** The sentence for why a game's information is missing or stale. */
internal fun MetadataProblem.text(res: Resources): String = res.getString(
    when (this) {
        MetadataProblem.UNAVAILABLE -> R.string.metadata_error_unavailable
        MetadataProblem.UPDATE_FAILED -> R.string.metadata_error_update
        MetadataProblem.PARTIAL -> R.string.metadata_error_partial
    }
)

/**
 * How long a game takes, rounded like HowLongToBeat's website: minutes below an hour, half hours up to 100 hours, whole hours
 * beyond ("45 Mins", "3½ Hours").
 */
internal fun completionText(res: Resources, seconds: Long): String {
    if (seconds < 3600) {
        val minutes = ((seconds + 30) / 60).toInt()
        return res.getQuantityString(R.plurals.metadata_hltb_minutes, minutes, minutes)
    }
    val halves = if (seconds >= 100 * 3600L) ((seconds + 1800) / 3600) * 2 else (seconds + 900) / 1800
    val hours = "${halves / 2}${if (halves % 2 == 1L) "\u00bd" else ""}"
    return res.getQuantityString(R.plurals.metadata_hltb_hours, ((halves + 1) / 2).toInt(), hours)
}

internal fun openMetadataMenu(res: Resources, scope: CoroutineScope, shell: ShellState, tracker: FocusTracker, store: GameMetadataStore, pc: Pc, app: App) {
    if (BundledArtwork.kind(app) != null) return
    fun operation(@StringRes success: Int, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                val state = store.entry(pc, app)
                val error = state.error
                // A finished refresh/reset that found no IGDB match is not a success.
                if (error == null && state.data?.candidate == null) {
                    shell.showToast(res.getString(R.string.metadata_no_match_toast), Symbols.SEARCH)
                } else {
                    shell.showToast(error?.text(res) ?: res.getString(success), if (error == null) Symbols.CHECK_CIRCLE else Symbols.ERROR)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                shell.showToast(res.getString(R.string.metadata_update_failed_toast), Symbols.ERROR)
            }
        }
    }
    shell.openLayer(
        Layer.Context(
            ContextMenu(res.getString(R.string.metadata_menu_title, app.name), listOf(
                MenuItem(Symbols.SEARCH, res.getString(R.string.metadata_menu_search)) { searchMetadata(res, scope, shell, tracker, store, pc, app, app.name) },
                MenuItem(Symbols.REFRESH, res.getString(R.string.metadata_menu_refresh)) { operation(R.string.metadata_refreshed_toast) { store.ensure(pc, app, force = true) } },
                MenuItem(Symbols.RESET, res.getString(R.string.metadata_menu_reset)) { operation(R.string.metadata_reset_toast) { store.reset(pc, app) } },
            )), tracker.bounds(),
        ), tracker,
    )
}

private fun searchMetadata(res: Resources, scope: CoroutineScope, shell: ShellState, tracker: FocusTracker, store: GameMetadataStore, pc: Pc, app: App, term: String) {
    scope.launch {
        shell.showToast(res.getString(R.string.metadata_searching_toast, term), Symbols.SEARCH)
        val found = try {
            store.search(term)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            shell.showToast(res.getString(R.string.metadata_search_unavailable_toast), Symbols.ERROR)
            return@launch
        }
        fun showPage(page: Int) {
        val items = found.drop(page * 6).take(6).map { candidate ->
            MenuItem(Symbols.SPORTS_ESPORTS, candidate.year?.let { res.getString(R.string.metadata_candidate_with_year, candidate.name, it) } ?: candidate.name) {
                scope.launch {
                    try {
                        store.select(pc, app, candidate)
                        val error = store.entry(pc, app).error
                        shell.showToast(error?.text(res) ?: res.getString(R.string.metadata_matched_toast, candidate.name), if (error == null) Symbols.CHECK_CIRCLE else Symbols.ERROR)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        shell.showToast(res.getString(R.string.metadata_match_save_failed_toast), Symbols.ERROR)
                    }
                }
            }
        } + buildList {
            if (page > 0) add(MenuItem(Symbols.ARROW_BACK, res.getString(R.string.metadata_matches_previous)) { showPage(page - 1) })
            if ((page + 1) * 6 < found.size) add(MenuItem(Symbols.ARROW_FORWARD, res.getString(R.string.metadata_matches_more)) { showPage(page + 1) })
        } + MenuItem(Symbols.SEARCH_NAME, res.getString(R.string.metadata_search_other)) {
            shell.openLayer(
                Layer.Dialog(DialogSpec(
                    res.getString(R.string.metadata_search_title), res.getString(R.string.metadata_search_note),
                    listOf(DialogField(Symbols.SEARCH, term, res.getString(R.string.metadata_search_hint), maxLength = 100)), res.getString(R.string.metadata_search_action),
                ) { values ->
                    val next = values.firstOrNull()?.trim().orEmpty()
                    if (next.isEmpty()) false else {
                        searchMetadata(res, scope, shell, tracker, store, pc, app, next)
                        true
                    }
                }), tracker,
            )
        }
        shell.openLayer(Layer.Context(ContextMenu(if (found.isEmpty()) res.getString(R.string.metadata_matches_none, term) else res.getString(R.string.metadata_matches_title), items), tracker.bounds()), tracker)
        }
        showPage(0)
    }
}
