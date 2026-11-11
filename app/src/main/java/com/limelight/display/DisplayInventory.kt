package com.limelight.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.hardware.display.DeviceProductInfo
import androidx.annotation.RequiresApi
import android.view.Display
import androidx.preference.PreferenceManager
import com.limelight.diag.DiagLog
import java.util.Locale

/** One display of the device with what Delos made of it. */
data class ClassifiedDisplay(val display: Display, val facts: DisplayFacts, val classification: DisplayClassification) {
    val role get() = classification.role

    /** One technical line: `id 3 "Overlay #1" 1240x1080 420 dpi 60.0 Hz state ON, flags PRESENTATION: VIRTUAL (reason)`. English, for the log. */
    fun summary(): String = String.format(
        Locale.ROOT, "id %d \"%s\" %dx%d %d dpi %.1f Hz, state %s, flags %s: %s (%s)",
        facts.id, shownName(), facts.width, facts.height, facts.densityDpi, facts.refreshRate, DisplayInventory.stateName(facts.state),
        DisplayInventory.flagNames(facts.flags), role, classification.reason.text,
    )

    /**
     * The same facts as lines for the Debug info panel, with what the classifier looked at (the type text and the sink type,
     * which are the signals nobody can read off a device without a debugger).
     */
    fun details(): String = buildString {
        appendLine("id ${facts.id}, ${facts.width}x${facts.height}, ${facts.densityDpi} dpi, ${String.format(Locale.ROOT, "%.1f", facts.refreshRate)} Hz")
        appendLine("state ${DisplayInventory.stateName(facts.state)}, flags ${DisplayInventory.flagNames(facts.flags)}")
        appendLine("type text ${facts.typeHint}, product info ${facts.sink}")
        append("${role.name}: ${classification.reason.text}")
    }

    /** A wireless display carries a name somebody gave it ("Living room TV"), which must not enter a log. */
    fun shownName(): String = if (facts.typeHint == DisplayTypeHint.WIFI) "wireless display" else facts.name
}

/**
 * Every display of the device, classified by [DisplayClassifier] with the user's [SecondDisplayOverride]. The single place
 * that decides which display is a stream target: [external] is what the external-display feature (`ServerHelper`,
 * `ExternalDisplayControlActivity`, `DelosStreamActivity`) may use; a built-in companion or a virtual display never is
 * (docs/DUAL_SCREEN.md).
 */
object DisplayInventory {
    private const val TAG = "Display"

    @JvmStatic
    fun overrideOf(context: Context): SecondDisplayOverride =
        SecondDisplayOverride.read(PreferenceManager.getDefaultSharedPreferences(context.applicationContext))

    /** All displays Android lists, lowest id first, classified. */
    @JvmStatic
    fun list(context: Context): List<ClassifiedDisplay> {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val override = overrideOf(context)
        return manager.displays.sortedBy { it.displayId }.map { display ->
            val facts = facts(display)
            ClassifiedDisplay(display, facts, DisplayClassifier.classify(facts, override))
        }
    }

    /** The display the external-display feature may use: the first one classified [DisplayRole.EXTERNAL], null when none. */
    @JvmStatic
    fun external(context: Context): Display? = list(context).firstOrNull { it.role == DisplayRole.EXTERNAL }?.display

    /** The built-in companion panels (the second screen of a dual-screen handheld). */
    @JvmStatic
    fun companions(context: Context): List<Display> = list(context).filter { it.role == DisplayRole.BUILT_IN_COMPANION }.map { it.display }

    @Suppress("DEPRECATION") // getRealMetrics is the one public way to read a density of any display without a context for it
    fun facts(display: Display): DisplayFacts {
        val mode = display.mode
        val metrics = DisplayMetrics().also { display.getRealMetrics(it) }
        val sink = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) sinkConnection(display) else SinkConnection.NONE
        return DisplayFacts(
            id = display.displayId,
            name = display.name.orEmpty(),
            flags = display.flags,
            width = mode.physicalWidth,
            height = mode.physicalHeight,
            densityDpi = metrics.densityDpi,
            refreshRate = display.refreshRate,
            state = display.state,
            typeHint = DisplayClassifier.typeHint(display.toString()),
            sink = sink,
        )
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun sinkConnection(display: Display): SinkConnection {
        val productInfo = display.deviceProductInfo ?: return SinkConnection.NONE
        return when (productInfo.connectionToSinkType) {
            DeviceProductInfo.CONNECTION_TO_SINK_BUILT_IN -> SinkConnection.BUILT_IN
            DeviceProductInfo.CONNECTION_TO_SINK_DIRECT -> SinkConnection.DIRECT
            DeviceProductInfo.CONNECTION_TO_SINK_TRANSITIVE -> SinkConnection.TRANSITIVE
            else -> SinkConnection.UNKNOWN
        }
    }

    fun stateName(state: Int): String = when (state) {
        Display.STATE_ON -> "ON"
        Display.STATE_OFF -> "OFF"
        Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
        Display.STATE_VR -> "VR"
        else -> "UNKNOWN"
    }

    /** The public flags by name, `none` when no flag is set. */
    fun flagNames(flags: Int): String = buildList {
        if (flags and Display.FLAG_PRESENTATION != 0) add("PRESENTATION")
        if (flags and Display.FLAG_PRIVATE != 0) add("PRIVATE")
        if (flags and Display.FLAG_SECURE != 0) add("SECURE")
        if (flags and Display.FLAG_SUPPORTS_PROTECTED_BUFFERS != 0) add("PROTECTED_BUFFERS")
        if (flags and Display.FLAG_ROUND != 0) add("ROUND")
    }.ifEmpty { listOf("none") }.joinToString("|")

    private var listener: DisplayManager.DisplayListener? = null
    private val lastLogged = HashMap<Int, String>()

    /**
     * Writes one diagnostic-log line for every display now and one for every later change: added, changed (only when the
     * text of the line differs, since Android also reports brightness and rotation), removed. Display names are device
     * names ("Built-in Screen", "HDMI Screen"); a wireless display's name is replaced. Safe to call again.
     */
    @JvmStatic
    fun startLogging(app: Context) {
        synchronized(this) {
            if (listener != null) return
            val manager = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val next = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = log(app, displayId, "added")
                override fun onDisplayChanged(displayId: Int) = log(app, displayId, "changed")
                override fun onDisplayRemoved(displayId: Int) {
                    synchronized(this@DisplayInventory) { lastLogged.remove(displayId) }
                    DiagLog.i(TAG, "Display $displayId removed")
                }
            }
            listener = next
            manager.registerDisplayListener(next, Handler(Looper.getMainLooper()))
            list(app).forEach { entry ->
                lastLogged[entry.facts.id] = entry.summary()
                DiagLog.i(TAG, "Display present: ${entry.summary()}")
            }
        }
    }

    private fun log(app: Context, displayId: Int, what: String) {
        val entry = list(app).firstOrNull { it.facts.id == displayId } ?: return
        val line = entry.summary()
        synchronized(this) {
            if (lastLogged.put(displayId, line) == line) return
        }
        DiagLog.i(TAG, "Display $what: $line")
    }
}
