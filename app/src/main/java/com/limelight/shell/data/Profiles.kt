package com.limelight.shell.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import androidx.preference.PreferenceManager
import com.limelight.LimeLog
import com.limelight.shell.ui.Symbols
import com.limelight.shell.settings.SettingsCatalog
import com.limelight.shell.settings.StreamProfileStore
import com.limelight.shell.settings.ProfileLayouts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** An avatar to choose from (the prototype's `AVATARS`): icon, name and color. */
@Immutable
data class Avatar(val icon: String, val name: String, val hue: Float)

val AVATARS = listOf(
    Avatar(Symbols.SAILING, "Sailor", 205f),
    Avatar(Symbols.ROCKET_LAUNCH, "Voyager", 262f),
    Avatar(Symbols.SPORTS_ESPORTS, "Player", 330f),
    Avatar(Symbols.PETS, "Fox", 24f),
    Avatar(Symbols.BOLT, "Spark", 48f),
    Avatar(Symbols.SPA, "Calm", 150f),
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
class ProfileStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = PreferenceManager.getDefaultSharedPreferences(app)
    private val state = MutableStateFlow(read())
    private val settingsListeners = CopyOnWriteArrayList<() -> Unit>()
    val catalog = SettingsCatalog(app)
    val streamProfiles = StreamProfileStore(app, catalog)

    val profiles: StateFlow<Profiles> = state.asStateFlow()

    init {
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
            prefs.all.forEach { (key, value) -> if (key.startsWith("ui_") && key != KEY_PROFILES && key != KEY_CURRENT) editor.put(key, value) }
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
            LimeLog.warning("New UI: unreadable profiles: $it")
            emptyList()
        }
        return Profiles(all, prefs.getString(KEY_CURRENT, null))
    }

    private fun save(profiles: Profiles) {
        val array = JSONArray()
        profiles.all.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("avatar", it.avatar)) }
        prefs.edit().putString(KEY_PROFILES, array.toString()).putString(KEY_CURRENT, profiles.currentId).apply()
        state.value = profiles
    }

    private companion object {
        const val KEY_PROFILES = "ui_profiles"
        const val KEY_CURRENT = "ui_profile"

        /** The classic AppView's hidden games (per PC uuid), taken over by the first profile. */
        const val CLASSIC_HIDDEN_APPS = "HiddenApps"
    }
}

/** A profile's hidden games on [pcUuid] (app ids as strings). */
fun hiddenAppsKey(pcUuid: String) = "ui_hidden_$pcUuid"
