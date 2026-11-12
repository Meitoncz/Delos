package com.limelight.shell.companion

import androidx.compose.runtime.Immutable
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.shell.data.Library
import com.limelight.shell.data.Pc

/*
 * The data and the pure decisions behind the PC pages of the companion (docs/DUAL_SCREEN.md, "PC and library pages"): what a PC card knows, which facts it
 * lists in which order, which touch buttons it offers for which status. No Compose and no resources here, so every rule is tested (CompanionPcPageTest);
 * [CompanionPcPage] draws the result and [PublishCompanionPc] hands it to the shell.
 */

/** How a PC is doing, as the PC screens of the main panel word it: online, asleep (offline but with a MAC address, so it can be woken) or offline. */
internal enum class PcStatus { ONLINE, ASLEEP, OFFLINE }

/** The streaming host software, as far as the host's own answers tell it. */
internal enum class HostSoftware { VIBEPOLLO, GEFORCE, SUNSHINE }

/** The screen a PC card is published for: it decides the buttons (what that screen's own menu offers, and the companion shortcuts). */
internal enum class PcContext { START, DEVICES, PAIR, HOME }

/**
 * What the card of one PC knows, taken from what the shell already has (the PC snapshot, the library of the active PC): nothing is queried for it.
 * What the app does not know stays null and is shown as unavailable (there is no version, GPU or last-seen timestamp in the app's PC data).
 */
@Immutable
internal data class PcCardData(
    val uuid: String,
    val name: String,
    /** The name the PC reports itself. */
    val serverName: String,
    val status: PcStatus,
    /** A MAC address is saved: Wake-on-LAN is possible. Independent of [status] so an offline PC with a MAC can be shown as offline. */
    val canWake: Boolean,
    /** Whether this is the default PC; null where the screen does not know (the library). */
    val isDefault: Boolean?,
    val connected: Boolean,
    val software: HostSoftware?,
    /** The number of applications the library of this PC has, null when the shell holds no library of it. */
    val apps: Int?,
    /** The count comes from the stored app list of a PC that does not answer now. */
    val appsCached: Boolean,
    /** The host says a game runs. */
    val busy: Boolean,
    /** The name of that game when the library knows it. */
    val runningGame: String?,
) {
    val online: Boolean get() = status == PcStatus.ONLINE
}

/** The facts of a PC card; the order of the declaration is not the order of display ([PcCard.facts]). */
internal enum class PcFactKind { FLAGS, HOST, VERSION, HOST_NAME, LIBRARY, SESSION, WAKE, LAST_SEEN }

/** The touch buttons of the first row. */
internal enum class PcActionKind { LIBRARY, WAKE, SEARCH, RENAME, DEFAULT_ON, DEFAULT_OFF }

/** The destructive buttons, set apart below a rule. */
internal enum class PcDangerKind { UNPAIR, REMOVE }

internal data class PcActionSet(val primary: List<PcActionKind>, val separated: List<PcDangerKind>)

internal object PcCard {
    fun status(pc: Pc): PcStatus = if (pc.online) PcStatus.ONLINE else if (pc.canWake) PcStatus.ASLEEP else PcStatus.OFFLINE

    /**
     * The host software from what the host's last /serverinfo said: GeForce Experience announces itself, a Vibepollo advertises its PyroWave fields to a
     * paired client, anything else that answered is Sunshine or built on it. Null for a PC that does not answer: nothing is asserted about it.
     */
    fun software(pc: Pc): HostSoftware? {
        if (!pc.online) return null
        val details: ComputerDetails = pc.details
        val pyro = details.pyroWave
        return when {
            details.nvidiaServer -> HostSoftware.GEFORCE
            pyro != null && (pyro.wireBudgetVersion > 0 || pyro.udpProbeVersion > 0 || pyro.udpHandshakeVersion > 0) -> HostSoftware.VIBEPOLLO
            else -> HostSoftware.SUNSHINE
        }
    }

    /** The card of [pc]; [library] is the library the shell holds (of the active PC), the source of the application count and the running game's name. */
    fun of(pc: Pc, defaultUuid: String?, library: Library, defaultKnown: Boolean = true): PcCardData {
        val ready = (library as? Library.Ready)?.takeIf { it.pc.uuid == pc.uuid }
        val libraryPc = when (library) {
            is Library.Ready -> library.pc
            is Library.Offline -> library.pc
            else -> null
        }
        return PcCardData(
            uuid = pc.uuid,
            name = pc.name,
            serverName = pc.serverName,
            status = status(pc),
            canWake = pc.canWake,
            isDefault = if (defaultKnown) pc.uuid == defaultUuid else null,
            connected = pc.online && libraryPc?.uuid == pc.uuid,
            software = software(pc),
            apps = ready?.apps?.size,
            appsCached = ready != null && !pc.online,
            busy = pc.online && pc.runningGameId != 0,
            runningGame = if (pc.online) ready?.apps?.firstOrNull { it.running }?.name else null,
        )
    }

    /**
     * The facts of the card in the order they are shown: the ones the app knows first (each group in its usual order), the ones it does not know after
     * them, so a short panel that cuts the list cuts what is "unavailable" before anything real. The host name only appears when the user named the PC
     * differently; the version and the last-seen time of an offline PC are never known to the app.
     */
    fun facts(d: PcCardData): List<PcFactKind> {
        val order = if (d.online) {
            listOf(PcFactKind.FLAGS, PcFactKind.HOST, PcFactKind.LIBRARY, PcFactKind.SESSION, PcFactKind.HOST_NAME, PcFactKind.LAST_SEEN, PcFactKind.VERSION)
        } else {
            listOf(PcFactKind.FLAGS, PcFactKind.LIBRARY, PcFactKind.WAKE, PcFactKind.HOST_NAME, PcFactKind.LAST_SEEN, PcFactKind.HOST, PcFactKind.SESSION, PcFactKind.VERSION)
        }
        return order.filter { it != PcFactKind.HOST_NAME || (d.serverName.isNotBlank() && d.serverName != d.name) }
            .sortedBy { !known(d, it) }
    }

    /** Whether the app has a value for [kind]; a fact it does not have is shown as unavailable. */
    fun known(d: PcCardData, kind: PcFactKind): Boolean = when (kind) {
        PcFactKind.FLAGS -> d.isDefault != null || d.connected
        PcFactKind.HOST_NAME, PcFactKind.WAKE -> true
        PcFactKind.HOST -> d.software != null
        PcFactKind.LIBRARY -> d.apps != null
        PcFactKind.SESSION, PcFactKind.LAST_SEEN -> d.online
        PcFactKind.VERSION -> false
    }

    /** The first [max] facts of [facts] that [fits] (one line in a column of the grid); a fact that does not fit is left out, never cut. */
    fun select(facts: List<PcFactKind>, max: Int, fits: (PcFactKind) -> Boolean): List<PcFactKind> = facts.filter(fits).take(max)

    /**
     * The buttons of a card. First row: the way into the PC (Library when it is online, Wake when it is offline with a MAC, else Search: no fake wake),
     * Rename and, where the main screen has a default PC, Set/Clear default. Set apart: Unpair only on Devices and only for an online PC, Remove on Start
     * and Devices (the companion's shortcut; the Start controller menu has no Remove). The library's offline card only has the way in and Rename.
     */
    fun actions(d: PcCardData, context: PcContext): PcActionSet {
        val main = if (d.online) PcActionKind.LIBRARY else if (d.canWake) PcActionKind.WAKE else PcActionKind.SEARCH
        val primary = buildList {
            add(main)
            add(PcActionKind.RENAME)
            if (context != PcContext.HOME) add(if (d.isDefault == true) PcActionKind.DEFAULT_OFF else PcActionKind.DEFAULT_ON)
        }
        val separated = when (context) {
            PcContext.START -> listOf(PcDangerKind.REMOVE)
            PcContext.DEVICES -> listOfNotNull(PcDangerKind.UNPAIR.takeIf { d.online }, PcDangerKind.REMOVE)
            PcContext.PAIR, PcContext.HOME -> emptyList()
        }
        return PcActionSet(primary, separated)
    }

    /**
     * How many buttons share a row: as many as [count] allows while the widest label (plus [padding] and the row's [gap]s) still fits one line of
     * every button in [available], else fewer; at least one. Labels are never cut with an ellipsis, a narrow panel gets a second row instead.
     */
    fun buttonsPerRow(widths: List<Float>, available: Float, gap: Float, padding: Float): Int {
        if (widths.isEmpty()) return 1
        val widest = widths.max() + padding
        for (n in widths.size downTo 2) {
            if ((available - gap * (n - 1)) / n >= widest) return n
        }
        return 1
    }

    /** The one hint of the main screen the card quotes (the first with a button letter) and how many more the main hint bar has. */
    fun mainHint(hints: List<CompanionHint>): Pair<CompanionHint, Int>? {
        val first = hints.firstOrNull { it.key != null } ?: return null
        return first to hints.size - 1
    }
}

/** The size of the digits of a pairing PIN: as large as the box allows by width and height, never wider than the box so they never wrap. */
internal object PinFit {
    /** The font size in px: [boxWidth] x [boxHeight] px is the room, the PIN measured [widthAtRef] px wide at the font size [ref] px, a line is [lineRatio] times the size. */
    fun fontSize(boxWidth: Float, boxHeight: Float, widthAtRef: Float, ref: Float, lineRatio: Float = 1.2f): Float {
        if (widthAtRef <= 0f || boxWidth <= 0f || boxHeight <= 0f) return 0f
        return minOf(boxWidth * ref / widthAtRef, boxHeight / lineRatio)
    }
}
