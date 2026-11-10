package com.limelight.shell.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.limelight.DelosStreamActivity
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.preferences.UpscalerMode
import com.limelight.preferences.PyroWaveBitrate
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
        stream.write(spec("list_pyrowave_chroma"), "444")
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
        val recommended = PreferenceConfiguration.getDefaultBitrate("1920x1080", "60", "pyrowave", true, false)
        assertEquals(recommended, a.preferences.getInt("seekbar_bitrate_kbps", -1))
        stream.write(spec("video_format"), "forceav1")
        assertFalse(a.isCustom("seekbar_bitrate_kbps"))
        assertEquals(528000, a.preferences.getInt("seekbar_bitrate_kbps", -1))
        stream.write(spec("video_format"), "pyrowave")
        assertEquals(recommended, a.preferences.getInt("seekbar_bitrate_kbps", -1))
        stream.close()
        profileStream.close()
    }

    @Test fun perGameChromaRecalculatesInheritedPyroWaveWithoutLeakingOrBreakingInheritance() {
        val profileStream = StreamSettingsStore(profiles.catalog, profiles::settings)
        profileStream.write(spec("video_format"), "pyrowave")
        profileStream.write(spec("seekbar_bitrate_kbps"), 528000)
        val selected = game()
        val stream = StreamSettingsStore(profiles.catalog) { selected.preferences }
        val sdr444 = PyroWaveBitrate.recommendedKbps(1920, 1080, 60, true, false)
        val sdr420 = PyroWaveBitrate.recommendedKbps(1920, 1080, 60, false, false)
        val hdr420 = PyroWaveBitrate.recommendedKbps(1920, 1080, 60, false, true)
        assertFalse(selected.isCustom("seekbar_bitrate_kbps"))
        stream.write(spec("list_pyrowave_chroma"), "444")
        assertEquals(sdr444, selected.preferences.getInt("seekbar_bitrate_kbps", -1))
        assertTrue(selected.isCustom("seekbar_bitrate_kbps"))
        stream.write(spec("list_pyrowave_chroma"), "420")
        assertEquals(sdr420, selected.preferences.getInt("seekbar_bitrate_kbps", -1))
        stream.write(spec("list_pyrowave_chroma"), "444")
        stream.write(spec("checkbox_enable_hdr"), true)
        assertEquals(hdr420, selected.preferences.getInt("seekbar_bitrate_kbps", -1))
        for (chroma in listOf("420", "444")) {
            stream.write(spec("list_pyrowave_chroma"), chroma)
            assertEquals(hdr420, selected.preferences.getInt("seekbar_bitrate_kbps", -1))
        }
        stream.write(spec("checkbox_enable_hdr"), false)
        assertEquals(sdr444, selected.preferences.getInt("seekbar_bitrate_kbps", -1))
        assertEquals(528000, profiles.settings().getInt("seekbar_bitrate_kbps", -1))
        assertEquals(528000, game(id = 2).preferences.getInt("seekbar_bitrate_kbps", -1))
        assertFalse(game(id = 2).isCustom("list_pyrowave_chroma"))
        assertFalse(selected.preferences.contains(codecBitrateKey("pyrowave")))
        stream.write(spec("video_format"), "forceav1")
        assertFalse(selected.isCustom("seekbar_bitrate_kbps"))
        assertFalse(selected.preferences.contains(codecBitrateKey("forceav1")))
        assertEquals(sdr444, selected.preferences.getInt(codecBitrateKey("pyrowave"), -1))
        profiles.settings().edit().putInt("seekbar_bitrate_kbps", 37000).commit()
        assertEquals(37000, selected.preferences.getInt("seekbar_bitrate_kbps", -1))
        stream.write(spec("video_format"), "pyrowave")
        assertEquals(sdr444, selected.preferences.getInt("seekbar_bitrate_kbps", -1))
        selected.reset("seekbar_bitrate_kbps")
        assertFalse(selected.preferences.contains(codecBitrateKey("pyrowave")))
        assertEquals(37000, selected.preferences.getInt("seekbar_bitrate_kbps", -1))
        stream.close()
        profileStream.close()
    }

    @Test fun automaticGameBitrateUsesSnapshotChromaAndHdrPrecedence() {
        profiles.settings().edit().putString("video_format", "pyrowave")
            .putString("list_pyrowave_chroma", "420").putInt("seekbar_bitrate_kbps", 0).commit()
        val selected = game()
        selected.preferences.edit().putString("list_pyrowave_chroma", "444").commit()
        for (hdr in listOf(false, true)) {
            selected.preferences.edit().putBoolean("checkbox_enable_hdr", hdr).commit()
            val intent = Intent(context, DelosStreamActivity::class.java)
            GameSettingsStore.attach(intent, selected.snapshot())
            val activity = Robolectric.buildActivity(DelosStreamActivity::class.java, intent).get()
            assertEquals(PyroWaveBitrate.recommendedKbps(1920, 1080, 60, !hdr, hdr),
                PreferenceConfiguration.readPreferences(activity).bitrate)
        }
        assertEquals(PyroWaveBitrate.recommendedKbps(1920, 1080, 60, false, false),
            PreferenceConfiguration.readPreferences(context).bitrate)
    }

    @Test fun streamReaderUsesLaunchSnapshotWithoutLeakingIntoOtherActivitiesOrLaterGames() {
        val a = game()
        a.preferences.edit().putString("list_resolution", "1280x720").putInt("seekbar_bitrate_kbps", 17000)
            .putBoolean("checkbox_use_virtual_display", false).commit()
        val intent = Intent(context, DelosStreamActivity::class.java)
        GameSettingsStore.attach(intent, a.snapshot())
        val activity = Robolectric.buildActivity(DelosStreamActivity::class.java, intent).get()
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
        val nextIntent = Intent(context, DelosStreamActivity::class.java)
        GameSettingsStore.attach(nextIntent, game(id = 2).snapshot())
        val next = Robolectric.buildActivity(DelosStreamActivity::class.java, nextIntent).get()
        assertEquals(1920, PreferenceConfiguration.readPreferences(next).width)
    }

    @Test fun externalDisplayWrapperCarriesSnapshotToInnerStreamIntent() {
        game().preferences.edit().putString("list_resolution", "1280x720").commit()
        val inner = Intent(context, DelosStreamActivity::class.java)
        val outer = Intent(context, ExternalDisplayControlActivity::class.java)
            .putExtra(ExternalDisplayControlActivity.EXTRA_LAUNCH_INTENT, inner)
        GameSettingsStore.attach(outer, game().snapshot())
        @Suppress("DEPRECATION")
        val stream = outer.getParcelableExtra<Intent>(ExternalDisplayControlActivity.EXTRA_LAUNCH_INTENT)!!
        assertEquals(1280, PreferenceConfiguration.readPreferences(Robolectric.buildActivity(DelosStreamActivity::class.java, stream).get()).width)

        val unwrapped = ServerHelper.getStreamIntent(outer)
        unwrapped.putExtra(DelosStreamActivity.EXTRA_PC_NAME, "Living room")
        outer.putExtra(ExternalDisplayControlActivity.EXTRA_LAUNCH_INTENT, unwrapped)
        @Suppress("DEPRECATION")
        val configured = outer.getParcelableExtra<Intent>(ExternalDisplayControlActivity.EXTRA_LAUNCH_INTENT)!!
        assertEquals("Living room", configured.getStringExtra(DelosStreamActivity.EXTRA_PC_NAME))
        assertSame(configured, ServerHelper.getStreamIntent(configured))
    }

    @Test fun hdrUpscalerQualityUsesGameSnapshotAndSessionChangesLeaveDefaultsIntact() {
        val a = game()
        val store = StreamSettingsStore(profiles.catalog) { a.preferences }
        store.write(spec("checkbox_enable_hdr"), true)
        store.write(spec("checkbox_sgsr"), true)
        store.write(spec("list_sgsr_quality"), "high")
        store.write(spec("seekbar_sgsr_sharpness"), 75)
        val intent = Intent(context, DelosStreamActivity::class.java)
        GameSettingsStore.attach(intent, a.snapshot())
        val activity = Robolectric.buildActivity(DelosStreamActivity::class.java, intent).get()
        val config = PreferenceConfiguration.readPreferences(activity)
        assertTrue(config.enableHdr)
        assertTrue(config.sgsrEnabled)
        assertEquals(UpscalerMode.HIGH, config.sgsrMode)
        assertEquals(75, config.sgsrSharpness)
        assertEquals(SettingsTab.DISPLAY, spec("list_sgsr_quality").tab)
        assertEquals(listOf("standard", "high", "temporal", "temporal-quality", "sharpen"), spec("list_sgsr_quality").choices.map { it.value })
        // The Settings list and the stream menu name every mode alike
        assertEquals(UpscalerMode.values().map { context.getString(it.labelRes()) }, spec("list_sgsr_quality").choices.map { it.label })
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
        val intent=Intent(context,DelosStreamActivity::class.java)
        GameSettingsStore.attach(intent,game().snapshot())
        val config=PreferenceConfiguration.readPreferences(Robolectric.buildActivity(DelosStreamActivity::class.java,intent).get())
        assertTrue(config.sgsrEnabled)
        assertTrue(config.enableHdr)
        assertEquals(UpscalerMode.VIDEO, config.sgsrMode)
        assertNotEquals(UpscalerMode.VIDEO, PreferenceConfiguration.readPreferences(context).sgsrMode)
        profiles.settings().edit().putString("list_sgsr_quality","unknown-future-mode").commit()
        val fallback=PreferenceConfiguration.readPreferences(context)
        assertEquals(UpscalerMode.STANDARD, fallback.sgsrMode)
    }

    @Test fun temporalQualityModeIsItsOwnModeAndSharpenOnlyIsStoredAsSharpen() {
        profiles.settings().edit().putString("list_sgsr_quality","temporal-quality").commit()
        assertEquals(UpscalerMode.VIDEO_QUALITY, PreferenceConfiguration.readPreferences(context).sgsrMode)
        // A build before Sharpen only stored nothing else than the four older values, which read unchanged
        profiles.settings().edit().putString("list_sgsr_quality","sharpen").commit()
        val config=PreferenceConfiguration.readPreferences(context)
        assertEquals(UpscalerMode.SHARPEN_ONLY, config.sgsrMode)
        assertFalse(config.sgsrMode.scales())
    }

    @Test fun sharpenOnlySavedForAGameIsReadBackByItsNextLaunchOnly() {
        GameSettingsStore.forLaunch(launched())!!.save(upscalerSaveValues(true, UpscalerMode.SHARPEN_ONLY.index(), 65, 2))
        val next = PreferenceConfiguration.readPreferences(launched())
        assertTrue(next.sgsrEnabled)
        assertEquals(UpscalerMode.SHARPEN_ONLY, next.sgsrMode)
        assertEquals(65, next.sgsrSharpness)
        assertEquals(UpscalerMode.STANDARD, PreferenceConfiguration.readPreferences(launched(id = 2)).sgsrMode)
    }

    private fun launched(pc: String = "pc-a", id: Int = 1, snapshot: Boolean = true): DelosStreamActivity {
        val intent = Intent(context, DelosStreamActivity::class.java).putExtra(DelosStreamActivity.EXTRA_PC_UUID, pc).putExtra(DelosStreamActivity.EXTRA_APP_ID, id)
        if (snapshot) GameSettingsStore.attach(intent, game(pc, id).snapshot())
        return Robolectric.buildActivity(DelosStreamActivity::class.java, intent).get()
    }

    private fun profileValues() = profiles.settings().all.filterKeys { !it.startsWith("ui_game_") }

    @Test fun savingTheLiveUpscalerWritesOnlyItsFourOverridesForThatGame() {
        profiles.settings().edit().putBoolean("checkbox_sgsr", false).putString("list_sgsr_quality", "high")
            .putInt("seekbar_sgsr_sharpness", 40).putString("list_sgsr_supersampling_level", "1").commit()
        game().preferences.edit().putString("list_resolution", "1280x720").putInt("seekbar_bitrate_kbps", 17000)
            .putBoolean("checkbox_enable_hdr", true).commit()
        game(id = 2).preferences.edit().putInt("seekbar_sgsr_sharpness", 10).commit()
        game(pc = "pc-b").preferences.edit().putBoolean("checkbox_sgsr", true).commit()
        val profileBefore = profileValues()
        val otherBefore = game(id = 2).overrides()
        val otherPcBefore = game(pc = "pc-b").overrides()
        val gameBefore = game().overrides()

        GameSettingsStore.forLaunch(launched())!!.save(upscalerSaveValues(true, 3, 75, 2))

        val saved = game().overrides().filterKeys { it !in gameBefore.keys }
        assertEquals(mapOf("checkbox_sgsr" to true, "list_sgsr_quality" to "temporal-quality",
            "seekbar_sgsr_sharpness" to 75, "list_sgsr_supersampling_level" to "2"), saved)
        // The game's other overrides, the profile, other games and other PCs are untouched
        assertEquals(gameBefore, game().overrides().filterKeys { it in gameBefore.keys })
        assertEquals(profileBefore, profileValues())
        assertEquals(otherBefore, game(id = 2).overrides())
        assertEquals(otherPcBefore, game(pc = "pc-b").overrides())
    }

    @Test fun aLaterLaunchUsesTheSavedUpscalerWhileOtherGamesKeepTheProfile() {
        profiles.settings().edit().putBoolean("checkbox_sgsr", false).putString("list_sgsr_quality", "high")
            .putInt("seekbar_sgsr_sharpness", 40).putString("list_sgsr_supersampling_level", "1").commit()
        GameSettingsStore.forLaunch(launched())!!.save(upscalerSaveValues(true, 3, 75, 2))

        val next = PreferenceConfiguration.readPreferences(launched())
        assertTrue(next.sgsrEnabled)
        assertEquals(UpscalerMode.VIDEO_QUALITY, next.sgsrMode)
        assertEquals(75, next.sgsrSharpness)
        assertEquals(2, next.sgsrSupersamplingLevel)
        val other = PreferenceConfiguration.readPreferences(launched(id = 2))
        assertFalse(other.sgsrEnabled)
        assertEquals(UpscalerMode.HIGH, other.sgsrMode)
        assertEquals(40, other.sgsrSharpness)
        assertEquals(1, other.sgsrSupersamplingLevel)

        game().reset("list_sgsr_supersampling_level")
        assertEquals(1, PreferenceConfiguration.readPreferences(launched()).sgsrSupersamplingLevel)
        assertTrue(game().isCustom("checkbox_sgsr"))
        game().resetAll()
        assertTrue(game().overrides().isEmpty())
        val reset = PreferenceConfiguration.readPreferences(launched())
        assertFalse(reset.sgsrEnabled)
        assertEquals(40, reset.sgsrSharpness)
    }

    @Test fun theSavedLevelIsAnOrdinaryPerGameSetting() {
        val level = spec("list_sgsr_supersampling_level")
        assertEquals(SettingsTab.DISPLAY, level.tab)
        assertEquals("1", level.default)
        assertEquals(listOf("1", "2"), level.choices.map { it.value })
        assertEquals("checkbox_sgsr", level.dependency)
        assertTrue("list_sgsr_supersampling_level" in GameSettingsStore.DISPLAY_KEYS)
        assertTrue(UPSCALER_KEYS.all { it in GameSettingsStore.KEYS })
        assertFalse(game().isCustom("list_sgsr_supersampling_level"))
        game().preferences.edit().putString("list_sgsr_supersampling_level", "2").commit()
        assertTrue(game().isCustom("list_sgsr_supersampling_level"))
        assertEquals("1", game(id = 2).preferences.getString("list_sgsr_supersampling_level", "1"))
    }

    @Test fun gyroRowsAreOverridableResetAndCountedOnTheStreamTab() {
        val gyro = GameSettingsStore.GYRO_KEYS
        gyro.forEach { assertFalse(it, game().isCustom(it)) }
        game().preferences.edit().putInt("seekbar_gyro_smoothing", 40).putBoolean("checkbox_gamepad_motion_fallback", true).commit()
        assertTrue(game().isCustom("seekbar_gyro_smoothing"))
        assertTrue(game().isCustom("checkbox_gamepad_motion_fallback"))
        assertFalse(game(id = 2).isCustom("seekbar_gyro_smoothing"))
        assertEquals(setOf(1), GameSettingsStore.customAppIds(profiles.settings().all, "pc-a", listOf(1, 2)))
        assertEquals(2, com.limelight.shell.screens.customCount(game().overrides().keys, GameSettingsStore.STREAM_KEYS))
        assertEquals(0, com.limelight.shell.screens.customCount(game().overrides().keys, GameSettingsStore.DISPLAY_KEYS))
        game().reset("seekbar_gyro_smoothing")
        assertFalse(game().isCustom("seekbar_gyro_smoothing"))
        game().resetAll()
        gyro.forEach { assertFalse(it, game().isCustom(it)) }
    }

    @Test fun aLaunchReadsTheGamesGyroOverrideElseTheProfile() {
        profiles.settings().edit().putInt("seekbar_gyro_smoothing", 20).putBoolean("checkbox_gamepad_motion_fallback", false).commit()
        val inherited = PreferenceConfiguration.readPreferences(launched())
        assertEquals(20, inherited.gyroSmoothing)
        assertFalse(inherited.gamepadMotionSensorsFallbackToDevice)
        game().preferences.edit().putInt("seekbar_gyro_smoothing", 75).putBoolean("checkbox_gamepad_motion_fallback", true)
            .putBoolean("checkbox_force_device_motion", true).putBoolean("checkbox_gamepad_motion_sensors", false).commit()
        val config = PreferenceConfiguration.readPreferences(launched())
        assertEquals(75, config.gyroSmoothing)
        assertTrue(config.gamepadMotionSensorsFallbackToDevice)
        assertTrue(config.forceMotionSensorsFallbackToDevice)
        assertFalse(config.gamepadMotionSensors)
        assertEquals(20, PreferenceConfiguration.readPreferences(launched(id = 2)).gyroSmoothing)
        assertEquals(20, PreferenceConfiguration.readPreferences(context).gyroSmoothing)
        // The menu's automatic save: only this game's override changes, never the profile
        GameSettingsStore.forLaunch(launched())!!.save(gyroSmoothingSaveValues(10))
        assertEquals(10, PreferenceConfiguration.readPreferences(launched()).gyroSmoothing)
        assertEquals(20, profiles.settings().getInt("seekbar_gyro_smoothing", -1))
    }

    @Test fun gyroSmoothingEnablementUsesTheGamesEffectiveSources() {
        val smoothing = spec("seekbar_gyro_smoothing")
        val selected = game()
        val stream = StreamSettingsStore(profiles.catalog) { selected.preferences }
        assertTrue(stream.enabled(smoothing, null))
        // The profile has the external gyroscope on; this game turns it off and the built-in one stays off
        selected.preferences.edit().putBoolean("checkbox_gamepad_motion_sensors", false).commit()
        assertFalse(stream.enabled(smoothing, null))
        selected.preferences.edit().putBoolean("checkbox_gamepad_motion_fallback", true).commit()
        assertTrue(stream.enabled(smoothing, null))
        // Another game follows the profile (external gyroscope on)
        assertTrue(StreamSettingsStore(profiles.catalog) { game(id = 2).preferences }.enabled(smoothing, null))
        stream.close()
    }

    @Test fun anUnknownStoredSupersamplingLevelFallsBackToOne() {
        assertEquals(1, PreferenceConfiguration.readPreferences(context).sgsrSupersamplingLevel)
        listOf("3", "0", "-1", "", "two", "2.0").forEach {
            profiles.settings().edit().putString("list_sgsr_supersampling_level", it).commit()
            assertEquals("level $it", 1, PreferenceConfiguration.readPreferences(context).sgsrSupersamplingLevel)
        }
        profiles.settings().edit().putString("list_sgsr_supersampling_level", "2").commit()
        assertEquals(2, PreferenceConfiguration.readPreferences(context).sgsrSupersamplingLevel)
    }

    @Test fun upscalerSaveValuesAreClampedToWhatALaterLaunchReadsBack() {
        assertEquals(UPSCALER_KEYS.toSet(), upscalerSaveValues(false, 0, 50, 1).keys)
        listOf(-5 to 0, 0 to 0, 5 to 5, 100 to 100, 105 to 100).forEach { (live, stored) ->
            assertEquals(stored, upscalerSaveValues(true, 0, live, 1)["seekbar_sgsr_sharpness"])
        }
        listOf(-1 to "1", 0 to "1", 1 to "1", 2 to "2", 3 to "2").forEach { (live, stored) ->
            assertEquals(stored, upscalerSaveValues(true, 0, 50, live)["list_sgsr_supersampling_level"])
        }
        listOf(-1 to "standard", 0 to "standard", 1 to "high", 2 to "temporal", 3 to "temporal-quality", 4 to "sharpen", 5 to "sharpen")
            .forEach { (live, stored) -> assertEquals(stored, upscalerSaveValues(true, live, 50, 1)["list_sgsr_quality"]) }
        assertEquals(false, upscalerSaveValues(false, 0, 50, 1)["checkbox_sgsr"])
        assertEquals(true, upscalerSaveValues(true, 0, 50, 1)["checkbox_sgsr"])
        // Every value is one the settings screen offers for that key
        (0..4).forEach { mode ->
            val values = upscalerSaveValues(true, mode, 50, 2)
            assertTrue(spec("list_sgsr_quality").choices.any { it.value == values["list_sgsr_quality"] })
            assertTrue(spec("list_sgsr_supersampling_level").choices.any { it.value == values["list_sgsr_supersampling_level"] })
        }
    }

    @Test fun onlyAStreamLaunchedWithItsGameIdentityCanSaveForThatGame() {
        assertNotNull(GameSettingsStore.forLaunch(launched()))
        assertNull(GameSettingsStore.forLaunch(launched(snapshot = false)))
        assertNull(GameSettingsStore.forLaunch(launched(id = 0)))
        assertNull(GameSettingsStore.forLaunch(launched(pc = "")))
        assertNull(GameSettingsStore.forLaunch(Robolectric.buildActivity(DelosStreamActivity::class.java, Intent(context, DelosStreamActivity::class.java)).get()))
        assertThrows(IllegalArgumentException::class.java) { game().save(mapOf("ui_profile" to "other")) }
    }

    @Test fun undoOfAResetRestoresWhatItRemovedIncludingCodecSlotsAndKeepsLaterEdits() {
        val selected = game()
        val stream = StreamSettingsStore(profiles.catalog) { selected.preferences }
        stream.write(spec("video_format"), "forceav1")
        stream.write(spec("seekbar_bitrate_kbps"), 200000)
        stream.write(spec("video_format"), "forceh265")
        stream.write(spec("list_resolution"), "1280x720")
        val before = selected.backup()
        assertTrue(before.containsKey(codecBitrateKey("forceav1")))
        selected.reset("seekbar_bitrate_kbps")
        assertFalse(selected.isCustom("seekbar_bitrate_kbps"))
        assertFalse(selected.preferences.contains(codecBitrateKey("forceav1")))
        // Changed again after the reset: the undo must not overwrite it
        stream.write(spec("list_resolution"), "3840x2160")
        selected.restoreMissing(before)
        assertTrue(selected.isCustom("seekbar_bitrate_kbps"))
        assertEquals(before["seekbar_bitrate_kbps"], selected.backup()["seekbar_bitrate_kbps"])
        assertEquals(200000, selected.preferences.getInt(codecBitrateKey("forceav1"), -1))
        assertEquals("3840x2160", selected.overrides()["list_resolution"])
        stream.close()
    }

    @Test fun undoOfResetAllBringsBackEveryOverrideOfThatGameOnly() {
        val selected = game()
        val other = game(id = 2)
        selected.save(mapOf("list_resolution" to "1280x720", "checkbox_enable_hdr" to true, "seekbar_sgsr_sharpness" to 70))
        other.save(mapOf("list_fps" to "120"))
        val before = selected.backup()
        selected.resetAll()
        assertTrue(selected.overrides().isEmpty())
        selected.restoreMissing(before)
        assertEquals(mapOf("list_resolution" to "1280x720", "checkbox_enable_hdr" to true, "seekbar_sgsr_sharpness" to 70), selected.overrides())
        assertEquals(mapOf("list_fps" to "120"), other.overrides())
        // Nothing foreign is ever written back
        selected.restoreMissing(mapOf("ui_profile" to "x", "list_fps" to "30"))
        assertEquals("30", selected.overrides()["list_fps"])
        assertNull(selected.backup()["ui_profile"])
    }
}
