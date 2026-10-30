package com.limelight.shell.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Real file decoding/downloads observed through the same Compose states read by the artwork UI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ArtworkStoreTest {
    private lateinit var context: Context
    private lateinit var store: ArtworkStore
    private lateinit var pc: Pc
    private val app = App(1728588470, "The Walking Dead", false, false, NvApp())

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = ArtworkStore(context)
        // Robolectric's application/preferences are sandboxed; never contact the real SGDB service.
        store.apiKey = "regression-test-only"
        pc = Pc("artwork-test-${UUID.randomUUID()}", "Test PC", "Test PC", true, true, 0, false, ComputerDetails())
    }

    @Test fun validJpegAlreadyOnDiskReplacesTheInitialMissingImageInTheConsumer() {
        val jpeg = jpeg(1920, 620, Color.RED)
        imageFile(app, ArtKind.HERO).writeBytes(jpeg)
        var result = ArtworkResult(null, false)
        var consumerImage: ImageBitmap? = null
        CompositionDriver().use { driver ->
            driver.setContent {
                val loaded = rememberArtworkResult(store, pc, app, ArtKind.HERO).value
                val visible = rememberArtwork(store, pc, app, ArtKind.HERO).value
                SideEffect { result = loaded; consumerImage = visible }
            }
            assertNull(consumerImage)
            assertFalse(result.resolved)
            driver.until { result.resolved && consumerImage != null }
            assertNotNull(result.image)
            assertImage(consumerImage, 1920, 620, Color.RED)
        }
    }

    @Test fun manualSelectionRemovalAndReselectionUpdateAnAlreadyComposedConsumer() {
        JpegServer(jpeg(1920, 620, Color.RED)).use { server ->
            val first = SgdbImage(5018, server.url, server.url, 1920, 620)
            var result = ArtworkResult(null, false)
            var consumerImage: ImageBitmap? = null
            CompositionDriver().use { driver ->
                driver.setContent {
                    val loaded = rememberArtworkResult(store, pc, app, ArtKind.HERO).value
                    val visible = rememberArtwork(store, pc, app, ArtKind.HERO).value
                    SideEffect { result = loaded; consumerImage = visible }
                }
                driver.until { result.resolved }
                assertNull(consumerImage)
                val originalVersion = store.version.intValue

                driver.await(driver.scope.async { store.apply(pc, app, ArtKind.HERO, first) })
                driver.until { consumerImage != null }
                assertEquals(first.id, store.chosen(pc, app, ArtKind.HERO))
                assertTrue(store.version.intValue > originalVersion)
                assertImage(consumerImage, 1920, 620, Color.RED)
                val originalImage = consumerImage

                // Same PC/game/kind and the same downloaded image id: only the pixels change.
                server.jpeg = jpeg(1920, 620, Color.BLUE)
                driver.await(driver.scope.async { store.apply(pc, app, ArtKind.HERO, first) })
                driver.until { consumerImage != null && consumerImage !== originalImage }
                assertImage(consumerImage, 1920, 620, Color.BLUE)

                driver.await(driver.scope.async { store.apply(pc, app, ArtKind.HERO, null) })
                driver.until { consumerImage == null && result.resolved && result.image == null }
                assertEquals(NONE, store.chosen(pc, app, ArtKind.HERO))
                assertFalse(imageFile(app, ArtKind.HERO).exists())

                driver.await(driver.scope.async { store.apply(pc, app, ArtKind.HERO, first) })
                driver.until { consumerImage != null }
                assertEquals(first.id, store.chosen(pc, app, ArtKind.HERO))
                assertImage(consumerImage, 1920, 620, Color.BLUE)
            }
            // A recreated store must decode the actual selected file, not depend on its old cache.
            val reopened = ArtworkStore(context)
            CompositionDriver().use { driver ->
                assertImage(driver.await(driver.scope.async { reopened.load(pc, app, ArtKind.HERO) }), 1920, 620, Color.BLUE)
                assertEquals(first.id, reopened.chosen(pc, app, ArtKind.HERO))
            }
        }
    }

    @Test fun cancelingThePickerDuringDownloadStillPublishesACommittedSelection() {
        JpegServer(jpeg(1920, 620, Color.RED), holdResponse = true).use { server ->
            val selected = SgdbImage(5018, server.url, server.url, 1920, 620)
            var result = ArtworkResult(null, false)
            var consumerImage: ImageBitmap? = null
            CompositionDriver().use { driver ->
                driver.setContent {
                    val loaded = rememberArtworkResult(store, pc, app, ArtKind.HERO).value
                    val visible = rememberArtwork(store, pc, app, ArtKind.HERO).value
                    SideEffect { result = loaded; consumerImage = visible }
                }
                driver.until { result.resolved }
                assertNull(consumerImage)
                val originalVersion = store.version.intValue
                val applying = driver.scope.async { store.apply(pc, app, ArtKind.HERO, selected) }
                driver.until { server.requested.count == 0L }

                // Navigating away cancels rememberCoroutineScope(), but the blocking HTTP call
                // continues, saves the JPEG and commits match.json before withContext(IO) returns.
                applying.cancel()
                server.release()
                driver.until { applying.isCompleted }
                assertTrue(applying.isCancelled)
                assertEquals(selected.id, store.chosen(pc, app, ArtKind.HERO))
                assertTrue(imageFile(app, ArtKind.HERO).length() > 0)
                assertTrue("A committed selection must invalidate existing consumers even if the picker was canceled",
                    store.version.intValue > originalVersion)
                driver.until { consumerImage != null }
                assertImage(consumerImage, 1920, 620, Color.RED)
            }
        }
    }

    @Test fun changingTheGameOrKindNeverPublishesThePreviousGamesImage() {
        val other = app.copy(id = app.id + 1, name = "Other game")
        imageFile(app, ArtKind.HERO).writeBytes(jpeg(1920, 620, Color.RED))
        imageFile(other, ArtKind.HERO).writeBytes(jpeg(1280, 400, Color.BLUE))
        val selected = mutableStateOf(app)
        val kind = mutableStateOf(ArtKind.HERO)
        data class Publication(val appId: Int, val kind: ArtKind, val result: ArtworkResult, val image: ImageBitmap?)
        val publications = mutableListOf<Publication>()
        fun assertCurrentImage(publication: Publication, image: ImageBitmap?) {
            if (publication.kind == ArtKind.LOGO) {
                assertNull("A missing logo must not retain the hero", image)
            } else if (image != null) {
                val original = publication.appId == app.id
                assertImage(image, if (original) 1920 else 1280, if (original) 620 else 400,
                    if (original) Color.RED else Color.BLUE)
            }
        }
        CompositionDriver().use { driver ->
            driver.setContent {
                val currentApp = selected.value
                val currentKind = kind.value
                val loaded = rememberArtworkResult(store, pc, currentApp, currentKind).value
                val visible = rememberArtwork(store, pc, currentApp, currentKind).value
                SideEffect { publications.add(Publication(currentApp.id, currentKind, loaded, visible)) }
            }
            driver.until { publications.lastOrNull()?.image != null }
            assertImage(publications.last().image, 1920, 620, Color.RED)

            selected.value = other
            // Observe the applied key, not an unresolved state from either independent loader.
            // A replacement may already be decoded when this composition is applied.
            driver.until {
                publications.lastOrNull()?.let {
                    it.appId == other.id && it.kind == ArtKind.HERO && it.result.resolved && it.image != null
                } == true
            }
            assertImage(publications.last().image, 1280, 400, Color.BLUE)

            kind.value = ArtKind.LOGO
            driver.until {
                publications.lastOrNull()?.let {
                    it.appId == other.id && it.kind == ArtKind.LOGO && it.result.resolved
                } == true
            }
            assertNull(publications.last().image)

            // Reject stale images in every applied composition, even if a later load replaces them.
            for (publication in publications) {
                assertCurrentImage(publication, publication.result.image)
                assertCurrentImage(publication, publication.image)
            }
        }
    }

    private fun imageFile(app: App, kind: ArtKind): File =
        File(context.filesDir, "artwork/${pc.uuid}/${app.id}/${kind.file}").apply { parentFile!!.mkdirs() }

    private fun jpeg(width: Int, height: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertImage(image: ImageBitmap?, width: Int, height: Int, color: Int) {
        assertNotNull("The UI-facing artwork state must contain the decoded JPEG", image)
        image!!
        assertEquals(width, image.width)
        assertEquals(height, image.height)
        val actual = image.asAndroidBitmap().getPixel(width / 2, height / 2)
        // JPEG is lossy even for a solid-color fixture.
        assertEquals(Color.red(color).toDouble(), Color.red(actual).toDouble(), 3.0)
        assertEquals(Color.green(color).toDouble(), Color.green(actual).toDouble(), 3.0)
        assertEquals(Color.blue(color).toDouble(), Color.blue(actual).toDouble(), 3.0)
    }

    /** No UI-test library: execute the real runtime/recomposer and explicitly supply its frames. */
    private class CompositionDriver : Closeable {
        private val tasks = ConcurrentLinkedQueue<Runnable>()
        private val errors = ConcurrentLinkedQueue<Throwable>()
        private val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        }
        private val clock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + dispatcher + clock + CoroutineExceptionHandler { _, error -> errors.add(error) })
        private val recomposer = Recomposer(scope.coroutineContext)
        private val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = error("These tests must not emit UI nodes")
            override fun insertBottomUp(index: Int, instance: Unit) = error("These tests must not emit UI nodes")
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun onClear() = Unit
        }, recomposer)

        init { scope.launch { recomposer.runRecomposeAndApplyChanges() } }

        fun setContent(content: @Composable () -> Unit) { composition.setContent(content) }

        fun until(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!condition()) {
                errors.poll()?.let { throw AssertionError("Composition failed", it) }
                assertTrue("Timed out waiting for the artwork consumer", System.nanoTime() < deadline)
                shadowOf(Looper.getMainLooper()).idle()
                Snapshot.sendApplyNotifications()
                // Apply queued recompositions while IO completion remains asynchronous.
                tasks.poll()?.run()
                if (clock.hasAwaiters) clock.sendFrame(System.nanoTime())
                Thread.yield()
            }
            errors.poll()?.let { throw AssertionError("Composition failed", it) }
        }

        fun <T> await(deferred: Deferred<T>): T {
            until { deferred.isCompleted }
            // Completion has already been driven; this adds no dispatcher or timing dependency.
            return kotlinx.coroutines.runBlocking { deferred.await() }
        }

        override fun close() {
            composition.dispose()
            recomposer.cancel()
            scope.cancel()
            while (true) (tasks.poll() ?: break).run()
        }
    }

    /** A real loopback HTTP response, so apply() exercises download, rename and cache invalidation. */
    private class JpegServer(@Volatile var jpeg: ByteArray, holdResponse: Boolean = false) : Closeable {
        private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        val url = "http://127.0.0.1:${socket.localPort}/hero.jpg"
        val requested = CountDownLatch(1)
        private val responseAllowed = CountDownLatch(if (holdResponse) 1 else 0)

        init {
            executor.submit {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (error: java.net.SocketException) {
                        if (socket.isClosed) break else throw error
                    }
                    client.use {
                        val reader = it.getInputStream().bufferedReader(Charsets.US_ASCII)
                        while (!reader.readLine().isNullOrEmpty()) { /* Consume request headers. */ }
                        requested.countDown()
                        check(responseAllowed.await(10, TimeUnit.SECONDS)) { "Timed out releasing the test HTTP response" }
                        val bytes = jpeg
                        it.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            write(bytes)
                            flush()
                        }
                    }
                }
            }
        }

        fun release() { responseAllowed.countDown() }

        override fun close() {
            release()
            socket.close()
            executor.shutdownNow()
        }
    }
}
