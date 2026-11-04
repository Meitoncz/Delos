package com.limelight.shell

import android.content.SharedPreferences

/**
 * Configuration readers also perform migrations/normalization. A launch label must never persist
 * those writes, either into the profile or through GameEditor's deliberately strict key boundary.
 * An empty override map reads the profile directly rather than the per-game view.
 */
internal fun launchParameterPreferences(
    profile: SharedPreferences,
    game: SharedPreferences,
    overrides: Map<String, *>,
): SharedPreferences {
    val source = if (overrides.isEmpty()) profile else game
    return object : SharedPreferences by source {
        override fun edit(): SharedPreferences.Editor = NoopLaunchEditor
    }
}

private object NoopLaunchEditor : SharedPreferences.Editor {
    override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
    override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
    override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
    override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
    override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
    override fun remove(key: String?): SharedPreferences.Editor = this
    override fun clear(): SharedPreferences.Editor = this
    override fun commit(): Boolean = true
    override fun apply() = Unit
}
