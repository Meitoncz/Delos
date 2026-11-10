package com.limelight.shell.settings

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.preference.PreferenceManager
import java.util.Locale

/**
 * The language of the whole app: the single source of truth is the platform's per-app locale (docs/LOCALIZATION.md, "The Language
 * setting and per-app locales"). The system's per-app language picker (Android 13+, listing `locales_config.xml`) and the in-app
 * Language row (`list_languages`) both read and write it, so they always agree.
 *
 * * Android 13+: [LocaleManager.setApplicationLocales]. The system updates the process configuration and tells the running
 *   activities; the ones that declare `locale` in `configChanges` (the shell, the stream) change in place, with no restart.
 * * Android 10-12: no platform per-app locale. The tag is kept in a small preference file and applied by [wrap], which every
 *   activity calls in `attachBaseContext` (and by [initApplication] for the application context); changing it recreates the
 *   foreground activity, because its resources are fixed when it is created. (`AppCompatDelegate.setApplicationLocales` would do the
 *   same, but it needs an `AppCompatActivity` with an AppCompat theme; the shell is a `ComponentActivity`.)
 *
 * A choice is [SYSTEM] ("default": follow the system language, English when the app has no translation for it) or one of [TAGS].
 * The functions above the platform section are pure and unit-tested.
 */
object AppLanguage {
    /** The choice that follows the system language. */
    const val SYSTEM = "default"

    /** The key of the Language row (a classic preference key, stored per profile as a mirror of the app locale). */
    const val PREF_KEY = "list_languages"

    /** The languages the app is translated into, in the order of `locales_config.xml`, `language_values` and `StringResourcesTest`. */
    val TAGS = listOf("en", "cs", "es", "pt-BR", "fr", "de", "ja", "ko", "zh-CN", "uk")

    private const val FILE = "app_language"
    private const val KEY_TAG = "tag"
    private const val KEY_ADOPTED = "legacy_adopted"

    // ---- pure choice logic ----

    /** A stored or incoming value as a valid choice: a canonical entry of [TAGS], else [SYSTEM]. */
    fun normalizeChoice(value: String?): String {
        val v = value?.trim().orEmpty()
        if (v.isEmpty() || v.equals(SYSTEM, ignoreCase = true)) return SYSTEM
        return TAGS.firstOrNull { it.equals(v.replace('_', '-'), ignoreCase = true) } ?: SYSTEM
    }

    /** The locale list to give the platform for [choice]: none (follow the system) or the one tag. */
    fun tagsFor(choice: String): List<String> = normalizeChoice(choice).takeIf { it != SYSTEM }?.let(::listOf) ?: emptyList()

    /**
     * The choice that represents the app's locales as the platform reports them: [SYSTEM] when none is set, else the supported
     * entry of the first one (English when that language is not translated: that is what the resources then show).
     */
    fun choiceFor(appLocaleTags: List<String>): String {
        val first = appLocaleTags.firstOrNull { it.isNotBlank() } ?: return SYSTEM
        return supportedTag(first) ?: "en"
    }

    /** The language the app shows when it follows the system: the first system language that is translated, else English. */
    fun effectiveTag(systemLocaleTags: List<String>): String = systemLocaleTags.firstNotNullOfOrNull(::supportedTag) ?: "en"

    /**
     * The entry of [TAGS] that serves a locale tag, or null: the language alone is enough for entries without a region (`de-AT` is
     * `de`); `pt` and `zh` (and `zh-Hans`) are the Brazilian and Simplified Chinese entries, `pt-PT` and `zh-TW` / `zh-Hant` are not.
     */
    fun supportedTag(tag: String): String? {
        val locale = Locale.forLanguageTag(tag.replace('_', '-'))
        if (locale.language.isEmpty()) return null
        TAGS.firstOrNull { it.equals(locale.toLanguageTag(), ignoreCase = true) }?.let { return it }
        return TAGS.firstOrNull { entry ->
            val candidate = Locale.forLanguageTag(entry)
            candidate.language == locale.language && (
                candidate.country.isEmpty() || candidate.country == locale.country ||
                    (locale.country.isEmpty() && locale.script != "Hant")
                )
        }
    }

    // ---- platform ----

    /** The current choice: [SYSTEM] or an entry of [TAGS]. */
    @JvmStatic
    fun selected(context: Context): String = choiceFor(appLocaleTags(context))

    /**
     * Makes [choice] the language of the whole app, at once. Android 13+: the system updates every activity; <33: the tag is saved and
     * the foreground activity is recreated. Does nothing when [choice] is already selected.
     */
    @JvmStatic
    fun choose(context: Context, choice: String) {
        val wanted = normalizeChoice(choice)
        if (wanted == selected(context)) return
        val tags = tagsFor(wanted)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.applicationContext.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(tags.joinToString(","))
        } else {
            prefs(context).edit().putString(KEY_TAG, tags.firstOrNull().orEmpty()).apply()
            applyToApplication(context.applicationContext)
            findActivity(context)?.recreate()
        }
    }

    /** Android 10-12: puts the saved language on an activity's base context. Call from `attachBaseContext`; a no-op on Android 13+. */
    @JvmStatic
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val locale = savedLocale(base) ?: return base
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(LocaleList(locale))
        return base.createConfigurationContext(configuration)
    }

    /**
     * Call from `Application.onCreate`: Android 10-12 applies the saved language to the application context (toasts and services use
     * it); every version adopts the language the old Language preference had stored, once (see [adoptLegacy]).
     */
    @JvmStatic
    fun initApplication(app: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) applyToApplication(app)
        adoptLegacy(app)
    }

    /**
     * The Language row used to be a stream-only preference (`list_languages`). A language chosen back then becomes the app language
     * once, when the app has none yet, so nobody has to choose again; afterwards the platform locale alone decides.
     */
    private fun adoptLegacy(app: Context) {
        val flags = prefs(app)
        if (flags.getBoolean(KEY_ADOPTED, false)) return
        flags.edit().putBoolean(KEY_ADOPTED, true).apply()
        val legacy = normalizeChoice(PreferenceManager.getDefaultSharedPreferences(app).getString(PREF_KEY, SYSTEM))
        if (legacy != SYSTEM && selected(app) == SYSTEM) choose(app, legacy)
    }

    /**
     * Keeps the Language row's stored value equal to the app locale, so the row shows what the system picker chose too: writes
     * [selected] into [settings] when it differs. Call when settings are (re)bound and when the activity resumes.
     */
    fun mirror(context: Context, settings: SharedPreferences) {
        val current = selected(context)
        if (settings.getString(PREF_KEY, SYSTEM) != current) settings.edit().putString(PREF_KEY, current).apply()
    }

    /** The other direction: a change of the Language row (in [settings], key [PREF_KEY]) becomes the app language. */
    fun onPreferenceChanged(context: Context, settings: SharedPreferences, key: String?) {
        if (key == PREF_KEY) choose(context, settings.getString(PREF_KEY, SYSTEM) ?: SYSTEM)
    }

    private fun appLocaleTags(context: Context): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.applicationContext.getSystemService(LocaleManager::class.java).applicationLocales
            List(locales.size()) { locales[it].toLanguageTag() }
        } else {
            listOfNotNull(prefs(context).getString(KEY_TAG, null)?.takeIf { it.isNotEmpty() })
        }

    private fun savedLocale(context: Context): Locale? =
        prefs(context).getString(KEY_TAG, null)?.takeIf { it.isNotEmpty() }?.let(Locale::forLanguageTag)

    @Suppress("DEPRECATION")
    private fun applyToApplication(app: Context) {
        val locale = savedLocale(app) ?: return
        Locale.setDefault(locale)
        val configuration = Configuration(app.resources.configuration)
        configuration.setLocales(LocaleList(locale))
        app.resources.updateConfiguration(configuration, app.resources.displayMetrics)
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun findActivity(context: Context): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}
