package com.limelight.shell.companion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import com.limelight.R
import com.limelight.shell.LocalDeviceActions
import com.limelight.shell.LocalLibrary
import com.limelight.shell.LocalShell
import com.limelight.shell.Screen
import com.limelight.shell.data.Library
import com.limelight.shell.data.Pc
import java.util.concurrent.atomic.AtomicBoolean

/*
 * Publishers of the PC pages (CompanionPcPage.kt): called by the screens that own the data (Start, Devices, Pair a PC, the PIN screen, Home's library
 * states). Every page is remembered on the inputs that change what it shows, so it is only published again when one of them does; the callbacks it carries
 * run the same `DeviceActions` as the controller's menus, on the PC the page was made for.
 */

/** What the Pair a PC screen has focused, for its companion page. */
internal sealed interface PairFocus {
    data class Host(val uuid: String) : PairFocus
    data object Address : PairFocus
    data object Search : PairFocus
}

/** The hints of the main hint bar as the screen publishes them for the fallback (the very list the bar draws); empty before the shell has published them. */
@Composable
private fun mainHints(): List<CompanionHint> = LocalShell.current.companionContext?.hints.orEmpty()

/** The card of [pc] for [context]; null while no PC is focused. [defaultUuid] is the default PC of the profile. */
@Composable
private fun rememberPcPage(pc: Pc?, context: PcContext, defaultUuid: String?): PcPage? {
    val actions = LocalDeviceActions.current
    val library = LocalLibrary.current
    // The library's card is not about the main screen's A (Start): no hint line there
    val hints = if (context == PcContext.HOME) emptyList() else mainHints()
    val data = pc?.let { PcCard.of(it, defaultUuid, library, defaultKnown = context != PcContext.HOME) }
    val calls = remember(pc, actions) {
        pc?.let {
            PcCallbacks(
                connect = { actions.connect(it) },
                wake = { actions.wake(it) },
                rescan = { actions.rescan() },
                rename = { actions.rename(it) },
                setDefault = { actions.setDefault(it) },
                clearDefault = { actions.setDefault(null) },
                unpair = { actions.unpair(it) },
                remove = { actions.remove(it) },
            )
        }
    }
    return remember(data, calls, hints, context) { if (data == null || calls == null) null else PcPage(data, context, hints, calls) }
}

/** Start and Devices: the focused PC's card ([pc] null when nothing PC-like is focused: the welcome or fallback card shows then). */
@Composable
internal fun PublishCompanionPc(pc: Pc?, context: PcContext, defaultUuid: String?) {
    PublishCompanionPage(rememberPcPage(pc, context, defaultUuid))
}

/**
 * Pair a PC: a host that is not paired shows its status and the two ways to pair; a paired one the PC card; the address and search circles an
 * explanation ([focus] null before anything is focused: nothing is published).
 */
@Composable
internal fun PublishCompanionPairList(focus: PairFocus?, pcs: List<Pc>, screen: Screen.PairPc, defaultUuid: String?) {
    val actions = LocalDeviceActions.current
    val hints = mainHints()
    val host = (focus as? PairFocus.Host)?.let { f -> pcs.firstOrNull { it.uuid == f.uuid } }
    val pcPage = rememberPcPage(host?.takeIf { it.paired }, PcContext.PAIR, defaultUuid)
    val hostPage = remember(host, actions, screen) {
        host?.takeIf { !it.paired }?.let {
            PairHostPage(it.uuid, it.name, it.online, PcCard.software(it), onPin = { actions.pair(it, screen) }, onCode = { actions.pairWithCode(it, screen) })
        }
    }
    val enterAddress = stringResource(R.string.pairing_pc_address)
    val infoHints = remember(enterAddress) { listOf(CompanionHint("A", null, enterAddress)) }
    val infoPage = remember(focus, infoHints, actions) {
        when (focus) {
            PairFocus.Address -> PairInfoPage(PairInfo.ADDRESS, infoHints) { actions.rescan() }
            PairFocus.Search -> PairInfoPage(PairInfo.SEARCH, infoHints) { actions.rescan() }
            else -> null
        }
    }
    PublishCompanionPage(pcPage ?: hostPage ?: infoPage)
}

/**
 * The PIN screen: the PIN (or the one-time-code instruction), then the result once the PC accepted. [onCancel] is the screen's own cancel, [onOpen] what the
 * screen itself runs after its delay (the library of the paired PC); the page's buttons call these, never a pairing of their own, and no passphrase is
 * ever given to the page.
 */
@Composable
internal fun PublishCompanionPin(screen: Screen.Pin, pin: String, accepted: Boolean, onCancel: () -> Unit, onOpen: () -> Unit) {
    val shell = LocalShell.current
    val hints = mainHints()
    val cancel by rememberUpdatedState(onCancel)
    val open by rememberUpdatedState(onOpen)
    val oneTime = screen.otp != null
    val page = remember(screen.pcUuid, screen.pcName, pin, accepted, oneTime, hints, shell) {
        when {
            accepted -> PairResultPage(screen.pcUuid, screen.pcName, { open() }, { shell.navigate(screen.back) })
            oneTime -> CodePage(screen.pcUuid, screen.pcName, hints)
            else -> PinPage(screen.pcUuid, screen.pcName, pin) { cancel() }
        }
    }
    PublishCompanionPage(page)
}

/** Makes [action] run once however many times it is called: the PIN screen opens the library itself after a moment, a touch on the result must not do it twice. */
internal fun runOnce(action: () -> Unit): () -> Unit {
    val done = AtomicBoolean(false)
    return { if (done.compareAndSet(false, true)) action() }
}

/**
 * Home when there is no game to focus: an offline PC gets its card with Wake (or Search without a saved MAC) and Rename, an empty library "No applications"
 * with Reconnect and the PC's details, a library that is still being read says so with a way back, and no PC at all says that. A library with games
 * publishes nothing here (the focused game does; the filtered-out state belongs to the library filter).
 */
@Composable
internal fun PublishCompanionLibraryState(library: Library) {
    val shell = LocalShell.current
    val actions = LocalDeviceActions.current
    val card = rememberPcPage((library as? Library.Offline)?.pc, PcContext.HOME, null)
    val notice = remember(library.noticeKey(), shell, actions) {
        when (library) {
            Library.Loading -> LibraryNoticePage(LibraryNotice.LOADING, null, null, {}, { shell.navigate(Screen.Start) })
            Library.NoPc -> LibraryNoticePage(LibraryNotice.NO_PC, null, null, {}, { shell.navigate(Screen.Start) })
            is Library.Ready -> if (library.apps.isEmpty()) {
                LibraryNoticePage(LibraryNotice.EMPTY, library.pc.name, PcCard.status(library.pc), { actions.connect(library.pc) }, { shell.navigate(Screen.Devices) })
            } else null
            is Library.Offline -> null
        }
    }
    PublishCompanionPage(card ?: notice)
}

/** What decides the notice page of a library state: the state itself, and for an empty library the PC (a new snapshot of it makes new callbacks). */
private fun Library.noticeKey(): Any? = when (this) {
    is Library.Ready -> if (apps.isEmpty()) pc else null
    else -> this
}
