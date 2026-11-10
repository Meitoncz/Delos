package com.limelight.shell.settings

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.limelight.shadows.ShadowGameManager
import com.limelight.shadows.ShadowMoonBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en", shadows = [ShadowMoonBridge::class, ShadowGameManager::class])
class SettingsCatalogLocaleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun resourcesIn(locale: Locale) =
        context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) }).resources

    @Test fun theRowsAreRebuiltOnlyWhenTheLocaleChanges() {
        val catalog = SettingsCatalog(context)
        val built = catalog.all
        catalog.refresh(context.resources)
        assertSame("same locale: nothing to rebuild", built, catalog.all)
        catalog.refresh(resourcesIn(Locale.GERMAN))
        assertNotSame("another locale: rebuilt", built, catalog.all)
        assertEquals(built.map { it.key }, catalog.all.map { it.key })
        assertEquals(catalog.all.filterNot { PhoneOnlySettings.isHidden(it.key) }, catalog.visible)
    }

    @Test fun theTabsReadTheirTextFromTheGivenResources() {
        val res = resourcesIn(Locale.US)
        assertEquals("Stream", SettingsTab.STREAM.label(res))
        assertEquals("Paired PCs and what happens when Delos starts.", SettingsTab.DEVICES.description(res))
    }
}
