package com.limelight.shell.data

import com.limelight.BuildConfig
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** The blocking lookups [GameMetadataStore] needs. The store calls them interruptibly off the main thread. */
internal interface MetadataProviders {
    val credentialsAvailable: Boolean
    fun search(query: String): List<MetadataCandidate>
    fun game(id: Long): MetadataCandidate?
    fun hdr(candidate: MetadataCandidate): HdrEvidence
    fun completion(candidate: MetadataCandidate): CompletionTime?
}

/** IGDB, PCGamingWiki and HowLongToBeat over HTTP with the build's application-owned credentials. */
internal class HttpMetadataProviders : MetadataProviders {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val igdb = IgdbMetadataClient(http, BuildConfig.DELOS_IGDB_CLIENT_ID, BuildConfig.DELOS_IGDB_CLIENT_SECRET)
    private val pcgw = PcGamingWikiClient(http, BuildConfig.DELOS_PCGW_BOT_USERNAME, BuildConfig.DELOS_PCGW_BOT_PASSWORD)
    private val hltb = HowLongToBeatClient(http)

    override val credentialsAvailable: Boolean
        get() = BuildConfig.DELOS_IGDB_CLIENT_ID.isNotBlank() && BuildConfig.DELOS_IGDB_CLIENT_SECRET.isNotBlank() &&
            BuildConfig.DELOS_PCGW_BOT_USERNAME.isNotBlank() && BuildConfig.DELOS_PCGW_BOT_PASSWORD.isNotBlank()

    override fun search(query: String) = igdb.search(query)
    override fun game(id: Long) = igdb.game(id)
    override fun hdr(candidate: MetadataCandidate) = pcgw.lookup(candidate.steamAppIds, candidate.name)
    override fun completion(candidate: MetadataCandidate) = hltb.lookup(candidate.name, candidate.steamAppIds)
}
