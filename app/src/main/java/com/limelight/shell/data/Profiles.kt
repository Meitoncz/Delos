package com.limelight.shell.data

import androidx.annotation.StringRes
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import androidx.preference.PreferenceManager
import com.limelight.LimeLog
import com.limelight.R
import com.limelight.shell.ui.Symbols
import com.limelight.shell.settings.SettingsCatalog
import com.limelight.shell.settings.GameSettingsStore
import com.limelight.shell.settings.StreamProfileStore
import com.limelight.shell.settings.ProfileLayouts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An avatar to choose from (the prototype's `AVATARS`): its [name] (a language independent identifier, never shown),
 * the displayed [label] (a string resource), its color, the illustration [asset]
 * (a file under `assets/avatars`, drawn over the color) and the font [icon] shown until the image is
 * loaded or when it is missing. A profile stores the avatar's index, so the order must never change:
 * new avatars are only appended.
 */
@Immutable
data class Avatar(val icon: String, val name: String, val hue: Float, val asset: String?, @StringRes val label: Int)

val AVATARS = listOf(
    Avatar(Symbols.SAILING, "Sailor", 205f, "sailor.webp", R.string.firstrun_avatar_sailor), // l10n-ignore
    Avatar(Symbols.ROCKET_LAUNCH, "Voyager", 262f, "voyager.webp", R.string.firstrun_avatar_voyager), // l10n-ignore
    Avatar(Symbols.SPORTS_ESPORTS, "Player", 330f, "player.webp", R.string.firstrun_avatar_player), // l10n-ignore
    Avatar(Symbols.PETS, "Fox", 24f, "fox.webp", R.string.firstrun_avatar_fox), // l10n-ignore
    Avatar(Symbols.SPARK, "Spark", 48f, "spark.webp", R.string.firstrun_avatar_spark), // l10n-ignore
    Avatar(Symbols.SPA, "Calm", 150f, "calm.webp", R.string.firstrun_avatar_calm), // l10n-ignore
    Avatar(Symbols.SWORDS, "Knight", 232f, "knight.webp", R.string.firstrun_avatar_knight), // l10n-ignore
    Avatar(Symbols.ROBOT, "Robot", 182f, "robot.webp", R.string.firstrun_avatar_robot), // l10n-ignore
    Avatar(Symbols.CAT, "Cat", 84f, "cat.webp", R.string.firstrun_avatar_cat), // l10n-ignore
    Avatar(Symbols.MAGIC_WAND, "Wizard", 292f, "wizard.webp", R.string.firstrun_avatar_wizard), // l10n-ignore
    Avatar(Symbols.SPEED, "Racer", 356f, "racer.webp", R.string.firstrun_avatar_racer), // l10n-ignore
    Avatar(Symbols.GHOST, "Ghost", 118f, "ghost.webp", R.string.firstrun_avatar_ghost), // l10n-ignore
)

/** A user profile of the new UI: identity and a complete, independent set of preferences. */
@Immutable
data class Profile(val id: String, val name: String, val avatar: Int) {
    val look: Avatar get() = AVATARS[avatar.coerceIn(AVATARS.indices)]
}

@Immutable
data class Profiles(val all: List<Profile>, val currentId: String?) {
    val current: Profile? get() = all.firstOrNull { it.id == currentId } ?: all.firstOrNull()
}

/**
 * Stores the profiles as JSON in the default SharedPreferences (`ui_profiles`, `ui_profile`). Every
 * profile keeps all of its settings in its own preferences file (`ui_profile_<id>`): appearance, how
 * Delos starts, hidden games, the last played game and the names given to PCs.
 */
class ProfileStore(context: Context, private val later: (Long, () -> Unit) -> Unit = ::onMainThreadLater) {
    private val app = context.applicationContext
    private val prefs = PreferenceManager.getDefaultSharedPreferences(app)
    private val state = MutableStateFlow(read())
    private val settingsListeners = CopyOnWriteArrayList<() -> Unit>()
    private val deleted = LinkedHashMap<String, Deleted>()

    /** A profile in its undo grace period: where it was, whether it was current and who took over. */
    private class Deleted(val profile: Profile, val index: Int, val wasCurrent: Boolean, val successor: String?)

    val catalog = SettingsCatalog(app)
    val streamProfiles = StreamProfileStore(app, catalog)

    val profiles: StateFlow<Profiles> = state.asStateFlow()

    init {
        // Profiles deleted before the last close are gone for good: nobody can undo them any more
        val leftovers = prefs.getStringSet(KEY_DELETED, emptySet()).orEmpty()
        if (leftovers.isNotEmpty()) {
            leftovers.filter { id -> state.value.all.none { it.id == id } }.forEach { purgeFiles(it) }
            prefs.edit().remove(KEY_DELETED).apply()
        }
        // Profiles made before they had their own settings take over the new UI's settings so far
        state.value.all.forEach { p -> settingsOf(p.id).takeIf { it.all.isEmpty() }?.let { copySettings(into = it, from = null) } }
        streamProfiles.activate(state.value.current, settings())
        state.value.current?.let { ProfileLayouts.initialize(app, it.id, settings()) }
    }

    /** The current profile's settings; the default preferences before the first profile exists. */
    fun settings(): SharedPreferences = state.value.current?.let { settingsOf(it.id) } ?: prefs

    /** [listener] runs whenever other settings apply (another profile, or the first one). */
    fun onSettingsChanged(listener: () -> Unit) {
        settingsListeners += listener
    }

    /** Adds a profile with a copy of the current one's settings and makes it the current one. */
    fun create(name: String, avatar: Int): Profile {
        val profile = Profile(UUID.randomUUID().toString(), name, avatar)
        copySettings(into = settingsOf(profile.id), from = state.value.current)
        ProfileLayouts.copy(app, state.value.current?.id, profile.id)
        settingsOf(profile.id).edit().putBoolean("ui_layouts_initialized", true).apply()
        save(Profiles(state.value.all + profile, profile.id))
        streamProfiles.activate(profile, settings())
        settingsListeners.forEach { it() }
        return profile
    }

    fun update(id: String, transform: (Profile) -> Profile) {
        save(state.value.copy(all = state.value.all.map { if (it.id == id) transform(it) else it }))
        state.value.all.firstOrNull { it.id == id }?.let(streamProfiles::rename)
    }

    fun switchTo(id: String) {
        if (id == state.value.current?.id || state.value.all.none { it.id == id }) return
        save(state.value.copy(currentId = id))
        streamProfiles.activate(state.value.current, settings())
        state.value.current?.let { ProfileLayouts.initialize(app, it.id, settings()) }
        settingsListeners.forEach { it() }
    }

    /**
     * Deletes a profile without asking: it disappears at once, the next profile becomes the current
     * one, and the linked stream settings profile goes. Its settings (with the per-game overrides) and
     * control layouts are kept for [DELETE_GRACE_MS] so [undoDelete] can bring it back; after that, or
     * at the next start if Delos was closed meanwhile, [purge] removes them for good. Returns false
     * when no profile is left (the first launch starts again).
     */
    fun delete(id: String): Boolean {
        val profile = state.value.all.firstOrNull { it.id == id } ?: return state.value.all.isNotEmpty()
        val wasCurrent = state.value.current?.id == id
        val rest = state.value.all - profile
        val successor = rest.firstOrNull()?.id
        deleted[id] = Deleted(profile, state.value.all.indexOf(profile), wasCurrent, successor)
        save(Profiles(rest, if (wasCurrent) successor else state.value.currentId))
        streamProfiles.delete(id)
        if (wasCurrent) activateCurrent()
        later(DELETE_GRACE_MS) { purge(id) }
        return rest.isNotEmpty()
    }

    /**
     * Brings back a profile deleted less than [DELETE_GRACE_MS] ago at its old place with everything it
     * kept. It becomes the current one again when it was and nobody has chosen another profile since.
     * False when it was purged already.
     */
    fun undoDelete(id: String): Boolean {
        val gone = deleted.remove(id) ?: return false
        val takesOver = gone.wasCurrent && state.value.current?.id == gone.successor
        val all = state.value.all.toMutableList().apply { add(gone.index.coerceAtMost(size), gone.profile) }
        save(Profiles(all, if (takesOver) id else state.value.currentId))
        if (takesOver) activateCurrent()
        return true
    }

    /** Removes what a deleted profile kept: its settings, control layouts and per-game overrides. */
    private fun purge(id: String) {
        if (id !in deleted) return
        purgeFiles(id)
        deleted.remove(id)
        prefs.edit().putStringSet(KEY_DELETED, deleted.keys.toSet()).apply()
    }

    private fun purgeFiles(id: String) {
        ProfileLayouts.delete(app, id)
        settingsOf(id).edit().clear().commit()
        app.deleteSharedPreferences("ui_profile_$id")
    }

    /** The stream settings, layouts and listeners follow the profile that is current now. */
    private fun activateCurrent() {
        streamProfiles.activate(state.value.current, settings())
        state.value.current?.let { ProfileLayouts.initialize(app, it.id, settings()) }
        settingsListeners.forEach { it() }
    }

    /** Drops the per-game overrides of every app on [pcUuid], in every profile (the PC was removed), also those still restorable. */
    fun forgetGameSettings(pcUuid: String) {
        GameSettingsStore.purge(prefs, pcUuid)
        (state.value.all.map { it.id } + deleted.keys).forEach { GameSettingsStore.purge(settingsOf(it), pcUuid) }
    }

    private fun settingsOf(id: String): SharedPreferences = app.getSharedPreferences("ui_profile_$id", Context.MODE_PRIVATE)

    /**
     * Copies the settings of profile [from], or without one: the new UI's settings from before profiles
     * had their own (`ui_*` in the default preferences) and the classic UI's hidden games.
     */
    private fun copySettings(into: SharedPreferences, from: Profile?) {
        val editor = into.edit().clear()
        if (from != null) {
            settingsOf(from.id).all.forEach { (key, value) -> editor.put(key, value) }
        } else {
            prefs.all.forEach { (key, value) -> if (key.startsWith("ui_") && key != KEY_PROFILES && key != KEY_CURRENT && key != KEY_DELETED) editor.put(key, value) }
            app.getSharedPreferences(CLASSIC_HIDDEN_APPS, Context.MODE_PRIVATE).all.forEach { (pcUuid, ids) ->
                if (ids is Set<*>) editor.putStringSet(hiddenAppsKey(pcUuid), ids.filterIsInstance<String>().toSet())
            }
        }
        editor.apply()
    }

    private fun SharedPreferences.Editor.put(key: String, value: Any?) {
        when (value) {
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is String -> putString(key, value)
            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }

    private fun read(): Profiles {
        val all = runCatching {
            val array = JSONArray(prefs.getString(KEY_PROFILES, "[]"))
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Profile(o.getString("id"), o.getString("name"), o.optInt("avatar", 0))
            }
        }.getOrElse {
            LimeLog.warning("New UI: unreadable profiles: $it") // l10n-ignore
            emptyList()
        }
        return Profiles(all, prefs.getString(KEY_CURRENT, null))
    }

    private fun save(profiles: Profiles) {
        val array = JSONArray()
        profiles.all.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("avatar", it.avatar)) }
        // One edit: the list and the profiles still to purge never disagree on disk
        prefs.edit().putString(KEY_PROFILES, array.toString()).putString(KEY_CURRENT, profiles.currentId)
            .putStringSet(KEY_DELETED, deleted.keys.toSet()).apply()
        state.value = profiles
    }

    companion object {
        private const val KEY_PROFILES = "ui_profiles"
        private const val KEY_CURRENT = "ui_profile"

        /** Ids of the profiles deleted but not yet purged (the undo grace period); the next start finishes them. */
        private const val KEY_DELETED = "ui_profiles_deleted"

        /** How long a deleted profile can still be restored. */
        const val DELETE_GRACE_MS = 10_000L

        /** The classic AppView's hidden games (per PC uuid), taken over by the first profile. */
        private const val CLASSIC_HIDDEN_APPS = "HiddenApps"

        /**
         * The current profile's settings, only read: for the stream window, which must not parse the
         * settings catalog or activate stream profiles while a game is starting (that is the shell's job).
         */
        fun currentSettings(context: Context): SharedPreferences {
            val app = context.applicationContext
            val prefs = PreferenceManager.getDefaultSharedPreferences(app)
            val id = prefs.getString(KEY_CURRENT, null) ?: return prefs
            return app.getSharedPreferences("ui_profile_$id", Context.MODE_PRIVATE)
        }
    }
}

private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

/** The default way [ProfileStore] waits out the undo grace period. */
private fun onMainThreadLater(delayMs: Long, work: () -> Unit) {
    mainHandler.postDelayed(work, delayMs)
}

/** A profile's hidden games on [pcUuid] (app ids as strings). */
fun hiddenAppsKey(pcUuid: String) = "ui_hidden_$pcUuid"
