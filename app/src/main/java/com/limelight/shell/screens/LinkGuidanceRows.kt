package com.limelight.shell.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.limelight.shell.LocalDeviceActions
import com.limelight.shell.LocalLibrary
import com.limelight.shell.LocalShell
import com.limelight.shell.data.Library
import com.limelight.shell.data.Pc
import com.limelight.shell.link.LinkCodec
import com.limelight.shell.link.LinkGuidance
import com.limelight.shell.link.LinkTestStore
import com.limelight.shell.link.currentTransport
import com.limelight.shell.link.linkFormat
import com.limelight.shell.link.linkGuidance
import com.limelight.shell.link.mbps
import com.limelight.shell.settings.SettingSpec
import com.limelight.shell.ui.RowValue
import com.limelight.shell.ui.Symbols

/** The stored link tests (docs/STREAM_QUALITY.md, "Link guidance"); null where the shell has none (previews). */
internal val LocalLinkTests = staticCompositionLocalOf<LinkTestStore?> { null }

/** The key of the settings rows the link guidance adds under the bitrate row. */
internal const val LINK_TEST_ROW = "link_test"
internal const val LINK_USE_ROW = "link_use_recommended"
private const val BITRATE_ROW = "seekbar_bitrate_kbps"

/** The PC the settings talk about: the one whose library is open, else the first paired one. */
internal fun settingsPc(library: Library, pcs: List<Pc>): Pc? =
    when (library) {
        is Library.Ready -> library.pc
        is Library.Offline -> library.pc
        else -> null
    } ?: pcs.firstOrNull { it.paired }

/**
 * The guidance for [pc] and the format in [values], or null when there is no PC (the settings then keep their plain
 * "Recommended: N Mbps" hint for PyroWave and nothing for the other codecs). Every codec gets it, because the probe
 * measures the network, not the codec; but an ordinary codec shows it only when the PC can test or a result exists,
 * so a PC that cannot test adds no noise. Reads the stored result of the network the device is on now.
 */
@Composable
internal fun rememberLinkGuidance(pc: Pc?, values: Map<String, *>): LinkGuidance? {
    val tests = LocalLinkTests.current
    val context = LocalContext.current
    if (pc == null) return null
    val format = linkFormat(
        values["video_format"] as? String, values["list_resolution"] as? String, values["list_fps"] as? String,
        values["checkbox_enable_hdr"] as? Boolean ?: false, values["list_pyrowave_chroma"] as? String,
    ) ?: return null
    val tested = tests?.get(pc.uuid, currentTransport(context))
    val info = pc.details.pyroWave
    if (format.codec != LinkCodec.PYROWAVE && !info.supportsLinkTest() && tested == null) return null
    return linkGuidance(format, info, tested)
}

/**
 * The Settings rows of the guidance, next to the bitrate row: the row itself gets the help of the last test, and
 * Test link and Use recommended bitrate follow it. Nothing is sent and no bitrate is changed except by the user's
 * own press of Use recommended bitrate.
 */
@Composable
internal fun withLinkRows(
    items: List<SettingsItem>,
    guidance: LinkGuidance?,
    pc: Pc?,
    values: Map<String, *>,
    bitrateSpec: SettingSpec?,
    setBitrate: (SettingSpec, Int) -> Unit,
): List<SettingsItem> {
    if (guidance == null || pc == null || bitrateSpec == null) return items
    val shell = LocalShell.current
    val devices = LocalDeviceActions.current
    val context = LocalContext.current
    val transport = currentTransport(context)
    val now = System.currentTimeMillis()
    val recommended = guidance.recommendation
    val current = (values[BITRATE_ROW] as? Number)?.toInt() ?: recommended.kbps
    return items.flatMap { item ->
        if (item.key != BITRATE_ROW) return@flatMap listOf(item)
        val test = SettingsItem(
            LINK_TEST_ROW, item.tab, item.section, Symbols.WIFI, "Test link",
            guidance.testRowText(now, transport),
            RowValue.Action {
                when {
                    !pc.online -> shell.showToast("${pc.name} is offline", Symbols.WIFI_OFF)
                    guidance.testUnavailable != null -> shell.showToast(guidance.testUnavailable, Symbols.INFO)
                    else -> devices.testLink(pc, current)
                }
            },
            enabled = true,
            detail = "Measures what this network carries from ${pc.name}: test traffic for about 2 seconds per step, " +
                "at up to several hundred Mbps. Stay on ${transport.label}, with nothing else running.\n\n" +
                guidance.help(now, transport),
            searchTerms = "bandwidth probe measure speed network wifi",
        )
        val use = SettingsItem(
            LINK_USE_ROW, item.tab, item.section, Symbols.SPEED, "Use recommended bitrate",
            "${mbps(recommended.kbps)} · ${recommended.reasonText}",
            RowValue.Action {
                setBitrate(bitrateSpec, recommended.kbps)
                shell.showToast("Bitrate set to ${mbps(recommended.kbps)}", Symbols.CHECK_CIRCLE)
            },
            enabled = current != recommended.kbps,
            detail = "Fills the bitrate with ${mbps(recommended.kbps)}: ${recommended.reasonText}. " +
                "Delos never changes the bitrate by itself.",
            searchTerms = "recommended bitrate link",
        )
        listOf(item.copy(detail = item.detail + "\n\n" + guidance.help(now, transport)), test, use)
    }
}
