package com.limelight.shell.data

import com.limelight.BuildConfig
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** The blocking lookups [GameMetadataStore] needs. The store calls them interruptibly off the main thread. */
internal interface MetadataProviders {
    val proxyAvailable: Boolean
    fun search(query: String): List<MetadataCandidate>
    fun game(id: Long): MetadataCandidate?
    fun hdr(candidate: MetadataCandidate): HdrEvidence
    fun completion(candidate: MetadataCandidate): CompletionTime?
}

/** IGDB and PCGamingWiki through the Delos metadata Worker (the build's non-secret URL), HowLongToBeat directly. */
internal class HttpMetadataProviders : MetadataProviders {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val igdb = IgdbMetadataClient(http, BuildConfig.DELOS_METADATA_URL)
    private val pcgw = PcGamingWikiClient(http, BuildConfig.DELOS_METADATA_URL)
    private val hltb = HowLongToBeatClient(http)

    override val proxyAvailable: Boolean
        get() = BuildConfig.DELOS_METADATA_URL.isNotBlank()

    override fun search(query: String) = igdb.search(query)
    override fun game(id: Long) = igdb.game(id)
    override fun hdr(candidate: MetadataCandidate) = pcgw.lookup(candidate.steamAppIds, candidate.name)
    override fun completion(candidate: MetadataCandidate) = hltb.lookup(candidate.name, candidate.steamAppIds)
}
