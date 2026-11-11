package com.limelight.display

import android.view.Display
import com.limelight.display.DisplayReason.*
import com.limelight.display.DisplayRole.*
import com.limelight.display.DisplayTypeHint.INTERNAL
import com.limelight.display.DisplayTypeHint.OVERLAY
import com.limelight.display.DisplayTypeHint.UNKNOWN
import com.limelight.display.DisplayTypeHint.WIFI
import com.limelight.display.SecondDisplayOverride.AUTOMATIC
import com.limelight.display.SecondDisplayOverride.BUILT_IN
import com.limelight.display.SecondDisplayOverride.EXTERNAL as FORCE_EXTERNAL
import org.junit.Assert.assertEquals
import org.junit.Test

/** The table behind docs/DUAL_SCREEN.md: what each kind of display is classified as, and why. */
class DisplayClassifierTest {
    private val presentation = Display.FLAG_PRESENTATION
    private val secure = Display.FLAG_SECURE or Display.FLAG_SUPPORTS_PROTECTED_BUFFERS
    private val privateFlag = Display.FLAG_PRIVATE

    private class Case(
        val what: String,
        val facts: DisplayFacts,
        val override: SecondDisplayOverride = AUTOMATIC,
        val role: DisplayRole,
        val reason: DisplayReason,
    )

    private fun facts(
        id: Int, name: String, flags: Int, width: Int = 1920, height: Int = 1080,
        hint: DisplayTypeHint = UNKNOWN, sink: SinkConnection = SinkConnection.NONE,
    ) = DisplayFacts(id, name, flags, width, height, 420, 60f, Display.STATE_ON, hint, sink)

    // What the Android 13 emulator reports for display 0 and an overlay display (docs/DUAL_SCREEN.md)
    private val builtInMain = facts(0, "Built-in Screen", secure, hint = INTERNAL)
    private val overlay = facts(3, "Overlay #1", presentation, 1240, 1080, hint = OVERLAY)

    // An external monitor or TV as AOSP LocalDisplayAdapter makes it: not internal, FLAG_PRESENTATION, EDID product info
    private val monitor = facts(2, "HDMI Screen", presentation, hint = DisplayTypeHint.EXTERNAL, sink = SinkConnection.DIRECT)

    // A second panel of the device as AOSP makes an internal display: no FLAG_PRESENTATION, type INTERNAL
    private val bottomPanel = facts(1, "Built-in Screen", secure, 1240, 1080, hint = INTERNAL)

    private val table = listOf(
        Case("default display is main", builtInMain, role = MAIN, reason = DEFAULT_DISPLAY),
        Case("default display stays main under every override", builtInMain, BUILT_IN, MAIN, DEFAULT_DISPLAY),
        Case("default display stays main even when it is flagged like a presentation display", facts(0, "x", presentation, hint = OVERLAY), FORCE_EXTERNAL, MAIN, DEFAULT_DISPLAY),

        Case("monitor over USB-C or HDMI", monitor, role = EXTERNAL, reason = TYPE_EXTERNAL),
        Case("monitor when the type text is unreadable: product info", monitor.copy(typeHint = UNKNOWN), role = EXTERNAL, reason = SINK_CABLE),
        Case("monitor through an AV receiver", monitor.copy(typeHint = UNKNOWN, sink = SinkConnection.TRANSITIVE), role = EXTERNAL, reason = SINK_CABLE),
        Case("monitor without EDID and without readable type text is external by default", monitor.copy(typeHint = UNKNOWN, sink = SinkConnection.NONE), role = EXTERNAL, reason = FALLBACK_EXTERNAL),
        Case("monitor with unknown sink type is external by default", monitor.copy(typeHint = UNKNOWN, sink = SinkConnection.UNKNOWN), role = EXTERNAL, reason = FALLBACK_EXTERNAL),
        Case("monitor with a name from the system language", monitor.copy(name = "HDMI-Bildschirm", typeHint = UNKNOWN, sink = SinkConnection.NONE), role = EXTERNAL, reason = FALLBACK_EXTERNAL),
        Case("monitor with its EDID name", monitor.copy(name = "DELL U2720Q", typeHint = UNKNOWN, sink = SinkConnection.NONE), role = EXTERNAL, reason = FALLBACK_EXTERNAL),
        Case("monitor whose type text contradicts a missing presentation flag: the text wins", monitor.copy(flags = secure), role = EXTERNAL, reason = TYPE_EXTERNAL),
        Case("wireless display", facts(4, "Living room TV", presentation, hint = WIFI), role = EXTERNAL, reason = TYPE_WIRELESS),

        Case("overlay display", overlay, role = VIRTUAL, reason = TYPE_VIRTUAL),
        Case("app-created virtual display", facts(5, "Cast", presentation, hint = DisplayTypeHint.VIRTUAL), role = VIRTUAL, reason = TYPE_VIRTUAL),
        Case("overlay display, English name, no type text", overlay.copy(typeHint = UNKNOWN), role = VIRTUAL, reason = NAME_VIRTUAL),
        Case("virtual display, English name, no type text", facts(5, "Virtual Display", presentation), role = VIRTUAL, reason = NAME_VIRTUAL),
        // Known limit: the name is translated by the system, so without the type text a translated overlay name is not recognised
        Case("overlay display with a Czech name and no type text is not recognised", overlay.copy(name = "Překryvná vrstva č. 1", typeHint = UNKNOWN), role = EXTERNAL, reason = FALLBACK_EXTERNAL),
        Case("private display", facts(6, "Private", privateFlag or presentation, hint = DisplayTypeHint.VIRTUAL), role = VIRTUAL, reason = PRIVATE),
        Case("private internal panel is not usable either", bottomPanel.copy(flags = bottomPanel.flags or privateFlag), role = VIRTUAL, reason = PRIVATE),

        Case("second panel of a dual-screen handheld", bottomPanel, role = BUILT_IN_COMPANION, reason = TYPE_INTERNAL),
        Case("second panel, type text unreadable, translated name: no presentation flag", bottomPanel.copy(name = "Integrierter Bildschirm", typeHint = UNKNOWN), role = BUILT_IN_COMPANION, reason = NO_PRESENTATION_FLAG),
        Case("second panel with a bigger resolution than the main one", bottomPanel.copy(width = 3840, height = 2160, typeHint = UNKNOWN), role = BUILT_IN_COMPANION, reason = NO_PRESENTATION_FLAG),
        Case("second panel that the product info calls built in", bottomPanel.copy(flags = presentation, typeHint = UNKNOWN, sink = SinkConnection.BUILT_IN), role = BUILT_IN_COMPANION, reason = SINK_BUILT_IN),
        Case("tiny external display stays external", monitor.copy(width = 320, height = 240), role = EXTERNAL, reason = TYPE_EXTERNAL),

        Case("override part of the device: monitor", monitor, BUILT_IN, BUILT_IN_COMPANION, OVERRIDE_BUILT_IN),
        Case("override part of the device: overlay stands in for a second panel", overlay, BUILT_IN, BUILT_IN_COMPANION, OVERRIDE_BUILT_IN),
        Case("override part of the device: private display", bottomPanel.copy(flags = privateFlag), BUILT_IN, BUILT_IN_COMPANION, OVERRIDE_BUILT_IN),
        Case("override external: second panel", bottomPanel, FORCE_EXTERNAL, EXTERNAL, OVERRIDE_EXTERNAL),
        Case("override external: panel without any readable signal", bottomPanel.copy(typeHint = UNKNOWN), FORCE_EXTERNAL, EXTERNAL, OVERRIDE_EXTERNAL),
        Case("override external: monitor", monitor, FORCE_EXTERNAL, EXTERNAL, OVERRIDE_EXTERNAL),
        Case("override external never makes an overlay a stream target", overlay, FORCE_EXTERNAL, VIRTUAL, OVERRIDE_EXTERNAL_IGNORED),
        Case("override external never makes a private display a stream target", facts(6, "Private", privateFlag), FORCE_EXTERNAL, VIRTUAL, OVERRIDE_EXTERNAL_IGNORED),
        Case("automatic leaves the monitor alone", monitor, AUTOMATIC, EXTERNAL, TYPE_EXTERNAL),
    )

    @Test fun table() {
        table.forEach {
            val result = DisplayClassifier.classify(it.facts, it.override)
            assertEquals("${it.what}: role", it.role, result.role)
            assertEquals("${it.what}: reason", it.reason, result.reason)
        }
    }

    @Test fun everyReasonHasText() {
        DisplayReason.entries.forEach { assertEquals(it.name, false, it.text.isBlank()) }
    }

    @Test fun appProcessTextOfTheAndroid13EmulatorHasNoTypeField() {
        // Captured from Display.toString() inside the app on the API 33 emulator (docs/DUAL_SCREEN.md): it stops at committedState
        val text = "Display id 4: DisplayInfo{\"Overlay #1\", displayId 4\", displayGroupId 0, FLAG_PRESENTATION, FLAG_TRUSTED, real 1240 x 1080, " +
            "largest app 1240 x 1240, smallest app 1080 x 1080, appVsyncOff 0, presDeadline 33333332, mode 4, defaultMode 4, " +
            "modes [{id=4, width=1240, height=1080, fps=60.000004, alternativeRefreshRates=[]}], hdrCapabilities null, " +
            "userDisabledHdrTypes [], minimalPostProcessingSupported false, rotation 0, state ON, committedState UNKNOWN}, " +
            "DisplayMetrics{density=2.625, width=1240, height=1080, scaledDensity=2.625, xdpi=420.0, ydpi=420.0}, isValid=true"
        assertEquals(UNKNOWN, DisplayClassifier.typeHint(text))
    }

    @Test fun typeTextAsDumpsysAndOlderBuildsPrintIt() {
        val overlayText = "Display id 3: DisplayInfo{\"Overlay #1\", displayId 3\", displayGroupId 0, FLAG_PRESENTATION, FLAG_TRUSTED, real 1240 x 1080, " +
            "rotation 0, state ON, committedState UNKNOWN, type OVERLAY, uniqueId \"overlay:1\", app 1240 x 1080, density 420 (420.0 x 420.0) dpi, " +
            "layerStack 3}, DisplayMetrics{density=2.625, width=1240, height=1080}, isValid=true"
        assertEquals(OVERLAY, DisplayClassifier.typeHint(overlayText))
        assertEquals(INTERNAL, DisplayClassifier.typeHint(overlayText.replace("type OVERLAY", "type INTERNAL")))
        assertEquals(DisplayTypeHint.EXTERNAL, DisplayClassifier.typeHint(overlayText.replace("type OVERLAY", "type EXTERNAL")))
        assertEquals(WIFI, DisplayClassifier.typeHint(overlayText.replace("type OVERLAY", "type WIFI")))
        assertEquals(DisplayTypeHint.VIRTUAL, DisplayClassifier.typeHint(overlayText.replace("type OVERLAY", "type VIRTUAL")))
    }

    @Test fun typeTextOfAndroid10Names() {
        val text = "Display id 1: DisplayInfo{\"HDMI Screen\", displayId 1, FLAG_PRESENTATION, state ON, type HDMI, uniqueId \"local:1\", app 1920 x 1080}"
        assertEquals(DisplayTypeHint.EXTERNAL, DisplayClassifier.typeHint(text))
        assertEquals(INTERNAL, DisplayClassifier.typeHint(text.replace("type HDMI", "type BUILT_IN")))
    }

    @Test fun unreadableTypeTextIsUnknown() {
        assertEquals(UNKNOWN, DisplayClassifier.typeHint(""))
        assertEquals(UNKNOWN, DisplayClassifier.typeHint("Display id 1: something else entirely"))
        assertEquals(UNKNOWN, DisplayClassifier.typeHint("DisplayInfo{\"x\", state ON, type FUTURE_KIND, uniqueId \"a\"}"))
        // A display called "type OVERLAY" must not look like a type field
        assertEquals(UNKNOWN, DisplayClassifier.typeHint("DisplayInfo{\"type OVERLAY\", displayId 3}"))
    }
}
