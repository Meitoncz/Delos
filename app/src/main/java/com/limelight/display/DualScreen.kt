package com.limelight.display

import android.app.ActivityOptions
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.view.Display
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import com.limelight.R

/** Settings > Display > Main screen: which panel of a dual-screen device shows the shell and the stream (docs/DUAL_SCREEN.md). */
enum class MainScreenChoice(val value: String, @StringRes val labelRes: Int) {
    /** The larger panel (by pixels); the default display when both are the same size. */
    AUTOMATIC("automatic", R.string.settings_native_main_screen_automatic),

    /** The other panel. */
    SWAPPED("swapped", R.string.settings_native_main_screen_swapped),
    ;

    fun step(direction: Int): MainScreenChoice = entries[Math.floorMod(ordinal + direction, entries.size)]

    companion object {
        /** Device-wide, like [SecondDisplayOverride.KEY]: the default shared preferences are excluded from backup. */
        const val KEY = "display_main_screen"

        fun read(prefs: SharedPreferences): MainScreenChoice = entries.firstOrNull { it.value == prefs.getString(KEY, null) } ?: AUTOMATIC

        fun write(prefs: SharedPreferences, choice: MainScreenChoice) {
            prefs.edit().putString(KEY, choice.value).apply()
        }
    }
}

/** What [DualScreenPlanner] needs to know about one display: no `Display` object, so the choice is a table test. */
data class PanelSource(val id: Int, val role: DisplayRole, val width: Int, val height: Int)

data class Panel(val id: Int, val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
}

/** The two panels of a dual-screen device: the [main] one gets the shell and the stream, the [companion] one the companion screen. */
data class PanelPair(val main: Panel, val companion: Panel) {
    /** The panel that is not on display [displayId]; null when that display belongs to neither panel (an external monitor, an overlay). */
    fun otherThan(displayId: Int): Panel? = when (displayId) {
        main.id -> companion
        companion.id -> main
        else -> null
    }

    /**
     * Where an activity that lives on display [displayId] belongs: the main panel when it sits on the companion one, null when
     * it is already right or on neither panel (a monitor or TV, which the dual-screen logic leaves alone).
     */
    fun mainFor(displayId: Int): Int? = if (displayId == companion.id) main.id else null
}

/** Which of the two panels is main, as a pure function of the display list and the user's choice (docs/DUAL_SCREEN.md). */
object DualScreenPlanner {
    /**
     * The pair of panels, or null when there is no [DisplayRole.BUILT_IN_COMPANION] display (or no default display): then the
     * device has one screen as far as Delos is concerned and nothing about it changes. With several companions the one with the
     * lowest id counts. External and virtual displays are never part of the pair.
     */
    fun plan(displays: List<PanelSource>, choice: MainScreenChoice): PanelPair? {
        val default = displays.firstOrNull { it.id == Display.DEFAULT_DISPLAY && it.role == DisplayRole.MAIN }?.panel() ?: return null
        val companion = displays.filter { it.role == DisplayRole.BUILT_IN_COMPANION }.minByOrNull { it.id }?.panel() ?: return null
        // A tie goes to the default display: where the launcher put the app anyway
        val larger = if (companion.pixels > default.pixels) companion else default
        val smaller = if (larger === default) companion else default
        return if (choice == MainScreenChoice.AUTOMATIC) PanelPair(larger, smaller) else PanelPair(smaller, larger)
    }

    private fun PanelSource.panel() = Panel(id, width, height)
}

/**
 * The dual-screen plan of this device right now and the launch placement that follows from it. Every caller goes through
 * [plan]: without a built-in companion display it returns null / [Display.DEFAULT_DISPLAY] / the options untouched, so a
 * single-screen device (and the external-display mode with one device screen) behaves exactly as before.
 */
object DualScreen {
    @JvmStatic
    fun choiceOf(context: Context): MainScreenChoice =
        MainScreenChoice.read(PreferenceManager.getDefaultSharedPreferences(context.applicationContext))

    @JvmStatic
    fun plan(context: Context): PanelPair? = plan(DisplayInventory.list(context), choiceOf(context))

    fun plan(displays: List<ClassifiedDisplay>, choice: MainScreenChoice): PanelPair? =
        DualScreenPlanner.plan(displays.map { PanelSource(it.facts.id, it.role, it.facts.width, it.facts.height) }, choice)

    /** The id of the display [activity] is on. */
    @JvmStatic
    @Suppress("DEPRECATION") // Activity.getDisplay() is API 30; the activity's own window manager answers on API 29 too
    fun displayIdOf(activity: android.app.Activity): Int = activity.windowManager.defaultDisplay.displayId

    /** The display the shell and the stream belong on: the main panel of a dual-screen device, else the default display. */
    @JvmStatic
    fun mainDisplayId(context: Context): Int = plan(context)?.main?.id ?: Display.DEFAULT_DISPLAY

    /** Puts [options] on the main panel of a dual-screen device (the same object, changed); untouched when there is only one screen. */
    @JvmStatic
    fun placeOnMain(context: Context, options: ActivityOptions): ActivityOptions {
        val pair = plan(context) ?: return options
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) options.setLaunchDisplayId(pair.main.id)
        return options
    }
}
