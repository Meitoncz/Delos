package com.limelight.shell

import android.util.Log
import com.limelight.shell.companion.CodePage
import com.limelight.shell.companion.CompanionHint
import com.limelight.shell.companion.CompanionHostLine
import com.limelight.shell.companion.CompanionPage
import com.limelight.shell.companion.CompanionState
import com.limelight.shell.companion.HostSoftware
import com.limelight.shell.companion.LibraryNotice
import com.limelight.shell.companion.LibraryNoticePage
import com.limelight.shell.companion.PairHostPage
import com.limelight.shell.companion.PairResultPage
import com.limelight.shell.companion.PcCallbacks
import com.limelight.shell.companion.PcCardData
import com.limelight.shell.companion.PcContext
import com.limelight.shell.companion.PcPage
import com.limelight.shell.companion.PcStatus
import com.limelight.shell.companion.PinPage
import com.limelight.shell.companion.ShellContext
import com.limelight.shell.ui.Symbols

/**
 * Debug builds only: made-up companion states of the PC screens and the library states (`--es companion_fake <name>` / `--es preview_state <name>`):
 * `start_online`, `start_asleep`, `start_offline`, `start_offline_wake`, `devices`, `worst_pc`, `pair_list`, `pair_pin`, `pair_code`, `pair_result`,
 * `home_offline`, `home_empty`, `home_loading`. The buttons log what they would run (`adb logcat -s CompanionPreview`); nothing reaches a PC.
 */
internal object CompanionFakesPc {
    private fun log(what: String): () -> Unit = { Log.i("CompanionPreview", what) }

    private val calls = PcCallbacks(
        connect = log("connect (Library)"), wake = log("wake"), rescan = log("rescan (Search)"), rename = log("rename"),
        setDefault = log("setDefault"), clearDefault = log("setDefault(null)"), unpair = log("unpair"), remove = log("remove"),
    )

    private val online = PcCardData(
        uuid = "gaming-pc-7c2a", name = "GAMING-PC", serverName = "GAMING-PC", status = PcStatus.ONLINE, canWake = true, isDefault = true, connected = true,
        software = HostSoftware.VIBEPOLLO, apps = 48, appsCached = false, busy = true, runningGame = "Asterfall",
    )
    private val asleep = PcCardData(
        uuid = "living-room-pc-1f09", name = "LIVING-ROOM-PC", serverName = "LIVING-ROOM-PC", status = PcStatus.ASLEEP, canWake = true, isDefault = false, connected = false,
        software = null, apps = 31, appsCached = true, busy = false, runningGame = null,
    )
    private val offline = PcCardData(
        uuid = "attic-pc-93bd", name = "ATTIC-PC", serverName = "ATTIC-PC", status = PcStatus.OFFLINE, canWake = false, isDefault = false, connected = false,
        software = null, apps = null, appsCached = false, busy = false, runningGame = null,
    )
    private val worst = online.copy(
        uuid = "worst-pc-7c2a", name = "The extraordinarily long living-room gaming workstation for the entire household and all our shared adventures \u2014 primary desktop PC",
        serverName = "DESKTOP-9KQ2L7M",
    )

    private val startHints = listOf(CompanionHint("A", null, "Connect"), CompanionHint(null, Symbols.FILTER_NONE, "Menu"), CompanionHint(null, Symbols.MENU, "Options"))
    private val devicesHints = listOf(CompanionHint("A", null, "Select"), CompanionHint("B", null, "Back"), CompanionHint(null, Symbols.FILTER_NONE, "Menu"))
    private val pinHints = listOf(CompanionHint("B", null, "Cancel"))

    private fun shell(vararg pages: CompanionPage, title: String, purpose: String, hints: List<CompanionHint>, host: String? = "GAMING-PC"): CompanionState =
        CompanionState.Shell(
            host, pages.toList(),
            ShellContext(title, purpose, hints, host?.let { CompanionHostLine(it, "Online", "Asterfall") }, welcome = false),
        )

    private fun start(data: PcCardData) = shell(PcPage(data, PcContext.START, startHints, calls), title = "Start", purpose = "Choose the PC to play on.", hints = startHints, host = data.name.take(24))

    fun state(name: String): CompanionState? = when (name) {
        "start_online" -> start(online)
        "start_asleep" -> start(asleep)
        "start_offline" -> start(offline)
        "start_offline_wake" -> start(offline.copy(uuid = "attic-pc-wake-5e11", name = "ATTIC-PC", canWake = true, apps = 12, appsCached = true))
        "devices" -> shell(PcPage(online, PcContext.DEVICES, devicesHints, calls), title = "Devices", purpose = "The PCs paired with Delos.", hints = devicesHints)
        "worst_pc" -> shell(PcPage(worst, PcContext.DEVICES, devicesHints, calls), title = "Devices", purpose = "The PCs paired with Delos.", hints = devicesHints, host = "GAMING-PC")
        "pair_list" -> shell(
            PairHostPage("living-room-pc-1f09", "LIVING-ROOM-PC", online = true, software = HostSoftware.SUNSHINE, onPin = log("pair(pc, screen)"), onCode = log("pairWithCode(pc, screen)")),
            title = "Pair a PC", purpose = "Find a PC on your network and pair it.", hints = listOf(CompanionHint("A", null, "Select"), CompanionHint("B", null, "Back")), host = "LIVING-ROOM-PC",
        )
        "pair_pin" -> shell(PinPage("living-room-pc-1f09", "LIVING-ROOM-PC", "4826", log("cancel pairing")), title = "Pairing", purpose = "Confirm the pairing on your PC.", hints = pinHints, host = "LIVING-ROOM-PC")
        "pair_code" -> shell(CodePage("living-room-pc-1f09", "LIVING-ROOM-PC", pinHints), title = "Pairing", purpose = "Confirm the pairing on your PC.", hints = pinHints, host = "LIVING-ROOM-PC")
        "pair_result" -> shell(
            PairResultPage("living-room-pc-1f09", "LIVING-ROOM-PC", log("paired(uuid) (Open library)"), log("navigate(back) (Back to hosts)")),
            title = "Pairing", purpose = "Confirm the pairing on your PC.", hints = pinHints, host = "LIVING-ROOM-PC",
        )
        "home_offline" -> shell(
            PcPage(asleep, PcContext.HOME, emptyList(), calls), title = "Home", purpose = "Your games on the connected PC.", hints = listOf(CompanionHint("A", null, "Start")), host = "LIVING-ROOM-PC",
        )
        "home_empty" -> shell(
            LibraryNoticePage(LibraryNotice.EMPTY, "GAMING-PC", PcStatus.ONLINE, log("connect (Reconnect PC)"), log("navigate(Devices) (PC details)")),
            title = "Home", purpose = "Your games on the connected PC.", hints = listOf(CompanionHint("A", null, "Start")),
        )
        "home_loading" -> shell(
            LibraryNoticePage(LibraryNotice.LOADING, null, null, {}, log("navigate(Start) (Back to PCs)")),
            title = "Home", purpose = "Your games on the connected PC.", hints = listOf(CompanionHint("A", null, "Start")),
        )
        else -> null
    }
}
