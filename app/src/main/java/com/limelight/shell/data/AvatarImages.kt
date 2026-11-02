package com.limelight.shell.data

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.google.gson.JsonParser
import com.limelight.LimeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The avatar illustrations bundled under `assets/avatars` (from `tools/avatars/import.py`, or the fallback `tools/avatars/generate.py`), decoded
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

/**
 * The badge colors of an imported (generated) avatar: [c0] in the radial gradient's center, [c1] at its
 * edge. Avatars without an entry keep the gradient of their hue.
 */
@Immutable
data class AvatarGradient(val c0: Color, val c1: Color)

/**
 * `assets/avatars/avatars.json` (written by `tools/avatars/import.py`): `{"fox": {"c0": "#rrggbb", "c1": "#rrggbb"}}`,
 * keyed by the avatar's file name without `.webp`. Parsed once, at first use; a missing or broken file
 * means no avatar has colors of its own.
 */
internal object AvatarGradients {
    private const val FILE = "avatars/avatars.json"

    @Volatile
    private var loaded: Map<String, AvatarGradient>? = null

    /** The gradients keyed by slug; throws [IllegalArgumentException] on anything that is not valid. */
    fun parse(json: String): Map<String, AvatarGradient> {
        val root = try { JsonParser.parseString(json).asJsonObject } catch (e: RuntimeException) {
            throw IllegalArgumentException("avatars.json is not a JSON object: $e")
        }
        return root.entrySet().associate { (slug, value) ->
            val entry = try { value.asJsonObject } catch (e: RuntimeException) {
                throw IllegalArgumentException("$slug is not an object")
            }
            slug to AvatarGradient(color(slug, entry, "c0"), color(slug, entry, "c1"))
        }
    }

    private fun color(slug: String, entry: com.google.gson.JsonObject, key: String): Color {
        val text = entry.get(key)?.takeIf { it.isJsonPrimitive }?.asString
        require(text != null && HEX.matches(text)) { "$slug.$key is not #rrggbb: $text" }
        return Color(0xFF000000L or text.substring(1).toLong(16))
    }

    private val HEX = Regex("#[0-9a-f]{6}")

    private fun all(context: Context): Map<String, AvatarGradient> {
        loaded?.let { return it }
        synchronized(this) {
            loaded?.let { return it }
            val result = try {
                parse(context.assets.open(FILE).use { it.readBytes().toString(Charsets.UTF_8) })
            } catch (e: Exception) {
                LimeLog.warning("New UI: no avatar colors: $e")
                emptyMap()
            }
            loaded = result
            return result
        }
    }

    /** The colors of the avatar whose image is [asset] (a file name under assets/avatars), or null. */
    fun of(context: Context, asset: String?): AvatarGradient? = asset?.let { all(context)[it.removeSuffix(".webp")] }
}

/**
 * The imported colors of [asset]'s avatar, null for an avatar that keeps its hue gradient. The few
 * hundred bytes of json are read on first use and cached for good, so a badge never starts with the
 * wrong color and changes it afterwards.
 */
@Composable
fun rememberAvatarGradient(asset: String?): AvatarGradient? {
    val context = LocalContext.current.applicationContext
    return remember(asset) { AvatarGradients.of(context, asset) }
}
