package com.limelight.shell.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.limelight.shadows.ShadowGameManager
import com.limelight.shadows.ShadowMoonBridge
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which games the dashboard marks as having custom settings: real per-game keys of the right PC only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [ShadowMoonBridge::class, ShadowGameManager::class])
class CustomGameIdsTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("custom_game_ids", Context.MODE_PRIVATE)

    @Test fun onlyGamesWithAnOverrideOfThisPcAreCustom() {
        prefs.edit().clear().commit()
        GameSettingsStore(prefs, "pc-a", 1).preferences.edit().putInt("seekbar_bitrate_kbps", 4321).commit()
        GameSettingsStore(prefs, "pc-a", 3).preferences.edit().putBoolean("checkbox_enable_hdr", true).commit()
        GameSettingsStore(prefs, "pc-b", 2).preferences.edit().putBoolean("checkbox_enable_hdr", true).commit()
        prefs.edit().putString("unrelated", "x").commit()
        assertEquals(setOf(1, 3), GameSettingsStore.customAppIds(prefs.all, "pc-a", listOf(1, 2, 3, 4)))
        assertEquals(setOf(2), GameSettingsStore.customAppIds(prefs.all, "pc-b", listOf(1, 2, 3, 4)))
        assertEquals(emptySet<Int>(), GameSettingsStore.customAppIds(emptyMap<String, Any>(), "pc-a", listOf(1, 2)))
    }

    @Test fun resettingRemovesTheGame() {
        prefs.edit().clear().commit()
        val game = GameSettingsStore(prefs, "pc-a", 5)
        game.preferences.edit().putBoolean("checkbox_enable_hdr", true).commit()
        game.resetAll()
        assertEquals(emptySet<Int>(), GameSettingsStore.customAppIds(prefs.all, "pc-a", listOf(5)))
    }

    @Test fun aLoneCodecBitrateSlotIsNotCustom() {
        prefs.edit().clear().commit()
        GameSettingsStore(prefs, "pc-a", 6).preferences.edit().putInt("ui_codec_bitrate_h264", 5000).commit()
        assertEquals(1, prefs.all.size)
        assertEquals(emptySet<Int>(), GameSettingsStore.customAppIds(prefs.all, "pc-a", listOf(6)))
    }
}
