package com.limelight.shell.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.core.app.ApplicationProvider
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
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

    private fun app(id: Int, name: String, uuid: String? = null) =
        App(id, name, false, false, NvApp().apply { setAppUUID(uuid) })
    private val game = app(1, "Game")
    private val candidate = MetadataCandidate(10, "Game", 2020, listOf(100L), listOf(GameGenre(12, "RPG")), platformIds = listOf(6))

    @Before fun setUp() {
        root = folder.newFolder("metadata")
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("store-test", Context.MODE_PRIVATE)
    }

    private class FakeProviders : MetadataProviders {
        override var proxyAvailable = true
        val searchCalls = AtomicInteger()
        val gameCalls = AtomicInteger()
        val hdrCalls = AtomicInteger()
        val completionCalls = AtomicInteger()
        val steamCalls = AtomicInteger()
        @Volatile var lastSteamCandidate: MetadataCandidate? = null
        var onSearch: (String) -> List<MetadataCandidate> = { emptyList() }
        var onGame: (Long) -> MetadataCandidate? = { null }
        var onHdr: (MetadataCandidate) -> HdrEvidence = { HdrEvidence(NativeHdr.HDR, "Page") }
        var onCompletion: (MetadataCandidate) -> CompletionTime? = { null }
        var onSteam: (MetadataCandidate) -> List<String> = { emptyList() }

        override fun search(query: String): List<MetadataCandidate> { searchCalls.incrementAndGet(); return onSearch(query) }
        override fun game(id: Long): MetadataCandidate? { gameCalls.incrementAndGet(); return onGame(id) }
        override fun hdr(candidate: MetadataCandidate): HdrEvidence { hdrCalls.incrementAndGet(); return onHdr(candidate) }
        override fun completion(candidate: MetadataCandidate): CompletionTime? { completionCalls.incrementAndGet(); return onCompletion(candidate) }
        override fun steamTags(candidate: MetadataCandidate): List<String> {
            steamCalls.incrementAndGet()
            lastSteamCandidate = candidate
            return onSteam(candidate)
        }
    }

    private fun newStore(providers: MetadataProviders) =
        GameMetadataStore(root, { prefs }, providers, { now }, Dispatchers.IO, Dispatchers.Unconfined)

    private fun fileOf(app: App) = File(File(root, "pc-${pc.uuid}"), "${app.id}.json")

    // The write observer runs synchronously after a state write, including N/N immediately before null.
    private fun recordProgress(store: GameMetadataStore, observed: MutableList<ArtworkProgress?>) =
        Snapshot.registerGlobalWriteObserver { state ->
            if (state === store.progress) observed.add(store.progress.value)
        }

    private fun builtIns() = listOf(
        app(10, "Desktop"),
        app(11, "Remote Input"),
        app(12, "Remote Monitor"),
        app(13, "Steam Big Picture"),
        app(14, " steam bigpicture "),
        app(15, "Renamed Input", "9A1C5A25-58FE-40E0-B9AA-7D3F00000006"),
        app(16, "Renamed Monitor", "9a1c5a25-58fe-40e0-b9aa-7d3f00000005"),
    )

    private fun assertResolvedEmpty(store: GameMetadataStore, app: App) {
        val entry = store.entry(pc, app)
        assertTrue(app.name, entry.resolved)
        assertFalse(app.name, entry.loading)
        assertNull(app.name, entry.error)
        assertNull(app.name, entry.data)
        assertTrue(app.name, store.genres(pc, listOf(app)).isEmpty())
    }

    private fun assertNoProviderCalls(providers: FakeProviders) {
        assertEquals(0, providers.searchCalls.get())
        assertEquals(0, providers.gameCalls.get())
        assertEquals(0, providers.hdrCalls.get())
        assertEquals(0, providers.completionCalls.get())
        assertEquals(0, providers.steamCalls.get())
    }

    private suspend fun exerciseBuiltInGuards(store: GameMetadataStore, builtIn: App) {
        assertResolvedEmpty(store, builtIn)
        store.ensure(pc, builtIn)
        assertResolvedEmpty(store, builtIn)
        store.ensure(pc, builtIn, force = true)
        assertResolvedEmpty(store, builtIn)
        store.select(pc, builtIn, candidate)
        assertResolvedEmpty(store, builtIn)
        store.reset(pc, builtIn)
        assertResolvedEmpty(store, builtIn)
    }

    @Test fun builtInOperationsNeverFetchWriteCacheOrRegisterProgress() = runBlocking {
        val providers = FakeProviders()
        val store = newStore(providers)
        val builtIns = builtIns()
        val observed = Collections.synchronizedList(mutableListOf<ArtworkProgress?>())
        val observer = recordProgress(store, observed)
        try {
            store.ensureLibrary(pc, builtIns)
            builtIns.forEach { exerciseBuiltInGuards(store, it) }
            assertNoProviderCalls(providers)
            assertTrue(root.listFiles().orEmpty().isEmpty())
            assertTrue(observed.isEmpty())
            assertNull(store.progress.value)
        } finally {
            observer.dispose()
        }
    }

    @Test fun genreAndThemeFiltersSurviveARestart() {
        val filters = MetadataFilters(genreId = 12, themeId = 19, hdr = HdrFilter.HDR, length = LengthFilter.OVER_60)
        newStore(FakeProviders()).updateFilters(filters)
        assertEquals(filters, newStore(FakeProviders()).filters)
        newStore(FakeProviders()).updateFilters(MetadataFilters(genreId = 12))
        assertEquals(MetadataFilters(genreId = 12), newStore(FakeProviders()).filters)
    }

    @Test fun genreAndThemeListsFollowTheCachedRecordsAndIgnoreOlderSchemas() = runBlocking {
        val current = app(30, "Current")
        val outdated = app(31, "Outdated")
        fun record(schema: Int) =
            """{"schema":$schema,"data":{"candidate":{"id":10,"name":"Game","genres":[{"id":12,"name":"RPG"}],"themes":[{"id":19,"name":"Horror"}]},"updatedAt":$now}}"""
        fileOf(current).apply { parentFile!!.mkdirs(); writeText(record(6)) }
        fileOf(outdated).writeText(record(5))
        val providers = FakeProviders().apply { proxyAvailable = false }
        val store = newStore(providers)
        assertTrue(store.genres(pc, listOf(current)).isEmpty())
        store.ensureLibrary(pc, listOf(current, outdated))
        assertEquals(listOf(GameGenre(12, "RPG")), store.genres(pc, listOf(current, outdated)))
        assertEquals(listOf(GameGenre(19, "Horror")), store.themes(pc, listOf(current, outdated)))
        assertTrue(store.themes(pc, listOf(outdated)).isEmpty())
    }

    @Test fun builtInOperationsLeavePreExistingCacheFilesUntouched() = runBlocking {
        val builtIns = builtIns()
        val cached = builtIns.associateWith { builtIn ->
            """{"schema":6,"data":{"candidate":{"id":10,"name":"Game","genres":[{"id":12,"name":"RPG"}]},"hdr":{"support":"HDR"},"updatedAt":$now}}"""
                .also { content -> fileOf(builtIn).apply { parentFile!!.mkdirs(); writeText(content) } }
        }
        val providers = FakeProviders()
        val store = newStore(providers)
        val observed = Collections.synchronizedList(mutableListOf<ArtworkProgress?>())
        val observer = recordProgress(store, observed)
        try {
            store.ensureLibrary(pc, builtIns)
            builtIns.forEach { exerciseBuiltInGuards(store, it) }
            for ((builtIn, content) in cached) {
                assertTrue(fileOf(builtIn).isFile)
                assertEquals(content, fileOf(builtIn).readText())
            }
            assertNoProviderCalls(providers)
            assertTrue(observed.isEmpty())
            assertNull(store.progress.value)
        } finally {
            observer.dispose()
        }
    }

    @Test fun cachedOrdinaryGamesBecomingBuiltInsCannotExposeMetadataButCanRefreshWhenRestored() = runBlocking {
        val ordinary = listOf(
            app(20, "Game"),
            app(21, "Game"),
            app(22, "Game"),
        )
        val renamed = listOf(
            app(20, " remote monitor "),
            app(21, "Renamed Input", "9a1c5a25-58fe-40e0-b9aa-7d3f00000006"),
            app(22, "Renamed Monitor", "9A1C5A25-58FE-40E0-B9AA-7D3F00000005"),
        )
        val seeding = newStore(FakeProviders().apply { onSearch = { listOf(candidate) } })
        ordinary.forEach {
            seeding.ensure(pc, it)
            assertEquals(candidate, seeding.entry(pc, it).data?.candidate)
            assertEquals(NativeHdr.HDR, seeding.entry(pc, it).data?.hdr?.support)
        }
        val cached = ordinary.associateWith { fileOf(it).readText() }
        val providers = FakeProviders().apply {
            onSearch = { listOf(candidate) }
            onHdr = { HdrEvidence(NativeHdr.SDR, "Refreshed") }
        }
        val restarted = newStore(providers)
        val observed = Collections.synchronizedList(mutableListOf<ArtworkProgress?>())
        val observer = recordProgress(restarted, observed)
        try {
            // Check both already-loaded ordinary state and disk-only state after a restart.
            for (store in listOf(seeding, restarted)) {
                store.ensureLibrary(pc, renamed)
                renamed.forEach { exerciseBuiltInGuards(store, it) }
                assertTrue(store.genres(pc, renamed).isEmpty())
            }
            assertNoProviderCalls(providers)
            assertTrue(observed.isEmpty())
            for ((original, content) in cached) assertEquals(content, fileOf(original).readText())
            for (original in ordinary) {
                restarted.ensure(pc, original, force = true)
                assertEquals(candidate, restarted.entry(pc, original).data?.candidate)
                assertEquals(NativeHdr.SDR, restarted.entry(pc, original).data?.hdr?.support)
                assertEquals(listOf(GameGenre(12, "RPG")), restarted.genres(pc, listOf(original)))
            }
            assertEquals(ordinary.size, providers.searchCalls.get())
            assertEquals(ordinary.size, providers.hdrCalls.get())
            assertEquals(ordinary.size, providers.completionCalls.get())
            assertNull(restarted.progress.value)
        } finally {
            observer.dispose()
        }
    }

    @Test fun anEarlierOrdinaryLookupCannotExposeLoadingOrCompletedMetadataThroughABuiltInEntry() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val providers = FakeProviders().apply {
            onSearch = { started.countDown(); release.await(); listOf(candidate) }
        }
        val store = newStore(providers)
        val renamed = app(game.id, "Renamed Input", "9a1c5a25-58fe-40e0-b9aa-7d3f00000006")
        val job = launch(Dispatchers.Default) { store.ensure(pc, game) }
        try {
            assertTrue("ordinary lookup never started", started.await(10, TimeUnit.SECONDS))
            assertTrue(store.entry(pc, game).loading)
            assertResolvedEmpty(store, renamed)
            release.countDown()
            job.join()
            assertEquals(candidate, store.entry(pc, game).data?.candidate)
            assertResolvedEmpty(store, renamed)
        } finally {
            release.countDown()
            job.cancelAndJoin()
        }
    }

    @Test fun libraryProgressCountsOnlyStaleGamesAndIncludesEveryAttemptThroughCompletion() = runBlocking {
        val stale = listOf(game, app(2, "Another Game"), app(3, "Third Game"))
        val fresh = listOf(app(4, "Fresh Game"), app(5, "Other Fresh Game"))
        val seeding = newStore(FakeProviders().apply {
            onSearch = { listOf(candidate.copy(name = it)) }
        })
        stale.forEach { seeding.ensure(pc, it) }
        now += 8 * day
        fresh.forEach { seeding.ensure(pc, it) }

        val started = LinkedBlockingQueue<String>()
        val releases = stale.associate { it.name to CountDownLatch(1) }
        val providers = FakeProviders().apply {
            onSearch = { query ->
                started.add(query)
                releases.getValue(query).await()
                if (query == stale.last().name) throw IOException("fixture")
                listOf(candidate.copy(name = query))
            }
        }
        val store = newStore(providers)
        val observed = Collections.synchronizedList(mutableListOf<ArtworkProgress?>())
        val observer = recordProgress(store, observed)
        val advanced = CountDownLatch(1)
        val advancementObserver = Snapshot.registerGlobalWriteObserver { state ->
            if (state === store.progress && store.progress.value == ArtworkProgress(1, stale.size)) {
                advanced.countDown()
            }
        }
        val job = launch(Dispatchers.Default) { store.ensureLibrary(pc, stale + fresh) }
        try {
            val first = started.poll(10, TimeUnit.SECONDS)
            assertNotNull("first lookup never started", first)
            assertNotNull("second lookup never started", started.poll(10, TimeUnit.SECONDS))
            assertEquals(ArtworkProgress(0, stale.size), store.progress.value)
            fresh.forEach { assertEquals(it.name, store.entry(pc, it).data?.candidate?.name) }
            releases.getValue(first!!).countDown()
            assertTrue("first attempt never advanced progress", advanced.await(10, TimeUnit.SECONDS))
            assertEquals(ArtworkProgress(1, stale.size), store.progress.value)
            releases.values.forEach { it.countDown() }
            job.join()
            assertEquals(
                listOf(ArtworkProgress(0, 3), ArtworkProgress(1, 3), ArtworkProgress(2, 3), ArtworkProgress(3, 3), null),
                observed,
            )
            assertEquals(stale.size, providers.searchCalls.get())
            assertNotNull(store.entry(pc, stale.last()).error)
            assertNull(store.progress.value)
        } finally {
            releases.values.forEach { it.countDown() }
            job.cancelAndJoin()
            advancementObserver.dispose()
            observer.dispose()
        }
    }

    @Test fun aFullyCachedLibraryNeverRegistersProgress() = runBlocking {
        val games = listOf(game, app(2, "Another Game"))
        val seeding = newStore(FakeProviders().apply {
            onSearch = { listOf(candidate.copy(name = it)) }
        })
        games.forEach { seeding.ensure(pc, it) }
        now += day
        val providers = FakeProviders()
        val store = newStore(providers)
        val observed = Collections.synchronizedList(mutableListOf<ArtworkProgress?>())
        val observer = recordProgress(store, observed)
        try {
            store.ensureLibrary(pc, games)
            store.ensureLibrary(pc, games)
            games.forEach { assertEquals(it.name, store.entry(pc, it).data?.candidate?.name) }
            assertEquals(0, providers.searchCalls.get())
            assertTrue(observed.isEmpty())
            assertNull(store.progress.value)
        } finally {
            observer.dispose()
        }
    }

    @Test fun callsInsideFailureBackoffNeverRegisterProgress() = runBlocking {
        val failing = FakeProviders().apply { onSearch = { throw IOException("fixture") } }
        newStore(failing).ensure(pc, game)
        val providers = FakeProviders()
        val store = newStore(providers)
        val observed = Collections.synchronizedList(mutableListOf<ArtworkProgress?>())
        val observer = recordProgress(store, observed)
        try {
            store.ensureLibrary(pc, listOf(game))
            store.ensure(pc, game)
            store.ensureLibrary(pc, listOf(game))
            assertNotNull(store.entry(pc, game).error)
            assertEquals(0, providers.searchCalls.get())
            assertTrue(observed.isEmpty())
            assertNull(store.progress.value)
        } finally {
            observer.dispose()
        }
    }

    @Test fun standaloneAndMenuOperationsEachReportOneAttemptThenClearProgress() = runBlocking {
        lateinit var store: GameMetadataStore
        val providers = FakeProviders().apply {
            onSearch = {
                assertEquals(ArtworkProgress(0, 1), store.progress.value)
                listOf(candidate)
            }
            onHdr = {
                assertEquals(ArtworkProgress(0, 1), store.progress.value)
                HdrEvidence(NativeHdr.HDR, "Page")
            }
        }
        store = newStore(providers)
        val observed = Collections.synchronizedList(mutableListOf<ArtworkProgress?>())
        val observer = recordProgress(store, observed)
        suspend fun assertSingleAttempt(name: String, operation: suspend () -> Unit) {
            observed.clear()
            operation()
            assertEquals(name, listOf(ArtworkProgress(0, 1), ArtworkProgress(1, 1), null), observed)
            assertNull(name, store.progress.value)
        }
        try {
            assertSingleAttempt("ensure") { store.ensure(pc, game) }
            assertSingleAttempt("force refresh") { store.ensure(pc, game, force = true) }
            assertSingleAttempt("reset") { store.reset(pc, game) }
            assertSingleAttempt("select") { store.select(pc, game, candidate) }
            assertSingleAttempt("search") { store.search("Game") }
        } finally {
            observer.dispose()
        }
    }

    @Test fun cancellingALibraryClearsProgressAndEveryLoadingEntry() = runBlocking {
        val games = listOf(game, app(2, "Another Game"), app(3, "Third Game"))
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val providers = FakeProviders().apply {
            onSearch = { started.countDown(); release.await(); emptyList() }
        }
        val store = newStore(providers)
        val job = launch(Dispatchers.Default) { store.ensureLibrary(pc, games) }
        try {
            assertTrue("library lookups never started", started.await(10, TimeUnit.SECONDS))
            assertEquals(ArtworkProgress(0, games.size), store.progress.value)
            assertTrue(games.any { store.entry(pc, it).loading })
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertNull(store.progress.value)
            games.forEach {
                assertFalse(store.entry(pc, it).loading)
                assertNull(store.entry(pc, it).error)
            }
        } finally {
            release.countDown()
            job.cancelAndJoin()
        }
    }

    @Test fun aConcurrentFocusedRefreshCannotReplaceOrClearLibraryProgress() = runBlocking {
        val games = listOf(game, app(2, "Another Game"))
        val focused = app(3, "Focused Game")
        val started = LinkedBlockingQueue<String>()
        val releases = games.associate { it.name to CountDownLatch(1) }
        lateinit var store: GameMetadataStore
        val providers = FakeProviders().apply {
            onSearch = { query ->
                if (query == focused.name) {
                    assertEquals(2, store.progress.value?.total)
                } else {
                    started.add(query)
                    releases.getValue(query).await()
                }
                emptyList()
            }
        }
        store = newStore(providers)
        val observed = Collections.synchronizedList(mutableListOf<ArtworkProgress?>())
        val observer = recordProgress(store, observed)
        val advanced = CountDownLatch(1)
        val advancementObserver = Snapshot.registerGlobalWriteObserver { state ->
            if (state === store.progress && store.progress.value == ArtworkProgress(1, 2)) {
                advanced.countDown()
            }
        }
        val library = launch(Dispatchers.Default) { store.ensureLibrary(pc, games) }
        var focus: kotlinx.coroutines.Job? = null
        try {
            val first = started.poll(10, TimeUnit.SECONDS)
            assertNotNull("first library lookup never started", first)
            assertNotNull("second library lookup never started", started.poll(10, TimeUnit.SECONDS))
            focus = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                store.ensure(pc, focused, force = true)
            }
            releases.getValue(first!!).countDown()
            focus.join()
            assertTrue("library never advanced", advanced.await(10, TimeUnit.SECONDS))
            assertEquals(ArtworkProgress(1, 2), store.progress.value)
            assertFalse(library.isCompleted)
            assertFalse(store.entry(pc, focused).loading)
            assertEquals(listOf(ArtworkProgress(0, 2), ArtworkProgress(1, 2)), observed)
            releases.values.forEach { it.countDown() }
            library.join()
            assertEquals(
                listOf(ArtworkProgress(0, 2), ArtworkProgress(1, 2), ArtworkProgress(2, 2), null),
                observed,
            )
            assertNull(store.progress.value)
        } finally {
            releases.values.forEach { it.countDown() }
            focus?.cancelAndJoin()
            library.cancelAndJoin()
            advancementObserver.dispose()
            observer.dispose()
        }
    }

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

    @Test fun steamTagsAreFetchedForAGameWithASteamIdStoredAndReusedByAFreshStore() = runBlocking {
        val providers = FakeProviders().apply {
            onSearch = { listOf(candidate) }
            onSteam = { listOf("Survival Horror", "Horror", "Sci-fi") }
        }
        val first = newStore(providers)
        first.ensure(pc, game)
        assertEquals(1, providers.steamCalls.get())
        assertEquals(candidate, providers.lastSteamCandidate)
        val data = first.entry(pc, game).data!!
        assertEquals(listOf("Survival Horror", "Horror", "Sci-fi"), data.steamTags)
        assertEquals(0L, data.steamFailedAt)
        assertEquals("a fully answered record is stamped", now, data.updatedAt)
        assertNull(first.entry(pc, game).error)

        val restarted = newStore(providers)
        now += day
        restarted.ensure(pc, game)
        assertEquals(listOf("Survival Horror", "Horror", "Sci-fi"), restarted.entry(pc, game).data?.steamTags)
        assertEquals("the stored tags are served from disk", 1, providers.steamCalls.get())
        assertEquals(1, providers.searchCalls.get())
    }

    @Test fun aGameWithoutASteamIdNeverAsksTheSteamProvider() = runBlocking {
        val providers = FakeProviders().apply {
            onSearch = { listOf(candidate.copy(steamAppIds = emptyList())) }
            onSteam = { throw AssertionError("no Steam id, no Steam lookup") }
        }
        val store = newStore(providers)
        store.ensure(pc, game)
        store.ensure(pc, game, force = true)
        assertEquals(0, providers.steamCalls.get())
        assertEquals(2, providers.hdrCalls.get())
        val data = store.entry(pc, game).data!!
        assertTrue(data.steamTags.isEmpty())
        assertEquals(0L, data.steamFailedAt)
        assertTrue("without a Steam lookup the record is complete", data.updatedAt > 0)
    }

    @Test fun aFailedSteamFetchKeepsTheOldTagsRecordsTheBackoffAndRetriesAfterIt() = runBlocking {
        val providers = FakeProviders().apply {
            onSearch = { listOf(candidate) }
            onSteam = { listOf("Horror") }
        }
        val first = newStore(providers)
        first.ensure(pc, game)
        assertEquals(listOf("Horror"), first.entry(pc, game).data?.steamTags)

        providers.onSteam = { throw IOException("fixture") }
        now += 8 * day
        val failedAt = now
        val store = newStore(providers)
        store.ensure(pc, game)
        assertEquals(2, providers.steamCalls.get())
        val data = store.entry(pc, game).data!!
        assertEquals("the earlier answer for the same game is kept", listOf("Horror"), data.steamTags)
        assertEquals(failedAt, data.steamFailedAt)
        assertEquals("a failed provider leaves the record unstamped", 0L, data.updatedAt)
        assertEquals(NativeHdr.HDR, data.hdr.support)
        assertNull("the card falls back silently, not as an error", store.entry(pc, game).error)

        store.ensure(pc, game)
        newStore(providers).ensure(pc, game)
        assertEquals("focus changes and restarts must not retrigger the lookup", 2, providers.steamCalls.get())

        providers.onSteam = { listOf("Sci-fi") }
        now += TimeUnit.HOURS.toMillis(2)
        val later = newStore(providers)
        later.ensure(pc, game)
        assertEquals(3, providers.steamCalls.get())
        assertEquals(listOf("Sci-fi"), later.entry(pc, game).data?.steamTags)
        assertEquals(0L, later.entry(pc, game).data?.steamFailedAt)
        assertTrue(later.entry(pc, game).data!!.updatedAt > 0)
    }

    @Test fun aFailedFirstSteamFetchLeavesNoTagsAndAnExplicitRefreshIgnoresTheBackoff() = runBlocking {
        val providers = FakeProviders().apply {
            onSearch = { listOf(candidate) }
            onSteam = { throw IOException("fixture") }
        }
        val store = newStore(providers)
        store.ensure(pc, game)
        assertTrue(store.entry(pc, game).data!!.steamTags.isEmpty())
        assertTrue(store.entry(pc, game).data!!.steamFailedAt > 0)
        assertEquals("the other providers still answered", NativeHdr.HDR, store.entry(pc, game).data?.hdr?.support)

        providers.onSteam = { listOf("Open World") }
        store.ensure(pc, game, force = true)
        assertEquals(2, providers.steamCalls.get())
        assertEquals(listOf("Open World"), store.entry(pc, game).data?.steamTags)
        assertEquals(0L, store.entry(pc, game).data?.steamFailedAt)
    }

    @Test fun aStoreListingNoTagsIsASuccessfulEmptyAnswer() = runBlocking {
        val providers = FakeProviders().apply { onSearch = { listOf(candidate) } }
        val store = newStore(providers)
        store.ensure(pc, game)
        store.ensure(pc, game)
        assertEquals(1, providers.steamCalls.get())
        assertTrue(store.entry(pc, game).data!!.steamTags.isEmpty())
        assertEquals(0L, store.entry(pc, game).data?.steamFailedAt)
    }

    @Test fun tagsFromAnotherMatchAreNeverCarriedOver() = runBlocking {
        val providers = FakeProviders().apply {
            onSearch = { listOf(candidate) }
            onSteam = { listOf("Horror") }
        }
        val store = newStore(providers)
        store.ensure(pc, game)
        assertEquals(listOf("Horror"), store.entry(pc, game).data?.steamTags)

        // The next match is a different IGDB entry (id 11): the old entry's tags are not its evidence
        providers.onSearch = { listOf(candidate.copy(id = 11)) }
        providers.onSteam = { throw IOException("fixture") }
        now += 8 * day
        val later = newStore(providers)
        later.ensure(pc, game)
        val data = later.entry(pc, game).data!!
        assertEquals(11L, data.candidate?.id)
        assertTrue("another game's tags must not be shown", data.steamTags.isEmpty())
        assertTrue(data.steamFailedAt > 0)
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
        val release = CountDownLatch(1)
        val providers = FakeProviders().apply {
            onSearch = { started.countDown(); release.await(); emptyList() }
        }
        val store = newStore(providers)
        val job = launch(Dispatchers.Default, start = CoroutineStart.DEFAULT) { store.ensure(pc, game) }
        try {
            assertTrue("lookup never started", started.await(10, TimeUnit.SECONDS))
            assertEquals(ArtworkProgress(0, 1), store.progress.value)
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertFalse(store.entry(pc, game).loading)
            assertNull(store.entry(pc, game).error)
            assertNull(store.progress.value)
        } finally {
            release.countDown()
            job.cancelAndJoin()
        }

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
        // Schema 3 records were classified by the old HDR/matching rules; schema 4 records carry no themes; schema 5 records
        // carry no Steam tags. All are looked up again.
        for (content in listOf("{ not json", "", """{"schema":1,"data":{"candidate":{"id":1,"name":"Old"}}}""",
            """{"schema":3,"data":{"candidate":{"id":1,"name":"Old"},"updatedAt":$now}}""",
            """{"schema":4,"data":{"candidate":{"id":7,"name":"Game"},"updatedAt":$now}}""",
            """{"schema":5,"data":{"candidate":{"id":7,"name":"Game","steamAppIds":[100]},"updatedAt":$now}}""",
            """{"schema":6,"data":{"candidate":{"id":1}}}""")) {
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
            writeText("""{"schema":6,"data":{"candidate":{"id":7,"name":"Game"},"updatedAt":$now}}""")
        }
        val providers = FakeProviders()
        val store = newStore(providers)
        store.ensure(pc, game)
        val data = store.entry(pc, game).data!!
        assertEquals(7L, data.candidate?.id)
        assertEquals(NativeHdr.UNKNOWN, data.hdr.support)
        assertTrue(data.candidate!!.genres.isEmpty())
        assertTrue(MetadataFilters(hdr = HdrFilter.UNKNOWN).matches(game, data))
        assertFalse(MetadataFilters(hdr = HdrFilter.SDR).matches(game, data))
        assertEquals(0, providers.searchCalls.get())
    }

    @Test fun aRecordStampedInTheFutureIsRefreshedInsteadOfTrustedForever() = runBlocking {
        fileOf(game).apply {
            parentFile!!.mkdirs()
            writeText("""{"schema":6,"data":{"candidate":{"id":7,"name":"Game"},"updatedAt":${now + 30 * day}}}""")
        }
        val providers = FakeProviders().apply { onSearch = { listOf(candidate) } }
        newStore(providers).ensure(pc, game)
        assertEquals(1, providers.searchCalls.get())
    }

    @Test fun withoutTheMetadataServiceNoProviderIsContactedAndTheReasonIsShown() = runBlocking {
        val providers = FakeProviders().apply { proxyAvailable = false }
        val store = newStore(providers)
        store.ensure(pc, game)
        assertEquals(0, providers.searchCalls.get())
        assertEquals("Game metadata is not available in this build", store.entry(pc, game).error)
    }
}
