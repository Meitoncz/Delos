package com.limelight.shell.settings

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.limelight.profiles.ProfilesManager
import com.limelight.shadows.ShadowGameManager
import com.limelight.shadows.ShadowMoonBridge
import com.limelight.shell.StatsPosition
import com.limelight.shell.screens.NativeSettingsHelp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The help box is about [SettingsHelp.LINE_CHARS] characters wide and [SettingsHelp.MAX_LINES] lines
 * tall at the largest interface scale. Whatever the screens can render must fit it, so the text never
 * has to scroll; every violation is reported at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [ShadowMoonBridge::class, ShadowGameManager::class])
class SettingsHelpFitTest {
    private lateinit var catalog: SettingsCatalog

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        ProfilesManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        ProfilesManager.getInstance().load(context)
        catalog = SettingsCatalog(context)
    }

    /** Greedy word wrap, counting blank lines between paragraphs. */
    private fun renderedLines(text: String): Int = text.split('\n').sumOf { paragraph ->
        var lines = 1
        var used = 0
        paragraph.split(' ').filter { it.isNotEmpty() }.forEach { word ->
            if (used == 0) used = word.length
            else if (used + 1 + word.length <= SettingsHelp.LINE_CHARS) used += 1 + word.length
            else { lines++; used = word.length }
        }
        lines
    }

    private val problems = mutableListOf<String>()

    private fun assertFits(what: String, text: String) {
        if (text.isBlank()) problems += "Empty help for $what"
        else if (renderedLines(text) > SettingsHelp.MAX_LINES)
            problems += "Help for $what needs ${renderedLines(text)} lines (max ${SettingsHelp.MAX_LINES}):\n$text"
    }

    @After fun everyHelpFits() {
        assertTrue("${problems.size} help texts do not fit:\n\n" + problems.joinToString("\n\n"), problems.isEmpty())
    }

    /** Value sets that change what the help shows next to the selected value. */
    private val contexts = listOf<Map<String, Any>>(
        emptyMap(),
        mapOf("video_format" to "pyrowave", "list_resolution" to "3840x2160", "list_fps" to "120", "checkbox_enable_hdr" to true),
        mapOf("pref_low_latency_frame_balance" to true),
    )

    @Test fun everySettingAndEverySelectableValueFitsTheHelpBox() {
        catalog.all.forEach { spec ->
            val selectable = spec.choices.map { it.value }.ifEmpty { listOf(null) }
            contexts.forEach { context ->
                selectable.forEach { value ->
                    val values = if (value == null) context else context + (spec.key to value)
                    assertFits("${spec.key}=$value with $context", SettingsHelp.details(spec, values))
                }
            }
            // A stored choice the device cannot offer still shows its own help, plus the notice
            spec.choices.firstOrNull()?.let { first ->
                val rest = spec.choices.drop(1)
                if (rest.isNotEmpty()) assertFits("${spec.key} unavailable ${first.value}",
                    SettingsHelp.details(spec, mapOf(spec.key to first.value), rest))
            }
        }
    }

    @Test fun valueHelpAddsToTheSettingDescription() {
        catalog.all.forEach { spec ->
            spec.choices.forEach { choice ->
                val help = SettingsHelp.choice(spec.key, choice.value)
                assertFalse("${spec.key}/${choice.value} repeats its description",
                    help.equals(spec.description, ignoreCase = true) ||
                        spec.description.contains(help, ignoreCase = true) ||
                        help.contains(spec.description, ignoreCase = true))
            }
        }
    }

    @Test fun dynamicResolutionsFramerateAndLanguagesFit() {
        listOf("640x360", "1280x720", "2560x1600", "7680x4320", "1234x567").forEach {
            assertFits("resolution $it", SettingsHelp.choice("list_resolution", it))
        }
        listOf("24", "59.94", "60", "119.88", "240", "360").forEach {
            assertFits("fps $it", SettingsHelp.choice("list_fps", it))
        }
        listOf("default", "en", "cs").forEach { assertFits("language $it", SettingsHelp.choice("list_languages", it)) }
        assertFits("keyboard layout", SettingsHelp.choice("keyboard_axi_list", "3"))
    }

    @Test fun nativeShellAndStreamMenuHelpFit() {
        val native = mapOf(
            "ui_palette" to Palette.entries.map { it.label },
            "ui_background" to BackgroundKind.entries.map { it.label },
            "ui_clock" to ClockFormat.entries.map { it.label },
            "ui_home_layout" to HomeLayout.entries.map { it.label },
            "ui_dark" to listOf("Dark", "Light"),
            "ui_reduced_motion" to listOf("Full", "Reduced"),
            "ui_stats_position" to StatsPosition.entries.map { it.label },
            "ui_default_pc" to listOf("None", "Living room PC with a long name"),
        )
        native.forEach { (key, labels) -> labels.forEach {
            assertFits("$key/$it", "$it\n${NativeSettingsHelp.choice(key, it)}\n\n${SettingsHelp.description(key, "")}")
        } }
        listOf("ui_match_game_colors", "pref_debug_info", "import_special_button_file").forEach {
            assertFits(it, SettingsHelp.description(it, ""))
        }
        listOf(
            "Resume", "Keyboard", "Overlay", "Brightness", "Screen brightness", "Bitrate", "Apply bitrate", "Upscaler",
            "Upscaling", "Supersampling", "Supersampling quality", "Mode", "Sharpness", "More", "Disconnect", "Quit game",
            "Send keys", "Send clipboard", "Get clipboard", "Commands", "Task manager", "Zoom", "Mouse mode",
            "Local mouse cursor", "On-screen controls", "Full keyboard", "Key layout", "Touch sensitivity", "Rotate",
            "Keys: Ctrl + Alt + Delete", "Command: Restart the game",
        ).forEach { assertFits("stream menu $it", StreamMenuHelp.description(it)) }
        // Mode help adds the selected method and the upscaler pages add their status line
        listOf("standard", "high", "temporal", "temporal-quality").forEach {
            assertFits("stream menu Mode/$it", SettingsHelp.choice("list_sgsr_quality", it) + "\n\n" + StreamMenuHelp.description("Mode"))
        }
        listOf("0", "1", "2", "3", "4", "5").forEach {
            assertFits("stream menu mouse mode $it", SettingsHelp.choice("mouse_mode_list", it) +
                "\n\nApplies now; this stream only.")
        }
    }
}
