package com.limelight.shell.companion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import com.limelight.shell.Screen
import com.limelight.shell.data.App
import com.limelight.shell.data.Pc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The companion is never empty (docs/DUAL_SCREEN.md): every route of the shell maps to a kind of content, and the fallback card made for it has a title and
 * a purpose. `contentKind`, `screenTitle` and `screenPurpose` are exhaustive `when`s over `Screen`, so a route that is added does not compile until it is
 * placed; the list below must then name it too (this test counts them).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class CompanionRoutesTest {
    private val pc = Pc("pc-1", "Gaming PC", "Gaming PC", true, true, 0, false, ComputerDetails())
    private val app = App(1, "Asterfall", false, false, NvApp())

    private val routes: List<Screen> = listOf(
        Screen.Home, Screen.Settings, Screen.SettingsSearch, Screen.Profile, Screen.Devices, Screen.Start, Screen.Boot(), Screen.Boot(Screen.Settings),
        Screen.CreateProfile(false, null), Screen.PairPc(Screen.Start), Screen.DebugInfo, Screen.OscPreview, Screen.GameSettings(pc, app), Screen.Artwork(pc, app),
        Screen.Pin("pc-1", "Gaming PC", null, Screen.Start),
    )

    @Test fun everyRouteOfTheShellIsListed() {
        // Screen's direct subclasses: 13 data objects and classes, Boot twice here
        val kinds = routes.map { it::class }.toSet()
        assertEquals(14, kinds.size)
    }

    @Test fun everyRouteHasAContentKind() {
        for (route in routes) assertTrue("$route", contentKind(route) in ContentKind.entries)
    }

    @Test fun everyRouteHasATitleAndAPurposeForTheFallbackCard() {
        val res = ApplicationProvider.getApplicationContext<Context>().resources
        for (route in routes) {
            val title = res.getString(screenTitle(route))
            val purpose = res.getString(screenPurpose(route))
            assertTrue("$route: title", title.isNotBlank())
            assertTrue("$route: purpose", purpose.isNotBlank())
            assertNotEquals("$route: the purpose is not the title", title, purpose)
            // One line of purpose: a sentence, not a paragraph
            assertTrue("$route: $purpose", purpose.length <= 90)
        }
    }

    @Test fun aRouteWithoutContentOfItsOwnGetsTheFallbackCardWithItsTexts() {
        val res = ApplicationProvider.getApplicationContext<Context>().resources
        for (route in routes.filter { contentKind(it) == ContentKind.FALLBACK }) {
            val context = ShellContext(res.getString(screenTitle(route)), res.getString(screenPurpose(route)), emptyList(), null, welcome = false)
            val page = CompanionState.Shell("PC", emptyList(), context).page
            assertTrue("$route", page is FallbackPage)
            assertEquals(context, (page as FallbackPage).context)
        }
    }

    @Test fun theRoutesWithoutAPcGetTheWelcomeCardInsteadOfAStatusCard() {
        val context = ShellContext("Start", "Choose the PC to play on.", emptyList(), null, welcome = true)
        assertTrue(CompanionState.Shell(null, emptyList(), context).page is WelcomePage)
    }

    @Test fun theBiggestPriorityOfThePublishedPagesWins() {
        fun page(name: String, priority: Int) = object : CompanionPage {
            override val key = name
            override val priority = priority

            @androidx.compose.runtime.Composable
            override fun Content() = Unit
        }
        val context = ShellContext("Home", "Games.", emptyList(), null, welcome = false)
        val game = page("game", PagePriority.GAME)
        val filter = page("filter", PagePriority.MODAL)
        val loader = page("loader", PagePriority.OVERLAY)
        val screen = page("pc", PagePriority.SCREEN)
        assertEquals(loader, chooseShellPage(listOf(game, filter, loader, screen), context))
        assertEquals(filter, chooseShellPage(listOf(game, screen, filter), context))
        assertEquals(screen, chooseShellPage(listOf(screen, game), context))
        assertEquals(game, chooseShellPage(listOf(game), context))
    }
}
