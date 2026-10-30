package com.limelight.shell.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class GameSettingsLabelTest {
    @Test fun noOverridesFollowTheProfile() {
        assertEquals("Follows your profile", customSettingsLabel(0))
    }

    @Test fun overridesAreCountedWithTheRightNumber() {
        assertEquals("1 custom setting", customSettingsLabel(1))
        assertEquals("3 custom settings", customSettingsLabel(3))
    }
}
