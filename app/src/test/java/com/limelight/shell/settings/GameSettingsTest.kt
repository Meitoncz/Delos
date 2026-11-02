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
import com.limelight.utils.ServerHelper
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
        profiles = ProfileStore(context) { _, purge -> purge() }
        profiles.create("Player", 0)
        profiles.settings().edit().putString("list_resolution", "1920x1080").putString("list_fps", "60")
            .putString("video_format", "auto").putInt("seekbar_bitrate_kbps", 20000).commit()
    }

    private fun game(pc: String = "pc-a", id: Int = 1) = GameSettingsStore(profiles.settings(), pc, id)
    private fun spec(key: String) = profiles.catalog.all.first { it.key == key }

    @Test fun qualityChangesPreserveInheritedAndCustomNonPyroWaveBitrates() {
        val selected = game()
        val stream = StreamSettingsStore(profiles.catalog) { selected.preferences }
        stream.write(spec("list_resolution"), "1280x720")
        stream.write(spec("list_fps"), "120")
        stream.write(spec("video_format"), "forceav1")
        assertFalse(selected.isCustom("seekbar_bitrate_kbps"))
        assertEquals(20000, selected.preferences.getInt("seekbar_bitrate_kbps", -1))
        profiles.settings().edit().putInt("seekbar_bitrate_kbps", 37000).commit()
        assertEquals(37000, selected.preferences.getInt("seekbar_bitrate_kbps", -1))
        stream.write(spec("seekbar_bitrate_kbps"), 45000)
        stream.write(spec("list_resolution"), "3840x2160")
        stream.write(spec("list_fps"), "60")
        stream.write(spec("video_format"), "forceh265")
        profiles.settings().edit().putInt("seekbar_bitrate_kbps", 51000).commit()
        assertEquals(45000, selected.preferences.getInt("seekbar_bitrate_kbps", -1))
        assertEquals(51000, game(id = 2).preferences.getInt("seekbar_bitrate_kbps", -1))
        stream.close()
    }

    @Test fun threeCodecBitratesRoundtripInBothDirectionsAndStayLocalToPcAndGame() {
        val selected = game()
        val stream = StreamSettingsStore(profiles.catalog) { selected.preferences }
        stream.write(spec("video_format"), "forceav1")
        stream.write(spec("seekbar_bitrate_kbps"), 200000)
        stream.write(spec("list_fps"), "120")
        stream.write(spec("checkbox_enable_hdr"), true)
        stream.write(spec("video_format"), "forceh265")
        assertEquals(200000, selected.preferences.getInt("seekbar_bitrate_kbps", -1))
        stream.write(spec("seekbar_bitrate_kbps"), 80000)
        stream.write(spec("video_format"), "pyrowave")
        assertEquals(530000, selected.preferences.getInt("seekbar_bitrate_kbps", -1))
        stream.write(spec("seekbar_bitrate_kbps"), 528000)
        stream.write(spec("video_format"), "forceav1")
        assertEquals(200000, selected.preferences.getInt("seekbar_bitrate_kbps", -1))
        stream.write(spec("video_format"), "pyrowave")
        assertEquals(528000, selected.preferences.getInt("seekbar_bitrate_kbps", -1))
        stream.write(spec("video_format"), "forceh265")
        assertEquals(80000, selected.preferences.getInt("seekbar_bitrate_kbps", -1))
        stream.write(spec("video_format"), "forceav1")
        assertEquals(200000, selected.preferences.getInt("seekbar_bitrate_kbps", -1))
        val other = game(pc = "pc-b")
        val otherStream = StreamSettingsStore(profiles.catalog) { other.preferences }
        otherStream.write(spec("video_format"), "forceav1")
        otherStream.write(spec("seekbar_bitrate_kbps"), 110000)
        otherStream.write(spec("video_format"), "forceh265")
        otherStream.write(spec("seekbar_bitrate_kbps"), 90000)
        otherStream.write(spec("video_format"), "forceav1")
        assertEquals(110000, other.preferences.getInt("seekbar_bitrate_kbps", -1))
        assertEquals(200000, selected.preferences.getInt("seekbar_bitrate_kbps", -1))
        assertFalse(game(id = 2).preferences.contains(codecBitrateKey("forceav1")))
        assertEquals(20000, game(id = 2).preferences.getInt("seekbar_bitrate_kbps", -1))
        assertEquals(20000, profiles.settings().getInt("seekbar_bitrate_kbps", -1))
        stream.close()
        otherStream.close()
    }

    @Test fun resettingBitrateAndAllOverridesDropsOnlyThatGamesCodecSlots() {
        val selected = game()
        val other = game(id = 2)
        fun seed(target: GameSettingsStore, av1: Int, hevc: Int) {
            val stream = StreamSettingsStore(profiles.catalog) { target.preferences }
            stream.write(spec("video_format"), "forceav1")
            stream.write(spec("seekbar_bitrate_kbps"), av1)
            stream.write(spec("video_format"), "forceh265")
            stream.write(spec("seekbar_bitrate_kbps"), hevc)
            stream.write(spec("video_format"), "pyrowave")
            stream.write(spec("seekbar_bitrate_kbps"), 528000)
            stream.write(spec("video_format"), "forceav1")
            stream.close()
        }
        seed(selected, 200000, 80000)
        seed(other, 110000, 90000)
        selected.reset("seekbar_bitrate_kbps")
        listOf("forceav1", "forceh265", "pyrowave").forEach {
            assertFalse(selected.preferences.contains(codecBitrateKey(it)))
        }
        profiles.settings().edit().putInt("seekbar_bitrate_kbps", 37000).commit()
        val stream = StreamSettingsStore(profiles.catalog) { selected.preferences }
        stream.write(spec("video_format"), "forceh265")
        assertFalse(selected.isCustom("seekbar_bitrate_kbps"))
        assertEquals(37000, selected.preferences.getInt("seekbar_bitrate_kbps", -1))
        stream.write(spec("video_format"), "pyrowave")
        assertEquals(225000, selected.preferences.getInt("seekbar_bitrate_kbps", -1))
        stream.write(spec("seekbar_bitrate_kbps"), 300000)
        stream.write(spec("video_format"), "forceav1")
        selected.resetAll()
        assertTrue(selected.overrides().isEmpty())
        listOf("forceav1", "forceh265", "pyrowave").forEach {
            assertFalse(selected.preferences.contains(codecBitrateKey(it)))
        }
        assertEquals(37000, selected.preferences.getInt("seekbar_bitrate_kbps", -1))
        val otherStream = StreamSettingsStore(profiles.catalog) { other.preferences }
        otherStream.write(spec("video_format"), "pyrowave")
        assertEquals(528000, other.preferences.getInt("seekbar_bitrate_kbps", -1))
        otherStream.write(spec("video_format"), "forceh265")
        assertEquals(90000, other.preferences.getInt("seekbar_bitrate_kbps", -1))
        otherStream.write(spec("video_format"), "forceav1")
        assertEquals(110000, other.preferences.getInt("seekbar_bitrate_kbps", -1))
        stream.close()
        otherStream.close()
    }

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
        // The removed user takes their overrides with them
        val removed = context.getSharedPreferences("ui_profile_${second.id}", Context.MODE_PRIVATE)
        assertTrue(removed.all.keys.none { it.startsWith("ui_game_") })
    }

    @Test fun gameCodecSlotsAreCopiedIsolatedAndDeletedWithTheirUser() {
        val first = profiles.profiles.value.current!!.id
        val selected = game()
        val stream = StreamSettingsStore(profiles.catalog) { selected.preferences }
        stream.write(spec("video_format"), "forceav1")
        stream.write(spec("seekbar_bitrate_kbps"), 200000)
        stream.write(spec("video_format"), "forceh265")
        stream.write(spec("seekbar_bitrate_kbps"), 80000)
        stream.write(spec("video_format"), "pyrowave")
        stream.write(spec("seekbar_bitrate_kbps"), 528000)
        stream.write(spec("video_format"), "forceav1")
        val second = profiles.create("Other player", 1)
        val copied = game()
        val copiedStream = StreamSettingsStore(profiles.catalog) { copied.preferences }
        copiedStream.write(spec("video_format"), "forceh265")
        assertEquals(80000, copied.preferences.getInt("seekbar_bitrate_kbps", -1))
        copiedStream.write(spec("video_format"), "pyrowave")
        assertEquals(528000, copied.preferences.getInt("seekbar_bitrate_kbps", -1))
        copiedStream.write(spec("video_format"), "forceav1")
        assertEquals(200000, copied.preferences.getInt("seekbar_bitrate_kbps", -1))
        copiedStream.write(spec("seekbar_bitrate_kbps"), 310000)
        copiedStream.write(spec("video_format"), "forceh265")
        profiles.switchTo(first)
        stream.write(spec("video_format"), "pyrowave")
        assertEquals(528000, selected.preferences.getInt("seekbar_bitrate_kbps", -1))
        stream.write(spec("video_format"), "forceh265")
        assertEquals(80000, selected.preferences.getInt("seekbar_bitrate_kbps", -1))
        stream.write(spec("video_format"), "forceav1")
        assertEquals(200000, selected.preferences.getInt("seekbar_bitrate_kbps", -1))
        profiles.delete(second.id)
        val removed = context.getSharedPreferences("ui_profile_${second.id}", Context.MODE_PRIVATE)
        assertTrue(removed.all.keys.none { it.startsWith("ui_game_") })
        stream.close()
        copiedStream.close()
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

    @Test fun forgettingAPcDropsAllItsCodecSlotsAcrossUsersAndKeepsOtherPcs() {
        val first = profiles.profiles.value.current!!.id
        fun seed(target: GameSettingsStore, av1: Int, hevc: Int) {
            val stream = StreamSettingsStore(profiles.catalog) { target.preferences }
            stream.write(spec("video_format"), "forceav1")
            stream.write(spec("seekbar_bitrate_kbps"), av1)
            stream.write(spec("video_format"), "forceh265")
            stream.write(spec("seekbar_bitrate_kbps"), hevc)
            stream.write(spec("video_format"), "pyrowave")
            stream.write(spec("seekbar_bitrate_kbps"), 528000)
            stream.write(spec("video_format"), "forceav1")
            stream.close()
        }
        seed(game(), 200000, 80000)
        seed(game(id = 2), 30000, 40000)
        seed(game(pc = "pc-b"), 110000, 90000)
        val second = profiles.create("Other player", 1)
        profiles.forgetGameSettings("pc-a")
        for (id in listOf(second.id, first)) {
            profiles.switchTo(id)
            for (app in listOf(1, 2)) {
                val removed = game(id = app)
                assertTrue(removed.overrides().isEmpty())
                listOf("forceav1", "forceh265", "pyrowave").forEach {
                    assertFalse(removed.preferences.contains(codecBitrateKey(it)))
                }
            }
            val kept = game(pc = "pc-b")
            val stream = StreamSettingsStore(profiles.catalog) { kept.preferences }
            stream.write(spec("video_format"), "forceh265")
            assertEquals(90000, kept.preferences.getInt("seekbar_bitrate_kbps", -1))
            stream.write(spec("video_format"), "pyrowave")
            assertEquals(528000, kept.preferences.getInt("seekbar_bitrate_kbps", -1))
            stream.write(spec("video_format"), "forceav1")
            assertEquals(110000, kept.preferences.getInt("seekbar_bitrate_kbps", -1))
            stream.close()
        }
    }

    @Test fun inheritedBitratesNeverBecomeCodecSlotsAndLeavingPyroWaveResumesInheritance() {
        val profileStream = StreamSettingsStore(profiles.catalog, profiles::settings)
        profileStream.write(spec("video_format"), "forceav1")
        profileStream.write(spec("seekbar_bitrate_kbps"), 200000)
        profileStream.write(spec("video_format"), "forceh265")
        profileStream.write(spec("seekbar_bitrate_kbps"), 80000)
        profileStream.write(spec("video_format"), "forceav1")
        val a = game()
        val stream = StreamSettingsStore(profiles.catalog) { a.preferences }
        assertFalse(a.preferences.contains(codecBitrateKey("forceav1")))
        stream.write(spec("video_format"), "forceh265")
        assertFalse(a.isCustom("seekbar_bitrate_kbps"))
        assertEquals(200000, a.preferences.getInt("seekbar_bitrate_kbps", -1))
        stream.write(spec("video_format"), "pyrowave")
        assertTrue(a.isCustom("seekbar_bitrate_kbps"))
        assertEquals(225000, a.preferences.getInt("seekbar_bitrate_kbps", -1))
        assertFalse(a.preferences.contains(codecBitrateKey("forceh265")))
        stream.write(spec("seekbar_bitrate_kbps"), 528000)
        profiles.settings().edit().putInt("seekbar_bitrate_kbps", 37000).commit()
        stream.write(spec("video_format"), "forceh265")
        assertFalse(a.isCustom("seekbar_bitrate_kbps"))
        assertEquals(37000, a.preferences.getInt("seekbar_bitrate_kbps", -1))
        stream.write(spec("video_format"), "pyrowave")
        assertEquals(528000, a.preferences.getInt("seekbar_bitrate_kbps", -1))
        stream.write(spec("video_format"), "forceh265")
        profiles.settings().edit().putInt("seekbar_bitrate_kbps", 45000).commit()
        assertEquals(45000, a.preferences.getInt("seekbar_bitrate_kbps", -1))
        stream.close()
        profileStream.close()
    }

    @Test fun recalculatingAnInheritedPyroWaveCodecCreatesOnlyItsOwnSlotOnExit() {
        val profileStream = StreamSettingsStore(profiles.catalog, profiles::settings)
        profileStream.write(spec("video_format"), "pyrowave")
        profileStream.write(spec("seekbar_bitrate_kbps"), 528000)
        profileStream.write(spec("video_format"), "forceav1")
        profileStream.write(spec("seekbar_bitrate_kbps"), 80000)
        profileStream.write(spec("video_format"), "pyrowave")
        val a = game()
        val stream = StreamSettingsStore(profiles.catalog) { a.preferences }
        assertFalse(a.preferences.contains(codecBitrateKey("pyrowave")))
        assertFalse(a.preferences.contains(codecBitrateKey("forceav1")))
        stream.write(spec("checkbox_enable_hdr"), true)
        val recommended = PreferenceConfiguration.getDefaultBitrate("1920x1080", "60", "pyrowave", true)
        assertEquals(recommended, a.preferences.getInt("seekbar_bitrate_kbps", -1))
        stream.write(spec("video_format"), "forceav1")
        assertFalse(a.isCustom("seekbar_bitrate_kbps"))
        assertEquals(528000, a.preferences.getInt("seekbar_bitrate_kbps", -1))
        stream.write(spec("video_format"), "pyrowave")
        assertEquals(recommended, a.preferences.getInt("seekbar_bitrate_kbps", -1))
        stream.close()
        profileStream.close()
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

        val unwrapped = ServerHelper.getStreamIntent(outer)
        unwrapped.putExtra(Game.EXTRA_PC_NAME, "Living room")
        outer.putExtra(ExternalDisplayControlActivity.EXTRA_LAUNCH_INTENT, unwrapped)
        @Suppress("DEPRECATION")
        val configured = outer.getParcelableExtra<Intent>(ExternalDisplayControlActivity.EXTRA_LAUNCH_INTENT)!!
        assertEquals("Living room", configured.getStringExtra(Game.EXTRA_PC_NAME))
        assertSame(configured, ServerHelper.getStreamIntent(configured))
    }

    @Test fun hdrUpscalerQualityUsesGameSnapshotAndSessionChangesLeaveDefaultsIntact() {
        val a = game()
        val store = StreamSettingsStore(profiles.catalog) { a.preferences }
        store.write(spec("checkbox_enable_hdr"), true)
        store.write(spec("checkbox_sgsr"), true)
        store.write(spec("list_sgsr_quality"), "high")
        store.write(spec("seekbar_sgsr_sharpness"), 75)
        val intent = Intent(context, Game::class.java)
        GameSettingsStore.attach(intent, a.snapshot())
        val activity = Robolectric.buildActivity(Game::class.java, intent).get()
        val config = PreferenceConfiguration.readPreferences(activity)
        assertTrue(config.enableHdr)
        assertTrue(config.sgsrEnabled)
        assertTrue(config.sgsrHighQuality)
        assertEquals(75, config.sgsrSharpness)
        assertEquals(SettingsTab.DISPLAY, spec("list_sgsr_quality").tab)
        assertEquals(listOf("standard", "high", "temporal", "temporal-quality"), spec("list_sgsr_quality").choices.map { it.value })
        assertEquals("standard", game(id = 2).preferences.getString("list_sgsr_quality", "standard"))
        store.close()
    }

    @Test fun curatedKeysAreRealEditableSettingsAndCannotWriteUnrelatedPreferences() {
        GameSettingsStore.KEYS.forEach { key -> assertTrue(spec(key).kind in setOf(SettingKind.TOGGLE, SettingKind.CHOICE, SettingKind.SLIDER)) }
        assertThrows(IllegalArgumentException::class.java) { game().preferences.edit().putString("ui_profile", "other") }
    }

    @Test fun temporalVideoModeIsScopedToTheGameAndUnknownModesFallBackToStandard() {
        game().preferences.edit().putBoolean("checkbox_sgsr",true)
            .putBoolean("checkbox_enable_hdr",true).putString("list_sgsr_quality","temporal").commit()
        val intent=Intent(context,Game::class.java)
        GameSettingsStore.attach(intent,game().snapshot())
        val config=PreferenceConfiguration.readPreferences(Robolectric.buildActivity(Game::class.java,intent).get())
        assertTrue(config.sgsrEnabled)
        assertTrue(config.enableHdr)
        assertTrue(config.sgsrTemporal)
        assertFalse(config.sgsrHighQuality)
        assertFalse(PreferenceConfiguration.readPreferences(context).sgsrTemporal)
        profiles.settings().edit().putString("list_sgsr_quality","unknown-future-mode").commit()
        val fallback=PreferenceConfiguration.readPreferences(context)
        assertFalse(fallback.sgsrTemporal)
        assertFalse(fallback.sgsrHighQuality)
    }

    @Test fun temporalQualityModeEnablesTemporalQualityWithoutSpatialHighQuality() {
        profiles.settings().edit().putString("list_sgsr_quality","temporal-quality").commit()
        val config=PreferenceConfiguration.readPreferences(context)
        assertTrue(config.sgsrTemporal)
        assertTrue(config.sgsrTemporalQuality)
        assertFalse(config.sgsrHighQuality)
    }
}
