package com.limelight.shell.data

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.limelight.profiles.ProfilesManager
import com.limelight.shadows.ShadowGameManager
import com.limelight.shadows.ShadowMoonBridge
import com.limelight.shell.settings.GameSettingsStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Deleting a profile asks nothing; the undo grace period keeps what it lost restorable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [ShadowMoonBridge::class, ShadowGameManager::class])
class ProfileDeleteUndoTest {
    private lateinit var context: Context
    private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
    private lateinit var store: ProfileStore

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        ProfilesManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        ProfilesManager.getInstance().load(context)
        store = ProfileStore(context) { delay, work -> scheduled += delay to work }
    }

    private fun ids() = store.profiles.value.all.map { it.id }
    private fun settingsOf(id: String) = context.getSharedPreferences("ui_profile_$id", Context.MODE_PRIVATE)
    private fun layoutOf(id: String) = context.getSharedPreferences("ui_layout_${id}_OSC", Context.MODE_PRIVATE)

    /** A profile with a setting, a control layout and a per-game override of its own. */
    private fun profileWithData(name: String): Profile {
        val profile = store.create(name, 0)
        settingsOf(profile.id).edit().putString("marker", name).commit()
        layoutOf(profile.id).edit().putString("marker", name).commit()
        GameSettingsStore(settingsOf(profile.id), "pc-a", 7).preferences.edit().putInt("seekbar_bitrate_kbps", 4321).commit()
        return profile
    }

    @Test fun deletingHidesTheProfileAtOnceAndTheNextOneTakesOver() {
        val a = profileWithData("A")
        val b = profileWithData("B")
        assertEquals(b.id, store.profiles.value.current?.id)

        assertTrue(store.delete(b.id))

        assertEquals(listOf(a.id), ids())
        assertEquals(a.id, store.profiles.value.current?.id)
        assertEquals(1, scheduled.size)
        assertEquals(ProfileStore.DELETE_GRACE_MS, scheduled.single().first)
    }

    @Test fun undoBringsBackTheProfileWithEverythingItKept() {
        val a = profileWithData("A")
        val b = profileWithData("B")
        store.delete(b.id)

        assertTrue(store.undoDelete(b.id))

        assertEquals(listOf(a.id, b.id), ids())
        assertEquals(b.id, store.profiles.value.current?.id)
        assertEquals("B", store.settings().getString("marker", null))
        assertEquals("B", layoutOf(b.id).getString("marker", null))
        assertTrue(GameSettingsStore(store.settings(), "pc-a", 7).isCustom("seekbar_bitrate_kbps"))
        // The grace timer firing afterwards finds nothing to purge
        scheduled.forEach { it.second() }
        assertEquals("B", settingsOf(b.id).getString("marker", null))
        assertEquals("B", layoutOf(b.id).getString("marker", null))
    }

    @Test fun nothingIsRestorableOnceTheGracePeriodEnded() {
        profileWithData("A")
        val b = profileWithData("B")
        store.delete(b.id)

        scheduled.single().second()

        assertFalse(store.undoDelete(b.id))
        assertTrue(settingsOf(b.id).all.isEmpty())
        assertTrue(layoutOf(b.id).all.isEmpty())
        assertEquals(1, ids().size)
    }

    @Test fun closingDelosDuringTheGracePeriodFinishesTheDeletionAtTheNextStart() {
        val a = profileWithData("A")
        val b = profileWithData("B")
        store.delete(b.id)

        // The timer never ran: a new store is what the next start sees
        val restarted = ProfileStore(context) { _, _ -> }

        assertEquals(listOf(a.id), restarted.profiles.value.all.map { it.id })
        assertTrue(settingsOf(b.id).all.isEmpty())
        assertTrue(layoutOf(b.id).all.isEmpty())
        assertFalse(restarted.undoDelete(b.id))
    }

    @Test fun undoDoesNotTakeOverWhenAnotherProfileWasChosenMeanwhile() {
        val a = profileWithData("A")
        val b = profileWithData("B")
        val c = profileWithData("C")
        store.delete(c.id)
        store.switchTo(b.id)

        assertTrue(store.undoDelete(c.id))

        assertEquals(listOf(a.id, b.id, c.id), ids())
        assertEquals(b.id, store.profiles.value.current?.id)
    }

    @Test fun deletingANonCurrentProfileKeepsTheCurrentOne() {
        val a = profileWithData("A")
        val b = profileWithData("B")

        assertTrue(store.delete(a.id))
        assertEquals(b.id, store.profiles.value.current?.id)
        assertTrue(store.undoDelete(a.id))

        assertEquals(listOf(a.id, b.id), ids())
        assertEquals(b.id, store.profiles.value.current?.id)
    }

    @Test fun theLastProfileCanBeRestoredToo() {
        val a = profileWithData("A")

        assertFalse(store.delete(a.id))
        assertTrue(store.profiles.value.all.isEmpty())
        assertTrue(store.undoDelete(a.id))

        assertEquals(listOf(a.id), ids())
        assertEquals(a.id, store.profiles.value.current?.id)
        assertEquals("A", store.settings().getString("marker", null))
    }

    @Test fun aForgottenPcLeavesNoOverridesInARestoredProfile() {
        profileWithData("A")
        val b = profileWithData("B")
        store.delete(b.id)

        store.forgetGameSettings("pc-a")
        store.undoDelete(b.id)

        assertFalse(GameSettingsStore(store.settings(), "pc-a", 7).isCustom("seekbar_bitrate_kbps"))
        assertEquals("B", store.settings().getString("marker", null))
    }

    @Test fun deletingAnUnknownProfileChangesNothing() {
        val a = profileWithData("A")

        assertTrue(store.delete("missing"))

        assertEquals(listOf(a.id), ids())
        assertTrue(scheduled.isEmpty())
    }
}
