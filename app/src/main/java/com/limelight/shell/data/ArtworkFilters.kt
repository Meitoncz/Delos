package com.limelight.shell.data

import android.content.res.Resources
import androidx.annotation.StringRes
import com.limelight.R
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Which images the artwork page lists: still ones, animated ones or both. */
enum class ArtType(@StringRes val label: Int) {
    ALL(R.string.filters_art_type_all), STATIC(R.string.filters_art_type_static), ANIMATED(R.string.filters_art_type_animated)
}

/**
 * The order of the artwork page's grid. SteamGridDB has no sort parameter, so it is applied to what was loaded. [label] is
 * the option in the filter sheet, [chip] the removable chip of a non-default order and [first] the note under the toolbar.
 */
enum class ArtSort(@StringRes val label: Int, @StringRes val chip: Int, @StringRes val first: Int) {
    SCORE(R.string.filters_art_sort_score, R.string.filters_art_sort_score_chip, R.string.filters_art_sort_score_first),
    NEWEST(R.string.filters_art_sort_newest, R.string.filters_art_sort_newest_chip, R.string.filters_art_sort_newest_first),
    OLDEST(R.string.filters_art_sort_oldest, R.string.filters_art_sort_oldest_chip, R.string.filters_art_sort_oldest_first),
}

/**
 * The filters of one artwork tab (the filter sheet on the artwork screen). NSFW and humor images are never
 * requested, so they are not filters. [style] and [dimensions] are SteamGridDB's own values (null = any).
 */
data class ArtFilters(
    val type: ArtType = ArtType.ALL,
    val style: String? = null,
    val dimensions: String? = null,
    val sort: ArtSort = ArtSort.SCORE,
) {
    /** How many criteria differ from the defaults (the badge of the Filter pill). */
    val activeCount: Int
        get() = (if (type != ArtType.ALL) 1 else 0) + (if (style != null) 1 else 0) +
            (if (dimensions != null) 1 else 0) + (if (sort != ArtSort.SCORE) 1 else 0)

    val active: Boolean get() = activeCount > 0
}

/** One SteamGridDB request of a filter: [animated] marks its images, [params] are the query parameters. */
data class ArtQuery(val animated: Boolean, val params: Map<String, String>)

/** What can be filtered per kind, and how filters become SteamGridDB requests (everything here is pure). */
object ArtFilterRules {
    /** Logos have no animated variant. */
    fun hasType(kind: ArtKind) = kind != ArtKind.LOGO

    fun styles(kind: ArtKind): List<String> = when (kind) {
        ArtKind.COVER, ArtKind.WIDE -> listOf("alternate", "blurred", "white_logo", "material", "no_logo")
        ArtKind.HERO -> listOf("alternate", "blurred", "material")
        ArtKind.LOGO -> listOf("official", "white", "black", "custom")
    }

    fun dimensions(kind: ArtKind): List<String> = when (kind) {
        ArtKind.COVER -> listOf("600x900", "660x930", "342x482")
        ArtKind.WIDE -> listOf("920x430", "460x215")
        ArtKind.HERO -> listOf("1920x620", "3840x1240", "1600x650")
        ArtKind.LOGO -> emptyList()
    }

    /**
     * "white_logo" → "White logo". SteamGridDB's styles are a closed list of identifiers; one this version does not know
     * shows its identifier in words, in English.
     */
    fun styleLabel(res: Resources, style: String): String = when (style) {
        "alternate" -> res.getString(R.string.filters_art_style_alternate)
        "blurred" -> res.getString(R.string.filters_art_style_blurred)
        "white_logo" -> res.getString(R.string.filters_art_style_white_logo)
        "material" -> res.getString(R.string.filters_art_style_material)
        "no_logo" -> res.getString(R.string.filters_art_style_no_logo)
        "official" -> res.getString(R.string.filters_art_style_official)
        "white" -> res.getString(R.string.filters_art_style_white)
        "black" -> res.getString(R.string.filters_art_style_black)
        "custom" -> res.getString(R.string.filters_art_style_custom)
        else -> style.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** "600x900" → "600 × 900" (a technical readout, the same in every language). */
    fun dimensionsLabel(dimensions: String): String = dimensions.replace("x", " × ")

    /** Drops what [kind] cannot filter by, so a stale value never reaches a request. */
    fun normalized(kind: ArtKind, filters: ArtFilters): ArtFilters = filters.copy(
        type = if (hasType(kind)) filters.type else ArtType.ALL,
        style = filters.style?.takeIf { it in styles(kind) },
        dimensions = filters.dimensions?.takeIf { it in dimensions(kind) },
    )

    /**
     * The requests of [filters] for [kind]. Both types are two requests (SteamGridDB's answers do not say which
     * image is animated); animated ones ask for WebP only, the one animated format the app can show.
     */
    fun queries(kind: ArtKind, filters: ArtFilters): List<ArtQuery> {
        val f = normalized(kind, filters)
        fun query(animated: Boolean) = ArtQuery(animated, buildMap {
            put("types", if (animated) "animated" else "static")
            put("nsfw", "false")
            put("humor", "false")
            put("mimes", if (animated) "image/webp" else if (kind == ArtKind.LOGO) "image/png,image/webp" else "image/png,image/jpeg,image/webp")
            f.style?.let { put("styles", it) }
            // Covers and wide covers are limited to the shapes the app lays out well, even without a choice
            val dimensions = f.dimensions ?: dimensions(kind).takeIf { kind == ArtKind.COVER || kind == ArtKind.WIDE }?.joinToString(",")
            if (dimensions != null) put("dimensions", dimensions)
        })
        return when {
            !hasType(kind) -> listOf(query(false))
            f.type == ArtType.STATIC -> listOf(query(false))
            f.type == ArtType.ANIMATED -> listOf(query(true))
            else -> listOf(query(false), query(true))
        }
    }

    /**
     * One list from several answers, an image once (the earlier answer's copy), the answers interleaved so that neither
     * the still nor the animated images end up behind the other when the sort cannot tell them apart.
     */
    fun merge(lists: List<List<SgdbImage>>): List<SgdbImage> {
        val seen = HashSet<Int>()
        val unique = lists.map { list -> list.filter { seen.add(it.id) } }
        return (0 until (unique.maxOfOrNull { it.size } ?: 0)).flatMap { i -> unique.mapNotNull { it.getOrNull(i) } }
    }

    /** [images] in the order of [sort]; ties keep the order they came in (SteamGridDB lists the best rated first). */
    fun sort(images: List<SgdbImage>, sort: ArtSort): List<SgdbImage> = when (sort) {
        ArtSort.SCORE -> images.sortedByDescending { it.score }
        // Without a date the id stands in: SteamGridDB numbers images as they are uploaded
        ArtSort.NEWEST -> images.sortedWith(compareByDescending<SgdbImage> { it.date ?: 0L }.thenByDescending { it.id })
        ArtSort.OLDEST -> images.sortedWith(compareBy<SgdbImage> { it.date ?: Long.MAX_VALUE }.thenBy { it.id })
    }

    fun cacheKey(kind: ArtKind, filters: ArtFilters): String {
        val f = normalized(kind, filters)
        return "${kind.file}|${f.type}|${f.style}|${f.dimensions}"
    }

    /** The removable chips under the toolbar, in order. */
    fun chips(filters: ArtFilters): List<ActiveFilter> = buildList {
        if (filters.type != ArtType.ALL) add(ActiveFilter.Type(filters.type))
        filters.style?.let { add(ActiveFilter.Style(it)) }
        filters.dimensions?.let { add(ActiveFilter.Dimensions(it)) }
        if (filters.sort != ArtSort.SCORE) add(ActiveFilter.Sort(filters.sort))
    }

    fun clear(filters: ArtFilters, chip: ActiveFilter): ArtFilters = when (chip) {
        is ActiveFilter.Type -> filters.copy(type = ArtType.ALL)
        is ActiveFilter.Style -> filters.copy(style = null)
        is ActiveFilter.Dimensions -> filters.copy(dimensions = null)
        is ActiveFilter.Sort -> filters.copy(sort = ArtSort.SCORE)
    }
}

/** An active filter as a chip; each kind clears one field. [label] is the chip's text. */
sealed interface ActiveFilter {
    fun label(res: Resources): String

    data class Type(val type: ArtType) : ActiveFilter {
        override fun label(res: Resources) = res.getString(type.label)
    }

    data class Style(val style: String) : ActiveFilter {
        override fun label(res: Resources) = ArtFilterRules.styleLabel(res, style)
    }

    data class Dimensions(val dimensions: String) : ActiveFilter {
        override fun label(res: Resources) = ArtFilterRules.dimensionsLabel(dimensions)
    }

    data class Sort(val sort: ArtSort) : ActiveFilter {
        override fun label(res: Resources) = res.getString(sort.chip)
    }
}

/** A fact the info strip tells about the focused image; [ArtworkInfo.facts] lists only what SteamGridDB's answer contained. */
sealed interface ArtworkFact {
    data class Size(val width: Int, val height: Int) : ArtworkFact
    data class File(val format: String) : ArtworkFact

    /** [score] is null when the image has none; [author] is null when unknown. */
    data class Score(val score: Int?, val author: String?) : ArtworkFact
    data class Added(val epochSeconds: Long) : ArtworkFact
}

/** What the info strip tells about the focused image: only what SteamGridDB's answer contained. */
object ArtworkInfo {
    /** The strip's title: the image's style ("White logo"), else the kind's own name ([fallback]). */
    fun title(res: Resources, image: SgdbImage, fallback: String): String =
        image.style?.let { ArtFilterRules.styleLabel(res, it) } ?: fallback

    /** The facts in the order of the strip; a missing fact is left out. */
    fun facts(image: SgdbImage): List<ArtworkFact> = buildList {
        if (image.width > 0 && image.height > 0) add(ArtworkFact.Size(image.width, image.height))
        image.format?.let { add(ArtworkFact.File(it)) }
        val by = image.author?.takeIf { it.isNotBlank() }
        if (image.score != 0 || by != null) add(ArtworkFact.Score(image.score.takeIf { it != 0 }, by))
        image.date?.let { add(ArtworkFact.Added(it)) }
    }

    /** The day an image was added in [locale]'s medium date style ("Jun 23, 2025"), in UTC whatever the time zone of the device. */
    fun date(epochSeconds: Long, locale: Locale): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
            .withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(epochSeconds))
}
