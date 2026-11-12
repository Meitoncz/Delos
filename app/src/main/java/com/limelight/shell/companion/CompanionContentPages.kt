package com.limelight.shell.companion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.limelight.R
import com.limelight.shell.data.Avatar
import com.limelight.shell.ui.AvatarBadge
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.Symbols

/*
 * The companion pages of the screens that show a summary of the main display (docs/DUAL_SCREEN.md): the two filter sheets, the profile, the profile
 * being created and the first-run welcome. Everything here is immutable data the owning screen published; the touch button of the filter pages runs
 * the owner's own reset path. The artwork page is in CompanionArtworkPage.kt.
 */

/** How many matches a filter sheet shows: the chips of a filter row are cut to this many, the rest is counted ("+2"). */
internal const val FILTER_CHIPS = 3

/** The sentence about the number of matches: "12 of 36 images match." / "All 36 images." / "12 images match." (the total of an artwork list is not always known). */
internal sealed interface FilterCount {
    data class Of(val shown: Int, val total: Int) : FilterCount
    data class All(val total: Int) : FilterCount
    data class Some(val shown: Int) : FilterCount
}

/** Which sentence of [FilterCount] tells [shown] of [total] while the filters are [active]; [total] null means it is not known (then only [shown] is said). */
internal fun filterCount(shown: Int, total: Int?, active: Boolean): FilterCount = when {
    !active -> FilterCount.All(total ?: shown)
    total != null -> FilterCount.Of(shown, total)
    else -> FilterCount.Some(shown)
}

/** The "Reset filters" button: primary and live while a filter is set, dimmed and disabled when none is. [onReset] is the sheet's own reset path. */
internal fun filterResetAction(label: String, active: Boolean, onReset: () -> Unit): PageAction =
    PageAction(label, onReset, if (active) ButtonStyle.PRIMARY else ButtonStyle.NORMAL, enabled = active)

/**
 * The summary of a filter sheet that is open on the main display: [chips] are the labels of the filters that are set (the library's or the artwork
 * tab's own, never both), [count] says how many items match. [onReset] runs what the sheet's X runs, for THIS list only, and leaves the sheet open.
 */
@Immutable
internal data class FilterPage(
    val library: Boolean,
    val chips: List<String>,
    val count: FilterCount?,
    val unit: FilterUnit,
    val onReset: () -> Unit,
) : CompanionPage {
    override val key: String get() = if (library) "filter/library" else "filter/artwork"

    /** Wins over the screen under the sheet (the library, the artwork page): the sheet is what the user is changing. */
    override val priority: Int get() = PagePriority.MODAL

    @Composable
    override fun Content() {
        val colors = LocalDelosColors.current
        PageCard { fit ->
            PageHeading(
                Symbols.FILTER,
                stringResource(if (library) R.string.companion_filter_library_title else R.string.companion_filter_art_title),
                stringResource(R.string.companion_filter_sub),
            )
            filterCountText(count, unit)?.let { BodyText(it, color = colors.accent2) }
            val moreFormat = stringResource(R.string.companion_filter_more)
            val labels = CompanionFit.chips(chips, FILTER_CHIPS, fit.budget.innerWidth, fit.metrics, more = { moreFormat.format(it) })
            ChipRow(labels.ifEmpty { listOf(stringResource(R.string.companion_filter_none)) })
            BodyText(stringResource(R.string.companion_filter_change_main), Modifier.optional(60), color = colors.text2)
            Filler()
            ActionRow(listOf(filterResetAction(stringResource(R.string.filters_art_reset), chips.isNotEmpty(), onReset)))
        }
    }
}

/** What the sheet counts: the games of the library or the images of an artwork tab. */
internal enum class FilterUnit { GAMES, IMAGES }

@Composable
private fun filterCountText(count: FilterCount?, unit: FilterUnit): String? = when (count) {
    null -> null
    is FilterCount.Of -> pluralStringResource(if (unit == FilterUnit.GAMES) R.plurals.companion_filter_games_of else R.plurals.companion_filter_images_of, count.total, count.shown, count.total)
    is FilterCount.All -> pluralStringResource(if (unit == FilterUnit.GAMES) R.plurals.companion_filter_games_all else R.plurals.companion_filter_images_all, count.total, count.total)
    is FilterCount.Some -> pluralStringResource(R.plurals.companion_filter_images_some, count.shown, count.shown)
}

/** The number of settings that differ from the catalog's defaults in [all] (a profile's stored preferences); [defaults] maps a setting's key to its default. */
internal fun customSettingCount(all: Map<String, *>, defaults: Map<String, Any?>): Int =
    defaults.count { (key, default) -> all.containsKey(key) && all[key].toString() != default.toString() }

/** Which sentence says what a profile has changed: [DEFAULTS] when nothing, else the counts that are not zero. [NONE] while the counts are not known yet. */
internal enum class ProfileLine { NONE, DEFAULTS, SETTINGS, OVERRIDES, BOTH }

internal fun profileLine(settings: Int?, overrides: Int?): ProfileLine = when {
    settings == null || overrides == null -> ProfileLine.NONE
    settings > 0 && overrides > 0 -> ProfileLine.BOTH
    settings > 0 -> ProfileLine.SETTINGS
    overrides > 0 -> ProfileLine.OVERRIDES
    else -> ProfileLine.DEFAULTS
}

/**
 * The current profile (docs/DUAL_SCREEN.md): the avatar and name, how many settings and per-game overrides it holds (null while they are being counted,
 * or unknown) and the default PC if one is set. Read-only: edits stay on the main display.
 */
@Immutable
internal data class ProfilePage(val name: String, val look: Avatar, val settings: Int?, val overrides: Int?, val defaultPc: String?) : CompanionPage {
    override val key: String get() = "profile"

    @Composable
    override fun Content() {
        val colors = LocalDelosColors.current
        PageCard { fit ->
            val title = CompanionFit.title(name, stringResource(R.string.companion_screen_profile), fit.budget.innerWidth - HEADING_DISC, fit.metrics)
            PageHeading(Symbols.ACCOUNT_CIRCLE, title, stringResource(R.string.companion_profile_current))
            AvatarBadge(look, AVATAR_SIZE, AVATAR_SIZE / 2, Modifier.optional(40))
            val line = profileLine(settings, overrides)
            val text = when (line) {
                ProfileLine.NONE -> null
                ProfileLine.DEFAULTS -> stringResource(R.string.companion_profile_defaults)
                ProfileLine.SETTINGS -> stringResource(R.string.companion_profile_line_one, pluralStringResource(R.plurals.companion_profile_settings, settings ?: 0, settings ?: 0))
                ProfileLine.OVERRIDES -> stringResource(R.string.companion_profile_line_one, pluralStringResource(R.plurals.companion_profile_overrides, overrides ?: 0, overrides ?: 0))
                ProfileLine.BOTH -> stringResource(
                    R.string.companion_profile_line_both,
                    pluralStringResource(R.plurals.companion_profile_settings, settings ?: 0, settings ?: 0),
                    pluralStringResource(R.plurals.companion_profile_overrides, overrides ?: 0, overrides ?: 0),
                )
            }
            if (text != null && fit.metrics.lines(text, fit.budget.innerWidth) <= fit.budget.bodyLines) BodyText(text, Modifier.optional(60))
            defaultPc?.let {
                val pc = stringResource(R.string.companion_profile_default_pc, it)
                if (fit.metrics.lines(pc, fit.budget.innerWidth) <= 1) BodyText(pc, Modifier.optional(50), color = colors.text2)
            }
            Filler()
            BodyText(stringResource(R.string.companion_profile_edit_main), Modifier.optional(30), color = colors.accent)
        }
    }
}

/** The profile being created or edited, as it is typed on the main display (a draft: nothing is saved). */
@Immutable
internal data class CreateProfilePage(val name: String, val look: Avatar) : CompanionPage {
    override val key: String get() = "create-profile"

    @Composable
    override fun Content() {
        val colors = LocalDelosColors.current
        PageCard { fit ->
            val shown = name.trim().ifEmpty { stringResource(R.string.firstrun_create_name_placeholder) }
            val title = CompanionFit.title(shown, stringResource(R.string.companion_screen_create_profile), fit.budget.innerWidth - HEADING_DISC, fit.metrics)
            PageHeading(Symbols.ACCOUNT_CIRCLE, title, stringResource(R.string.companion_create_draft))
            AvatarBadge(look, AVATAR_SIZE, AVATAR_SIZE / 2, Modifier.optional(40))
            BodyText(stringResource(R.string.companion_create_choose), color = colors.text)
            BodyText(stringResource(R.string.companion_create_no_account), Modifier.optional(50), color = colors.text2)
            Filler()
            BodyText(stringResource(R.string.companion_profile_edit_main), Modifier.optional(30), color = colors.accent)
        }
    }
}

/** The first run's welcome: what the second display is for, and the one step to take on the main display (B only when the step has a way back). */
@Immutable
internal data class BootPage(val canGoBack: Boolean) : CompanionPage {
    override val key: String get() = "boot"

    @Composable
    override fun Content() {
        val colors = LocalDelosColors.current
        val begin = stringResource(R.string.companion_boot_hint_begin)
        val back = stringResource(R.string.shell_hint_back)
        PageCard { fit ->
            PageHeading(Symbols.INFO, stringResource(R.string.companion_screen_boot), stringResource(R.string.companion_boot_sub))
            val text = CompanionFit.summary(
                stringResource(R.string.companion_boot_text), stringResource(R.string.companion_purpose_boot), fit.budget.bodyLines, fit.budget.innerWidth, fit.metrics,
                stringResource(R.string.companion_see_main),
            )
            BodyText(text, color = colors.text)
            Filler()
            HintList(bootHints(canGoBack, begin, back), 0, Modifier.optional(70))
        }
    }
}

/** The hints of the Boot screen: A begins, B goes back only when the screen was opened from another one. */
internal fun bootHints(canGoBack: Boolean, begin: String, back: String): List<CompanionHint> =
    listOfNotNull(CompanionHint("A", null, begin), CompanionHint("B", null, back).takeIf { canGoBack })

/** The diameter of the avatar of the profile pages, stage px. */
private const val AVATAR_SIZE = 100f

/** The disc of a page heading and the gap after it, stage px: what the title has less than the card's inner width. */
private const val HEADING_DISC = 64f + 16f
