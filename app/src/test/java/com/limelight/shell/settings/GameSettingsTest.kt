package com.limelight.shell.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.limelight.Game
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.profiles.ProfilesManager
import com.limelight.shadows.ShadowGameManager
import com.limelight.shadows.ShadowMoonBridge
import com.limelight.shell.data.ProfileStore
import com.limelight.utils.ExternalDisplayControlActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [ShadowMoonBridge::class, ShadowGameManager::class])
class GameSettingsTest {
    private lateinit var context: Context
    private lateinit var profiles: ProfileStore

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        ProfilesManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        ProfilesManager.getInstance().load(context)
        profiles = ProfileStore(context)
        profiles.create("Player", 0)
        profiles.settings().edit().putString("list_resolution", "1920x1080").putString("list_fps", "60")
            .putString("video_format", "auto").putInt("seekbar_bitrate_kbps", 20000).commit()
    }

    private fun game(pc: String = "pc-a", id: Int = 1) = GameSettingsStore(profiles.settings(), pc, id)
    private fun spec(key: String) = profiles.catalog.all.first { it.key == key }

    @Test fun unmodifiedValuesFollowProfileWhileOverridesStayLocalToPcAndGame() {
        val a = game()
        assertTrue(a.overrides().isEmpty())
        a.preferences.edit().putString("list_resolution", "1280x720").commit()
        profiles.settings().edit().putString("list_resolution", "3840x2160").putString("list_fps", "120").commit()
        assertEquals("1280x720", a.preferences.getString("list_resolution", null))
        assertEquals("120", a.preferences.getString("list_fps", null))
        assertEquals("3840x2160", game(id = 2).preferences.getString("list_resolution", null))
        assertEquals("3840x2160", game(pc = "pc-b").preferences.getString("list_resolution", null))
        assertEquals("3840x2160", ProfilesManager.getInstance().getOverlayingSharedPreferences(context).getString("list_resolution", null))
        assertEquals("1280x720", game().preferences.getString("list_resolution", null))
        a.reset("list_resolution")
        assertEquals("3840x2160", a.preferences.getString("list_resolution", null))
    }

    @Test fun newUsersCopyGameOverridesThenRemainIndependent() {
        val first = profiles.profiles.value.current!!.id
        game().preferences.edit().putBoolean("checkbox_enable_hdr", true).commit()
        val second = profiles.create("Other player", 1)
        assertTrue(game().preferences.getBoolean("checkbox_enable_hdr", false))
        game().preferences.edit().putBoolean("checkbox_enable_hdr", false).commit()
        profiles.switchTo(first)
        assertTrue(game().preferences.getBoolean("checkbox_enable_hdr", false))
        profiles.delete(second.id)
        assertTrue(game().isCustom("checkbox_enable_hdr"))
    }

    @Test fun resettingAllOnlyRemovesThisGamesOverrides() {
        game().preferences.edit().putInt("seekbar_bitrate_kbps", 42000).putBoolean("checkbox_full_range", true).commit()
        game(id = 2).preferences.edit().putInt("seekbar_bitrate_kbps", 33000).commit()
        game().resetAll()
        assertTrue(game().overrides().isEmpty())
        assertEquals(20000, game().preferences.getInt("seekbar_bitrate_kbps", 0))
        assertEquals(33000, game(id = 2).preferences.getInt("seekbar_bitrate_kbps", 0))
        assertEquals(20000, profiles.settings().getInt("seekbar_bitrate_kbps", 0))
    }

    @Test fun codecAndHdrUseExistingBitrateGuidanceWithoutChangingProfile() {
        val a = game()
        val stream = StreamSettingsStore(profiles.catalog) { a.preferences }
        stream.write(spec("video_format"), "pyrowave")
        assertEquals(PreferenceConfiguration.getDefaultBitrate("1920x1080", "60", "pyrowave", false),
            a.preferences.getInt("seekbar_bitrate_kbps", 0))
        stream.write(spec("checkbox_enable_hdr"), true)
        assertEquals(PreferenceConfiguration.getDefaultBitrate("1920x1080", "60", "pyrowave", true),
            a.preferences.getInt("seekbar_bitrate_kbps", 0))
        assertEquals(20000, profiles.settings().getInt("seekbar_bitrate_kbps", 0))
        assertEquals("auto", profiles.settings().getString("video_format", null))
        stream.close()
    }

    @Test fun streamReaderUsesLaunchSnapshotWithoutLeakingIntoOtherActivitiesOrLaterGames() {
        val a = game()
        a.preferences.edit().putString("list_resolution", "1280x720").putInt("seekbar_bitrate_kbps", 17000)
            .putBoolean("checkbox_use_virtual_display", false).commit()
        val intent = Intent(context, Game::class.java)
        GameSettingsStore.attach(intent, a.snapshot())
        val activity = Robolectric.buildActivity(Game::class.java, intent).get()
        val config = PreferenceConfiguration.readPreferences(activity)
        assertEquals(1280, config.width)
        assertEquals(720, config.height)
        assertEquals(17000, config.bitrate)
        assertFalse(config.useVirtualDisplay)
        a.resetAll()
        assertEquals(1280, PreferenceConfiguration.readPreferences(ContextWrapper(activity)).width)
        assertEquals(1920, PreferenceConfiguration.readPreferences(context).width)
        val otherActivity = Robolectric.buildActivity(Activity::class.java, intent).get()
        assertEquals(1920, PreferenceConfiguration.readPreferences(otherActivity).width)
        val nextIntent = Intent(context, Game::class.java)
        GameSettingsStore.attach(nextIntent, game(id = 2).snapshot())
        val next = Robolectric.buildActivity(Game::class.java, nextIntent).get()
        assertEquals(1920, PreferenceConfiguration.readPreferences(next).width)
    }

    @Test fun externalDisplayWrapperCarriesSnapshotToInnerStreamIntent() {
        game().preferences.edit().putString("list_resolution", "1280x720").commit()
        val inner = Intent(context, Game::class.java)
        val outer = Intent(context, ExternalDisplayControlActivity::class.java)
            .putExtra(ExternalDisplayControlActivity.EXTRA_LAUNCH_INTENT, inner)
        GameSettingsStore.attach(outer, game().snapshot())
        @Suppress("DEPRECATION")
        val stream = outer.getParcelableExtra<Intent>(ExternalDisplayControlActivity.EXTRA_LAUNCH_INTENT)!!
        assertEquals(1280, PreferenceConfiguration.readPreferences(Robolectric.buildActivity(Game::class.java, stream).get()).width)
    }

    @Test fun curatedKeysAreRealEditableSettingsAndCannotWriteUnrelatedPreferences() {
        GameSettingsStore.KEYS.forEach { key -> assertTrue(spec(key).kind in setOf(SettingKind.TOGGLE, SettingKind.CHOICE, SettingKind.SLIDER)) }
        assertThrows(IllegalArgumentException::class.java) { game().preferences.edit().putString("ui_profile", "other") }
    }
}
