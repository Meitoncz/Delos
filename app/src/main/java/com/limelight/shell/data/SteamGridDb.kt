package com.limelight.shell.data

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.limelight.LimeLog
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** The four SteamGridDB images of a game (docs/GUI.md → SteamGridDB). */
enum class ArtKind(val file: String) { COVER("cover"), WIDE("wide"), HERO("hero"), LOGO("logo") }

/** A game found on SteamGridDB. */
data class SgdbGame(val id: Int, val name: String, val year: Int?)

/** One image on SteamGridDB: [thumb] for the choices, [url] for the full size. */
data class SgdbImage(val id: Int, val url: String, val thumb: String, val width: Int, val height: Int)

/** [ArtworkStore.chosen] for a kind left empty on purpose (the PC's artwork, or the name instead of a logo). */
const val NONE = -1

/**
 * The game an app was matched with ([id] null: searched, nothing found; searched again on refresh), the
 * image used for each kind ([chosen], by kind file name) and the kinds picked by hand ([picked]), which
 * downloading everything again leaves alone. [manual]: the game was chosen by hand.
 */
private data class Match(
    val id: Int?,
    val name: String?,
    val manual: Boolean = false,
    val chosen: Map<String, Int>? = null,
    val picked: Set<String>? = null,
)

class SteamGridDbException(message: String) : IOException(message)

/** The SteamGridDB API (v2) with the user's own key. Blocking calls, run them off the main thread. */
class SteamGridDbClient(private val key: String, private val http: OkHttpClient) {
    fun search(term: String): List<SgdbGame> {
        val json = get("search/autocomplete/${term.trim()}")
        return json.getAsJsonArray("data")?.mapNotNull { e ->
            val o = e.asJsonObject
            val year = o.get("release_date")?.takeIf { !it.isJsonNull }?.asLong
                ?.let { Calendar.getInstance().apply { timeInMillis = it * 1000 }.get(Calendar.YEAR) }
            SgdbGame(o.get("id").asInt, o.get("name").asString, year)
        }.orEmpty()
    }

    /** The still images of [kind] for [gameId], best rated first (SteamGridDB sorts them by votes). */
    fun images(gameId: Int, kind: ArtKind): List<SgdbImage> {
        val path = when (kind) {
            ArtKind.COVER, ArtKind.WIDE -> "grids/game/$gameId"
            ArtKind.HERO -> "heroes/game/$gameId"
            ArtKind.LOGO -> "logos/game/$gameId"
        }
        val params = buildMap {
            put("types", "static")
            put("nsfw", "false")
            put("humor", "false")
            put("mimes", if (kind == ArtKind.LOGO) "image/png,image/webp" else "image/png,image/jpeg,image/webp")
            when (kind) {
                ArtKind.COVER -> put("dimensions", "600x900,660x930,342x482")
                ArtKind.WIDE -> put("dimensions", "920x430,460x215")
                else -> Unit
            }
        }
        val data = get(path, params).getAsJsonArray("data") ?: return emptyList()
        return data.mapNotNull { e ->
            val o = e.asJsonObject
            val url = o.get("url")?.asString ?: return@mapNotNull null
            SgdbImage(o.get("id").asInt, url, o.get("thumb")?.asString ?: url, o.get("width")?.asInt ?: 0, o.get("height")?.asInt ?: 0)
        }
    }

    fun download(url: String, into: File) {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw SteamGridDbException("HTTP ${response.code}")
            val body = response.body
            if (body.contentLength() > MAX_FILE_BYTES) throw SteamGridDbException("image too large")
            val tmp = File(into.path + ".part")
            body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (tmp.length() > MAX_FILE_BYTES) {
                tmp.delete()
                throw SteamGridDbException("image too large")
            }
            into.delete()
            if (!tmp.renameTo(into)) throw SteamGridDbException("could not save the image")
        }
    }

    private fun get(path: String, params: Map<String, String> = emptyMap()): JsonObject {
        val url = "$BASE/".toHttpUrl().newBuilder().addPathSegments(path).apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $key").build()
        http.newCall(request).execute().use { response ->
            when (response.code) {
                401, 403 -> throw SteamGridDbException(KEY_REJECTED)
                404 -> return JsonObject()
            }
            if (!response.isSuccessful) throw SteamGridDbException("SteamGridDB answered ${response.code}")
            return JsonParser.parseString(response.body.string()).asJsonObject
        }
    }

    companion object {
        const val KEY_REJECTED = "SteamGridDB did not accept the API key"
        const val MAX_FILE_BYTES = 15L * 1024 * 1024
        private const val BASE = "https://www.steamgriddb.com/api/v2"
    }
}

/**
 * SteamGridDB images of the PCs' games (step 6.8), kept in the app's files (artwork/<pc>/<app>/), so they
 * survive a cache clean-up: the game each app was matched with (by name, or chosen by hand) and its cover,
 * wide cover, hero and logo. The API key and "download automatically" are the same for every profile on
 * this device. [version] changes whenever images change, so the screens load them again.
 */
class ArtworkStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = PreferenceManager.getDefaultSharedPreferences(app)
    private val root = File(app.filesDir, "artwork")
    val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val gson = Gson()
    private val memory = object : LruCache<String, ImageBitmap>(MEMORY_KIB) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4 / 1024
    }
    private val network = Semaphore(3)
    private val locks = HashMap<String, Mutex>()
    private val keyState = mutableStateOf(prefs.getString(KEY_API, "").orEmpty())
    private val autoState = mutableStateOf(prefs.getBoolean(KEY_AUTO, true))

    /** Bumped whenever downloaded images change; readers key their loads on it. */
    val version = mutableIntStateOf(0)

    /** The games being downloaded now ("pc/app"), and the image being applied per kind ("pc/app/kind" → its id). */
    private val busyGames = mutableStateMapOf<String, Boolean>()
    private val busyKinds = mutableStateMapOf<String, Int>()

    /** Downloading the artwork of many games: how far it is, null when nothing runs. */
    val progress = mutableStateOf<ArtworkProgress?>(null)

    fun isBusy(pc: Pc, app: App): Boolean = busyGames["${pc.uuid}/${app.id}"] == true

    /** The id of the image being downloaded for [kind] ([NONE] for none), or null. */
    fun applying(pc: Pc, app: App, kind: ArtKind): Int? = busyKinds[memoryKey(pc, app, kind)]

    private suspend fun <T> busy(pc: Pc, app: App, block: suspend () -> T): T {
        val key = "${pc.uuid}/${app.id}"
        withContext(Dispatchers.Main) { busyGames[key] = true }
        try {
            return block()
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { busyGames.remove(key) }
        }
    }

    var apiKey: String
        get() = keyState.value
        set(value) {
            keyState.value = value.trim()
            prefs.edit().putString(KEY_API, value.trim()).apply()
        }

    var automatic: Boolean
        get() = autoState.value
        set(value) {
            autoState.value = value
            prefs.edit().putBoolean(KEY_AUTO, value).apply()
        }

    val enabled: Boolean get() = apiKey.isNotEmpty()

    /**
     * Whether [app] is looked up by its name automatically: not the remote desktop, whose name would match
     * some random game (its artwork is chosen by hand on the artwork screen).
     */
    fun eligible(app: App): Boolean = !app.name.equals("Desktop", ignoreCase = true)

    fun cached(pc: Pc, app: App, kind: ArtKind): ImageBitmap? = memory.get(memoryKey(pc, app, kind))

    /** The downloaded image of [kind] for [app], or null. */
    suspend fun load(pc: Pc, app: App, kind: ArtKind): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = memoryKey(pc, app, kind)
        memory.get(key)?.let { return@withContext it }
        val file = file(pc, app, kind)
        if (!file.exists() || file.length() == 0L) return@withContext null
        decode(file, kind)?.also { memory.put(key, it) }
    }

    /** The SteamGridDB game [app] is matched with, if any. */
    fun matched(pc: Pc, app: App): SgdbGame? = readMatch(pc, app)?.let { m -> m.id?.let { SgdbGame(it, m.name.orEmpty(), null) } }

    /** Whether [app] has been looked up on SteamGridDB (found or not). */
    fun searched(pc: Pc, app: App): Boolean = readMatch(pc, app) != null

    /** The SteamGridDB image used for [kind] (its id), [NONE] when left empty on purpose, null if never set. */
    fun chosen(pc: Pc, app: App, kind: ArtKind): Int? = readMatch(pc, app)?.chosen?.get(kind.file)

    suspend fun search(term: String): List<SgdbGame> = withContext(Dispatchers.IO) { network.withPermit { client().search(term) } }

    /** The images [kind] can be chosen from for [app]'s matched game. */
    suspend fun choices(pc: Pc, app: App, kind: ArtKind): List<SgdbImage> = withContext(Dispatchers.IO) {
        val id = readMatch(pc, app)?.id ?: return@withContext emptyList()
        network.withPermit { client().images(id, kind) }
    }

    /**
     * Downloads [app]'s images, the best-rated of each kind. The game is looked up by name the first time;
     * images already there are kept unless [refresh], which fetches all but the hand-picked ones again.
     * Returns whether a game was found.
     */
    suspend fun fetch(pc: Pc, app: App, refresh: Boolean = false): Boolean = busy(pc, app) { fetchNow(pc, app, refresh) }

    private suspend fun fetchNow(pc: Pc, app: App, refresh: Boolean): Boolean = withContext(Dispatchers.IO) {
        lock(pc, app).withLock {
            val client = client()
            var match = readMatch(pc, app)
            if (match == null || (refresh && !match.manual)) {
                val found = network.withPermit { client.search(app.name) }.firstOrNull()
                match = if (match != null && found?.id == match.id) match else Match(found?.id, found?.name)
            }
            writeMatch(pc, app, match)
            val id = match.id ?: return@withContext false
            val chosen = (match.chosen ?: emptyMap()).toMutableMap()
            val picked = match.picked ?: emptySet()
            ArtKind.entries.forEach { kind ->
                if (kind.file in picked) return@forEach
                if (!refresh && kind.file in chosen) return@forEach
                try {
                    val image = network.withPermit { client.images(id, kind) }.firstOrNull()
                    store(pc, app, kind, image, client)
                    chosen[kind.file] = image?.id ?: NONE
                } catch (e: SteamGridDbException) {
                    if (e.message == SteamGridDbClient.KEY_REJECTED) throw e
                    LimeLog.info("SteamGridDB: no ${kind.file} for ${app.name}: ${e.message}")
                }
            }
            writeMatch(pc, app, match.copy(chosen = chosen))
            changed()
            true
        }
    }

    /** Matches [app] with [game] by hand and downloads its best-rated images (hand-picked ones start over). */
    suspend fun setGame(pc: Pc, app: App, game: SgdbGame) {
        withContext(Dispatchers.IO) {
            lock(pc, app).withLock { writeMatch(pc, app, Match(game.id, game.name, manual = true)) }
        }
        fetch(pc, app, refresh = true)
    }

    /** Uses [image] for [kind] (null: none, so the PC's artwork or the game's name), picked by hand. */
    suspend fun apply(pc: Pc, app: App, kind: ArtKind, image: SgdbImage?) {
        val key = memoryKey(pc, app, kind)
        withContext(Dispatchers.Main) { busyKinds[key] = image?.id ?: NONE }
        try {
            busy(pc, app) { applyNow(pc, app, kind, image) }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { busyKinds.remove(key) }
        }
    }

    private suspend fun applyNow(pc: Pc, app: App, kind: ArtKind, image: SgdbImage?) {
        withContext(Dispatchers.IO) {
            lock(pc, app).withLock {
                store(pc, app, kind, image, client())
                val match = readMatch(pc, app) ?: Match(null, null)
                writeMatch(pc, app, match.copy(
                    chosen = (match.chosen ?: emptyMap()) + (kind.file to (image?.id ?: NONE)),
                    picked = (match.picked ?: emptySet()) + kind.file,
                ))
            }
        }
        changed()
    }

    /** The apps of [apps] that have never been looked up. */
    fun missing(pc: Pc, apps: List<App>): List<App> = apps.filter { eligible(it) && readMatch(pc, it) == null }

    private fun store(pc: Pc, app: App, kind: ArtKind, image: SgdbImage?, client: SteamGridDbClient) {
        val file = file(pc, app, kind)
        if (image == null) file.delete() else client.download(image.url, file)
        memory.remove(memoryKey(pc, app, kind))
    }

    private suspend fun changed() = withContext(Dispatchers.Main) { version.intValue++ }

    private fun client(): SteamGridDbClient {
        val key = apiKey.ifEmpty { throw SteamGridDbException("Add your SteamGridDB API key first") }
        return SteamGridDbClient(key, http)
    }

    private fun decode(file: File, kind: ArtKind) = runCatching {
        // The game background is blurred and small anyway; covers and logos keep about their size
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val limit = if (kind == ArtKind.HERO) 1280 else 1000
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= limit) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()

    private fun dir(pc: Pc, app: App) = File(root, "${pc.uuid}/${app.id}")
    private fun file(pc: Pc, app: App, kind: ArtKind) = File(dir(pc, app).apply { mkdirs() }, kind.file)
    private fun memoryKey(pc: Pc, app: App, kind: ArtKind) = "${pc.uuid}/${app.id}/${kind.file}"

    private fun readMatch(pc: Pc, app: App): Match? = runCatching {
        val f = File(dir(pc, app), MATCH)
        if (f.exists()) gson.fromJson(f.readText(), Match::class.java) else null
    }.getOrNull()

    private fun writeMatch(pc: Pc, app: App, match: Match) {
        File(dir(pc, app).apply { mkdirs() }, MATCH).writeText(gson.toJson(match))
    }

    @Synchronized
    private fun lock(pc: Pc, app: App) = locks.getOrPut("${pc.uuid}/${app.id}") { Mutex() }

    private companion object {
        const val KEY_API = "sgdb_api_key"
        const val KEY_AUTO = "sgdb_automatic"
        const val MATCH = "match.json"
        /** Covers, wide covers and a few backgrounds. */
        const val MEMORY_KIB = 96 * 1024
    }
}

/**
 * Downloads the best-rated artwork of [apps] on [pc]: only games never looked up, or all of them again
 * with [refresh] (hand-picked images stay). Stops at the first error that concerns every game (no key, a
 * rejected key) and reports it with [onError]. Returns how many games were found on SteamGridDB.
 */
suspend fun ArtworkStore.downloadAll(pc: Pc, apps: List<App>, refresh: Boolean, onError: (String) -> Unit): Int {
    val todo = if (refresh) apps.filter { eligible(it) } else missing(pc, apps)
    if (todo.isEmpty() || progress.value != null) return 0
    var found = 0
    var done = 0
    var failed: String? = null
    val games = Semaphore(2)
    progress.value = ArtworkProgress(0, todo.size)
    try {
        coroutineScope {
            todo.forEach { app ->
                launch {
                    games.withPermit {
                        if (failed != null) return@withPermit
                        try {
                            if (fetch(pc, app, refresh)) found++
                        } catch (e: SteamGridDbException) {
                            failed = e.message ?: "SteamGridDB is not reachable"
                        } catch (e: IOException) {
                            LimeLog.info("SteamGridDB: ${app.name}: $e")
                        }
                        done++
                        progress.value = ArtworkProgress(done, todo.size)
                    }
                }
            }
        }
    } finally {
        progress.value = null
    }
    failed?.let(onError)
    return found
}

/** [done] of [total] games downloaded. */
data class ArtworkProgress(val done: Int, val total: Int)

val LocalArtwork = staticCompositionLocalOf<ArtworkStore> { error("ArtworkStore not provided") }

/**
 * [app]'s SteamGridDB image of [kind], or null; reloads when the store's images change. The state is
 * keyed by the app, so a caller that switches apps never sees the previous app's image (produceState
 * would keep it until the new one is loaded).
 */
@Composable
fun rememberArtwork(store: ArtworkStore, pc: Pc, app: App, kind: ArtKind): State<ImageBitmap?> {
    val version = store.version.intValue
    val state = remember(pc.uuid, app.id, kind) { mutableStateOf(store.cached(pc, app, kind)) }
    LaunchedEffect(state, version) { state.value = store.load(pc, app, kind) }
    return state
}

/** Thumbnails of the choices on the artwork screen, loaded from SteamGridDB and kept in memory. */
object Thumbnails {
    private val memory = object : LruCache<String, ImageBitmap>(48 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4 / 1024
    }
    private val network = Semaphore(4)

    fun cached(url: String): ImageBitmap? = memory.get(url)

    suspend fun load(http: OkHttpClient, url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        memory.get(url)?.let { return@withContext it }
        network.withPermit {
            runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val bytes = response.body.bytes()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= 520) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                }
            }.getOrNull()
        }?.also { memory.put(url, it) }
    }
}

@Composable
fun rememberThumbnail(store: ArtworkStore, url: String): State<ImageBitmap?> {
    val state = remember(url) { mutableStateOf(Thumbnails.cached(url)) }
    LaunchedEffect(state) { if (state.value == null) state.value = Thumbnails.load(store.http, url) }
    return state
}
