package com.limelight.shell.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import com.limelight.BuildConfig
import java.io.File
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Provider data and matches are device-local; filters follow the active Delos profile. */
class GameMetadataStore(
    context: Context,
    private val settings: () -> SharedPreferences = {
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    },
) {
    private val root = File(context.applicationContext.filesDir, "game-metadata")
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val igdb = IgdbMetadataClient(http, BuildConfig.DELOS_IGDB_CLIENT_ID, BuildConfig.DELOS_IGDB_CLIENT_SECRET)
    private val pcgw = PcGamingWikiClient(http, BuildConfig.DELOS_PCGW_BOT_USERNAME, BuildConfig.DELOS_PCGW_BOT_PASSWORD)
    private val hltb = HowLongToBeatClient(http)
    private val states = mutableStateMapOf<String, GameMetadataState>()
    private val initialized = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val locks = HashMap<String, Mutex>()
    private val network = Semaphore(2)
    private val emptyState = GameMetadataState()
    private var filterState by mutableStateOf(readFilters())

    val credentialsAvailable: Boolean
        get() = BuildConfig.DELOS_IGDB_CLIENT_ID.isNotBlank() && BuildConfig.DELOS_IGDB_CLIENT_SECRET.isNotBlank() &&
            BuildConfig.DELOS_PCGW_BOT_USERNAME.isNotBlank() && BuildConfig.DELOS_PCGW_BOT_PASSWORD.isNotBlank()

    val filters: MetadataFilters get() = filterState

    fun reloadFilters() { filterState = readFilters() }

    fun updateFilters(value: MetadataFilters) {
        filterState = value
        settings().edit()
            .putInt(KEY_GENRE, value.genreId ?: 0)
            .putString(KEY_HDR, value.hdr.name)
            .putString(KEY_LENGTH, value.length.name)
            .apply()
    }

    fun entry(pc: Pc, app: App): GameMetadataState = states[key(pc, app)] ?: emptyState

    fun genres(pc: Pc, apps: List<App>): List<GameGenre> = apps.asSequence()
        .flatMap { entry(pc, it).data?.candidate?.genres.orEmpty().asSequence() }
        .distinctBy { it.id }.sortedBy { it.name }.toList()

    suspend fun ensureLibrary(pc: Pc, apps: List<App>) {
        for (app in apps) {
            currentCoroutineContext().ensureActive()
            if (!app.name.equals("Desktop", ignoreCase = true)) ensure(pc, app)
        }
    }

    suspend fun ensure(pc: Pc, app: App, force: Boolean = false) {
        lock(pc, app).withLock {
            val key = key(pc, app)
            if (app.name.equals("Desktop", ignoreCase = true)) {
                publish(key, GameMetadataState(resolved = true))
                return
            }
            if (!initialized.contains(key)) {
                val disk = withContext(Dispatchers.IO) { read(pc, app) }
                initialized.add(key)
                if (disk != null) publish(key, GameMetadataState(data = disk, resolved = true))
            }
            val previous = entry(pc, app)
            if (!force && previous.resolved && previous.error == null &&
                System.currentTimeMillis() - (previous.data?.updatedAt ?: 0) < MAX_AGE) return
            if (!credentialsAvailable) {
                publish(key, previous.copy(resolved = true, error = "Game metadata credentials are not available in this build"))
                return
            }
            publish(key, previous.copy(loading = true, error = null))
            try {
                network.withPermit {
                    withContext(Dispatchers.IO) {
                        val candidate = if (previous.data?.manual == true) {
                            previous.data.candidate?.let { igdb.game(it.id) ?: throw IgdbMetadataException("IGDB match unavailable") }
                        } else {
                            val matches = igdb.search(app.name)
                            val name = normalizedName(app.name)
                            val exact = matches.filter { name.isNotEmpty() && normalizedName(it.name) == name }
                            exact.singleOrNull()
                        }
                        currentCoroutineContext().ensureActive()
                        if (candidate == null) {
                            val absent = GameMetadata(updatedAt = System.currentTimeMillis())
                            write(pc, app, absent)
                            publish(key, GameMetadataState(data = absent, resolved = true))
                        } else {
                            resolve(pc, app, candidate, previous.data?.manual == true, previous.data)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Provider payloads and authentication failures must never leak credentials to the UI/log.
                publish(key, entry(pc, app).copy(loading = false, resolved = true,
                    error = "Game information could not be updated. Open Metadata to refresh or choose a match."))
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    val current = states[key]
                    if (current?.loading == true) states[key] = current.copy(loading = false)
                }
            }
        }
    }

    suspend fun search(query: String): List<MetadataCandidate> = withContext(Dispatchers.IO) {
        igdb.search(query.trim())
    }

    suspend fun select(pc: Pc, app: App, candidate: MetadataCandidate) {
        lock(pc, app).withLock {
            val key = key(pc, app)
            network.withPermit {
                withContext(Dispatchers.IO) {
                    // Commit the explicit identity before slower secondary lookups. Never reuse another game's evidence.
                    val selected = GameMetadata(candidate = candidate, manual = true)
                    write(pc, app, selected)
                    initialized.add(key)
                    publish(key, GameMetadataState(data = selected, loading = true, resolved = true))
                    try {
                        resolve(pc, app, candidate, manual = true, previous = null)
                    } finally {
                        withContext(NonCancellable + Dispatchers.Main) {
                            val current = states[key]
                            if (current?.loading == true) states[key] = current.copy(loading = false)
                        }
                    }
                }
            }
        }
    }

    suspend fun reset(pc: Pc, app: App) {
        lock(pc, app).withLock {
            withContext(Dispatchers.IO) { AtomicFile(file(pc, app)).delete() }
            initialized.add(key(pc, app))
            publish(key(pc, app), emptyState)
        }
        ensure(pc, app, force = true)
    }

    private suspend fun resolve(pc: Pc, app: App, candidate: MetadataCandidate, manual: Boolean, previous: GameMetadata?) {
        val sameGame = previous?.candidate?.id == candidate.id
        var error = false
        val hdr = try {
            pcgw.lookup(candidate.steamAppId, candidate.name)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = true
            if (sameGame) previous.hdr else HdrEvidence(NativeHdr.UNKNOWN)
        }
        currentCoroutineContext().ensureActive()
        val completion = try {
            runInterruptible { hltb.lookup(candidate.name, candidate.steamAppId) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = true
            if (sameGame) previous.completion else null
        }
        currentCoroutineContext().ensureActive()
        val data = GameMetadata(candidate, hdr, completion, if (error) 0 else System.currentTimeMillis(), manual)
        write(pc, app, data)
        publish(key(pc, app), GameMetadataState(data = data, resolved = true,
            error = if (error) "Some game information is unavailable. Open Metadata to refresh." else null))
    }

    private suspend fun publish(key: String, state: GameMetadataState) =
        withContext(NonCancellable + Dispatchers.Main) { states[key] = state }

    private fun read(pc: Pc, app: App): GameMetadata? = runCatching {
        val atomic = AtomicFile(file(pc, app))
        if (!atomic.baseFile.exists()) return@runCatching null
        atomic.openRead().bufferedReader().use { reader ->
            gson.fromJson(reader, CachedMetadata::class.java)?.takeIf { it.schema == CACHE_SCHEMA }?.data
        }
    }.getOrNull()

    private fun write(pc: Pc, app: App, data: GameMetadata) {
        val target = file(pc, app)
        target.parentFile!!.mkdirs()
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try {
            output.write(gson.toJson(CachedMetadata(CACHE_SCHEMA, data)).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun file(pc: Pc, app: App) = File(File(root, "pc-${Uri.encode(pc.uuid)}"), "${app.id}.json")
    private fun key(pc: Pc, app: App) = "${pc.uuid}/${app.id}"

    @Synchronized
    private fun lock(pc: Pc, app: App) = locks.getOrPut(key(pc, app)) { Mutex() }

    private fun readFilters(): MetadataFilters {
        val prefs = settings()
        return MetadataFilters(
            prefs.getInt(KEY_GENRE, 0).takeIf { it > 0 },
            runCatching { HdrFilter.valueOf(prefs.getString(KEY_HDR, null).orEmpty()) }.getOrDefault(HdrFilter.ANY),
            runCatching { LengthFilter.valueOf(prefs.getString(KEY_LENGTH, null).orEmpty()) }.getOrDefault(LengthFilter.ANY),
        )
    }

    private data class CachedMetadata(val schema: Int, val data: GameMetadata)

    private companion object {
        const val CACHE_SCHEMA = 2
        const val KEY_GENRE = "metadata_filter_genre"
        const val KEY_HDR = "metadata_filter_hdr"
        const val KEY_LENGTH = "metadata_filter_length"
        const val MAX_AGE = 7 * 24 * 60 * 60 * 1000L
        val combiningMarks = "\\p{M}+".toRegex()
        val separators = "[^\\p{L}\\p{N}]+".toRegex()
        fun normalizedName(name: String) = Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(combiningMarks, "").lowercase(java.util.Locale.ROOT).replace(separators, "")
    }
}

val LocalGameMetadata = staticCompositionLocalOf<GameMetadataStore> { error("GameMetadataStore not provided") }
