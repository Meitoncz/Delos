package com.limelight.shell.data

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.limelight.LimeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The avatar illustrations bundled under `assets/avatars` (made by `tools/avatars/generate.py`), decoded
 * at their full 384 px off the main thread and kept in memory: all twelve fit, so a picker or a badge
 * never decodes the same one twice. Compose scales them to every badge size.
 */
internal object AvatarImages {
    private const val DIRECTORY = "avatars"

    /** Twelve 384 x 384 bitmaps are about 7 MiB. */
    private const val MEMORY_KIB = 8 * 1024

    private val memory = object : LruCache<String, ImageBitmap>(MEMORY_KIB) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4 / 1024
    }

    fun cached(asset: String): ImageBitmap? = memory.get(asset)

    /** The decoded [asset] (a file name under assets/avatars); null if it is missing or invalid. */
    suspend fun load(context: Context, asset: String): ImageBitmap? = withContext(Dispatchers.IO) {
        memory.get(asset)?.let { return@withContext it }
        try {
            val bitmap = context.assets.open("$DIRECTORY/$asset").use { BitmapFactory.decodeStream(it) }
            bitmap?.asImageBitmap()?.also { memory.put(asset, it) }
        } catch (e: Exception) {
            LimeLog.warning("New UI: no avatar image $asset: $e")
            null
        }
    }
}

/**
 * The illustration of an avatar's [asset], loaded in the background: null until it is there (it is there
 * at once when it was shown before) and null for good if the asset is missing or [asset] is null.
 */
@Composable
fun rememberAvatarImage(asset: String?): State<ImageBitmap?> {
    val context = LocalContext.current.applicationContext
    val state = remember(asset) { mutableStateOf(asset?.let(AvatarImages::cached)) }
    LaunchedEffect(asset) {
        if (asset != null && state.value == null) state.value = AvatarImages.load(context, asset)
    }
    return state
}
