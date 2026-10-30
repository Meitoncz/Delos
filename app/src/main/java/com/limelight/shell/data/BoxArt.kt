package com.limelight.shell.data

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.limelight.LimeLog
import com.limelight.binding.PlatformBinding
import com.limelight.nvstream.http.NvHTTP
import com.limelight.utils.CacheHelper
import com.limelight.utils.ServerHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Cover images from the PC ("box art"), with the classic UI's disk cache (cache/boxart/<pc>/<app>.png),
 * so both UIs share it. SteamGridDB images come in step 6.8.
 */
class BoxArtCache(context: Context, private val uniqueId: () -> String?) {
    private val app = context.applicationContext
    private val memory = object : LruCache<String, ImageBitmap>(MEMORY_KIB) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4 / 1024
    }
    private val network = Semaphore(3)

    fun cached(pc: Pc, app: App): ImageBitmap? = memory.get(key(pc, app))

    suspend fun load(pc: Pc, app: App): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = key(pc, app)
        memory.get(key)?.let { return@withContext it }
        val file = CacheHelper.openPath(false, this@BoxArtCache.app.cacheDir, "boxart", pc.uuid, "${app.id}.png")
        val image = decode(file) ?: download(pc, app, file)
        image?.also { memory.put(key, it) }
    }

    private fun decode(file: File): ImageBitmap? {
        if (!file.exists() || file.length() == 0L || file.length() > MAX_FILE_BYTES) return null
        return runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()
    }

    private suspend fun download(pc: Pc, app: App, file: File): ImageBitmap? = network.withPermit {
        val id = uniqueId() ?: return@withPermit null
        try {
            val http = NvHTTP(
                ServerHelper.getCurrentAddressFromComputer(pc.details), pc.details.httpsPort, id, pc.details.serverCert,
                PlatformBinding.getCryptoProvider(this.app),
            )
            http.getBoxArt(app.nvApp).use { input ->
                CacheHelper.openCacheFileForOutput(this.app.cacheDir, "boxart", pc.uuid, "${app.id}.png").use { out ->
                    CacheHelper.writeInputStreamToOutputStream(input, out, MAX_FILE_BYTES)
                }
            }
            decode(file)
        } catch (e: Exception) {
            LimeLog.info("New UI: no box art for ${app.name}: $e")
            file.delete()
            null
        }
    }

    private fun key(pc: Pc, app: App) = "${pc.uuid}/${app.id}"

    private companion object {
        /** About 60 covers at 600×800. */
        const val MEMORY_KIB = 120 * 1024
        const val MAX_FILE_BYTES = 5L * 1024 * 1024
    }
}

val LocalBoxArt = staticCompositionLocalOf<BoxArtCache> { error("BoxArtCache not provided") }

/**
 * The cover of [app], loaded in the background; null until it is there (or if the PC has none).
 * The state is keyed by the app, so a caller that switches apps never sees the previous app's cover.
 */
@Composable
fun rememberBoxArt(cache: BoxArtCache, pc: Pc, app: App): State<ImageBitmap?> {
    val builtin = BundledArtwork.kind(app) != null
    val state = remember(pc.uuid, app.id, builtin) {
        mutableStateOf(if (builtin) null else cache.cached(pc, app))
    }
    LaunchedEffect(state) {
        if (!builtin && state.value == null) state.value = cache.load(pc, app)
    }
    return state
}
