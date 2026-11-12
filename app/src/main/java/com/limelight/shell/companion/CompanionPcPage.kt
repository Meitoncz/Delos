package com.limelight.shell.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import com.limelight.R
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.Unbounded
import com.limelight.shell.ui.px

/*
 * The pages of the PC screens and of the library states (docs/DUAL_SCREEN.md, "PC and library pages"): the focused PC as a card with its facts and its
 * touch buttons, the pairing steps (the PIN in huge digits), the empty and loading library. Every page is immutable data plus owner-side callbacks;
 * the buttons run the same `DeviceActions` the controller's menus run. Budgets and the no-scroll net come from CompanionFit / FitColumn.
 */

/** What the buttons of a PC card run, owner side: the same `DeviceActions` methods as the controller's `startMenu` / `deviceMenu`, bound to this PC. */
@Immutable
internal class PcCallbacks(
    val connect: () -> Unit,
    val wake: () -> Unit,
    val rescan: () -> Unit,
    val rename: () -> Unit,
    val setDefault: () -> Unit,
    val clearDefault: () -> Unit,
    val unpair: () -> Unit,
    val remove: () -> Unit,
)

/** The status of a PC as a word, as the PC screens of the main panel say it. */
@Composable
private fun statusLabel(status: PcStatus): String = stringResource(
    when (status) {
        PcStatus.ONLINE -> R.string.devices_status_online
        PcStatus.ASLEEP -> R.string.devices_status_asleep_wakes
        PcStatus.OFFLINE -> R.string.devices_status_offline
    }
)

/** [full] when it fits one line of [width] stage px, else the [short] form. */
private fun oneLine(full: String, short: String, width: Float, fit: FitContext): String = if (fit.metrics.width(full) <= width) full else short

/** The title of a card: the PC's name when it fits one line, else the authored alias "PC · 7C2A" (the heading's disc takes 80 px). */
@Composable
private fun pcTitle(name: String, uuid: String, fit: FitContext): String =
    CompanionFit.title(name, CompanionFit.alias(stringResource(R.string.pc_alias_prefix), uuid), fit.budget.innerWidth - HEADING_DISC, fit.metrics)

private const val HEADING_DISC = 80f

/** The card of one PC: name, status, facts, the buttons for its status, the destructive ones apart, and the hint of the main screen. */
internal data class PcPage(val data: PcCardData, val context: PcContext, val hints: List<CompanionHint>, val calls: PcCallbacks) : CompanionPage {
    override val key: String get() = "pc/${data.uuid}"

    @Composable
    override fun Content() {
        val colors = LocalDelosColors.current
        val status = statusLabel(data.status)
        val eyebrow = stringResource(
            when (context) {
                PcContext.DEVICES -> R.string.pc_eyebrow_devices
                PcContext.HOME -> R.string.pc_eyebrow_library
                else -> R.string.pc_eyebrow_focused
            },
            status,
        )
        val facts = pcFacts(data)
        val set = PcCard.actions(data, context)
        val primary = set.primary.map { primaryAction(it) }
        val separated = set.separated.map { dangerAction(it) }
        val noMac = !data.online && !data.canWake
        val noMacFull = stringResource(R.string.pc_no_mac_note)
        val noMacShort = stringResource(R.string.pc_no_mac_short)
        val seeMain = stringResource(R.string.companion_see_main)
        val hintLine = PcCard.mainHint(hints)
        val hintText = hintLine?.let { (hint, rest) ->
            val bare = stringResource(R.string.pc_main_hint, hint.key.orEmpty(), hint.label)
            if (rest > 0) pluralStringResource(R.plurals.pc_main_hint_more, rest, hint.key.orEmpty(), hint.label, rest) to bare else bare to bare
        }
        PageCard { fit ->
            val b = fit.budget
            val width = b.innerWidth
            PageHeading(Symbols.DESKTOP_WINDOWS, pcTitle(data.name, data.uuid, fit), oneLine(eyebrow, status, width - HEADING_DISC, fit))
            val colWidth = (width - 24f) / 2f
            val shown = PcCard.select(facts.map { it.first }, b.facts) { kind -> facts.first { it.first == kind }.let { (_, texts) -> fit.metrics.width(texts.second) <= colWidth } }
            if (shown.isNotEmpty()) FactsGrid(shown.map { kind -> facts.first { it.first == kind }.second }, b.roomy, Modifier.optional(40))
            Filler()
            if (noMac) BodyText(CompanionFit.summary(noMacFull, noMacShort, b.bodyLines, width, fit.metrics, seeMain), Modifier.optional(55), color = colors.text2)
            val perRow = PcCard.buttonsPerRow(primary.map { fit.metrics.width(it.label) * LABEL_SLACK }, width, BUTTON_GAP, BUTTON_PADDING)
            primary.chunked(perRow).forEach { ActionRow(it) }
            if (separated.isNotEmpty()) {
                val together = separated.sumOf { (fit.metrics.width(it.label) * LABEL_SLACK + BUTTON_PADDING).toDouble() } + BUTTON_GAP * (separated.size - 1) <= width
                if (together) SeparatedActions(separated)
                else {
                    SeparatedActions(separated.take(1))
                    ActionRow(separated.drop(1))
                }
            }
            hintText?.let { (long, short) -> BodyText(oneLine(long, short, width, fit), Modifier.optional(30), color = colors.text3, maxLines = 1) }
        }
    }

    @Composable
    private fun primaryAction(kind: PcActionKind): PageAction = when (kind) {
        PcActionKind.LIBRARY -> PageAction(stringResource(R.string.pc_action_library), calls.connect, ButtonStyle.PRIMARY)
        PcActionKind.WAKE -> PageAction(stringResource(R.string.pc_action_wake), calls.wake, ButtonStyle.PRIMARY)
        PcActionKind.SEARCH -> PageAction(stringResource(R.string.pc_action_search), calls.rescan, ButtonStyle.PRIMARY)
        PcActionKind.RENAME -> PageAction(stringResource(R.string.devices_menu_rename), calls.rename)
        PcActionKind.DEFAULT_ON -> PageAction(stringResource(R.string.pc_action_default_on), calls.setDefault)
        PcActionKind.DEFAULT_OFF -> PageAction(stringResource(R.string.pc_action_default_off), calls.clearDefault)
    }

    @Composable
    private fun dangerAction(kind: PcDangerKind): PageAction = when (kind) {
        PcDangerKind.UNPAIR -> PageAction(stringResource(R.string.devices_menu_unpair), calls.unpair, ButtonStyle.DANGER)
        PcDangerKind.REMOVE -> PageAction(stringResource(R.string.pc_action_remove), calls.remove, ButtonStyle.DANGER)
    }
}

/** The side padding of a button of the companion (22 px each side) and the gap between buttons of a row; [LABEL_SLACK] covers the semi-bold face being wider than the measured one. */
private const val BUTTON_PADDING = 44f
private const val BUTTON_GAP = 8f
private const val LABEL_SLACK = 1.12f

/** Label and value of every fact of [d], in display order ([PcCard.facts]). */
@Composable
private fun pcFacts(d: PcCardData): List<Pair<PcFactKind, Pair<String, String>>> {
    return PcCard.facts(d).map { kind ->
        kind to when (kind) {
            PcFactKind.FLAGS -> stringResource(R.string.pc_fact_flags) to when {
                d.isDefault == null && !d.connected -> stringResource(R.string.pc_unknown_flags)
                d.isDefault == true && d.connected -> stringResource(R.string.devices_status_with_default, stringResource(R.string.devices_status_connected), stringResource(R.string.devices_status_default))
                d.connected -> stringResource(R.string.devices_status_connected)
                d.isDefault == true -> stringResource(R.string.pc_flags_default)
                else -> stringResource(R.string.pc_flags_none)
            }
            PcFactKind.HOST -> stringResource(R.string.pc_fact_host) to (d.software?.let { stringResource(softwareName(it)) } ?: stringResource(R.string.pc_unknown_host))
            PcFactKind.VERSION -> stringResource(R.string.pc_fact_version) to stringResource(R.string.pc_unknown_version)
            PcFactKind.HOST_NAME -> stringResource(R.string.pc_fact_host_name) to d.serverName
            PcFactKind.LIBRARY -> stringResource(R.string.pc_fact_library) to when {
                d.apps == null -> stringResource(R.string.pc_unknown_library)
                d.appsCached -> pluralStringResource(R.plurals.pc_apps_cached, d.apps, d.apps)
                else -> pluralStringResource(R.plurals.pc_apps, d.apps, d.apps)
            }
            PcFactKind.SESSION -> stringResource(R.string.pc_fact_session) to when {
                !d.online -> stringResource(R.string.pc_unknown_session)
                d.runningGame != null -> stringResource(R.string.companion_host_running, d.runningGame)
                d.busy -> stringResource(R.string.pc_session_busy)
                else -> stringResource(R.string.pc_session_idle)
            }
            PcFactKind.WAKE -> stringResource(R.string.pc_fact_wake) to stringResource(if (d.canWake) R.string.pc_wake_saved else R.string.pc_wake_none)
            PcFactKind.LAST_SEEN -> stringResource(R.string.pc_fact_last_seen) to (if (d.online) stringResource(R.string.pc_value_now) else stringResource(R.string.pc_unknown_last_seen))
        }
    }
}

private fun softwareName(software: HostSoftware): Int = when (software) {
    HostSoftware.VIBEPOLLO -> R.string.pc_host_vibepollo
    HostSoftware.GEFORCE -> R.string.pc_host_geforce
    HostSoftware.SUNSHINE -> R.string.pc_host_sunshine
}

/** The host that was found on the Pair a PC screen and is not paired: its status and what is known of its software, and the two ways to pair. */
internal data class PairHostPage(
    val uuid: String,
    val name: String,
    val online: Boolean,
    val software: HostSoftware?,
    val onPin: () -> Unit,
    val onCode: () -> Unit,
) : CompanionPage {
    override val key: String get() = "pair/host/$uuid"

    @Composable
    override fun Content() {
        val colors = LocalDelosColors.current
        val softwareLine = software?.let { stringResource(R.string.pair_host_software, stringResource(softwareName(it))) }
        PageCard { fit ->
            PageHeading(Symbols.ADD_LINK, pcTitle(name, uuid, fit), oneLine(stringResource(R.string.pair_eyebrow_host), stringResource(R.string.pairing_pc_title), fit.budget.innerWidth - HEADING_DISC, fit))
            BodyText(stringResource(if (online) R.string.pair_host_online else R.string.pair_host_offline))
            if (softwareLine != null) BodyText(softwareLine, Modifier.optional(50), color = colors.text2)
            Filler()
            val pin = stringResource(R.string.pairing_pc_with_pin)
            val code = stringResource(R.string.pair_action_code)
            val perRow = PcCard.buttonsPerRow(listOf(pin, code).map { fit.metrics.width(it) * LABEL_SLACK }, fit.budget.innerWidth, BUTTON_GAP, BUTTON_PADDING)
            listOf(PageAction(pin, onPin, ButtonStyle.PRIMARY), PageAction(code, onCode)).chunked(perRow).forEach { ActionRow(it) }
        }
    }
}

/** What the "Enter address" and "Search again" circles of the Pair a PC screen do, for the companion's explanation page. */
internal enum class PairInfo { ADDRESS, SEARCH }

internal data class PairInfoPage(val info: PairInfo, val hints: List<CompanionHint>, val onSearch: () -> Unit) : CompanionPage {
    override val key: String get() = "pair/info/$info"

    @Composable
    override fun Content() {
        val colors = LocalDelosColors.current
        val seeMain = stringResource(R.string.companion_see_main)
        PageCard { fit ->
            val b = fit.budget
            val address = info == PairInfo.ADDRESS
            PageHeading(
                if (address) Symbols.ADD_ADDRESS else Symbols.REFRESH,
                stringResource(if (address) R.string.pairing_pc_address else R.string.pairing_pc_search_again),
                stringResource(R.string.pairing_pc_title),
            )
            val full = stringResource(if (address) R.string.pair_address_note else R.string.pair_search_note)
            val short = if (address) stringResource(R.string.pair_address_short) else full
            BodyText(CompanionFit.summary(full, short, b.bodyLines, b.innerWidth, fit.metrics, seeMain), color = colors.text2)
            Filler()
            if (address) {
                val (shown, rest) = CompanionFit.top(hints, 1)
                if (shown.isNotEmpty()) HintList(shown, rest, Modifier.optional(50))
            } else {
                ActionRow(listOf(PageAction(stringResource(R.string.pairing_pc_search_again), onSearch, ButtonStyle.PRIMARY, Symbols.REFRESH)))
            }
        }
    }
}

/**
 * The PIN of a pairing, in digits as large as the card allows (readable across a desk), the instruction and the PC's name; Cancel runs the screen's own
 * cancel. The PIN is what the screen itself shows; it is on the page only while the pairing screen is, and no passphrase ever is.
 */
internal data class PinPage(val uuid: String, val pcName: String, val pin: String, val onCancel: () -> Unit) : CompanionPage {
    override val key: String get() = "pair/pin/$uuid"

    @Composable
    override fun Content() {
        val colors = LocalDelosColors.current
        val seeMain = stringResource(R.string.companion_see_main)
        val note = stringResource(R.string.pairing_pin_note)
        val noteShort = stringResource(R.string.pair_pin_short)
        val alias = CompanionFit.alias(stringResource(R.string.pc_alias_prefix), uuid)
        PageCard { fit ->
            val b = fit.budget
            val title = CompanionFit.title(stringResource(R.string.pair_pin_title, pcName), stringResource(R.string.pair_pin_title, alias), b.innerWidth - HEADING_DISC, fit.metrics)
            PageHeading(Symbols.DIALPAD, title, stringResource(R.string.pair_pin_eyebrow))
            BodyText(CompanionFit.summary(note, noteShort, 2, b.innerWidth, fit.metrics, seeMain), color = colors.text2)
            PinDigits(pin, Modifier.fills(minHeight = 120.px))
            ActionRow(listOf(PageAction(stringResource(R.string.pair_cancel_action), onCancel, ButtonStyle.NORMAL, Symbols.CLOSE)))
        }
    }
}

/** The digits of a PIN on one line, scaled to the box they get (never wrapped, never wider than the box). */
@Composable
private fun PinDigits(pin: String, modifier: Modifier) {
    val colors = LocalDelosColors.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val shape = RoundedCornerShape(28.px)
    BoxWithConstraints(modifier.clip(shape).background(colors.surface3).border(1.px, colors.line, shape), contentAlignment = Alignment.Center) {
        val ref = with(density) { 100.sp.toPx() }
        val style = TextStyle(color = colors.accent, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 100.sp, letterSpacing = .12.em, fontFeatureSettings = "tnum")
        val widthAtRef = measurer.measure(AnnotatedString(pin), style, softWrap = false, constraints = Constraints()).size.width.toFloat()
        val padding = with(density) { 24.px.toPx() }
        val size = PinFit.fontSize(constraints.maxWidth - 2 * padding, constraints.maxHeight - 2 * padding, widthAtRef, ref)
        if (size > 0f) {
            BasicText(pin, style = style.copy(fontSize = with(density) { size.toSp() }), softWrap = false, maxLines = 1)
        }
    }
}

/** A one-time-code pairing: the instruction and the promise that the passphrase is never shown here; the hints are the main screen's own. */
internal data class CodePage(val uuid: String, val pcName: String, val hints: List<CompanionHint>) : CompanionPage {
    override val key: String get() = "pair/code/$uuid"

    @Composable
    override fun Content() {
        val colors = LocalDelosColors.current
        val seeMain = stringResource(R.string.companion_see_main)
        val full = stringResource(R.string.pair_code_text)
        val short = stringResource(R.string.pair_code_short)
        val alias = CompanionFit.alias(stringResource(R.string.pc_alias_prefix), uuid)
        PageCard { fit ->
            val b = fit.budget
            PageHeading(Symbols.PASSWORD, stringResource(R.string.pair_code_title), CompanionFit.title(pcName, alias, b.innerWidth - HEADING_DISC, fit.metrics))
            BodyText(CompanionFit.summary(full, short, b.bodyLines, b.innerWidth, fit.metrics, seeMain), color = colors.text2)
            BodyText(stringResource(R.string.pair_code_privacy), color = colors.accent)
            Filler()
            val (shown, rest) = CompanionFit.top(hints, minOf(b.hints, 2))
            if (shown.isNotEmpty()) HintList(shown, rest, Modifier.optional(50))
        }
    }
}

/** The PC accepted the pairing: what the PIN screen shows for the moment before it moves on, and the two ways on (the screen's own: open the library, back). */
internal data class PairResultPage(val uuid: String, val pcName: String, val onOpen: () -> Unit, val onBack: () -> Unit) : CompanionPage {
    override val key: String get() = "pair/result/$uuid"

    @Composable
    override fun Content() {
        PageCard { fit ->
            PageHeading(Symbols.CHECK_CIRCLE, pcTitle(pcName, uuid, fit), stringResource(R.string.pair_result_eyebrow))
            BodyText(stringResource(R.string.pair_result_text))
            Filler()
            val open = stringResource(R.string.pair_open_library)
            val back = stringResource(R.string.pair_back_hosts)
            val perRow = PcCard.buttonsPerRow(listOf(open, back).map { fit.metrics.width(it) * LABEL_SLACK }, fit.budget.innerWidth, BUTTON_GAP, BUTTON_PADDING)
            listOf(PageAction(open, onOpen, ButtonStyle.PRIMARY), PageAction(back, onBack)).chunked(perRow).forEach { ActionRow(it) }
        }
    }
}

/** The library states that have no game to focus and no card of a PC: nothing to read from the host yet, no PC at all, or an empty library. */
internal enum class LibraryNotice { EMPTY, LOADING, NO_PC }

internal data class LibraryNoticePage(
    val notice: LibraryNotice,
    val pcName: String?,
    val status: PcStatus?,
    /** Reconnect (empty library), nothing for the other notices. */
    val onReconnect: () -> Unit,
    /** PC details (empty library) or Back to PCs. */
    val onDetails: () -> Unit,
) : CompanionPage {
    override val key: String get() = "library/$notice"

    @Composable
    override fun Content() {
        val colors = LocalDelosColors.current
        val seeMain = stringResource(R.string.companion_see_main)
        val emptyFull = stringResource(R.string.pc_empty_text)
        val emptyShort = stringResource(R.string.pc_empty_short)
        val eyebrow = when (notice) {
            LibraryNotice.EMPTY -> if (pcName != null && status != null) stringResource(R.string.pc_name_status, pcName, statusLabel(status)) else null
            LibraryNotice.LOADING -> stringResource(R.string.pc_loading_eyebrow)
            LibraryNotice.NO_PC -> null
        }
        PageCard { fit ->
            val b = fit.budget
            val (icon, title) = when (notice) {
                LibraryNotice.EMPTY -> Symbols.SPORTS_ESPORTS to stringResource(R.string.pc_empty_title)
                LibraryNotice.LOADING -> Symbols.LOADING to stringResource(R.string.pc_loading_title)
                LibraryNotice.NO_PC -> Symbols.DEVICES to stringResource(R.string.pc_nopc_title)
            }
            // Subtitle: the PC and its status when it is known and fits one line, else the status alone
            val subtitle = if (notice == LibraryNotice.EMPTY && eyebrow != null && status != null) oneLine(eyebrow, statusLabel(status), b.innerWidth - HEADING_DISC, fit) else eyebrow
            PageHeading(icon, title, subtitle)
            when (notice) {
                LibraryNotice.EMPTY -> BodyText(CompanionFit.summary(emptyFull, emptyShort, b.bodyLines, b.innerWidth, fit.metrics, seeMain), color = colors.text2)
                LibraryNotice.LOADING -> {
                    BodyText(stringResource(R.string.pc_loading_text), color = colors.text2)
                    BodyText(stringResource(R.string.pc_loading_note), Modifier.optional(50), color = colors.text3)
                }
                LibraryNotice.NO_PC -> BodyText(stringResource(R.string.pc_nopc_text), color = colors.text2)
            }
            Filler()
            val actions = when (notice) {
                LibraryNotice.EMPTY -> listOf(
                    PageAction(stringResource(R.string.pc_action_reconnect), onReconnect, ButtonStyle.PRIMARY),
                    PageAction(stringResource(R.string.pc_action_details), onDetails),
                )
                else -> listOf(PageAction(stringResource(R.string.pc_action_back_pcs), onDetails, ButtonStyle.PRIMARY))
            }
            val perRow = PcCard.buttonsPerRow(actions.map { fit.metrics.width(it.label) * LABEL_SLACK }, b.innerWidth, BUTTON_GAP, BUTTON_PADDING)
            actions.chunked(perRow).forEach { ActionRow(it) }
        }
    }
}
