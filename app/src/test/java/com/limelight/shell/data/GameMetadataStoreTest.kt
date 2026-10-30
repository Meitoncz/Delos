package com.limelight.shell.data

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The store with fake providers, an injected clock and no real sleeping: only the policy around the
 * providers (matching, caching, backoff, cancellation) is under test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GameMetadataStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var prefs: SharedPreferences
    private val pc = Pc("store-test-pc", "Test PC", "Test PC", true, true, 0, false, ComputerDetails())
    private var now = 1_700_000_000_000L
    private val day = TimeUnit.DAYS.toMillis(1)

    private fun app(id: Int, name: String) = App(id, name, false, false, NvApp())
    private val game = app(1, "Game")
    private val candidate = MetadataCandidate(10, "Game", 2020, listOf(100L), listOf(GameGenre(12, "RPG")), platformIds = listOf(6))

    @Before fun setUp() {
        root = folder.newFolder("metadata")
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("store-test", Context.MODE_PRIVATE)
    }

    private class FakeProviders : MetadataProviders {
        override var credentialsAvailable = true
        val searchCalls = AtomicInteger()
        val gameCalls = AtomicInteger()
        val hdrCalls = AtomicInteger()
        val completionCalls = AtomicInteger()
        var onSearch: (String) -> List<MetadataCandidate> = { emptyList() }
        var onGame: (Long) -> MetadataCandidate? = { null }
        var onHdr: (MetadataCandidate) -> HdrEvidence = { HdrEvidence(NativeHdr.HDR, "Page") }
        var onCompletion: (MetadataCandidate) -> CompletionTime? = { null }

        override fun search(query: String): List<MetadataCandidate> { searchCalls.incrementAndGet(); return onSearch(query) }
        override fun game(id: Long): MetadataCandidate? { gameCalls.incrementAndGet(); return onGame(id) }
        override fun hdr(candidate: MetadataCandidate): HdrEvidence { hdrCalls.incrementAndGet(); return onHdr(candidate) }
        override fun completion(candidate: MetadataCandidate): CompletionTime? { completionCalls.incrementAndGet(); return onCompletion(candidate) }
    }

    private fun newStore(providers: MetadataProviders) =
        GameMetadataStore(root, { prefs }, providers, { now }, Dispatchers.IO, Dispatchers.Unconfined)

    private fun fileOf(app: App) = File(File(root, "pc-${pc.uuid}"), "${app.id}.json")

    @Test fun anAutomaticMatchIsCachedOnDiskAndReusedByAFreshStoreWithoutAskingProvidersAgain() = runBlocking {
        val providers = FakeProviders().apply { onSearch = { listOf(candidate) } }
        val first = newStore(providers)
        first.ensure(pc, game)
        val entry = first.entry(pc, game)
        assertEquals(candidate, entry.data?.candidate)
        assertEquals(NativeHdr.HDR, entry.data?.hdr?.support)
        assertNull(entry.error)

        val restarted = newStore(providers)
        now += day
        restarted.ensure(pc, game)
        assertEquals(candidate, restarted.entry(pc, game).data?.candidate)
        assertEquals(1, providers.searchCalls.get())
    }

    @Test fun cachedRecordsAreVisibleBeforeAnySlowNetworkLookupHasFinished() = runBlocking {
        val seeded = FakeProviders().apply { onSearch = { listOf(candidate) } }
        val seeding = newStore(seeded)
        val cachedApps = listOf(app(1, "Game"), app(2, "Game"))
        cachedApps.forEach { seeding.ensure(pc, it) }

        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = FakeProviders().apply {
            onSearch = { started.countDown(); release.await(); emptyList() }
        }
        val store = newStore(slow)
        val uncached = app(3, "Uncached")
        val job = launch(Dispatchers.Default) { store.ensureLibrary(pc, cachedApps + uncached) }
        try {
            assertTrue("network lookup never started", started.await(10, TimeUnit.SECONDS))
            for (cached in cachedApps) assertEquals(candidate, store.entry(pc, cached).data?.candidate)
            assertNull(store.entry(pc, uncached).data)
        } finally {
            release.countDown()
        }
        job.join()
        assertEquals(1, slow.searchCalls.get())
        assertTrue(store.entry(pc, uncached).resolved)
    }

    @Test fun anUnmatchedGameIsRetriedLongBeforeAMatchedOneButNotOnEveryFocus() = runBlocking {
        val providers = FakeProviders()
        val store = newStore(providers)
        store.ensure(pc, game)
        store.ensure(pc, game)
        assertEquals(1, providers.searchCalls.get())
        assertNull(store.entry(pc, game).data?.candidate)

        now += day
        store.ensure(pc, game)
        assertEquals(2, providers.searchCalls.get())

        providers.onSearch = { listOf(candidate) }
        now += day
        store.ensure(pc, game)
        assertEquals(3, providers.searchCalls.get())
        now += day
        store.ensure(pc, game)
        assertEquals("a matched record stays fresh for days", 3, providers.searchCalls.get())
    }

    @Test fun aFailingProviderIsNotRetriedOnEveryFocusOrRestartButIsAfterItsBackoff() = runBlocking {
        val providers = FakeProviders().apply {
            onSearch = { listOf(candidate) }
            onHdr = { throw IOException("fixture") }
        }
        val first = newStore(providers)
        first.ensure(pc, game)
        assertEquals(1, providers.hdrCalls.get())
        assertNotNull(first.entry(pc, game).error)
        assertEquals("the other provider's answer is kept", candidate, first.entry(pc, game).data?.candidate)

        first.ensure(pc, game)
        newStore(providers).ensure(pc, game)
        assertEquals("focus changes and restarts must not retrigger the lookup", 1, providers.hdrCalls.get())

        providers.onHdr = { HdrEvidence(NativeHdr.SDR, "Page") }
        now += TimeUnit.HOURS.toMillis(2)
        val later = newStore(providers)
        later.ensure(pc, game)
        assertEquals(2, providers.hdrCalls.get())
        assertEquals(NativeHdr.SDR, later.entry(pc, game).data?.hdr?.support)
        assertNull(later.entry(pc, game).error)
    }

    @Test fun anIgdbFailureIsRememberedToo() = runBlocking {
        val providers = FakeProviders().apply { onSearch = { throw IOException("fixture") } }
        val store = newStore(providers)
        store.ensure(pc, game)
        assertNotNull(store.entry(pc, game).error)
        store.ensure(pc, game)
        assertEquals(1, providers.searchCalls.get())
        now += TimeUnit.HOURS.toMillis(2)
        store.ensure(pc, game)
        assertEquals(2, providers.searchCalls.get())
    }

    @Test fun anExplicitRefreshIgnoresTheBackoff() = runBlocking {
        val providers = FakeProviders().apply {
            onSearch = { listOf(candidate) }
            onHdr = { throw IOException("fixture") }
        }
        val store = newStore(providers)
        store.ensure(pc, game)
        store.ensure(pc, game, force = true)
        assertEquals(2, providers.hdrCalls.get())
    }

    @Test fun aCancelledLookupStopsPromptlyAndIsNotRecordedAsAProviderFailure() = runBlocking {
        val started = CountDownLatch(1)
        val providers = FakeProviders().apply {
            onSearch = { started.countDown(); CountDownLatch(1).await(); emptyList() }
        }
        val store = newStore(providers)
        val job = launch(Dispatchers.Default, start = CoroutineStart.DEFAULT) { store.ensure(pc, game) }
        assertTrue("lookup never started", started.await(10, TimeUnit.SECONDS))
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(store.entry(pc, game).loading)
        assertNull(store.entry(pc, game).error)

        providers.onSearch = { listOf(candidate) }
        store.ensure(pc, game)
        assertEquals("a cancelled attempt must not leave a backoff behind", 2, providers.searchCalls.get())
        assertEquals(candidate, store.entry(pc, game).data?.candidate)
    }

    @Test fun aManualMatchIsRefreshedByItsIgdbIdAndNeverReplacedBySearch() = runBlocking {
        val providers = FakeProviders().apply { onGame = { candidate.copy(name = "Renamed") } }
        val store = newStore(providers)
        store.select(pc, game, candidate)
        assertEquals(candidate, store.entry(pc, game).data?.candidate)

        now += 8 * day
        store.ensure(pc, game)
        assertEquals(0, providers.searchCalls.get())
        assertEquals(1, providers.gameCalls.get())
        assertEquals("Renamed", store.entry(pc, game).data?.candidate?.name)
        assertTrue(store.entry(pc, game).data?.manual == true)
    }

    @Test fun anAmbiguousNameStaysUnmatchedInsteadOfGuessing() = runBlocking {
        val providers = FakeProviders().apply {
            onSearch = { listOf(candidate.copy(id = 1, platformIds = listOf(6)), candidate.copy(id = 2, platformIds = listOf(6))) }
        }
        val store = newStore(providers)
        store.ensure(pc, game)
        assertNull(store.entry(pc, game).data?.candidate)
        assertTrue(store.entry(pc, game).resolved)
        assertEquals(0, providers.hdrCalls.get())
    }

    @Test fun corruptOrForeignCacheFilesAreIgnoredAndReplacedByAFreshLookup() = runBlocking {
        // Schema 3 records were classified by the old HDR/matching rules and must be looked up again.
        for (content in listOf("{ not json", "", """{"schema":1,"data":{"candidate":{"id":1,"name":"Old"}}}""",
            """{"schema":3,"data":{"candidate":{"id":1,"name":"Old"},"updatedAt":$now}}""",
            """{"schema":4,"data":{"candidate":{"id":1}}}""")) {
            fileOf(game).apply { parentFile!!.mkdirs(); writeText(content) }
            val providers = FakeProviders().apply { onSearch = { listOf(candidate) } }
            val store = newStore(providers)
            store.ensure(pc, game)
            assertEquals(content, 1, providers.searchCalls.get())
            assertEquals(content, candidate, store.entry(pc, game).data?.candidate)
        }
    }

    @Test fun aCacheRecordMissingFieldsIsUsableWithoutCrashingAndKeepsUnknownHdrUnknown() = runBlocking {
        fileOf(game).apply {
            parentFile!!.mkdirs()
            writeText("""{"schema":4,"data":{"candidate":{"id":7,"name":"Game"},"updatedAt":$now}}""")
        }
        val providers = FakeProviders()
        val store = newStore(providers)
        store.ensure(pc, game)
        val data = store.entry(pc, game).data!!
        assertEquals(7L, data.candidate?.id)
        assertEquals(NativeHdr.UNKNOWN, data.hdr.support)
        assertTrue(data.candidate!!.genres.isEmpty())
        assertTrue(MetadataFilters(hdr = HdrFilter.UNKNOWN).matches(data))
        assertFalse(MetadataFilters(hdr = HdrFilter.SDR).matches(data))
        assertEquals(0, providers.searchCalls.get())
    }

    @Test fun aRecordStampedInTheFutureIsRefreshedInsteadOfTrustedForever() = runBlocking {
        fileOf(game).apply {
            parentFile!!.mkdirs()
            writeText("""{"schema":4,"data":{"candidate":{"id":7,"name":"Game"},"updatedAt":${now + 30 * day}}}""")
        }
        val providers = FakeProviders().apply { onSearch = { listOf(candidate) } }
        newStore(providers).ensure(pc, game)
        assertEquals(1, providers.searchCalls.get())
    }

    @Test fun withoutCredentialsNoProviderIsContactedAndTheReasonIsShown() = runBlocking {
        val providers = FakeProviders().apply { credentialsAvailable = false }
        val store = newStore(providers)
        store.ensure(pc, game)
        assertEquals(0, providers.searchCalls.get())
        assertNotNull(store.entry(pc, game).error)
    }
}
