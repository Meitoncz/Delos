package com.limelight.shell

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import com.limelight.diag.ProblemMonitor
import com.limelight.shell.data.ArtworkStore
import com.limelight.shell.data.BoxArtCache
import com.limelight.shell.data.GameLauncher
import com.limelight.shell.data.GameMetadataStore
import com.limelight.shell.data.Library
import com.limelight.shell.data.Pc
import com.limelight.shell.data.PcRepository
import com.limelight.shell.data.PcSetup
import com.limelight.shell.data.ProfileStore
import com.limelight.shell.data.Profiles
import com.limelight.shell.settings.AppearanceStore
import com.limelight.shell.settings.HomeLayout
import com.limelight.shell.settings.StartupStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

/**
 * Debug builds only: preferences that live in memory and vanish with the process. The preview sandbox ([PreviewContext]) hands them
 * out instead of the app's preference files, so a screen preview never writes the user's settings, profiles or paired PCs.
 */
internal class MemoryPrefs : SharedPreferences {
    private val store = HashMap<String, Any>()
    private val listeners = CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = synchronized(store) { HashMap(store) }
    override fun getString(key: String, defValue: String?): String? = synchronized(store) { store[key] as? String } ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        synchronized(store) { (store[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() } ?: defValues
    override fun getInt(key: String, defValue: Int): Int = synchronized(store) { store[key] as? Int } ?: defValue
    override fun getLong(key: String, defValue: Long): Long = synchronized(store) { store[key] as? Long } ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = synchronized(store) { store[key] as? Float } ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = synchronized(store) { store[key] as? Boolean } ?: defValue
    override fun contains(key: String): Boolean = synchronized(store) { store.containsKey(key) }
    override fun edit(): SharedPreferences.Editor = Edit()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners += listener
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners -= listener
    }

    private inner class Edit : SharedPreferences.Editor {
        private val staged = LinkedHashMap<String, Any?>()
        private var cleared = false

        private fun stage(key: String, value: Any?): SharedPreferences.Editor {
            staged[key] = value
            return this
        }

        override fun putString(key: String, value: String?) = stage(key, value)
        override fun putStringSet(key: String, value: MutableSet<String>?) = stage(key, value?.toSet())
        override fun putInt(key: String, value: Int) = stage(key, value)
        override fun putLong(key: String, value: Long) = stage(key, value)
        override fun putFloat(key: String, value: Float) = stage(key, value)
        override fun putBoolean(key: String, value: Boolean) = stage(key, value)
        override fun remove(key: String) = stage(key, null)

        override fun clear(): SharedPreferences.Editor {
            cleared = true
            return this
        }

        override fun commit(): Boolean {
            flush()
            return true
        }

        override fun apply() = flush()

        private fun flush() {
            val changed = LinkedHashSet<String>()
            synchronized(store) {
                if (cleared) {
                    changed += store.keys
                    store.clear()
                }
                staged.forEach { (key, value) ->
                    if (value == null) {
                        if (store.remove(key) != null) changed += key
                    } else if (store.put(key, value) != value) changed += key
                }
            }
            changed.forEach { key -> listeners.forEach { it.onSharedPreferenceChanged(this@MemoryPrefs, key) } }
        }
    }
}

/**
 * Debug builds only: the context the screen previews run the real shell in. Preferences are [MemoryPrefs], every directory (files, cache,
 * databases) is under [root], the application context is this one, and no service is ever bound; everything else is the activity's.
 */
internal class PreviewContext(base: Context, private val root: File) : ContextWrapper(base) {
    private val prefs = HashMap<String, MemoryPrefs>()

    private fun dir(name: String) = File(root, name).apply { mkdirs() }

    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = synchronized(prefs) { prefs.getOrPut(name.orEmpty()) { MemoryPrefs() } }
    override fun deleteSharedPreferences(name: String?): Boolean = synchronized(prefs) { prefs.remove(name.orEmpty()) != null }
    override fun moveSharedPreferencesFrom(sourceContext: Context?, name: String?): Boolean = false
    override fun getFilesDir(): File = dir("files")
    override fun getNoBackupFilesDir(): File = dir("no-backup")
    override fun getCacheDir(): File = dir("cache")
    override fun getCodeCacheDir(): File = dir("code-cache")
    override fun getDir(name: String?, mode: Int): File = dir("app_${name.orEmpty()}")
    override fun getExternalFilesDir(type: String?): File = dir("external-files/${type.orEmpty()}")
    override fun getExternalCacheDir(): File = dir("external-cache")
    override fun getDatabasePath(name: String?): File = File(dir("databases"), name.orEmpty())
    override fun getFileStreamPath(name: String?): File = File(filesDir, name.orEmpty())
    override fun openFileInput(name: String?): FileInputStream = FileInputStream(getFileStreamPath(name))
    override fun openFileOutput(name: String?, mode: Int): FileOutputStream = FileOutputStream(getFileStreamPath(name), mode and MODE_APPEND != 0)
    override fun deleteFile(name: String?): Boolean = getFileStreamPath(name).delete()
    override fun fileList(): Array<String> = filesDir.list() ?: emptyArray()
    override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean = false
}

/** Debug builds only: pairing that shows its fixed PIN and then waits, as it does while nobody enters it on the PC. */
internal class PreviewSetup(context: Context, repo: PcRepository, profiles: ProfileStore) : PcSetup(context, repo, profiles) {
    override fun newPin(): String = PreviewFixtures.PIN
    override suspend fun pair(pc: Pc, pin: String, passphrase: String?): PcSetup.PairResult = awaitCancellation()
}

/** What a shell preview opens: the [screen] (it gets the world, to pick a fake PC or game), a [layer] over it and the Home [layout]. */
internal class ShellRoute(
    val layout: HomeLayout = HomeLayout.ROW,
    val layer: Layer? = null,
    val screen: (PreviewWorld) -> Screen,
)

/**
 * Debug builds only: everything the real [Shell] needs, with fake data and no way to reach the user's data: a [PreviewContext] (memory
 * preferences, files in a cache folder that is emptied at each start), a [PcRepository] that was never bound to the service and is
 * fed [PreviewFixtures] PCs, the artwork and metadata stores of the fixtures (no network), and a [ProfileStore] that never has a
 * profile (a profile would be projected into the user's stream settings profiles); the profiles the screens show are
 * [PreviewFixtures.profiles] (`Shell`'s `previewProfiles`). The appearance is the defaults, dark, with the route's Home layout; it is
 * kept in the memory preferences, so the user's settings are not touched.
 */
internal class PreviewWorld(base: Context, activity: Activity, route: ShellRoute, textScale: Float) {
    private val root = File(base.cacheDir, "screen-previews").apply {
        deleteRecursively()
        mkdirs()
    }
    val context = PreviewContext(base, root)
    val profiles = ProfileStore(context, later = { _, _ -> })
    val keys = GlobalKeys()
    val appearanceStore = AppearanceStore(context, profiles::settings)
    val startupStore = StartupStore(profiles::settings)
    val repo = PcRepository(context, profiles::settings)
    val boxArt = BoxArtCache(context) { null }
    val artwork: ArtworkStore = PreviewArtworkStore(context)
    val metadata = GameMetadataStore(
        root = File(context.filesDir, "game-metadata"), settings = profiles::settings, providers = PreviewMetadataProviders,
        clock = { PreviewFixtures.NOW }, io = Dispatchers.IO, main = Dispatchers.Main,
    )
    val launcher = GameLauncher(activity, repo)
    val setup: PcSetup = PreviewSetup(context, repo, profiles)
    val links = MutableStateFlow<ShellLink?>(null)
    val results = MutableStateFlow<StreamResult?>(null)
    val previewProfiles: Profiles = PreviewFixtures.profiles()
    val shell: ShellState

    init {
        // The shell offers a report for a crash of an earlier run, once per process: that was this call, so no card covers a preview
        runCatching { ProblemMonitor.detectAtStart(context) }
        appearanceStore.update { it.copy(dark = true, homeLayout = route.layout, uiScale = (textScale * 100f).roundToInt().coerceIn(100, 130)) }
        startupStore.update { it.copy(defaultPc = PreviewFixtures.ACTIVE_PC) }
        PreviewFixtures.seed(repo, profiles.settings())
        checkNotNull(repo.library.value as? Library.Ready) { "The preview library did not load" } // l10n-ignore
        shell = ShellState(route.screen(this))
        route.layer?.let { shell.openLayer(it, FocusTracker()) }
    }

    /** The games of the active fake PC, once the repository has parsed them. */
    val library: Library.Ready get() = repo.library.value as Library.Ready

    fun close() {
        profiles.streamProfiles.close()
    }
}
