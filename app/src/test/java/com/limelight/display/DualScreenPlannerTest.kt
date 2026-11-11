package com.limelight.display

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.limelight.display.DisplayRole.BUILT_IN_COMPANION
import com.limelight.display.DisplayRole.EXTERNAL
import com.limelight.display.DisplayRole.MAIN
import com.limelight.display.DisplayRole.VIRTUAL
import com.limelight.display.MainScreenChoice.AUTOMATIC
import com.limelight.display.MainScreenChoice.SWAPPED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which panel of a dual-screen device is main and which gets the companion screen (docs/DUAL_SCREEN.md). */
class DualScreenPlannerTest {
    // The AYN Thor: top panel is the default display, the bottom one is smaller
    private val top = PanelSource(0, MAIN, 1920, 1080)
    private val bottom = PanelSource(4, BUILT_IN_COMPANION, 1240, 1080)

    private fun main(pair: PanelPair?) = pair?.main?.id
    private fun companion(pair: PanelPair?) = pair?.companion?.id

    @Test fun automaticTakesTheLargerPanelAsMain() {
        val pair = DualScreenPlanner.plan(listOf(top, bottom), AUTOMATIC)
        assertEquals(0, main(pair))
        assertEquals(4, companion(pair))
    }

    @Test fun automaticFollowsThePanelWhenTheCompanionIsTheLargerOne() {
        // A device whose default display is the small one
        val small = PanelSource(0, MAIN, 1240, 1080)
        val large = PanelSource(2, BUILT_IN_COMPANION, 1920, 1080)
        val pair = DualScreenPlanner.plan(listOf(small, large), AUTOMATIC)
        assertEquals(2, main(pair))
        assertEquals(0, companion(pair))
    }

    @Test fun swappedTakesTheOtherPanel() {
        assertEquals(4, main(DualScreenPlanner.plan(listOf(top, bottom), SWAPPED)))
        assertEquals(0, companion(DualScreenPlanner.plan(listOf(top, bottom), SWAPPED)))
        val small = PanelSource(0, MAIN, 1240, 1080)
        val large = PanelSource(2, BUILT_IN_COMPANION, 1920, 1080)
        assertEquals(0, main(DualScreenPlanner.plan(listOf(small, large), SWAPPED)))
    }

    @Test fun equalPanelsGoToTheDefaultDisplayAndSwapMovesThem() {
        val same = PanelSource(3, BUILT_IN_COMPANION, 1920, 1080)
        assertEquals(0, main(DualScreenPlanner.plan(listOf(top, same), AUTOMATIC)))
        assertEquals(3, main(DualScreenPlanner.plan(listOf(top, same), SWAPPED)))
    }

    @Test fun theSizeIsTheNumberOfPixelsNotTheOrientation() {
        // A panel reported 1080 x 1240 (rotated) is as large as 1240 x 1080
        val rotated = PanelSource(4, BUILT_IN_COMPANION, 1080, 1240)
        assertEquals(0, main(DualScreenPlanner.plan(listOf(top, rotated), AUTOMATIC)))
    }

    @Test fun noCompanionDisplayMeansNoPlanWhateverTheChoice() {
        for (choice in MainScreenChoice.entries) {
            assertNull(DualScreenPlanner.plan(listOf(top), choice))
            assertNull(DualScreenPlanner.plan(emptyList(), choice))
            // A monitor or TV, an overlay or a virtual display is not a second panel
            assertNull(DualScreenPlanner.plan(listOf(top, PanelSource(2, EXTERNAL, 3840, 2160)), choice))
            assertNull(DualScreenPlanner.plan(listOf(top, PanelSource(3, VIRTUAL, 1240, 1080)), choice))
        }
    }

    @Test fun externalAndVirtualDisplaysNextToTheTwoPanelsAreIgnored() {
        val pair = DualScreenPlanner.plan(listOf(top, PanelSource(2, EXTERNAL, 3840, 2160), PanelSource(3, VIRTUAL, 5000, 5000), bottom), AUTOMATIC)
        assertEquals(0, main(pair))
        assertEquals(4, companion(pair))
    }

    @Test fun noDefaultDisplayMeansNoPlan() {
        assertNull(DualScreenPlanner.plan(listOf(bottom), AUTOMATIC))
    }

    @Test fun theLowestCompanionCounts() {
        val other = PanelSource(7, BUILT_IN_COMPANION, 3000, 3000)
        assertEquals(4, main(DualScreenPlanner.plan(listOf(top, other, bottom), SWAPPED)))
        assertEquals(4, companion(DualScreenPlanner.plan(listOf(top, other, bottom), AUTOMATIC)))
    }

    @Test fun anActivityOnTheCompanionPanelMovesToTheMainOne() {
        val pair = DualScreenPlanner.plan(listOf(top, bottom), AUTOMATIC)!!
        assertEquals(0, pair.mainFor(4))
        assertNull(pair.mainFor(0))
        // On a monitor, the dual-screen logic leaves it alone
        assertNull(pair.mainFor(2))
        assertEquals(4, DualScreenPlanner.plan(listOf(top, bottom), SWAPPED)!!.mainFor(0))
    }

    @Test fun theCompanionGoesOnThePanelThatIsNotTheActivitys() {
        val pair = DualScreenPlanner.plan(listOf(top, bottom), AUTOMATIC)!!
        assertEquals(4, pair.otherThan(0)?.id)
        assertEquals(0, pair.otherThan(4)?.id)
        // An activity on a monitor gets no companion
        assertNull(pair.otherThan(2))
    }

    @Test fun theChoiceStepsAndWrapsAround() {
        assertEquals(SWAPPED, AUTOMATIC.step(1))
        assertEquals(AUTOMATIC, SWAPPED.step(1))
        assertEquals(SWAPPED, AUTOMATIC.step(-1))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class MainScreenChoiceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)

    @Test fun defaultsToAutomatic() {
        assertEquals(AUTOMATIC, MainScreenChoice.read(prefs))
        assertEquals(AUTOMATIC, DualScreen.choiceOf(context))
    }

    @Test fun storedChoiceIsReadBack() {
        MainScreenChoice.entries.forEach {
            MainScreenChoice.write(prefs, it)
            assertEquals(it, MainScreenChoice.read(prefs))
            assertEquals(it, DualScreen.choiceOf(context))
        }
    }

    @Test fun anUnknownStoredValueMeansAutomatic() {
        prefs.edit().putString(MainScreenChoice.KEY, "something_else").apply()
        assertEquals(AUTOMATIC, MainScreenChoice.read(prefs))
    }

    @Test fun aSingleScreenDeviceHasNoPlanAndStaysOnTheDefaultDisplay() {
        assertNull(DualScreen.plan(context))
        assertEquals(android.view.Display.DEFAULT_DISPLAY, DualScreen.mainDisplayId(context))
    }
}
