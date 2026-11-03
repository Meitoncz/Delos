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
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Provider data and matches are device-local; filters follow the active Delos profile. */
class GameMetadataStore internal constructor(
    private val root: File,
    private val settings: () -> SharedPreferences,
    private val providers: MetadataProviders,
    private val clock: () -> Long,
    private val io: CoroutineDispatcher,
    private val main: CoroutineDispatcher,
) {
    constructor(
        context: Context,
        settings: () -> SharedPreferences = {
            PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        },
    ) : this(
        File(context.applicationContext.filesDir, "game-metadata"), settings, HttpMetadataProviders(),
        System::currentTimeMillis, Dispatchers.IO, Dispatchers.Main,
    )

    private val gson = Gson()
    private val states = mutableStateMapOf<String, GameMetadataState>()
    private val initialized = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val locks = HashMap<String, Mutex>()
    private val network = Semaphore(2)
    private val emptyState = GameMetadataState()
    private val resolvedEmptyState = GameMetadataState(resolved = true)
    private var filterState by mutableStateOf(readFilters())

    /** Games being fetched from providers; cached/backed-off reads never start a batch. */
    val progress = mutableStateOf<ArtworkProgress?>(null)
    private val progressMutex = Mutex()
    private val progressBatches = ArrayList<ProgressBatch>()

    val proxyAvailable: Boolean get() = providers.proxyAvailable

    val filters: MetadataFilters get() = filterState

    fun reloadFilters() { filterState = readFilters() }

    fun updateFilters(value: MetadataFilters) {
        filterState = value
        settings().edit()
            .putInt(KEY_GENRE, value.genreId ?: 0)
            .putInt(KEY_THEME, value.themeId ?: 0)
            .putString(KEY_HDR, value.hdr.name)
            .putString(KEY_LENGTH, value.length.name)
            .apply()
    }

    fun entry(pc: Pc, app: App): GameMetadataState =
        if (BundledArtwork.kind(app) != null) resolvedEmptyState else states[key(pc, app)] ?: emptyState

    /** Every genre any of [apps] has in the records published so far, by name. */
    fun genres(pc: Pc, apps: List<App>): List<GameGenre> = apps.asSequence()
        .flatMap { entry(pc, it).data?.candidate?.genres.orEmpty().asSequence() }
        .distinctBy { it.id }.sortedBy { it.name }.toList()

    /** Every theme any of [apps] has in the records published so far, by name. */
    fun themes(pc: Pc, apps: List<App>): List<GameGenre> = apps.asSequence()
        .flatMap { entry(pc, it).data?.candidate?.themes.orEmpty().asSequence() }
        .distinctBy { it.id }.sortedBy { it.name }.toList()

    /**
     * Publishes every cached record first, so filters and the genre list are complete immediately, and only
     * then refreshes the stale ones with bounded concurrency. A slow provider never hides cached games.
     */
    suspend fun ensureLibrary(pc: Pc, apps: List<App>) {
        val games = apps.filter { BundledArtwork.kind(it) == null }
        for (app in games) {
            currentCoroutineContext().ensureActive()
            lock(pc, app).withLock { loadLocked(pc, app) }
        }
        val now = clock()
        val pending = games.filter { refreshDue(entry(pc, it).data, now) }
        if (pending.isEmpty()) return
        if (!providers.proxyAvailable) {
            for (app in pending) ensure(pc, app)
            return
        }
        val gate = Semaphore(LIBRARY_CONCURRENCY)
        withProgressBatch(pending.size) { batch ->
            coroutineScope {
                for (app in pending) launch {
                    gate.withPermit {
                        val fetched = ensure(pc, app, force = false, batch = batch)
                        finishProgressGame(batch, fetched)
                    }
                }
            }
        }
    }

    suspend fun ensure(pc: Pc, app: App, force: Boolean = false) {
        ensure(pc, app, force, batch = null)
    }

    private suspend fun ensure(pc: Pc, app: App, force: Boolean, batch: ProgressBatch?): Boolean {
        if (BundledArtwork.kind(app) != null) return false
        lock(pc, app).withLock {
            val key = key(pc, app)
            val previous = loadLocked(pc, app)
            if (!force && !refreshDue(previous.data, clock())) return false
            if (!providers.proxyAvailable) {
                publish(key, previous.copy(resolved = true, error = "Game metadata is not available in this build"))
                return false
            }
            if (batch == null) {
                withProgressBatch(1) { single ->
                    refreshLocked(pc, app, key, previous, force)
                    finishProgressGame(single, fetched = true)
                }
            } else {
                refreshLocked(pc, app, key, previous, force)
            }
            return true
        }
    }

    private suspend fun refreshLocked(pc: Pc, app: App, key: String, previous: GameMetadataState, force: Boolean) {
        publish(key, previous.copy(loading = true, error = null))
        try {
            network.withPermit { refresh(pc, app, previous.data, force) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A cancelled call can surface as an I/O error; that is not a provider failure.
            currentCoroutineContext().ensureActive()
            // Provider payloads and failures must never leak details to the UI/log.
            recordLookupFailure(pc, app, previous.data)
        } finally {
            clearLoading(key)
        }
    }

    suspend fun search(query: String): List<MetadataCandidate> = withProgressBatch(1) { batch ->
        val matches = blocking { providers.search(query.trim()) }
        finishProgressGame(batch, fetched = true)
        matches
    }

    suspend fun select(pc: Pc, app: App, candidate: MetadataCandidate) {
        if (BundledArtwork.kind(app) != null) return
        lock(pc, app).withLock {
            val key = key(pc, app)
            withProgressBatch(1) { batch ->
                network.withPermit {
                    // Commit the explicit identity before slower secondary lookups. Never reuse another game's evidence.
                    val selected = GameMetadata(candidate = candidate, manual = true)
                    withContext(io) { write(pc, app, selected) }
                    initialized.add(key)
                    publish(key, GameMetadataState(data = selected, loading = true, resolved = true))
                    try {
                        resolve(pc, app, candidate, manual = true, previous = null, force = true)
                        finishProgressGame(batch, fetched = true)
                    } finally {
                        clearLoading(key)
                    }
                }
            }
        }
    }

    suspend fun reset(pc: Pc, app: App) {
        if (BundledArtwork.kind(app) != null) return
        lock(pc, app).withLock {
            withContext(io) { AtomicFile(file(pc, app)).delete() }
            initialized.add(key(pc, app))
            publish(key(pc, app), emptyState)
        }
        ensure(pc, app, force = true)
    }

    /** Reads the record from disk once per process; the caller holds the game's lock. */
    private suspend fun loadLocked(pc: Pc, app: App): GameMetadataState {
        val key = key(pc, app)
        if (!initialized.contains(key)) {
            val disk = withContext(io) { read(pc, app) }
            initialized.add(key)
            if (disk != null) publish(key, GameMetadataState(data = disk, resolved = true, error = errorFor(disk)))
        }
        return entry(pc, app)
    }

    private suspend fun refresh(pc: Pc, app: App, previous: GameMetadata?, force: Boolean) {
        val manualCandidate = previous?.takeIf { it.manual }?.candidate
        val candidate = if (manualCandidate != null) {
            blocking { providers.game(manualCandidate.id) } ?: throw IgdbMetadataException("IGDB match unavailable")
        } else {
            chooseMatch(app.name, blocking { providers.search(app.name) })
        }
        currentCoroutineContext().ensureActive()
        if (candidate == null) {
            val absent = GameMetadata(updatedAt = clock())
            withContext(io) { write(pc, app, absent) }
            publish(key(pc, app), GameMetadataState(data = absent, resolved = true))
        } else {
            resolve(pc, app, candidate, manualCandidate != null, previous, force)
        }
    }

    /**
     * Asks PCGamingWiki and HowLongToBeat about a matched game. A provider whose last failure is still inside its
     * backoff is skipped (unless [force]) and keeps its previous answer; the failure time is persisted per provider.
     */
    private suspend fun resolve(pc: Pc, app: App, candidate: MetadataCandidate, manual: Boolean, previous: GameMetadata?, force: Boolean) {
        val carried = previous?.takeIf { it.candidate?.id == candidate.id }
        val started = clock()
        var hdr = carried?.hdr ?: HdrEvidence(NativeHdr.UNKNOWN)
        var hdrFailedAt = carried?.pcgwFailedAt ?: 0L
        if (force || !isBackedOff(hdrFailedAt, started)) {
            try {
                hdr = blocking { providers.hdr(candidate) }
                hdrFailedAt = 0
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                hdrFailedAt = clock()
            }
        }
        currentCoroutineContext().ensureActive()
        var completion = carried?.completion
        var completionFailedAt = carried?.hltbFailedAt ?: 0L
        if (force || !isBackedOff(completionFailedAt, started)) {
            try {
                completion = blocking { providers.completion(candidate) }
                completionFailedAt = 0
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                completionFailedAt = clock()
            }
        }
        currentCoroutineContext().ensureActive()
        val failed = hdrFailedAt > 0 || completionFailedAt > 0
        val data = GameMetadata(
            candidate, hdr, completion, if (failed) 0 else clock(), manual,
            igdbFailedAt = 0, pcgwFailedAt = hdrFailedAt, hltbFailedAt = completionFailedAt,
        )
        withContext(io) { write(pc, app, data) }
        publish(key(pc, app), GameMetadataState(data = data, resolved = true, error = errorFor(data)))
    }

    /** IGDB (or the cache) failed: remember when, so focusing the game again does not retry before the backoff. */
    private suspend fun recordLookupFailure(pc: Pc, app: App, previous: GameMetadata?) {
        val failed = (previous ?: GameMetadata()).copy(updatedAt = 0, igdbFailedAt = clock())
        try {
            withContext(io) { write(pc, app, failed) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The in-memory state below still suppresses retries for this session.
        }
        publish(key(pc, app), GameMetadataState(data = failed, resolved = true, error = errorFor(failed)))
    }

    private fun errorFor(data: GameMetadata): String? = when {
        data.igdbFailedAt > 0 -> "Game information could not be updated. Open Metadata to refresh or choose a match."
        data.pcgwFailedAt > 0 || data.hltbFailedAt > 0 -> "Some game information is unavailable. Open Metadata to refresh."
        else -> null
    }

    private suspend fun <T> withProgressBatch(total: Int, block: suspend (ProgressBatch) -> T): T {
        val batch = ProgressBatch(total)
        try {
            withContext(main) {
                progressMutex.withLock {
                    progressBatches.add(batch)
                    publishProgress()
                }
            }
            return block(batch)
        } finally {
            withContext(NonCancellable + main) {
                progressMutex.withLock {
                    progressBatches.remove(batch)
                    publishProgress()
                }
            }
        }
    }

    private suspend fun finishProgressGame(batch: ProgressBatch, fetched: Boolean) =
        withContext(NonCancellable + main) {
            progressMutex.withLock {
                if (fetched) batch.done++ else batch.total--
                publishProgress()
            }
        }

    /** Called on main with progressMutex held; a focused lookup cannot replace a larger library batch. */
    private fun publishProgress() {
        val batch = progressBatches.maxByOrNull { it.total }
        progress.value = if (batch == null || batch.total == 0) null else ArtworkProgress(batch.done, batch.total)
    }

    private suspend fun clearLoading(key: String) = withContext(NonCancellable + main) {
        val current = states[key]
        if (current?.loading == true) states[key] = current.copy(loading = false)
    }

    /** Provider clients block; cancelling the coroutine interrupts them (pacing sleeps and the next socket call). */
    private suspend fun <T> blocking(block: () -> T): T = runInterruptible(io, block)

    private suspend fun publish(key: String, state: GameMetadataState) =
        withContext(NonCancellable + main) { states[key] = state }

    private fun read(pc: Pc, app: App): GameMetadata? = runCatching {
        val atomic = AtomicFile(file(pc, app))
        if (!atomic.baseFile.exists()) return@runCatching null
        atomic.openRead().bufferedReader().use { reader ->
            gson.fromJson(reader, CachedMetadata::class.java)?.takeIf { it.schema == CACHE_SCHEMA }?.data?.validated()
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
            prefs.getInt(KEY_THEME, 0).takeIf { it > 0 },
            runCatching { HdrFilter.valueOf(prefs.getString(KEY_HDR, null).orEmpty()) }.getOrDefault(HdrFilter.ANY),
            runCatching { LengthFilter.valueOf(prefs.getString(KEY_LENGTH, null).orEmpty()) }.getOrDefault(LengthFilter.ANY),
        )
    }

    private class ProgressBatch(var total: Int, var done: Int = 0)

    private data class CachedMetadata(val schema: Int, val data: GameMetadata)

    private companion object {
        const val CACHE_SCHEMA = 5
        const val LIBRARY_CONCURRENCY = 2
        const val KEY_GENRE = "metadata_filter_genre"
        const val KEY_THEME = "metadata_filter_theme"
        const val KEY_HDR = "metadata_filter_hdr"
        const val KEY_LENGTH = "metadata_filter_length"
    }
}

val LocalGameMetadata = staticCompositionLocalOf<GameMetadataStore> { error("GameMetadataStore not provided") }
