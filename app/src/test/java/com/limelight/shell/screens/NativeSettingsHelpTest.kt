package com.limelight.shell.screens

import com.limelight.shell.settings.BackgroundKind
import com.limelight.shell.settings.ClockFormat
import com.limelight.shell.settings.HomeLayout
import com.limelight.shell.StatsPosition
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSettingsHelpTest {
    @Test fun everyAppearanceChoiceHasHelp() {
        val choices = mapOf(
            "ui_background" to BackgroundKind.entries.map { it.label },
            "ui_clock" to ClockFormat.entries.map { it.label },
            "ui_home_layout" to HomeLayout.entries.map { it.label },
            "ui_dark" to listOf("Dark", "Light"),
            "ui_reduced_motion" to listOf("Full", "Reduced"),
            "ui_stats_position" to StatsPosition.entries.map { it.label },
        )
        choices.forEach { (key, labels) -> labels.forEach {
            assertTrue("Missing help for $key/$it", NativeSettingsHelp.choice(key, it).isNotBlank())
        } }
    }
}
