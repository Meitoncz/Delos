package com.limelight.shell.screens

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import com.limelight.shell.ui.compactScale
import com.limelight.shell.ui.impactLegendWidth
import kotlin.math.roundToInt
import com.limelight.shell.ui.SETTING_LIST_ROOM
import com.limelight.shell.ui.settingListViewport
import com.limelight.shell.ui.FocusGlow
import com.limelight.shell.ui.FocusRing
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.limelight.shell.LocalLibrary
import com.limelight.shell.LocalShell
import com.limelight.shell.Screen
import com.limelight.shell.data.Pc
import com.limelight.shell.data.ProfileStore
import com.limelight.shell.settings.Appearance
import com.limelight.shell.settings.SettingKind
import com.limelight.shell.settings.SettingsCapabilities
import com.limelight.shell.settings.SettingsHelp
import com.limelight.shell.settings.impact
import com.limelight.shell.settings.choiceLabel
import com.limelight.shell.settings.nextSettingChoice
import com.limelight.shell.settings.pyroWaveRecommendedBitrateKbps
import com.limelight.shell.settings.SettingsTab
import com.limelight.shell.settings.Startup
import com.limelight.shell.settings.StreamSettingsStore
import com.limelight.shell.ui.Cue
import com.limelight.shell.ui.AutoScrollingHelp
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.FocusTransferTarget
import com.limelight.shell.ui.animateFocusScale
import com.limelight.shell.ui.rememberFocusTransfer
import com.limelight.shell.ui.rememberPressPulse
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.text.style.TextOverflow
import com.limelight.shell.ui.StageMetrics
import com.limelight.shell.ui.StageShape
import com.limelight.shell.ui.cpx
import com.limelight.shell.ui.glass
import com.limelight.shell.ui.targetPx
import com.limelight.shell.ui.InitialFocus
import com.limelight.shell.ui.HeroBitmaps
import com.limelight.shell.ui.PlaceholderArt
import com.limelight.shell.ui.LocalBackdropHaze
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalReducedMotion
import com.limelight.shell.ui.LocalSelectionPill
import com.limelight.shell.ui.Motion
import com.limelight.shell.ui.selectionPillTarget
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import com.limelight.shell.ui.backdropBlur
import com.limelight.shell.ui.LocalFeedback
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.rememberMarginBringIntoView
import com.limelight.shell.ui.rememberFocusedListNavigation
import com.limelight.shell.ui.focusedListBoundary
import com.limelight.shell.ui.focusedListRow
import com.limelight.shell.ui.PillButton
import com.limelight.shell.ui.ImpactLegend
import com.limelight.shell.ui.ImpactLegendSentence
import com.limelight.shell.ui.RowValue
import com.limelight.shell.ui.SettingRow
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.TextFieldPill
import com.limelight.shell.ui.Unbounded
import com.limelight.shell.ui.fadeEdges
import com.limelight.shell.ui.focusEffects
import com.limelight.shell.ui.px
import com.limelight.shell.ui.textResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.limelight.R
import com.limelight.shell.ui.tpx
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

/** Cycles an enum by [dir] steps. */
inline fun <reified E : Enum<E>> E.step(dir: Int): E {
    val all = enumValues<E>()
    return all[Math.floorMod(ordinal + dir, all.size)]
}

/** The prototype's icon tabs, scrolling rows and description panel, backed by real preferences. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    appearance: Appearance,
    update: ((Appearance) -> Appearance) -> Unit,
    startup: Startup,
    updateStartup: ((Startup) -> Startup) -> Unit,
    pcs: List<Pc>,
    profiles: ProfileStore,
    search: Boolean = false,
) {
    val shell = LocalShell.current
    val res = textResources()
    // The catalog is read in the language of these resources
    profiles.catalog.refresh(res)
    val feedback = LocalFeedback.current
    val colors = LocalDelosColors.current
    val activity = LocalActivity.current
    val stream = remember(profiles) { StreamSettingsStore(profiles.catalog, profiles::settings) }
    val profile by profiles.profiles.collectAsStateWithLifecycle()
    DisposableEffect(stream, profile.currentId) {
        stream.reload()
        onDispose { stream.close() }
    }
    val values by stream.values.collectAsStateWithLifecycle()
    val pc = settingsPc(LocalLibrary.current, pcs)
    val guidance = rememberLinkGuidance(pc, values)
    // The PC's own line when the link is known (tested or not), else PyroWave's guide alone
    val bitrateHint = guidance?.line(res) ?: pyroWaveRecommendedBitrateKbps(values)?.let { res.getString(R.string.link_recommended_plain, it / 1000) }
    var capabilities by remember(activity) { mutableStateOf<SettingsCapabilities?>(null) }
    LaunchedEffect(activity) {
        if (activity != null) capabilities = withContext(Dispatchers.Default) { SettingsCapabilities.read(activity) }
    }
    LaunchedEffect(capabilities, profile.currentId, values["checkbox_unlock_fps"]) { capabilities?.let { stream.normalize(res, it) } }
    val act = settingsActions(profiles, stream)
    val classic = profiles.catalog.visible.filter { spec ->
        spec.key !in (capabilities?.hidden ?: emptySet()) &&
            (activity?.packageManager?.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN) != false ||
                spec.category !in setOf("category_onscreen_controls", "category_special_key_layout"))
    }.map { spec ->
        val current = values[spec.key] ?: spec.default
        val row = when (spec.kind) {
            SettingKind.TOGGLE -> RowValue.Toggle(current as? Boolean ?: false) { stream.write(spec, it) }
            SettingKind.CHOICE -> {
                val choices = capabilities?.choices(res, spec, values) ?: spec.choices
                RowValue.Choice(spec.choiceLabel(res, current, choices), { dir ->
                    nextSettingChoice(current, choices, dir)?.let { stream.write(spec, it.value) }
                })
            }
            SettingKind.SLIDER -> RowValue.Slider((current as? Number)?.toInt() ?: spec.min, spec.min, spec.max, spec.step, spec.unit,
                divisor = spec.divisor,
                minText = if (spec.key == "seekbar_gyro_smoothing") res.getString(R.string.settings_screen_gyro_off, 0) else null, onChange = { stream.write(spec, it) })
            SettingKind.TEXT -> RowValue.Text(current?.toString().orEmpty()) { act(spec) }
            SettingKind.ACTION -> RowValue.Action { act(spec) }
        }
        val description = if (spec.key == "checkbox_enable_hdr" && capabilities?.brokenHdr == true)
            res.getString(R.string.settings_screen_hdr_firmware) else spec.description
        SettingsItem(spec.key, spec.tab, spec.section, spec.icon, spec.label,
            description, row, stream.enabled(spec, capabilities),
            detail = SettingsHelp.details(res, spec.copy(description = description), values, capabilities?.choices(res, spec, values) ?: spec.choices),
            searchTerms = settingSearchTerms(res, spec), impact = impact(spec.key, values))
    }
    val withLink = withLinkRows(classic, guidance, pc, values, profiles.catalog.all.firstOrNull { it.key == "seekbar_bitrate_kbps" }) { spec, kbps -> stream.write(spec, kbps) }
    val all = nativeSettings(appearance, update, startup, updateStartup, pcs, profiles.settings()) + withLink
    if (search) {
        SettingsSearch(all, appearance.uiScale / 100f)
        return
    }
    // Hold focus while a tab's eager list is replaced, then assign its intended row silently.
    val focusTransfer = rememberFocusTransfer()
    val stage = LocalStage.current
    val k = stage.dpPerPx * LocalDensity.current.density
    // Keep the section heading (including its padding and the top fade) above its first row.
    // A smaller margin stops short of the list's start when moving up with the controller.
    val tab = shell.settingsTab
    // Group sections so a category split across tabs still has one heading per tab.
    val rows = all.filter { it.tab == tab }.groupBy { it.section }.values.flatten()
    var focusedKey by remember(tab) { mutableStateOf<String?>(null) }
    val focused = rows.firstOrNull { it.key == focusedKey }
    val focusedForSearch by rememberUpdatedState(focusedKey)
    DisposableEffect(shell) {
        val handler: () -> Unit = {
            shell.settingsFocus = focusedForSearch
            feedback.play(Cue.OPEN)
            shell.navigate(Screen.SettingsSearch)
        }
        shell.onSettingsSearch = handler
        onDispose { if (shell.onSettingsSearch === handler) shell.onSettingsSearch = null }
    }
    val navigation = key(tab) { rememberFocusedListNavigation(rows.map { it.key }) }
    val scroll = remember(tab) { ScrollState(0) }
    val compact = stage.shape == StageShape.COMPACT
    val bringIntoView = rememberMarginBringIntoView(scroll, before = 72f * k, after = (if (compact) SettingsLayout.COMPACT_LIST_AFTER else 110f) * k, stagePx = k)
    val window = LocalWindowInfo.current
    val target = shell.settingsFocus?.takeIf { id -> rows.any { it.key == id } } ?: rows.firstOrNull()?.key
    LaunchedEffect(tab, target, navigation) {
        if (target != null) {
            snapshotFlow { window.isWindowFocused }.first { it }
            withFrameNanos { }
            withFrameNanos { }
            // Every target is laid out, even while the visual scroll is still catching up.
            feedback.silenceNextFocus()
            navigation.requester(target).requestFocus()
        }
    }
    DisposableEffect(shell, tab) {
        val handler: (Int) -> Unit = { dir ->
            focusTransfer.request()
            shell.settingsFocus = null
            shell.settingsTab = tab.step(dir)
            feedback.play(Cue.MOVE)
        }
        shell.onShoulder = handler
        onDispose { if (shell.onShoulder === handler) shell.onShoulder = null }
    }
    val margin = SettingsLayout.margin(stage)
    val selectTab: (SettingsTab) -> Unit = { next ->
        if (next != tab) {
            focusTransfer.request()
            shell.settingsFocus = null
            shell.settingsTab = next
        } else target?.let {
            feedback.silenceNextFocus()
            navigation.requester(it).requestFocus()
        }
    }
    // The eight tabs; on a compact stage only the selected one carries its label, so the whole row stays on screen
    val tabs: @Composable () -> Unit = {
        ShoulderLabel("L1")
        SettingsTab.entries.forEach { next ->
            TabPill(next.icon, next.label(res), tab == next, Modifier.padding(horizontal = (if (compact) 3 else 5).px), collapse = compact) { selectTab(next) }
        }
        ShoulderLabel("R1")
    }
    val list: @Composable (Modifier) -> Unit = { listModifier ->
        CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView, com.limelight.shell.ui.LocalEntranceStagger provides com.limelight.shell.ui.rememberEntranceStagger(tab)) {
            key(tab) {
                // One ring glides between the rows; the host carries the list's weight and its viewport (room at the sides for the plate's glow,
                // edges that fade while rows continue behind them), so the ring fades with the rows
                com.limelight.shell.ui.SettingRowList(listModifier.settingListViewport(stage, scroll, if (compact) SettingsLayout.COMPACT_LIST_FADE_TOP else 14f, if (compact) SettingsLayout.COMPACT_LIST_FADE else 90f)) {
                    Column(Modifier.fillMaxSize().focusedListBoundary(navigation).verticalScroll(scroll)
                        .padding(start = (18f + SETTING_LIST_ROOM).px, end = (18f + SETTING_LIST_ROOM).px, top = 14.px, bottom = 140.px),
                        verticalArrangement = Arrangement.spacedBy(10.px)) {
                        var previous = ""
                        rows.forEach { row ->
                            if (row.section != previous) {
                                key("section-${row.section}") { SectionHeading(row.section) }
                                previous = row.section
                            }
                            key(row.key) {
                                SettingsItemRow(row, Modifier.focusedListRow(navigation, row.key),
                                    hint = when (row.key) {
                                        "seekbar_bitrate_kbps" -> bitrateHint
                                        LINK_TEST_ROW, LINK_USE_ROW -> row.description
                                        else -> null
                                    }) { focusedKey = row.key }
                            }
                        }
                    }
                }
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        FocusTransferTarget(focusTransfer)
        if (compact) {
            // Compact: no title (the status bar and the selected tab say where this is). The tabs are centred on the stage and the search circle is
            // flush with the content's right edge; when the tabs would run into it the whole group is flush right instead. The list takes the
            // full width and the description is a dock under it, so the focused row and its help are always on screen together
            val searchSize = SettingsLayout.searchSize(stage)
            TabRow(SettingsLayout.COMPACT_TABS_Y, SettingsLayout.TABS_HEIGHT.px, left = margin, right = stage.stageWidth - margin - searchSize - SettingsLayout.SEARCH_GAP) { tabs() }
            CircleIconButton(Symbols.SEARCH, stringResource(R.string.settings_screen_search),
                Modifier.offset((stage.stageWidth - margin - searchSize).px, (SettingsLayout.COMPACT_TABS_Y + (SettingsLayout.TABS_HEIGHT - searchSize) / 2f).px).size(searchSize.px)) { shell.onSettingsSearch?.invoke() }
            Column(Modifier.fillMaxSize().padding(start = margin.px, end = margin.px, top = SettingsLayout.COMPACT_LIST_TOP.px, bottom = SettingsLayout.bottom(appearance).px),
                verticalArrangement = Arrangement.spacedBy(HelpDockLayout.LIST_GAP.px)) {
                list(Modifier.weight(1f).fillMaxWidth())
                val dockHeight = HelpDockLayout.height(stage.stageHeight, SettingsLayout.COMPACT_LIST_TOP + 14f, SettingsLayout.bottom(appearance), SettingsLayout.tallRow(stage))
                DescriptionPanel(tab, focused, Modifier.fillMaxWidth().height(dockHeight.px), appearance)
            }
        } else {
            BasicText(stringResource(R.string.settings_screen_title), style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 46.tpx),
                modifier = Modifier.offset(SettingsLayout.TITLE_X.px, SettingsLayout.TITLE_Y.px))
            // Kept the same distance from the right edge as at 1920 (x 1530), so a wider or narrower stage moves it with the edge
            PillButton(stringResource(R.string.settings_screen_search), Symbols.SEARCH, { shell.onSettingsSearch?.invoke() }, Modifier.offset((stage.stageWidth - 390f).px, 122.px), clickCue = null)
            TabRow(SettingsLayout.TABS_Y, SettingsLayout.TABS_HEIGHT.px, left = SettingsLayout.TITLE_X, right = stage.stageWidth - SettingsLayout.TITLE_X) { tabs() }
            Row(Modifier.fillMaxSize().padding(start = SettingsLayout.TITLE_X.px, end = SettingsLayout.TITLE_X.px, top = SettingsLayout.LIST_TOP.px, bottom = SettingsLayout.bottom(appearance).px),
                horizontalArrangement = Arrangement.spacedBy(56.px)) {
                list(Modifier.weight(1f))
                DescriptionPanel(tab, focused, Modifier.width(520.px), appearance)
            }
        }
    }
}

@Composable
private fun SectionHeading(label: String) {
    BasicText(label.uppercase(Locale.ROOT), style = TextStyle(color = LocalDelosColors.current.text3, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 16.tpx, letterSpacing = .08.em),
        modifier = Modifier.padding(start = 26.px, top = 10.px, bottom = 2.px))
}

@Composable
private fun SettingsItemRow(item: SettingsItem, modifier: Modifier = Modifier, hint: String? = null, focused: () -> Unit = {}) {
    SettingRow(item.icon, item.label, item.value, modifier,
        description = hint, enabled = item.enabled, onFocused = focused, impact = item.impact)
}

/** The L1 / R1 hint beside a row of tabs; its border sits [SHOULDER_OUTSET] inside the room it takes. */
@Composable
internal fun ShoulderLabel(label: String) {
    val colors = LocalDelosColors.current
    BasicText(label, style = TextStyle(color = colors.text3, fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 18.tpx),
        modifier = Modifier.padding(horizontal = SHOULDER_OUTSET.px).border(1.px, colors.line, RoundedCornerShape(10.px)).padding(horizontal = 12.px, vertical = 6.px))
}

/** The room outside the border of a [ShoulderLabel]: the visible ends of a tab row sit this far inside the row (stage px). */
internal const val SHOULDER_OUTSET = 10f

/**
 * A row of tabs between its L1 and R1 badges (a [com.limelight.shell.ui.SelectionPillHost]) at [y], placed by [TabRowLayout] on every
 * shape: exactly centred on the stage, or, when [centred] is false or the centred row would leave [left]..[right] (stage px: the content's
 * edges, less what a title or a button beside the row takes), with the R1 badge's edge exactly at [right]. A centred row that overhangs
 * both edges by a few pixels is tightened to end exactly on them ([TabRowLayout.width]).
 */
@Composable
internal fun TabRow(y: Float, height: Dp, left: Float, right: Float, centred: Boolean = true, content: @Composable () -> Unit) {
    val stage = LocalStage.current
    com.limelight.shell.ui.SelectionPillHost(Modifier.offset(y = y.px).layout { measurable, constraints ->
        val k = stage.dpPerPx * density
        val outset = SHOULDER_OUTSET * k
        val natural = measurable.maxIntrinsicWidth(constraints.maxHeight).toFloat()
        val width = if (centred) TabRowLayout.width(natural, outset, left * k, right * k, TabRowLayout.MAX_TIGHTEN * k) else natural
        val row = measurable.measure(constraints.copy(minWidth = 0, maxWidth = width.roundToInt().coerceAtMost(constraints.maxWidth)))
        val x = if (centred) TabRowLayout.x(constraints.maxWidth.toFloat(), row.width.toFloat(), outset, left * k, right * k)
        else TabRowLayout.flushRight(row.width.toFloat(), outset, right * k)
        layout(constraints.maxWidth, row.height) { row.place(x.roundToInt(), 0) }
    }.height(height), content = content)
}

/**
 * A tab of a row switched with L1 / R1 (the settings categories, the artwork kinds). Tabs are touch only: they are never
 * focusable, so the D-pad and the stick pass over them, and the selected one is shown by the accent pill of the surrounding
 * [com.limelight.shell.ui.SelectionPillHost] with dark ([DelosColors.onAccent]) icon and label, fading to the quiet
 * [DelosColors.text2] when another tab is selected. A tap squeezes the tab for a moment and plays the select cue.
 */
@Composable
internal fun TabPill(icon: String, label: String, selected: Boolean, modifier: Modifier, badge: Int = 0, collapse: Boolean = false, onClick: () -> Unit) {
    val colors = LocalDelosColors.current
    val reduced = LocalReducedMotion.current
    val feedback = LocalFeedback.current
    val pulse = rememberPressPulse()
    val scale by animateFocusScale(focused = false, pressed = pulse.pressed, focusScale = 1f)
    // Inside a SelectionPillHost the selected tab's accent pill is the host's, gliding between the tabs
    val hosted = LocalSelectionPill.current != null
    val fade = if (reduced) snap<Color>() else tween(Motion.FADE, easing = Motion.Ease)
    val contentColor by animateColorAsState(if (selected) colors.onAccent else colors.text2, fade, label = "tabContent")
    val currentClick by rememberUpdatedState(onClick)
    Row(modifier.selectionPillTarget(selected)
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(CircleShape)
        .then(if (selected && !hosted) Modifier.background(colors.accent) else Modifier)
        .pointerInput(Unit) {
            detectTapGestures(onTap = {
                feedback.play(Cue.SELECT)
                pulse.fire()
                currentClick()
            })
        }
        .semantics {
            role = Role.Tab
            this.selected = selected
            onClick { feedback.play(Cue.SELECT); currentClick(); true }
        }
        .height(targetPx(66)).padding(horizontal = (if (collapse && !selected) 16 else 22).cpx),
        horizontalArrangement = Arrangement.spacedBy(12.px), verticalAlignment = Alignment.CenterVertically) {
        Symbol(icon, 30.tpx, contentColor, filled = selected)
        // A collapsed tab shows its icon alone until it is the selected one
        if (!collapse || selected) BasicText(label, style = TextStyle(color = contentColor,
            fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 22.tpx))
        // The count of this tab's custom per-game settings (a quiet accent3 pill; dark on the selected tab's accent pill)
        if (badge > 0) {
            val badgeFill by animateColorAsState(if (selected) colors.onAccent.copy(alpha = .16f) else colors.accent3.copy(alpha = .22f), fade, label = "tabBadgeFill")
            val badgeText by animateColorAsState(if (selected) colors.onAccent else colors.accent3, fade, label = "tabBadgeText")
            BasicText("$badge", style = TextStyle(color = badgeText, fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 16.tpx),
                modifier = Modifier.clip(CircleShape).background(badgeFill).padding(horizontal = 9.px, vertical = 2.px))
        }
    }
}

@Composable
private fun DescriptionPanel(tab: SettingsTab, focused: SettingsItem?, modifier: Modifier, appearance: Appearance) {
    val colors = LocalDelosColors.current
    val res = textResources()
    val compact = LocalStage.current.shape == StageShape.COMPACT
    val body: @Composable ColumnScope.() -> Unit = {
        BasicText(focused?.detail?.takeIf { it.isNotBlank() } ?: tab.description(res),
            style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = (if (compact) 21 else 22).tpx, lineHeight = 1.45.em))
        if (focused?.enabled == false) BasicText(stringResource(R.string.settings_screen_unavailable), style = TextStyle(color = colors.text3, fontFamily = Figtree, fontSize = 18.tpx))
        val rowValue = focused?.value
        if (rowValue is RowValue.Text) BasicText(rowValue.text,
            style = TextStyle(color = colors.accent, fontFamily = Figtree, fontSize = 22.tpx, lineHeight = 1.3.em))
        // The palette's three colors, only for the palette row
        if (focused?.key == "ui_palette") {
            Row(horizontalArrangement = Arrangement.spacedBy(16.px)) {
                listOf(colors.accent to R.string.settings_screen_palette_main, colors.accent2 to R.string.settings_screen_palette_secondary, colors.accent3 to R.string.settings_screen_palette_accent).forEach { (color, name) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.px)) {
                        Box(Modifier.size(width = 120.cpx, height = 72.cpx).clip(RoundedCornerShape(18.px)).background(color).border(1.px, colors.line, RoundedCornerShape(18.px)))
                        BasicText(stringResource(name), style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 18.tpx))
                    }
                }
            }
        }
        // A preview of the game background, only for the rows that change it
        if (focused?.key in GAME_BACKGROUND_ROWS) {
            val hero = LocalShell.current.hero
            val image by produceState<ImageBitmap?>(null, hero?.id, appearance.heroBlur) {
                value = hero?.image?.let { source -> withContext(Dispatchers.Default) { HeroBitmaps.renderLens(hero.id, source, appearance.heroBlur) } }
            }
            Box(Modifier.fillMaxWidth().height(150.cpx).clip(RoundedCornerShape(20.px)).background(colors.bg2), contentAlignment = Alignment.Center) {
                if (appearance.heroBackground && hero != null) {
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = appearance.heroOpacity / 100f }) {
                        if (image != null) Image(image!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else PlaceholderArt(hero.hue, hero.hue2, hero.icon, Modifier.fillMaxSize(), glyphSize = 100f)
                    }
                }
                BasicText(stringResource(R.string.settings_native_hero_background_title), style = TextStyle(color = colors.text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 20.tpx))
            }
        }
    }
    if (compact) {
        HelpDock(modifier, focused?.icon ?: tab.icon, focused?.label ?: tab.label(res), focused?.key to focused?.detail, focused?.impact != null, body = body)
        return
    }
    SettingsPanelFrame(modifier) {
        Box(Modifier.size(120.px).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
            Symbol(focused?.icon ?: tab.icon, 64.tpx, colors.onAccent, filled = true)
        }
        BasicText(focused?.label ?: tab.label(res), style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 30.tpx), modifier = Modifier.padding(top = 8.px))
        AutoScrollingHelp(focused?.key to focused?.detail, Modifier.weight(1f), content = body)
        // What the three chips of the focused row mean
        if (focused?.impact != null) ImpactLegend()
    }
}

/**
 * The compact stage's description: a glass dock under the list ([HelpDockLayout]: about a quarter of the stage's height) instead of the 520 px
 * panel on its right, so the focused row and its help are always on screen together. The header stays put: the icon disc, the name (one
 * line) and, while a row with impact chips is focused and there is room, their legend at the right. Everything else is ONE area under it
 * that scrolls as a whole (auto scrolling when it is long) and fades at an edge only while it continues behind it: [body] (the value's detail and
 * the help; per game the source box first), the legend when the header has no room for it, what its bars mean, then [footer].
 */
@Composable
internal fun HelpDock(
    modifier: Modifier, icon: String, title: String, identity: Any?, legend: Boolean,
    footer: (@Composable ColumnScope.() -> Unit)? = null, body: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    val shape = RoundedCornerShape(28.px)
    val titleStyle = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 22.tpx)
    BoxWithConstraints(modifier.clip(shape)
        .then(LocalBackdropHaze.current?.let { Modifier.backdropBlur(it, 8f, colors.surface) } ?: Modifier.background(colors.surface))
        .border(1.px, colors.line, shape).padding(horizontal = HelpDockLayout.PAD_X.px, vertical = HelpDockLayout.PAD_Y.px)) {
        val legendInHeader = legend && run {
            val measurer = rememberTextMeasurer()
            val k = stage.dpPerPx * LocalDensity.current.density
            val titleWidth = remember(title, titleStyle, k) { measurer.measure(title, titleStyle, maxLines = 1).size.width / k }
            HelpDockLayout.legendInHeader(maxWidth.value / stage.dpPerPx, titleWidth, impactLegendWidth())
        }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(HelpDockLayout.BODY_GAP.px)) {
            Row(Modifier.height(HelpDockLayout.DISC.px), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(HelpDockLayout.DISC.px).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
                    Symbol(icon, 28.tpx, colors.onAccent, filled = true)
                }
                BasicText(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = HelpDockLayout.TITLE_GAP.px), style = titleStyle)
                if (legendInHeader) ImpactLegend(Modifier.padding(start = HelpDockLayout.LEGEND_GAP.px), sentence = false)
            }
            AutoScrollingHelp(identity, Modifier.weight(1f).fillMaxWidth(), spacing = 10f, edgeFade = HelpDockLayout.FADE) {
                body()
                if (legend) {
                    if (!legendInHeader) ImpactLegend(sentence = false)
                    ImpactLegendSentence()
                }
                footer?.invoke(this)
            }
        }
    }
}

/**
 * An action beside a compact stage's tab row (the settings' search, a game's Reset all): its icon alone in a glass circle, where the labelled
 * pill would not fit beside the tabs; [label] is what it says to accessibility. [modifier] places and sizes it.
 */
@Composable
internal fun CircleIconButton(icon: String, label: String, modifier: Modifier, clickCue: Cue? = null, click: () -> Unit) {
    val colors = LocalDelosColors.current
    var focused by remember { mutableStateOf(false) }
    // The pills' ring and glow (a 70 px circle around the centre): it ends inside the 48 px margin beside the circle
    Box(modifier.focusEffects(onClick = click, corner = 999f, focusScale = 1.08f, ring = FocusRing(4f, 4f), glow = FocusGlow(18f, 50f, -12f), clickCue = clickCue, onFocusChange = { focused = it })
        .clip(CircleShape).glass(colors.surface).border(1.px, colors.line, CircleShape).semantics { contentDescription = label },
        contentAlignment = Alignment.Center) {
        Symbol(icon, 30.tpx, colors.text, filled = focused)
    }
}

/** The glass panel on the right of the settings screens (520 stage px wide when the caller says so): 34 px corners, 44 px padding. */
@Composable
internal fun SettingsPanelFrame(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalDelosColors.current
    val shape = RoundedCornerShape(34.px)
    Column(modifier.clip(shape)
        // The same gentle frosted glass as the focused row
        .then(LocalBackdropHaze.current?.let { Modifier.backdropBlur(it, 8f, colors.surface) } ?: Modifier.background(colors.surface))
        .border(1.px, colors.line, shape).padding(44.px),
        verticalArrangement = Arrangement.spacedBy(18.px), content = content)
}

/** Controller letter picker and typed search share one query and jump to the exact settings row. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SettingsSearch(all: List<SettingsItem>, scale: Float) {
    val shell = LocalShell.current
    val res = textResources()
    val colors = LocalDelosColors.current
    val field = rememberTextFieldState()
    val query = field.text.toString()
    val first = remember { FocusRequester() }
    InitialFocus(first)
    val results = searchSettings(res, all, query)
    val compact = LocalStage.current.shape == StageShape.COMPACT
    val keys = stringResource(R.string.settings_screen_search_letters)
    val columns = LetterPicker.columns(keys.length)
    // Compact: the picker is a column of smaller keys (60 px, 6 px apart) so the results keep room beside it
    val keySize = if (compact) 60 else LetterPicker.keySize(columns)
    val keyGap = if (compact) 6 else LetterPicker.GAP
    val pickerWidth = if (compact) columns * keySize + (columns - 1) * keyGap else 820
    val margin = SettingsLayout.margin(LocalStage.current)
    Column(Modifier.fillMaxSize().padding(start = (if (compact) margin else 150f).px, end = (if (compact) margin else 150f).px, top = (if (compact) 112f else 130f).px, bottom = (96f * scale).px)) {
        BasicText(stringResource(R.string.settings_screen_search_title), style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = (if (compact) 34 else 46).tpx))
        if (!compact) BasicText(stringResource(R.string.settings_screen_search_hint), style = TextStyle(color = colors.text3, fontFamily = Figtree, fontSize = 22.tpx), modifier = Modifier.padding(top = 14.px, bottom = 32.px))
        else Spacer(Modifier.height(12.px))
        Row(horizontalArrangement = Arrangement.spacedBy((if (compact) 28 else 70).px), modifier = Modifier.weight(1f)) {
            Column(Modifier.width(pickerWidth.px), verticalArrangement = Arrangement.spacedBy((if (compact) 12 else 18).px)) {
                TextFieldPill(field, Symbols.SEARCH, placeholder = stringResource(R.string.settings_screen_search_placeholder), maxLength = 80, width = (pickerWidth - 30).toFloat())
                Column(Modifier.padding(top = (if (compact) 4 else 16).px), verticalArrangement = Arrangement.spacedBy(keyGap.px)) {
                    // Nine keys to a row; a language with more than six rows of keys (Japanese kana) gets more, slightly smaller ones
                    keys.chunked(columns).forEachIndexed { row, letters ->
                        Row(horizontalArrangement = Arrangement.spacedBy(keyGap.px)) {
                            letters.forEachIndexed { col, letter ->
                                LetterKey(letter.toString(), keySize, if (row == 0 && col == 0) Modifier.focusRequester(first) else Modifier) { field.setTextAndPlaceCursorAtEnd((query + letter.lowercaseChar()).take(80)) }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy((if (compact) 8 else 14).px)) {
                    PillButton(stringResource(R.string.settings_screen_search_space), null, { field.setTextAndPlaceCursorAtEnd((query + " ").take(80)) }, compact = compact)
                    PillButton(stringResource(R.string.settings_screen_search_delete), Symbols.BACKSPACE, { field.setTextAndPlaceCursorAtEnd(query.dropLast(1)) }, compact = compact)
                    PillButton(stringResource(R.string.settings_screen_search_clear), Symbols.CLEAR, { field.clearText() }, compact = compact)
                }
            }
            Column(Modifier.weight(1f)) {
                BasicText(pluralStringResource(R.plurals.settings_screen_search_count, results.size, results.size), style = TextStyle(color = colors.text3, fontFamily = Figtree, fontSize = 22.tpx), modifier = Modifier.padding(bottom = (if (compact) 8 else 16).px))
                if (results.isEmpty()) BasicText(stringResource(R.string.settings_screen_search_none), style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 26.tpx))
                key(query) {
                    // At most 110 entries (77 visible catalog rows + 33 native rows): keep
                    // every neighbour composed while the animated scroll catches up.
                    val navigation = rememberFocusedListNavigation(results.map { it.key }, allowUpExit = false)
                    val scroll = remember { ScrollState(0) }
                    val stage = LocalStage.current
                    val k = stage.dpPerPx * LocalDensity.current.density
                    val bringIntoView = rememberMarginBringIntoView(scroll, before = 72f * k, after = 110f * k, stagePx = k)
                    CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView) {
                        com.limelight.shell.ui.SettingRowList(Modifier.fillMaxSize().settingListViewport(stage, scroll, 14f, 40f)) {
                            Column(
                                Modifier.fillMaxSize().focusedListBoundary(navigation).verticalScroll(scroll)
                                    .padding(start = (18f + SETTING_LIST_ROOM).px, end = (18f + SETTING_LIST_ROOM).px, top = 14.px, bottom = 48.px),
                                verticalArrangement = Arrangement.spacedBy(10.px),
                            ) {
                                results.forEach { item ->
                                    key(item.key) {
                                        SettingRow(item.icon, item.label, RowValue.Action {
                                            shell.settingsTab = item.tab
                                            shell.settingsFocus = item.key
                                            shell.navigate(Screen.Settings)
                                        }, Modifier.focusedListRow(navigation, item.key),
                                            description = dotJoin(res, item.tab.label(res), item.section))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LetterKey(letter: String, size: Int, modifier: Modifier, click: () -> Unit) {
    val colors = LocalDelosColors.current
    var focused by remember { mutableStateOf(false) }
    Box(modifier.size(size.px).focusEffects(click, corner = 18f, focusScale = 1.06f, onFocusChange = { focused = it })
        .clip(RoundedCornerShape(18.px)).background(if (focused) colors.surface2 else colors.surface), contentAlignment = Alignment.Center) {
        BasicText(letter, style = TextStyle(color = if (focused) colors.accent else colors.text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 28.tpx))
    }
}

/** The rows the description panel previews the game background for. */
private val GAME_BACKGROUND_ROWS = setOf("ui_hero_background", "ui_hero_blur", "ui_hero_opacity")

/** Where the settings screens put their title, tab row and list; the per-game screen reuses it 1:1. */
internal object SettingsLayout {
    const val TITLE_X = 150f
    const val TITLE_Y = 124f
    const val TABS_Y = 206f
    const val TABS_HEIGHT = 66f
    const val LIST_TOP = 312f

    /**
     * The compact stage (small panels) drops the title: the tab row sits 24 px under the status bar (y 0..104), the list under it (its own
     * top padding of 14 px is the room to the tabs).
     */
    const val COMPACT_MARGIN = 48f
    const val COMPACT_TABS_Y = 128f
    const val COMPACT_LIST_TOP = 202f

    /**
     * The compact list's bottom fade and the room the focused row keeps to the list's end, shorter than the 90 / 110 px of the tall 16:9
     * list: the plate's glow (48 px under the row) ends above the fade.
     */
    const val COMPACT_LIST_FADE = 40f
    const val COMPACT_LIST_AFTER = 90f

    /** The compact list's top fade while rows are scrolled away behind it: they dissolve over 24 px under the tabs instead of ending at them. */
    const val COMPACT_LIST_FADE_TOP = 24f

    /** Between the R1 badge and the compact search circle: the circle's focus growth (64 × 0.08), its ring (8) and 12 px. */
    const val SEARCH_GAP = 26f

    /** The compact search circle: the tab row's height scaled, a 64 px target at least. */
    fun searchSize(stage: StageMetrics): Float = maxOf(TABS_HEIGHT * stage.compactScale, 64f)

    /**
     * The height of a list's tallest row (one with impact chips) on this stage: its least height, or its two lines (the chips and the name,
     * about 64 px at an interface scale of 1) plus its scaled padding when that is more. The dock under the list leaves room for three of
     * them ([HelpDockLayout.height]).
     */
    fun tallRow(stage: StageMetrics): Float = maxOf(120f * stage.compactScale, 64.2f * stage.textScale + 28f * stage.compactScale)

    /** The side margin of the screen: [TITLE_X] on every shape but the compact one, where it is the 48 px floor plus the display cutout. */
    fun margin(stage: StageMetrics): Float =
        if (stage.shape == StageShape.COMPACT) COMPACT_MARGIN + maxOf(stage.safe.left, stage.safe.right) else TITLE_X

    /** The room kept free at the bottom for the hint bar, which grows with the interface scale. */
    fun bottom(appearance: Appearance): Float = bottom(appearance.uiScale / 100f)

    /** The same from the stage's interface scale ([StageMetrics.textScale]). */
    fun bottom(scale: Float): Float = 96f + (scale - 1f) * 92f
}
