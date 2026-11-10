package com.limelight.shell.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
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
import kotlin.math.roundToInt
import java.security.MessageDigest
import java.io.IOException
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** The four SteamGridDB images of a game (docs/GUI.md → SteamGridDB). */
enum class ArtKind(val file: String) { COVER("cover"), WIDE("wide"), HERO("hero"), LOGO("logo") }

/** A game found on SteamGridDB. */
data class SgdbGame(val id: Int, val name: String, val year: Int?)

/**
 * One image on SteamGridDB: [thumb] for the choices, [url] for the full size. The rest is what the API tells
 * about it (null / 0 when it does not): its [style], [score], the [author]'s name, the upload [date] (epoch
 * seconds) and [mime]. [animated]: it came from an "animated" request (an animated WebP).
 */
data class SgdbImage(
    val id: Int,
    val url: String,
    val thumb: String,
    val width: Int,
    val height: Int,
    val style: String? = null,
    val score: Int = 0,
    val author: String? = null,
    val date: Long? = null,
    val mime: String? = null,
    val animated: Boolean = false,
) {
    /** The file format as the info strip shows it ("WEBP", "JPG", "PNG"), null when unknown. */
    val format: String?
        get() = when (val m = mime?.substringAfter('/')?.lowercase()) {
            null, "" -> null
            "jpeg" -> "JPG"
            else -> m.uppercase()
        }

    /**
     * What a tile shows first: the thumbnail, except that SteamGridDB gives animated images a WebM thumbnail the app cannot decode
     * (the tile would load for ever), so those use the full image, whose first frame is the still.
     */
    val previewUrl: String get() = if (thumb.substringBefore('?').endsWith(".webm", ignoreCase = true)) url else thumb

    companion object {
        /** The image of one entry of a SteamGridDB list; null when it has no usable URL or is a WebM (the app cannot play it). */
        fun parse(o: JsonObject, animated: Boolean): SgdbImage? {
            val id = o.number("id")?.toInt() ?: return null
            val url = o.text("url") ?: return null
            val mime = o.text("mime")
            if (mime == "image/webm" || url.endsWith(".webm", ignoreCase = true)) return null
            val thumb = o.text("thumb") ?: url
            // Newer answers may list several styles: the first is shown
            val style = o.get("style")?.let { e ->
                if (e.isJsonArray) e.asJsonArray.firstOrNull()?.takeIf { it.isJsonPrimitive }?.asString else e.takeIf { it.isJsonPrimitive }?.asString
            }?.takeIf { it.isNotEmpty() }
            return SgdbImage(
                id, url, thumb, o.number("width")?.toInt() ?: 0, o.number("height")?.toInt() ?: 0,
                style = style, score = o.number("score")?.toInt() ?: 0,
                author = (o.get("author") as? JsonObject)?.text("name"),
                date = parseDate(o.get("date") ?: o.get("created_at")),
                mime = mime, animated = animated,
            )
        }

        /** Epoch seconds from a number (seconds or milliseconds) or an ISO date string; null if it is neither. */
        internal fun parseDate(e: com.google.gson.JsonElement?): Long? {
            if (e == null || !e.isJsonPrimitive) return null
            val p = e.asJsonPrimitive
            if (p.isNumber) return p.asLong.let { if (it > 100_000_000_000L) it / 1000 else it }
            val s = p.asString
            return s.toLongOrNull()?.let { if (it > 100_000_000_000L) it / 1000 else it }
                ?: runCatching { java.time.Instant.parse(s).epochSecond }.getOrNull()
                ?: runCatching { java.time.OffsetDateTime.parse(s).toEpochSecond() }.getOrNull()
        }

        private fun JsonObject.text(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
        private fun JsonObject.number(name: String): Number? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asNumber
    }
}

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
    val year: Int? = null,
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

    /**
     * The images of [kind] for [gameId] that match [filters], in the order of [ArtFilters.sort]. The still images
     * are the default (what the automatic downloads use); the filter sheet asks for both types. NSFW and humor
     * images are never requested.
     */
    fun images(gameId: Int, kind: ArtKind, filters: ArtFilters = ArtFilters(type = ArtType.STATIC)): List<SgdbImage> {
        val path = when (kind) {
            ArtKind.COVER, ArtKind.WIDE -> "grids/game/$gameId"
            ArtKind.HERO -> "heroes/game/$gameId"
            ArtKind.LOGO -> "logos/game/$gameId"
        }
        val queries = ArtFilterRules.queries(kind, filters)
        val lists = queries.map { query ->
            try {
                val data = get(path, query.params).getAsJsonArray("data") ?: return@map emptyList()
                data.mapNotNull { e -> (e as? JsonObject)?.let { SgdbImage.parse(it, query.animated) } }
            } catch (e: SteamGridDbException) {
                // Both types asked: the still images are still worth showing when the animated request fails
                if (queries.size > 1 && query.animated && e.message != KEY_REJECTED) emptyList() else throw e
            }
        }
        return ArtFilterRules.sort(ArtFilterRules.merge(lists), filters.sort)
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
        const val MAX_FILE_BYTES = 2048L * 1024 * 1024
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
    private val appContext = context.applicationContext
    private val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
    private val root = File(appContext.filesDir, "artwork")
    init { Thumbnails.init(appContext.cacheDir) }
    val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val gson = Gson()
    private val memory = object : LruCache<String, ArtworkResult>(MEMORY_KIB) {
        override fun sizeOf(key: String, value: ArtworkResult): Int = value.image!!.let { it.width * it.height * 4 / 1024 }
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
     * Built-in utilities have fixed offline artwork and are never matched or customized on SteamGridDB.
     */
    fun eligible(app: App): Boolean = BundledArtwork.kind(app) == null

    fun cached(pc: Pc, app: App, kind: ArtKind): ImageBitmap? = cachedResult(pc, app, kind)?.image

    internal fun cachedResult(pc: Pc, app: App, kind: ArtKind): ArtworkResult? {
        val builtin = BundledArtwork.kind(app)
        if (builtin != null) {
            val asset = builtin.assetPath(kind) ?: return null
            return memory.get("bundled:$asset")
        }
        return memory.get(memoryKey(pc, app, kind))
    }

    /**
     * The chosen image of [kind] as a file when it is an animated WebP the dashboard may play (the hero, a cover, a wide cover,
     * a logo), else null (a still image, a missing file, an app with built-in artwork, a canvas over [AnimatedWebp.MAX_PIXELS]).
     * The chosen file is the stored one: choosing an image stores the downloaded bytes as they are (the animation stays), and
     * the still readers ([load]) decode its first frame. The answer is kept per file (size and date), so a tile that scrolls
     * back in does not read the header again.
     */
    suspend fun animatedFile(pc: Pc, app: App, kind: ArtKind): File? = withContext(Dispatchers.IO) {
        if (!eligible(app)) return@withContext null
        val file = file(pc, app, kind)
        val length = file.length()
        val modified = file.lastModified()
        if (!file.isFile || length == 0L) return@withContext null
        val key = memoryKey(pc, app, kind)
        animatedMemory[key]?.takeIf { it.length == length && it.modified == modified }?.let { return@withContext it.file }
        val header = runCatching { file.inputStream().use { AnimatedWebp.read(it, AnimatedWebp.HEADER_BYTES) } }.getOrNull() ?: return@withContext null
        val playable = file.takeIf { AnimatedWebp.isAnimated(header) && AnimatedWebp.pixels(header) in 1..AnimatedWebp.MAX_PIXELS }
        animatedMemory.put(key, AnimatedEntry(length, modified, playable))
        playable
    }

    /** What [animatedFile] answered last for [kind], null when unknown, so a tile can start with it instead of waiting for the lookup. */
    fun cachedAnimatedFile(pc: Pc, app: App, kind: ArtKind): File? =
        if (!eligible(app)) null else animatedMemory[memoryKey(pc, app, kind)]?.file

    private class AnimatedEntry(val length: Long, val modified: Long, val file: File?)

    private val animatedMemory = LruCache<String, AnimatedEntry>(512)

    /** Built-ins ignore old local choices; ordinary apps retain their existing local-image precedence. */
    suspend fun load(pc: Pc, app: App, kind: ArtKind): ImageBitmap? = loadResult(pc, app, kind).image

    internal suspend fun loadResult(pc: Pc, app: App, kind: ArtKind): ArtworkResult = withContext(Dispatchers.IO) {
        val builtin = BundledArtwork.kind(app)
        if (builtin != null) {
            val asset = builtin.assetPath(kind) ?: return@withContext ArtworkResult(null, true)
            val key = "bundled:$asset"
            memory.get(key)?.let { return@withContext it }
            val image = appContext.assets.open(asset).use { input ->
                checkNotNull(BitmapFactory.decodeStream(input)) { "Invalid bundled artwork: $asset" }.asImageBitmap()
            }
            return@withContext ArtworkResult(image, resolved = true, bundledAsset = asset).also { memory.put(key, it) }
        }
        val key = memoryKey(pc, app, kind)
        memory.get(key)?.let { return@withContext it }
        val file = file(pc, app, kind)
        val image = if (file.exists() && file.length() > 0L) decode(file, kind) else null
        val result = ArtworkResult(image, resolved = true)
        if (image != null) memory.put(key, result)
        result
    }

    /** The SteamGridDB game [app] is matched with, if any. */
    fun matched(pc: Pc, app: App): SgdbGame? = readMatch(pc, app)?.let { m -> m.id?.let { SgdbGame(it, m.name.orEmpty(), m.year) } }

    /** Whether [app] has been looked up on SteamGridDB (found or not). */
    fun searched(pc: Pc, app: App): Boolean = readMatch(pc, app) != null

    /** The SteamGridDB image used for [kind] (its id), [NONE] when left empty on purpose, null if never set. */
    fun chosen(pc: Pc, app: App, kind: ArtKind): Int? = readMatch(pc, app)?.chosen?.get(kind.file)

    suspend fun search(term: String): List<SgdbGame> = withContext(Dispatchers.IO) { network.withPermit { client().search(term) } }

    /** The images [kind] can be chosen from for [app]'s matched game. */
    suspend fun choices(pc: Pc, app: App, kind: ArtKind, filters: ArtFilters = ArtFilters()): List<SgdbImage> = withContext(Dispatchers.IO) {
        if (!eligible(app)) return@withContext emptyList()
        val id = readMatch(pc, app)?.id ?: return@withContext emptyList()
        network.withPermit { client().images(id, kind, filters) }
    }

    /**
     * Downloads [app]'s images, the best-rated of each kind. The game is looked up by name the first time;
     * images already there are kept unless [refresh], which fetches all but the hand-picked ones again.
     * Returns whether a game was found.
     */
    suspend fun fetch(pc: Pc, app: App, refresh: Boolean = false): Boolean {
        if (!eligible(app)) return false
        return busy(pc, app) { fetchNow(pc, app, refresh) }
    }

    private suspend fun fetchNow(pc: Pc, app: App, refresh: Boolean): Boolean = withContext(Dispatchers.IO) {
        lock(pc, app).withLock {
            val client = client()
            var match = readMatch(pc, app)
            if (match == null || (refresh && !match.manual)) {
                val found = network.withPermit { client.search(app.name) }.firstOrNull()
                match = if (match != null && found?.id == match.id) match else Match(found?.id, found?.name, year = found?.year)
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
        if (!eligible(app)) return
        withContext(Dispatchers.IO) {
            lock(pc, app).withLock { writeMatch(pc, app, Match(game.id, game.name, manual = true, year = game.year)) }
        }
        fetch(pc, app, refresh = true)
    }

    /** Uses [image] for [kind] (null: none, so the PC's artwork or the game's name), picked by hand. */
    suspend fun apply(pc: Pc, app: App, kind: ArtKind, image: SgdbImage?) {
        if (!eligible(app)) return
        val key = memoryKey(pc, app, kind)
        withContext(Dispatchers.Main) { busyKinds[key] = image?.id ?: NONE }
        try {
            busy(pc, app) { applyNow(pc, app, kind, image) }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { busyKinds.remove(key) }
        }
    }

    private suspend fun applyNow(pc: Pc, app: App, kind: ArtKind, image: SgdbImage?) {
        try {
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
        } finally {
            // Blocking IO may commit after the picker scope is cancelled.
            withContext(NonCancellable + Dispatchers.Main) { version.intValue++ }
        }
    }

    /** The apps of [apps] that have never been looked up. */
    fun missing(pc: Pc, apps: List<App>): List<App> = apps.filter { eligible(it) && readMatch(pc, it) == null }

    private fun store(pc: Pc, app: App, kind: ArtKind, image: SgdbImage?, client: SteamGridDbClient) {
        val file = file(pc, app, kind)
        if (image == null) file.delete() else client.download(image.url, file)
        memory.remove(memoryKey(pc, app, kind))
        animatedMemory.remove(memoryKey(pc, app, kind))
    }

    private suspend fun changed() = withContext(Dispatchers.Main) { version.intValue++ }

    private fun client(): SteamGridDbClient {
        val key = apiKey.ifEmpty { throw SteamGridDbException("Add your SteamGridDB API key first") }
        return SteamGridDbClient(key, http)
    }

    private fun decode(file: File, kind: ArtKind) = runCatching {
        // The game background is blurred and small anyway; covers keep about their size, logos only their display box
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (kind == ArtKind.LOGO) return@runCatching decodeLogo(file, bounds.outWidth, bounds.outHeight)
        val limit = if (kind == ArtKind.HERO) 1280 else 1000
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= limit) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()

    /**
     * A logo is drawn at most [LOGO_BOX_WIDTH] x [LOGO_BOX_HEIGHT] stage pixels (the home screen's title box; the launch card's is smaller), so it
     * is decoded to that box at this display's scale, aspect kept. A tall 1796 x 2161 logo is not kept as a 15 MB bitmap.
     */
    private fun decodeLogo(file: File, width: Int, height: Int): ImageBitmap? {
        val metrics = appContext.resources.displayMetrics
        val k = minOf(maxOf(metrics.widthPixels, metrics.heightPixels) / 1920f, minOf(metrics.widthPixels, metrics.heightPixels) / 1080f)
        val target = logoSize(width, height, LOGO_BOX_WIDTH * k * INTERFACE_SCALE_MAX, LOGO_BOX_HEIGHT * k * INTERFACE_SCALE_MAX)
        val options = BitmapFactory.Options().apply { inSampleSize = target?.let { sampleSize(width, height, it.first, it.second) } ?: 1 }
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: return null
        if (target == null || (bitmap.width == target.first && bitmap.height == target.second)) return bitmap.asImageBitmap()
        return Bitmap.createScaledBitmap(bitmap, target.first, target.second, true).also { if (it !== bitmap) bitmap.recycle() }.asImageBitmap()
    }

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
 * [app]'s local or bundled artwork, or null; reloads when the store's images change. The state is
 * keyed by the app, so a caller that switches apps never sees the previous app's image (produceState
 * would keep it until the new one is loaded).
 */
@Composable
fun rememberArtwork(store: ArtworkStore, pc: Pc, app: App, kind: ArtKind): State<ImageBitmap?> {
    val result = rememberArtworkResult(store, pc, app, kind)
    return remember(result) { derivedStateOf { result.value.image } }
}

/** An unresolved lookup must not be treated as a confirmed missing image. */
data class ArtworkResult(val image: ImageBitmap?, val resolved: Boolean, val bundledAsset: String? = null)


@Composable
fun rememberArtworkResult(store: ArtworkStore, pc: Pc, app: App, kind: ArtKind): State<ArtworkResult> {
    val version = store.version.intValue
    val state = remember(store, pc.uuid, app.id, app.name, BundledArtwork.kind(app), kind) {
        mutableStateOf(store.cachedResult(pc, app, kind) ?: ArtworkResult(null, false))
    }
    LaunchedEffect(state, version) { state.value = store.loadResult(pc, app, kind) }
    return state
}

/** The chosen [kind] image as an animated WebP file ([ArtworkStore.animatedFile]), null while unknown or when it is a still image. */
@Composable
fun rememberAnimatedArtwork(store: ArtworkStore, pc: Pc, app: App, kind: ArtKind): State<File?> {
    val version = store.version.intValue
    val state = remember(store, pc.uuid, app.id, app.name, kind) { mutableStateOf(store.cachedAnimatedFile(pc, app, kind)) }
    LaunchedEffect(state, version) { state.value = store.animatedFile(pc, app, kind) }
    return state
}

/** Thumbnails of the choices on the artwork screen, loaded from SteamGridDB and kept in memory. */
object Thumbnails {
    private val memory = object : LruCache<String, ImageBitmap>(48 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4 / 1024
    }
    private val network = Semaphore(4)

    fun cached(url: String): ImageBitmap? = memory.get(url)

    /** Thumbnails found to be SteamGridDB's takedown placeholder (black, one line of text): the API does not flag them, the picture does. */
    private val removed = mutableStateMapOf<String, Boolean>()

    fun isRemoved(url: String): Boolean = removed[url] == true

    /**
     * Whether [argb] (a small downscaled thumbnail) is a placeholder rather than artwork: nearly all of it opaque pure black,
     * which real artwork, even a dark one, hardly ever is. Transparent images (logos) never qualify.
     */
    internal fun looksRemoved(argb: IntArray): Boolean {
        if (argb.isEmpty()) return false
        val black = argb.count { c -> (c ushr 24) >= 200 && ((c shr 16) and 255) < 6 && ((c shr 8) and 255) < 6 && (c and 255) < 6 }
        return black >= argb.size * 0.9
    }

    private fun checkRemoved(url: String, image: ImageBitmap) {
        val small = android.graphics.Bitmap.createScaledBitmap(image.asAndroidBitmap(), 48, 48, true)
        val pixels = IntArray(48 * 48)
        small.getPixels(pixels, 0, 48, 0, 0, 48, 48)
        if (looksRemoved(pixels)) removed[url] = true
    }

    /** Where downloaded previews are kept: files, so a 100 MB animation never sits in the heap. Set once by [ArtworkStore]. */
    @Volatile private var dir: File? = null

    internal fun init(cache: File) {
        dir = File(cache, "sgdb-preview").apply { mkdirs() }
    }

    private const val MAX_PREVIEW_BYTES = 2048L * 1024 * 1024
    private const val DISK_BUDGET_BYTES = 2048L * 1024 * 1024

    private fun fileFor(url: String): File? = dir?.let { File(it, MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { b -> "%02x".format(b) }) }

    /** [url] as a file of at most [MAX_PREVIEW_BYTES] (kept on disk, the oldest files dropped past [DISK_BUDGET_BYTES]); null when it cannot be had. */
    private suspend fun download(http: OkHttpClient, url: String): File? {
        val file = fileFor(url) ?: return null
        // One download per URL: the still and the animation of a tile ask for the same file
        return locks.getOrPut(url) { kotlinx.coroutines.sync.Mutex() }.withLock { fetch(http, url, file) }
    }

    private suspend fun fetch(http: OkHttpClient, url: String, file: File): File? {
        if (file.length() > 0L) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        return network.withPermit {
            runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body
                    if (body.contentLength() > MAX_PREVIEW_BYTES) return@use null
                    val tmp = File(file.path + ".part")
                    val total = body.contentLength()
                    var written = 0L
                    body.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                written += n
                                if (total > 0) progress[url] = (written.toFloat() / total).coerceIn(0f, 1f)
                                if (written > MAX_PREVIEW_BYTES) { tmp.delete(); return@use null }
                                out.write(buffer, 0, n)
                            }
                        }
                    }
                    if (!tmp.renameTo(file)) { tmp.delete(); return@use null }
                    trimDisk(file.parentFile)
                    file
                }
            }.getOrNull().also { progress.remove(url) }
        }
    }

    private fun trimDisk(folder: File?) {
        val files = folder?.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= DISK_BUDGET_BYTES) break
            total -= f.length()
            f.delete()
        }
    }

    suspend fun load(http: OkHttpClient, url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        memory.get(url)?.let { return@withContext it }
        val file = download(http, url) ?: return@withContext null
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 520) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }.getOrNull()?.also {
            memory.put(url, it)
            runCatching { checkRemoved(url, it) }
        }
    }

    /** URLs whose file is known to be (true) or not to be (false) an animated WebP the app can play. */
    private val animatedKnown = mutableStateMapOf<String, Boolean>()

    /** Download progress 0..1 of the previews being fetched (absent: not started or its size is unknown). */
    val progress = mutableStateMapOf<String, Float>()

    private val locks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    /** The playable animation of [image] when it was already fetched (null: not known yet or none). */
    fun cachedAnimation(image: SgdbImage): File? = listOf(image.previewUrl, image.url).firstOrNull { animatedKnown[it] == true }?.let { fileFor(it)?.takeIf { f -> f.length() > 0L } }

    /** Whether [image]'s animation was tried and found nothing to play (the tile keeps its first frame). */
    fun hasNoAnimation(image: SgdbImage): Boolean = animatedKnown[image.previewUrl] == false &&
        (image.mime != "image/webp" || image.url == image.previewUrl || animatedKnown[image.url] == false)

    /**
     * The animated WebP file of [image] ([SgdbImage.animated]) to play in its tile: its preview (the full file when the
     * thumbnail is a WebM), else the full file when that is a WebP; null when neither is playable.
     */
    suspend fun loadAnimation(http: OkHttpClient, image: SgdbImage): File? = withContext(Dispatchers.IO) {
        if (!image.animated) return@withContext null
        cachedAnimation(image)?.let { return@withContext it }
        probe(http, image.previewUrl)
            ?: if (image.mime == "image/webp" && image.url != image.previewUrl) probe(http, image.url) else null
    }

    private suspend fun probe(http: OkHttpClient, url: String): File? {
        val file = download(http, url) ?: return null
        val header = runCatching { file.inputStream().use { AnimatedWebp.read(it, AnimatedWebp.HEADER_BYTES) } }.getOrNull() ?: return null
        val playable = AnimatedWebp.isAnimated(header) && AnimatedWebp.pixels(header) in 1..AnimatedWebp.MAX_PIXELS
        animatedKnown[url] = playable
        return file.takeIf { playable }
    }
}

@Composable
fun rememberThumbnail(store: ArtworkStore, url: String): State<ImageBitmap?> {
    val state = remember(url) { mutableStateOf(Thumbnails.cached(url)) }
    LaunchedEffect(state) { if (state.value == null) state.value = Thumbnails.load(store.http, url) }
    return state
}

/** The largest area a logo is drawn in, stage pixels (the home screen's title box). */
private const val LOGO_BOX_WIDTH = 900f
private const val LOGO_BOX_HEIGHT = 180f

/** The largest interface scale (130 %): the logo keeps its sharpness there. */
private const val INTERFACE_SCALE_MAX = 1.3f

/** The size an image of [width] x [height] is kept at to fit in [boxWidth] x [boxHeight], aspect kept; null when it already fits. */
internal fun logoSize(width: Int, height: Int, boxWidth: Float, boxHeight: Float): Pair<Int, Int>? {
    if (width <= 0 || height <= 0) return null
    val scale = minOf(boxWidth / width, boxHeight / height)
    if (scale >= 1f) return null
    return maxOf(1, (width * scale).roundToInt()) to maxOf(1, (height * scale).roundToInt())
}

/** The largest power-of-two decode subsampling that still leaves an image of [width] x [height] at least [targetWidth] x [targetHeight]. */
internal fun sampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
    var sample = 1
    while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) sample *= 2
    return sample
}
