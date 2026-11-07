package com.limelight.shell.data

/** Which images the artwork page lists: still ones, animated ones or both. */
enum class ArtType(val label: String) { ALL("All"), STATIC("Static"), ANIMATED("Animated") }

/** The order of the artwork page's grid. SteamGridDB has no sort parameter, so it is applied to what was loaded. */
enum class ArtSort(val label: String) { SCORE("Highest score"), NEWEST("Newest"), OLDEST("Oldest") }

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

    /** "white_logo" → "White logo", "600x900" → "600 × 900". */
    fun styleLabel(style: String): String = style.replace('_', ' ').replaceFirstChar { it.uppercase() }

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
        if (filters.type != ArtType.ALL) add(ActiveFilter.Type(filters.type.label))
        filters.style?.let { add(ActiveFilter.Style(styleLabel(it))) }
        filters.dimensions?.let { add(ActiveFilter.Dimensions(dimensionsLabel(it))) }
        if (filters.sort != ArtSort.SCORE) add(ActiveFilter.Sort("Sort: ${filters.sort.label}"))
    }

    fun clear(filters: ArtFilters, chip: ActiveFilter): ArtFilters = when (chip) {
        is ActiveFilter.Type -> filters.copy(type = ArtType.ALL)
        is ActiveFilter.Style -> filters.copy(style = null)
        is ActiveFilter.Dimensions -> filters.copy(dimensions = null)
        is ActiveFilter.Sort -> filters.copy(sort = ArtSort.SCORE)
    }

    /** The result line: "10 of 41 covers", or "41 covers" when the unfiltered total is not known. */
    fun resultLine(shown: Int, total: Int?, noun: String): String {
        val name = if (shown == 1) noun else noun + "s"
        return if (total != null && total != shown) "$shown of $total ${noun}s" else "$shown $name"
    }
}

/** An active filter as a chip (its [label]); each kind clears one field. */
sealed interface ActiveFilter {
    val label: String
    data class Type(override val label: String) : ActiveFilter
    data class Style(override val label: String) : ActiveFilter
    data class Dimensions(override val label: String) : ActiveFilter
    data class Sort(override val label: String) : ActiveFilter
}

/** What the info strip tells about the focused image: only what SteamGridDB's answer contained. */
object ArtworkInfo {
    /** The strip's title: the image's style ("White logo"), else the kind's own name. */
    fun title(image: SgdbImage, fallback: String): String = image.style?.let(ArtFilterRules::styleLabel) ?: fallback

    /** Label / value pairs in the order of the strip; a missing fact is left out. */
    fun fields(image: SgdbImage): List<Pair<String, String>> = buildList {
        if (image.width > 0 && image.height > 0) add("SIZE" to "${image.width} × ${image.height} px")
        image.format?.let { add("FILE" to it) }
        val by = image.author?.takeIf { it.isNotBlank() }
        if (image.score != 0 || by != null) {
            add("SCORE" to listOfNotNull(image.score.takeIf { it != 0 }?.toString(), by?.let { "by $it" }).joinToString(" · "))
        }
        image.date?.let { add("ADDED" to date(it)) }
    }

    /** "23 Jun 2025", in UTC, whatever the language of the device. */
    fun date(epochSeconds: Long): String =
        java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH)
            .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.ofEpochSecond(epochSeconds))
}
