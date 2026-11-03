package com.limelight.shell.data

import androidx.compose.runtime.Immutable

@Immutable
data class GameGenre(val id: Int, val name: String)

/** IGDB's Windows/PC platform ID. */
private const val IGDB_PC_PLATFORM = 6

@Immutable
data class MetadataCandidate(
    val id: Long,
    val name: String,
    val year: Int?,
    /** Every distinct Steam UID IGDB lists for this game; several mean base game plus bundle/edition entries. */
    val steamAppIds: List<Long> = emptyList(),
    val genres: List<GameGenre> = emptyList(),
    val summary: String? = null,
    /** IGDB platform IDs; empty means IGDB did not list any. */
    val platformIds: List<Int> = emptyList(),
    /** IGDB's count of user and critic ratings; the only popularity signal used to break an otherwise exact tie. */
    val ratingCount: Int = 0,
    /** IGDB themes (Fantasy, Horror, ...), same shape as [genres]; empty when IGDB lists none. */
    val themes: List<GameGenre> = emptyList(),
) {
    val isPc: Boolean get() = IGDB_PC_PLATFORM in platformIds
}

enum class NativeHdr { HDR, SDR, UNKNOWN }

@Immutable
data class HdrEvidence(val support: NativeHdr, val page: String? = null, val notes: String? = null)

/** HLTB's headline comp_all, not Main Story or a detailed-table arithmetic average. */
@Immutable
data class CompletionTime(val id: Long, val name: String, val seconds: Long, val url: String, val headline: String)

@Immutable
data class GameMetadata(
    val candidate: MetadataCandidate? = null,
    val hdr: HdrEvidence = HdrEvidence(NativeHdr.UNKNOWN),
    val completion: CompletionTime? = null,
    /** Last time every provider answered (or, for an unmatched game, the last answered IGDB search); 0 after a failure. */
    val updatedAt: Long = 0,
    val manual: Boolean = false,
    /** When the provider last failed, 0 if its last request succeeded. Drives the per-provider retry backoff. */
    val igdbFailedAt: Long = 0,
    val pcgwFailedAt: Long = 0,
    val hltbFailedAt: Long = 0,
)

@Immutable
data class GameMetadataState(
    val data: GameMetadata? = null,
    val loading: Boolean = false,
    val resolved: Boolean = false,
    val error: String? = null,
)

enum class HdrFilter { ANY, HDR, SDR, UNKNOWN }
enum class LengthFilter { ANY, UNDER_10, FROM_10_TO_30, FROM_30_TO_60, OVER_60, UNKNOWN }

@Immutable
data class MetadataFilters(
    val genreId: Int? = null,
    val hdr: HdrFilter = HdrFilter.ANY,
    val length: LengthFilter = LengthFilter.ANY,
) {
    val active: Boolean get() = activeCount > 0

    /** How many of the three criteria narrow the library. */
    val activeCount: Int get() = listOf(genreId != null, hdr != HdrFilter.ANY, length != LengthFilter.ANY).count { it }

    fun matches(app: App, metadata: GameMetadata?): Boolean {
        if (BundledArtwork.kind(app) != null) return !active
        if (genreId != null && metadata?.candidate?.genres?.none { it.id == genreId } != false) return false
        val support = metadata?.hdr?.support ?: NativeHdr.UNKNOWN
        if (hdr != HdrFilter.ANY && support.name != hdr.name) return false
        val seconds = metadata?.completion?.seconds?.takeIf { it > 0 }
        return when (length) {
            LengthFilter.ANY -> true
            LengthFilter.UNKNOWN -> seconds == null
            LengthFilter.UNDER_10 -> seconds != null && seconds < 10 * 3600L
            LengthFilter.FROM_10_TO_30 -> seconds != null && seconds >= 10 * 3600L && seconds < 30 * 3600L
            LengthFilter.FROM_30_TO_60 -> seconds != null && seconds >= 30 * 3600L && seconds < 60 * 3600L
            LengthFilter.OVER_60 -> seconds != null && seconds >= 60 * 3600L
        }
    }
}
