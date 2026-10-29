package com.limelight.shell.data

import androidx.compose.runtime.Immutable

@Immutable
data class GameGenre(val id: Int, val name: String)

@Immutable
data class MetadataCandidate(
    val id: Long,
    val name: String,
    val year: Int?,
    val steamAppId: Long?,
    val genres: List<GameGenre>,
    val summary: String? = null,
)

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
    val updatedAt: Long = 0,
    val manual: Boolean = false,
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
    val active: Boolean get() = genreId != null || hdr != HdrFilter.ANY || length != LengthFilter.ANY

    fun matches(metadata: GameMetadata?): Boolean {
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
