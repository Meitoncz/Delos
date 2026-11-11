package com.limelight.display

import android.content.SharedPreferences
import androidx.annotation.StringRes
import com.limelight.R
import android.view.Display

/** What a display is for Delos (docs/DUAL_SCREEN.md). */
enum class DisplayRole {
    /** The default display (id 0): the shell and the stream live here unless the external-display mode moves the stream. */
    MAIN,

    /** A second panel that is part of the device and always there (AYN Thor bottom panel, Ayaneo Pocket DS top panel). Never a stream target. */
    BUILT_IN_COMPANION,

    /** An attached screen: HDMI / DisplayPort / USB-C monitor or TV, a wireless display. The only role the external-display feature may use. */
    EXTERNAL,

    /** Overlay (developer option), app-created virtual or private displays: nothing Delos may put a stream or a companion on. */
    VIRTUAL,
}

/** The user's override for every display that is not the default one (Settings > Display > Second display; docs/DUAL_SCREEN.md). */
enum class SecondDisplayOverride(val value: String, @StringRes val labelRes: Int) {
    AUTOMATIC("automatic", R.string.settings_native_second_display_automatic),
    EXTERNAL("external", R.string.settings_native_second_display_external),
    BUILT_IN("built_in", R.string.settings_native_second_display_built_in),
    ;

    fun step(direction: Int): SecondDisplayOverride = entries[Math.floorMod(ordinal + direction, entries.size)]

    companion object {
        /** The device-wide preference (the default shared preferences are excluded from backup: the choice is about this device). */
        const val KEY = "display_second_role"

        fun read(prefs: SharedPreferences): SecondDisplayOverride =
            entries.firstOrNull { it.value == prefs.getString(KEY, null) } ?: AUTOMATIC

        fun write(prefs: SharedPreferences, override: SecondDisplayOverride) {
            prefs.edit().putString(KEY, override.value).apply()
        }
    }
}

/** What `Display.toString()` says in its `type` field. Parsed text, see [DisplayClassifier] for how far to trust it. */
enum class DisplayTypeHint { UNKNOWN, INTERNAL, EXTERNAL, WIFI, OVERLAY, VIRTUAL }

/**
 * `DeviceProductInfo.getConnectionToSinkType()` (API 31+). [NONE] is "no product info": the display has no EDID (a panel
 * wired without one, an overlay) or Android is older than 12.
 */
enum class SinkConnection { NONE, UNKNOWN, BUILT_IN, DIRECT, TRANSITIVE }

/** Everything the classifier looks at for one display, read from the public `Display` API by [DisplayInventory]. */
data class DisplayFacts(
    val id: Int,
    val name: String,
    /** `Display.getFlags()` */
    val flags: Int,
    /** `Display.getMode()` physical size. Reported and logged, not used as evidence. */
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val refreshRate: Float,
    /** `Display.getState()` (`Display.STATE_*`). Reported only: a closed lid or a folded panel is a state, not a kind. */
    val state: Int = Display.STATE_ON,
    val typeHint: DisplayTypeHint = DisplayTypeHint.UNKNOWN,
    val sink: SinkConnection = SinkConnection.NONE,
) {
    val presentation get() = flags and Display.FLAG_PRESENTATION != 0
    val privateDisplay get() = flags and Display.FLAG_PRIVATE != 0
}

/** Why a display got its role; [text] is the English technical sentence for Debug info and the diagnostic log. */
enum class DisplayReason(val text: String) {
    DEFAULT_DISPLAY("it is the default display"),
    OVERRIDE_BUILT_IN("manual setting: second displays are part of the device"),
    OVERRIDE_EXTERNAL("manual setting: second displays are external"),
    OVERRIDE_EXTERNAL_IGNORED("manual setting says external, but a virtual display is never a stream target"),
    PRIVATE("FLAG_PRIVATE: reserved for its owner, not usable by other apps"),
    TYPE_VIRTUAL("Display.toString() reports an overlay or virtual display"),
    TYPE_WIRELESS("Display.toString() reports a wireless display"),
    TYPE_EXTERNAL("Display.toString() reports an external display"),
    TYPE_INTERNAL("Display.toString() reports an internal panel"),
    SINK_BUILT_IN("the product info says the sink is built into the device"),
    SINK_CABLE("the product info (EDID) describes a monitor or TV behind a connector"),
    NAME_VIRTUAL("the name says overlay or virtual"),
    NO_PRESENTATION_FLAG("not the default display and without FLAG_PRESENTATION, which Android gives every external, wireless and overlay display: a local internal panel"),
    FALLBACK_EXTERNAL("nothing marks it as built-in or virtual: treated as external (the safe default)"),
}

data class DisplayClassification(val role: DisplayRole, val reason: DisplayReason)

/**
 * Decides what each Android display is, from public API facts only. Pure: no Android services, so the table in
 * `DisplayClassifierTest` covers it.
 *
 * Design rule: **anything not clearly a built-in companion and not clearly virtual is EXTERNAL.** The external-display
 * feature (docs/EXTERNAL_DISPLAY.md) was accepted on a real monitor and TV and must behave exactly as before; a wrong
 * EXTERNAL on a built-in panel is fixed by [SecondDisplayOverride], a wrong COMPANION on a monitor would silently break it.
 *
 * Rules for a display that is not the default one, in order (the first that applies wins):
 * 1. Override [SecondDisplayOverride.BUILT_IN]: COMPANION, whatever the display is (that is how an overlay display stands in
 *    for a second panel when testing).
 * 2. `FLAG_PRIVATE`: VIRTUAL. Android hides private displays from other apps, so seeing one means Delos owns it or the
 *    system exposes it; either way it is not a place for a stream.
 * 3. The `type` field of `Display.toString()` ([DisplayTypeHint]): OVERLAY and VIRTUAL give VIRTUAL, WIFI and EXTERNAL give
 *    EXTERNAL, INTERNAL gives COMPANION.
 * 4. The product info (API 31+, [SinkConnection]): BUILT_IN gives COMPANION, DIRECT and TRANSITIVE give EXTERNAL (an EDID
 *    came from a monitor or TV behind a connector). UNKNOWN and NONE say nothing.
 * 5. The name starts with or contains "overlay" or "virtual" (English only): VIRTUAL.
 * 6. No `FLAG_PRESENTATION`: COMPANION.
 * 7. Otherwise EXTERNAL.
 * An override of EXTERNAL changes only a COMPANION result into EXTERNAL; a VIRTUAL display stays VIRTUAL.
 *
 * Evidence, how sure each signal is (checked against AOSP `LocalDisplayAdapter` of Android 13 and the emulator, see
 * docs/DUAL_SCREEN.md; none of it was seen on a real dual-screen device):
 * - **Known** (AOSP source and emulator): a local display that is internal gets no `FLAG_PRESENTATION`, one that is not
 *   internal always gets it; overlay displays and wireless displays carry it; `FLAG_PRIVATE` marks private virtual displays
 *   and the OEM-configured private local ports. Overlay displays have no product info and the name "Overlay #N" (`dumpsys
 *   display` also says `type OVERLAY`).
 * - **Known but locale-bound**: the names. The system builds them from string resources ("Built-in Screen" for **every**
 *   internal display, "HDMI Screen" for external ones, "Overlay #N"), translated into the system language ("Integrierter
 *   Bildschirm"), so a name can only prove "virtual" in English and never proves "built-in". The name is not used for
 *   anything else.
 * - **Best effort, absent on the emulator**: the `type` field of `Display.toString()`. It is debug text of `DisplayInfo`, not
 *   API. The AOSP sources of Android 10, 12 and 14 print `, type <NAME>` there (names INTERNAL, EXTERNAL, WIFI, OVERLAY,
 *   VIRTUAL; Android 10 says BUILT_IN and HDMI, accepted too), but the app-process text of the Android 13 emulator ends at
 *   `committedState` with no type, so on such builds the hint is UNKNOWN and the rules below it decide alone (`dumpsys
 *   display` still shows the type, which is how the table was checked). Where it exists it is the only locale-free sign of
 *   the kind of a display, which is why it goes before the flags.
 * - **Guess**: that an OEM's second panel follows AOSP (internal type, no presentation flag). A vendor that builds the second
 *   panel as a virtual or wireless-style display is recognised only through the override.
 * - **Unknown**: how the AYN Thor, the Ayaneo Pocket DS and the Retroid add-on actually report their second panel; the size
 *   is not used because no size separates a small panel from a small monitor.
 */
object DisplayClassifier {
    fun classify(facts: DisplayFacts, override: SecondDisplayOverride): DisplayClassification {
        if (facts.id == Display.DEFAULT_DISPLAY) return DisplayClassification(DisplayRole.MAIN, DisplayReason.DEFAULT_DISPLAY)
        if (override == SecondDisplayOverride.BUILT_IN) {
            return DisplayClassification(DisplayRole.BUILT_IN_COMPANION, DisplayReason.OVERRIDE_BUILT_IN)
        }
        val natural = natural(facts)
        if (override == SecondDisplayOverride.EXTERNAL) {
            return when (natural.role) {
                DisplayRole.VIRTUAL -> DisplayClassification(DisplayRole.VIRTUAL, DisplayReason.OVERRIDE_EXTERNAL_IGNORED)
                else -> DisplayClassification(DisplayRole.EXTERNAL, DisplayReason.OVERRIDE_EXTERNAL)
            }
        }
        return natural
    }

    private fun natural(facts: DisplayFacts): DisplayClassification {
        if (facts.privateDisplay) return DisplayClassification(DisplayRole.VIRTUAL, DisplayReason.PRIVATE)
        when (facts.typeHint) {
            DisplayTypeHint.OVERLAY, DisplayTypeHint.VIRTUAL -> return DisplayClassification(DisplayRole.VIRTUAL, DisplayReason.TYPE_VIRTUAL)
            DisplayTypeHint.WIFI -> return DisplayClassification(DisplayRole.EXTERNAL, DisplayReason.TYPE_WIRELESS)
            DisplayTypeHint.EXTERNAL -> return DisplayClassification(DisplayRole.EXTERNAL, DisplayReason.TYPE_EXTERNAL)
            DisplayTypeHint.INTERNAL -> return DisplayClassification(DisplayRole.BUILT_IN_COMPANION, DisplayReason.TYPE_INTERNAL)
            DisplayTypeHint.UNKNOWN -> Unit
        }
        when (facts.sink) {
            SinkConnection.BUILT_IN -> return DisplayClassification(DisplayRole.BUILT_IN_COMPANION, DisplayReason.SINK_BUILT_IN)
            SinkConnection.DIRECT, SinkConnection.TRANSITIVE -> return DisplayClassification(DisplayRole.EXTERNAL, DisplayReason.SINK_CABLE)
            SinkConnection.UNKNOWN, SinkConnection.NONE -> Unit
        }
        if (VIRTUAL_NAME.containsMatchIn(facts.name)) return DisplayClassification(DisplayRole.VIRTUAL, DisplayReason.NAME_VIRTUAL)
        if (!facts.presentation) return DisplayClassification(DisplayRole.BUILT_IN_COMPANION, DisplayReason.NO_PRESENTATION_FLAG)
        return DisplayClassification(DisplayRole.EXTERNAL, DisplayReason.FALLBACK_EXTERNAL)
    }

    private val VIRTUAL_NAME = Regex("overlay|virtual", RegexOption.IGNORE_CASE)

    private val TYPE_FIELD = Regex(""", type ([A-Z_]+)[,}]""")

    /**
     * The `type` field of the text `Display.toString()` returns (`DisplayInfo{"Overlay #1", displayId 3, ..., type OVERLAY,
     * ...}`), [DisplayTypeHint.UNKNOWN] when there is none. Pure, so the format guess is tested.
     */
    fun typeHint(displayToString: String): DisplayTypeHint = when (TYPE_FIELD.find(displayToString)?.groupValues?.get(1)) {
        "INTERNAL", "BUILT_IN" -> DisplayTypeHint.INTERNAL
        "EXTERNAL", "HDMI" -> DisplayTypeHint.EXTERNAL
        "WIFI" -> DisplayTypeHint.WIFI
        "OVERLAY" -> DisplayTypeHint.OVERLAY
        "VIRTUAL" -> DisplayTypeHint.VIRTUAL
        else -> DisplayTypeHint.UNKNOWN
    }
}
