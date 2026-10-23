package com.limelight.shell.data

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.preference.PreferenceManager
import com.limelight.LimeLog
import com.limelight.shell.ui.Symbols
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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

/** A user profile of the new UI: a name and an avatar (what else it holds is decided in step 6.7). */
@Immutable
data class Profile(val id: String, val name: String, val avatar: Int) {
    val look: Avatar get() = AVATARS[avatar.coerceIn(AVATARS.indices)]
}

@Immutable
data class Profiles(val all: List<Profile>, val currentId: String?) {
    val current: Profile? get() = all.firstOrNull { it.id == currentId } ?: all.firstOrNull()
}

/** Stores the profiles as JSON in the default SharedPreferences (`ui_profiles`, `ui_profile`). */
class ProfileStore(context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    private val state = MutableStateFlow(read())

    val profiles: StateFlow<Profiles> = state.asStateFlow()

    /** Adds a profile and makes it the current one. */
    fun create(name: String, avatar: Int): Profile {
        val profile = Profile(UUID.randomUUID().toString(), name, avatar)
        save(Profiles(state.value.all + profile, profile.id))
        return profile
    }

    fun update(id: String, transform: (Profile) -> Profile) {
        save(state.value.copy(all = state.value.all.map { if (it.id == id) transform(it) else it }))
    }

    fun switchTo(id: String) {
        if (state.value.all.any { it.id == id }) save(state.value.copy(currentId = id))
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
    }
}
