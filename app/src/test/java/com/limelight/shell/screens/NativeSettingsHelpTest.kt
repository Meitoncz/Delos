package com.limelight.shell.screens

import androidx.test.core.app.ApplicationProvider
import com.limelight.display.SecondDisplayOverride
import com.limelight.shell.StatsPosition
import com.limelight.shell.settings.BackgroundKind
import com.limelight.shell.settings.ClockFormat
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class NativeSettingsHelpTest {
    private val res get() = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    @Test fun everyAppearanceChoiceHasHelp() {
        val choices = mapOf(
            "ui_background" to BackgroundKind.entries.map { it.name },
            "ui_clock" to ClockFormat.entries.map { it.name },
            "ui_dark" to listOf("dark", "light"),
            "ui_reduced_motion" to listOf("full", "reduced"),
            "ui_stats_position" to StatsPosition.entries.map { it.name },
            "ui_default_pc" to listOf("none", "pc"),
            SecondDisplayOverride.KEY to SecondDisplayOverride.entries.map { it.name },
        )
        choices.forEach { (key, ids) -> ids.forEach {
            assertTrue("Missing help for $key/$it", NativeSettingsHelp.choice(res, key, it).isNotBlank())
        } }
    }
}
