package com.limelight.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import android.content.res.Resources
import androidx.compose.ui.res.stringResource
import com.limelight.R
import com.limelight.shell.ui.textResources
import com.limelight.shell.ContextMenu
import com.limelight.shell.DeviceActions
import com.limelight.shell.Layer
import com.limelight.shell.LocalDeviceActions
import com.limelight.shell.LocalFocusTracker
import com.limelight.shell.LocalShell
import com.limelight.shell.MenuItem
import com.limelight.shell.Screen
import com.limelight.shell.data.Pc
import com.limelight.shell.data.ProfileStore
import com.limelight.shell.data.Profiles
import com.limelight.shell.settings.Startup
import com.limelight.shell.ui.AvatarBadge
import com.limelight.shell.ui.CenterColumn
import com.limelight.shell.ui.CircleItem
import com.limelight.shell.ui.Circles
import com.limelight.shell.ui.Cue
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.FocusGlow
import com.limelight.shell.ui.FocusRing
import com.limelight.shell.ui.Heading
import com.limelight.shell.ui.InitialFocus
import com.limelight.shell.ui.Compact
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.LocalStatusBarEdges
import com.limelight.shell.ui.StatusBarLayout
import com.limelight.shell.ui.compact
import com.limelight.shell.ui.placeBetween
import com.limelight.shell.ui.LocalFeedback
import com.limelight.shell.ui.OnlineGreen
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.focusEffects
import com.limelight.shell.ui.glass
import com.limelight.shell.ui.px
import com.limelight.shell.ui.tpx

/**
 * The start screen on every launch (GUI.md): the paired PCs in circles, the default one marked, and
 * the current profile in the top-left corner (switch or create one there).
 */
@Composable
fun StartScreen(profiles: Profiles, store: ProfileStore, pcs: List<Pc>, startup: Startup) {
    val res = textResources()
    val actions = LocalDeviceActions.current
    val shell = LocalShell.current
    val paired = pcs.filter { it.paired }.sortedBy { it.name.lowercase() }
    val focusPc = paired.firstOrNull { it.uuid == startup.defaultPc } ?: paired.firstOrNull { it.online } ?: paired.firstOrNull()
    val first = remember { FocusRequester() }
    InitialFocus(first, focusPc?.uuid)
    Box(Modifier.fillMaxSize()) {
        // A compact stage has no room for the chip on the middle next to the status icons: it keeps clear of them (CompactProfileChip)
        profiles.current?.let {
            if (LocalStage.current.compact) CompactProfileChip(profiles, store)
            else ProfileChip(profiles, store, Modifier.align(Alignment.TopCenter).padding(top = 44.px))
        }
        CenterColumn(gap = 48f) {
            val name = profiles.current?.name
            Heading(
                if (name != null) stringResource(R.string.devices_start_greeting, name) else stringResource(R.string.devices_start_welcome),
                stringResource(if (paired.isEmpty()) R.string.devices_start_pair_note else R.string.devices_start_choose_note),
            )
            Circles {
                // Every paired PC: the row scrolls when they do not fit (it used to stop at five)
                paired.forEach { pc ->
                    val default = pc.uuid == startup.defaultPc
                    CircleItem(
                        Symbols.DESKTOP_WINDOWS, pc.name,
                        onClick = { actions.connect(pc) },
                        modifier = if (pc == focusPc) Modifier.focusRequester(first) else Modifier,
                        meta = pcStatus(pc, default),
                        metaDot = if (pc.online) OnlineGreen else null,
                        big = true,
                        selected = default,
                        contextMenu = startMenu(res, pc, default, actions),
                    )
                }
                CircleItem(
                    Symbols.ADD_LINK, stringResource(R.string.devices_pair_new), { shell.navigate(Screen.PairPc(back = Screen.Start)) },
                    modifier = if (focusPc == null) Modifier.focusRequester(first) else Modifier,
                    meta = stringResource(R.string.devices_pair_new_meta), big = true,
                )
            }
        }
    }
}

/** The status line under a PC on the start screen: online, asleep or offline, and whether it is the default one. */
@Composable
private fun pcStatus(pc: Pc, default: Boolean): String {
    val status = stringResource(if (pc.online) R.string.devices_status_online else if (pc.canWake) R.string.devices_status_asleep_wakes else R.string.devices_status_offline)
    return if (default) stringResource(R.string.devices_status_with_default, status, stringResource(R.string.devices_status_default)) else status
}

private fun startMenu(res: Resources, pc: Pc, default: Boolean, actions: DeviceActions) = ContextMenu(
    pc.name,
    below = true,
    items = listOfNotNull(
        if (default) MenuItem(Symbols.STAR, res.getString(R.string.devices_menu_default_off)) { actions.setDefault(null) }
        else MenuItem(Symbols.STAR, res.getString(R.string.devices_menu_default_on)) { actions.setDefault(pc) },
        if (!pc.online && pc.canWake) MenuItem(Symbols.WAKE, res.getString(R.string.devices_menu_wake)) { actions.wake(pc) } else null,
        MenuItem(Symbols.EDIT, res.getString(R.string.devices_menu_rename)) { actions.rename(pc) },
    ),
)

/**
 * The profile chip on a compact stage: on the status bar's line, on the middle of the stage while that leaves 24 px to the status icons
 * (Wi-Fi, battery, clock: [LocalStatusBarEdges] says where they start), else in the middle of the free space left of them; a name too
 * long for that room is cut with an ellipsis. The same rule as the top navigation's ([placeBetween]).
 */
@Composable
private fun CompactProfileChip(profiles: Profiles, store: ProfileStore) {
    val stage = LocalStage.current
    val edges = LocalStatusBarEdges.current
    val start = (Compact.SIDE + stage.safe.left).px
    val fallbackEnd = (stage.stageWidth - Compact.SIDE - stage.safe.right - 360f).px
    val gap = StatusBarLayout.MIN_GAP.px
    val top = stage.safe.top.px
    // The bar is zoomed by the interface scale
    val bar = (Compact.STATUS_BAR * stage.textScale).px
    Layout({ ProfileChip(profiles, store, Modifier, shrink = true) }, Modifier.fillMaxSize()) { measurables, constraints ->
        val from = start.toPx()
        val to = (edges?.rightStartPx?.takeIf { it >= 0 }?.toFloat() ?: fallbackEnd.toPx()) - gap.toPx()
        val chip = measurables.first().measure(Constraints(maxWidth = (to - from).roundToInt().coerceAtLeast(0)))
        val x = placeBetween(from, to, chip.width.toFloat(), constraints.maxWidth / 2f) ?: from
        layout(constraints.maxWidth, constraints.maxHeight) { chip.place(x.roundToInt(), top.roundToPx() + (bar.roundToPx() - chip.height) / 2) }
    }
}

/** The current profile at the top of the start screen; A opens the profiles. [shrink]: a long name is cut to the width the chip gets. */
@Composable
private fun ProfileChip(profiles: Profiles, store: ProfileStore, modifier: Modifier, shrink: Boolean = false) {
    val res = com.limelight.shell.ui.textResources()
    val colors = LocalDelosColors.current
    val shell = LocalShell.current
    val tracker = LocalFocusTracker.current
    val feedback = LocalFeedback.current
    val profile = profiles.current ?: return
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .focusEffects(
                onClick = { openProfilesMenu(res, shell, tracker, feedback, profiles, store, Screen.Start) },
                corner = 999f, focusScale = 1.06f, ring = FocusRing(4f, 4f), glow = FocusGlow(14f, 40f, -12f),
                clickCue = null, onLookChange = { focused = it },
            )
            .clip(CircleShape)
            .glass(colors.surface)
            .border(1.px, colors.line, CircleShape)
            .height(68.px)
            .padding(start = 6.px, end = 26.px),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.px),
    ) {
        AvatarBadge(profile.look, 56f, 30f)
        BasicText(
            profile.name, style = TextStyle(color = colors.text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 24.tpx),
            maxLines = if (shrink) 1 else Int.MAX_VALUE, overflow = if (shrink) TextOverflow.Ellipsis else TextOverflow.Clip,
            modifier = if (shrink) Modifier.weight(1f, fill = false) else Modifier,
        )
        Symbol(Symbols.EXPAND_MORE, 28.tpx, if (focused) colors.accent else colors.text3)
    }
}

/**
 * `#scr-devices`: the paired PCs (the one home shows marked), each with its menu: connect, rename
 * (only in Delos), wake, unpair, remove; and pairing a new one.
 */
@Composable
fun DevicesScreen(pcs: List<Pc>, activeUuid: String?) {
    val res = textResources()
    val actions = LocalDeviceActions.current
    val shell = LocalShell.current
    val tracker = LocalFocusTracker.current
    val feedback = LocalFeedback.current
    val paired = pcs.filter { it.paired }.sortedBy { it.name.lowercase() }
    val first = remember { FocusRequester() }
    InitialFocus(first)
    CenterColumn(gap = 48f) {
        Heading(stringResource(R.string.devices_title), stringResource(R.string.devices_note))
        Circles {
            paired.forEachIndexed { i, pc ->
                val active = pc.uuid == activeUuid
                val menu = deviceMenu(res, pc, active, actions)
                CircleItem(
                    Symbols.DESKTOP_WINDOWS, pc.name,
                    onClick = {
                        feedback.play(Cue.OPEN)
                        shell.openLayer(Layer.Context(menu, tracker.bounds()), tracker)
                    },
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    meta = when {
                        active && pc.online -> stringResource(R.string.devices_status_connected)
                        pc.online -> stringResource(R.string.devices_status_online)
                        pc.canWake -> stringResource(R.string.devices_status_asleep)
                        else -> stringResource(R.string.devices_status_offline)
                    },
                    metaDot = if (pc.online) OnlineGreen else null,
                    big = true,
                    selected = active,
                )
            }
            CircleItem(
                Symbols.ADD_LINK, stringResource(R.string.devices_pair_new), { shell.navigate(Screen.PairPc(back = Screen.Devices)) },
                modifier = if (paired.isEmpty()) Modifier.focusRequester(first) else Modifier,
                meta = stringResource(R.string.devices_pair_new_meta), big = true,
            )
        }
    }
}

private fun deviceMenu(res: Resources, pc: Pc, active: Boolean, actions: DeviceActions) = ContextMenu(
    pc.name,
    below = true,
    items = listOfNotNull(
        MenuItem(Symbols.LOGIN, res.getString(if (active) R.string.devices_menu_open_games else R.string.devices_menu_connect)) { actions.connect(pc) },
        MenuItem(Symbols.EDIT, res.getString(R.string.devices_menu_rename)) { actions.rename(pc) },
        if (!pc.online && pc.canWake) MenuItem(Symbols.WAKE, res.getString(R.string.devices_menu_wake)) { actions.wake(pc) } else null,
        if (pc.online) MenuItem(Symbols.LINK_OFF, res.getString(R.string.devices_menu_unpair)) { actions.unpair(pc) } else null,
        MenuItem(Symbols.DELETE, res.getString(R.string.devices_menu_remove), danger = true) { actions.remove(pc) },
    ),
)
