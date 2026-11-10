package com.limelight.shell.screens

import android.content.SharedPreferences
import com.limelight.R
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.limelight.shell.ContextMenu
import com.limelight.shell.LocalShell
import com.limelight.shell.MenuItem
import com.limelight.shell.Screen
import com.limelight.shell.ToastAction
import com.limelight.shell.data.ArtKind
import com.limelight.shell.data.LocalArtwork
import com.limelight.shell.data.LocalBoxArt
import com.limelight.shell.data.ProfileStore
import com.limelight.shell.data.rememberArtwork
import com.limelight.shell.data.rememberBoxArt
import com.limelight.shell.link.currentTransport
import com.limelight.shell.settings.*
import com.limelight.shell.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The per-game settings (docs/PER_GAME_SETTINGS.md): a slim hero banner with the game's name, the number of its custom settings
 * and Reset all; the Stream / Display tabs centred under it like the global settings; the shared rows and gliding plate; and the
 * description panel of the global settings. A setting this game overrides is marked on its row ([RowOverride]) and resets with X
 * (or its touch button).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameSettingsScreen(screen: Screen.GameSettings, profiles: ProfileStore, appearance: Appearance) {
    val shell = LocalShell.current
    val res = textResources()
    // The catalog is read in the language of these resources
    profiles.catalog.refresh(res)
    val feedback = LocalFeedback.current
    val activity = LocalActivity.current
    val profile by profiles.profiles.collectAsStateWithLifecycle()
    val game = remember(screen.pc.uuid, screen.app.id, profile.currentId) {
        GameSettingsStore(profiles.settings(), screen.pc.uuid, screen.app.id)
    }
    val stream = remember(game) { StreamSettingsStore(profiles.catalog) { game.preferences } }
    DisposableEffect(stream) { onDispose { stream.close() } }
    val values by stream.values.collectAsStateWithLifecycle()
    // What the profile has, for "Profile: …" on the custom rows (the game's own store overlays its overrides on it)
    val profileStream = remember(game) { StreamSettingsStore(profiles.catalog, profiles::settings) }
    DisposableEffect(profileStream) { onDispose { profileStream.close() } }
    val profileValues by profileStream.values.collectAsStateWithLifecycle()
    // The same recommendation as the settings, for this game's PC; testing the link is done in Settings
    val guidance = rememberLinkGuidance(screen.pc, values)
    val bitrateHint = guidance?.line(res) ?: pyroWaveRecommendedBitrateKbps(values)?.let { res.getString(R.string.link_recommended_plain, it / 1000) }
    var caps by remember(activity) { mutableStateOf<SettingsCapabilities?>(null) }
    LaunchedEffect(activity) {
        if (activity != null) caps = withContext(Dispatchers.Default) { SettingsCapabilities.read(activity) }
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val keys = if (tab == 0) GameSettingsStore.STREAM_KEYS else GameSettingsStore.DISPLAY_KEYS
    val rows = keys.mapNotNull { key -> profiles.catalog.all.firstOrNull { it.key == key } }
        .filter { it.key !in (caps?.hidden ?: emptySet()) }
    var focusedKey by remember(tab) { mutableStateOf<String?>(null) }
    // Focus on the Reset all pill (above the list): the panel then shows the game, not the row last focused
    var onBanner by remember { mutableStateOf(false) }
    val focused = rows.firstOrNull { it.key == focusedKey }.takeIf { !onBanner }
    // The effective values do not change when an override equals the value it replaces, so the source
    // labels follow the stored overrides instead.
    var overrideVersion by remember(game) { mutableIntStateOf(0) }
    DisposableEffect(game) {
        val base = profiles.settings()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> overrideVersion++ }
        base.registerOnSharedPreferenceChangeListener(listener)
        onDispose { base.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val custom = remember(game, overrideVersion) { game.overrides().keys }
    val streamCustom = customCount(custom, GameSettingsStore.STREAM_KEYS)
    val displayCustom = customCount(custom, GameSettingsStore.DISPLAY_KEYS)
    val transfer = rememberFocusTransfer()
    val window = LocalWindowInfo.current
    val scroll = remember(tab) { ScrollState(0) }
    val navigation = key(tab, game) { rememberFocusedListNavigation(rows.map { it.key }) }
    val stage = LocalStage.current
    val k = stage.dpPerPx * LocalDensity.current.density
    val bringIntoView = rememberMarginBringIntoView(scroll, before = 72f * k, after = 110f * k, stagePx = k)
    fun changeTab(next: Int) {
        if (next == tab) return
        transfer.request()
        tab = next
    }
    DisposableEffect(shell, tab) {
        val handler: (Int) -> Unit = { dir ->
            changeTab(Math.floorMod(tab + dir, 2))
            feedback.play(Cue.MOVE)
        }
        shell.onShoulder = handler
        onDispose { if (shell.onShoulder === handler) shell.onShoulder = null }
    }
    LaunchedEffect(tab, navigation, caps) {
        if (caps == null) return@LaunchedEffect
        val first = rows.firstOrNull()?.key ?: return@LaunchedEffect
        snapshotFlow { window.isWindowFocused }.first { it }
        withFrameNanos { }
        withFrameNanos { }
        feedback.silenceNextFocus()
        navigation.requester(first).requestFocus()
    }
    // A reset never asks: it acts, says so, and the toast's Undo (Y or a tap) writes back what it removed
    val resetRow = { key: String ->
        val before = game.backup()
        game.reset(key)
        shell.showToast(res.getString(R.string.settings_game_reset_row_toast), Symbols.RESET, ToastAction(res.getString(R.string.settings_game_undo)) { game.restoreMissing(before) })
    }
    val resetRowNow by rememberUpdatedState(resetRow)
    val resetAll = {
        if (custom.isNotEmpty()) {
            val before = game.backup()
            game.resetAll()
            shell.showToast(res.getString(R.string.settings_game_reset_all_toast), Symbols.RESET, ToastAction(res.getString(R.string.settings_game_undo)) { game.restoreMissing(before) })
            // The pill dims now that nothing is custom: the focus goes back to the list, where it was
            (focusedKey?.takeIf { key -> rows.any { it.key == key } } ?: rows.firstOrNull()?.key)?.let { navigation.requester(it).requestFocus() }
        }
    }
    // X resets the focused custom row (set only then: otherwise X keeps its other meaning, the context menu)
    val focusedCustomKey = focusedKey?.takeIf { it in custom && !onBanner }
    DisposableEffect(shell, focusedCustomKey) {
        val key = focusedCustomKey
        val handler: (() -> Unit)? = key?.let { { feedback.play(Cue.SELECT); resetRowNow(it) } }
        shell.onGameReset = handler
        onDispose { if (shell.onGameReset === handler) shell.onGameReset = null }
    }
    // The input the last event came from: touch shows a round reset button on every custom row, the controller a pill on the focused one
    var touch by remember { mutableStateOf(false) }
    // The hero as the dashboard picks it (hero, else cover, else the PC's artwork), then the placeholder art
    val artwork = LocalArtwork.current
    val heroImage by rememberArtwork(artwork, screen.pc, screen.app, ArtKind.HERO)
    val coverImage by rememberArtwork(artwork, screen.pc, screen.app, ArtKind.COVER)
    val boxImage by rememberBoxArt(LocalBoxArt.current, screen.pc, screen.app)
    val art = appArt(screen.pc, screen.app)
    val bottom = SettingsLayout.bottom(appearance)
    Box(Modifier.fillMaxSize()
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press) touch = true
                }
            }
        }
        .onPreviewKeyEvent { if (touch) touch = false; false }) {
        FocusTransferTarget(transfer)
        GameBanner(art, heroImage ?: coverImage ?: boxImage, appearance.heroBlur)
        // The eyebrow, the game's name, the custom-settings chip and Reset all, on the banner
        GameHeader(screen.pc.name, screen.app.name, custom.size, Modifier.offset(SettingsLayout.TITLE_X.px, GameSettingsLayout.HEAD_TOP.px)
            .width((1920f - 2 * SettingsLayout.TITLE_X).px).height(GameSettingsLayout.HEAD_HEIGHT.px)) {
            PillButton(stringResource(R.string.settings_game_reset_all), Symbols.RESET, resetAll,
                Modifier.alpha(if (custom.isEmpty()) .4f else 1f)
                    .onFocusChanged { onBanner = it.hasFocus }
                    // Nothing to reset: dimmed and skipped. Down returns to the row last focused, not the nearest one
                    .focusProperties {
                        canFocus = custom.isNotEmpty()
                        down = (focusedKey?.takeIf { key -> rows.any { it.key == key } } ?: rows.firstOrNull()?.key)
                            ?.let { navigation.requester(it) } ?: androidx.compose.ui.focus.FocusRequester.Default
                    },
                compact = true, clickCue = if (custom.isEmpty()) null else Cue.SELECT)
        }
        com.limelight.shell.ui.SelectionPillHost(Modifier.offset(y = GameSettingsLayout.TABS_Y.px).fillMaxWidth().height(SettingsLayout.TABS_HEIGHT.px)) {
            ShoulderLabel("L1")
            TabPill(Symbols.CAST, SettingsTab.STREAM.label(res), tab == 0, Modifier.padding(horizontal = 5.px), streamCustom) { changeTab(0) }
            TabPill(Symbols.MONITOR, SettingsTab.DISPLAY.label(res), tab == 1, Modifier.padding(horizontal = 5.px), displayCustom) { changeTab(1) }
            ShoulderLabel("R1")
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView, LocalEntranceStagger provides rememberEntranceStagger(tab to game)) {
            key(tab, game) {
                // One ring glides between the rows; the host carries the list's size and faded edges, so the ring fades with the rows
                SettingRowList(Modifier.padding(start = GameSettingsLayout.LIST_X.px, top = GameSettingsLayout.LIST_TOP.px, bottom = bottom.px)
                    .width(GameSettingsLayout.LIST_WIDTH.px).fillMaxHeight().fadeEdges(stage, top = 14f, bottom = 70f)) {
                    Column(
                        modifier = Modifier.fillMaxSize().focusedListBoundary(navigation).verticalScroll(scroll)
                            .padding(start = 18.px, end = 18.px, top = GameSettingsLayout.LIST_INNER_TOP.px, bottom = 140.px),
                        verticalArrangement = Arrangement.spacedBy(10.px)) {
                        rows.forEach { spec ->
                            key(spec.key) {
                                val current = values[spec.key] ?: spec.default
                                val choices = if (spec.kind == SettingKind.CHOICE) caps?.choices(res, spec, values) ?: spec.choices else spec.choices
                                val value = when (spec.kind) {
                                    SettingKind.TOGGLE -> RowValue.Toggle(current as? Boolean ?: false) { stream.write(spec, it) }
                                    SettingKind.CHOICE -> RowValue.Choice(spec.choiceLabel(res, current, choices), { dir ->
                                        nextSettingChoice(current, choices, dir)?.let { stream.write(spec, it.value) }
                                    })
                                    SettingKind.SLIDER -> RowValue.Slider((current as? Number)?.toInt() ?: spec.min,
                                        spec.min, spec.max, spec.step, spec.unit, spec.divisor) { stream.write(spec, it) }
                                    else -> RowValue.Info(current?.toString().orEmpty())
                                }
                                val isCustom = spec.key in custom
                                val hint = bitrateHint.takeIf { spec.key == "seekbar_bitrate_kbps" }
                                val profileLine = res.getString(R.string.settings_game_profile_line, profileValueLabel(res, spec, profileValues[spec.key] ?: spec.default, choices))
                                // The START menu keeps the reset of a custom row; X resets it directly
                                val menu = if (isCustom) ContextMenu(spec.label, listOf(MenuItem(Symbols.RESET, res.getString(R.string.settings_game_reset_menu)) { resetRow(spec.key) })) else null
                                SettingRow(spec.icon, spec.label, value, Modifier.focusedListRow(navigation, spec.key),
                                    description = dotJoin(res, res.getString(R.string.settings_game_from_profile), hint).takeIf { !isCustom },
                                    override = if (isCustom) RowOverride(dotJoin(res, profileLine, hint), touch) { resetRow(spec.key) } else null,
                                    enabled = caps != null && stream.enabled(spec, caps), contextMenu = menu,
                                    impact = impact(spec.key, values),
                                    onFocused = { focusedKey = spec.key })
                            }
                        }
                    }
                }
            }
        }
        val linkNote = guidance?.help(res, System.currentTimeMillis(), currentTransport(LocalContext.current))?.let { res.getString(R.string.settings_game_link_note, it) }
        val detail = focused?.let {
            SettingsHelp.details(res, it, values, caps?.choices(res, it, values) ?: it.choices) +
                if (it.key == "seekbar_bitrate_kbps" && linkNote != null) "\n\n$linkNote" else ""
        }
        val focusedChoices = focused?.let { caps?.choices(res, it, values) ?: it.choices }.orEmpty()
        GameSettingsPanel(
            Modifier.align(Alignment.TopEnd).padding(top = GameSettingsLayout.PANEL_TOP.px, end = SettingsLayout.TITLE_X.px, bottom = bottom.px)
                .width(520.px).fillMaxHeight(),
            game = screen.app.name, focused = focused, isCustom = focused?.key in custom, touch = touch,
            currentLabel = focused?.let { profileValueLabel(res, it, values[it.key] ?: it.default, focusedChoices) },
            profileLabel = focused?.let { profileValueLabel(res, it, profileValues[it.key] ?: it.default, focusedChoices) },
            detail = detail, streamCustom = streamCustom, displayCustom = displayCustom,
            showImpactLegend = focused != null && impact(focused.key, values) != null,
        )
    }
}

/**
 * Where the per-game screen puts its banner, title block, tabs, list and panel (stage px; docs/GUI.md). The persistent status bar
 * (the DELOS logo at the left, Wi-Fi / battery / clock at the right) owns y 0..104, so nothing of the screen starts above [HEAD_TOP];
 * the tabs sit where the global settings' tabs sit ([SettingsLayout.TABS_Y]) and the first row where its first row sits.
 */
internal object GameSettingsLayout {
    const val BANNER_HEIGHT = 290f
    const val HEAD_TOP = 106f
    const val HEAD_HEIGHT = 92f
    const val TABS_Y = SettingsLayout.TABS_Y

    /** The list's top edge (its top fade is 14 px) and the padding inside it: the first row starts at 326, like the global list's. */
    const val LIST_TOP = 296f
    const val LIST_INNER_TOP = 30f
    const val LIST_X = 132f
    const val LIST_WIDTH = 1080f
    const val PANEL_TOP = SettingsLayout.LIST_TOP
}

/**
 * The hero banner: the game's artwork blurred like the app's hero ([HeroBitmaps.renderLens], the same cached bitmap as the
 * dashboard's background), the top of the hero's area; the placeholder art while there is no image (and under it while it
 * loads). A scrim keeps the title legible on bright art, and the strip fades into the page towards its bottom.
 */
@Composable
internal fun GameBanner(art: GameArt, image: ImageBitmap?, blurPx: Int) {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    Box(Modifier.fillMaxWidth().height(GameSettingsLayout.BANNER_HEIGHT.px)
        .fadeMask(stage, vertical = true, startClear = 0f, startSolid = 1f, endSolid = GameSettingsLayout.BANNER_HEIGHT * .65f, endClear = 0f),
        contentAlignment = Alignment.TopCenter) {
        PlaceholderArt(art.hue, art.hue2, art.icon, Modifier.fillMaxSize(), glyphSize = 420f, glyphRight = 84f, glyphTop = -.14f, glyphAlpha = .14f)
        if (image != null) {
            val lens by produceState(if (blurPx > 0) HeroBitmaps.cachedLens(art.id, image, blurPx) else null, art.id, image, blurPx) {
                value = if (blurPx > 0) HeroBitmaps.cachedLens(art.id, image, blurPx) ?: withContext(Dispatchers.Default) { HeroBitmaps.renderLens(art.id, image, blurPx) } else null
            }
            val shown = if (blurPx > 0) lens else image
            val alpha by animateFloatAsState(if (shown != null) 1f else 0f, tween(Motion.FADE, easing = Motion.Ease), label = "bannerArt")
            shown?.let {
                if (blurPx > 0) {
                    // The stage-sized top of the hero's overscanned 2100 x 1260 area, as the dashboard shows it
                    Image(it, null, Modifier.requiredSize(2100.px, 1260.px).offset(y = (-90).px).graphicsLayer { this.alpha = alpha },
                        contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.Low)
                } else {
                    Image(it, null, Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                }
            }
        }
        Box(Modifier.fillMaxSize().drawBehind {
            val bg = colors.bg
            drawRect(Brush.horizontalGradient(0f to bg.copy(alpha = .7f), .5f to bg.copy(alpha = .2f), 1f to bg.copy(alpha = 0f)))
            drawRect(Brush.verticalGradient(0f to bg.copy(alpha = .15f), 1f to bg.copy(alpha = .55f)))
        })
    }
}

/** The title block on the banner: the eyebrow, the game's name and, on the right, the custom-settings chip and [action] (Reset all). */
@Composable
internal fun GameHeader(pc: String, game: String, customCount: Int, modifier: Modifier, action: @Composable () -> Unit) {
    val colors = LocalDelosColors.current
    val res = textResources()
    val k = stageUnit()
    // The prototype's text shadows (0 2 10 and 0 4 24) keep the text legible on a bright hero
    val shadow = if (colors.dark) Shadow(Color.Black.copy(alpha = .5f), Offset(0f, 3f * k), 12f * k) else null
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.px)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.px)) {
            BasicText(stringResource(R.string.settings_game_eyebrow, pc).uppercase(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = colors.accent2, fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 16.tpx, letterSpacing = .14.em, shadow = shadow))
            BasicText(game, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 46.tpx, lineHeight = 1.35.em, shadow = shadow))
        }
        Chip(if (customCount == 0) Symbols.CHECK_CIRCLE else Symbols.TUNE, customSettingsLabel(res, customCount), if (customCount == 0) null else colors.accent3)
        action()
    }
}

/**
 * The description panel of the global settings (520 px, [SettingsPanelFrame]) for a game: the focused option's icon, name, where its
 * value comes from (custom for this game, with the profile's value, or the profile's), its help and the autosave note. With nothing
 * focused it sums up how many settings this game customises.
 */
@Composable
internal fun GameSettingsPanel(
    modifier: Modifier, game: String, focused: SettingSpec?, isCustom: Boolean, touch: Boolean,
    currentLabel: String?, profileLabel: String?, detail: String?, streamCustom: Int, displayCustom: Int,
    showImpactLegend: Boolean = false,
) {
    val colors = LocalDelosColors.current
    val res = textResources()
    SettingsPanelFrame(modifier) {
        Box(Modifier.size(120.px).clip(androidx.compose.foundation.shape.CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
            Symbol(focused?.icon ?: Symbols.TUNE, 64.tpx, colors.onAccent, filled = true)
        }
        BasicText(focused?.label ?: stringResource(R.string.settings_game_panel_title), style = TextStyle(color = colors.text, fontFamily = Unbounded,
            fontWeight = FontWeight.SemiBold, fontSize = 30.tpx), modifier = Modifier.padding(top = 8.px))
        // The sentence with [value] set in bold: the string is formatted around a marker that the value then replaces
        fun bold(@androidx.annotation.StringRes sentence: Int, value: String): AnnotatedString = buildAnnotatedString {
            val marker = "\u0001"
            val text = res.getString(sentence, marker)
            val at = text.indexOf(marker)
            append(text.substring(0, at))
            withStyle(SpanStyle(color = colors.text, fontWeight = FontWeight.Bold)) { append(value) }
            append(text.substring(at + marker.length))
        }
        val total = streamCustom + displayCustom
        when {
            focused == null -> SourceBox(total > 0, if (total > 0) Symbols.TUNE else Symbols.CHECK_CIRCLE, customSettingsLabel(res, total),
                AnnotatedString(customSummary(res, streamCustom, displayCustom)))
            isCustom -> SourceBox(true, Symbols.TUNE, stringResource(R.string.settings_game_custom_title),
                bold(if (touch) R.string.settings_game_profile_value_touch else R.string.settings_game_profile_value_button, profileLabel.orEmpty()))
            else -> SourceBox(false, Symbols.ACCOUNT_CIRCLE, stringResource(R.string.settings_game_from_profile),
                bold(R.string.settings_game_currently, currentLabel.orEmpty()))
        }
        AutoScrollingHelp(focused?.key to detail, Modifier.weight(1f)) {
            BasicText(detail?.takeIf { it.isNotBlank() } ?: stringResource(R.string.settings_game_only_this_game, game),
                style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 22.tpx, lineHeight = 1.45.em))
        }
        if (showImpactLegend) ImpactLegend()
        Row(Modifier.padding(top = 10.px), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.px)) {
            Symbol(Symbols.CHECK_CIRCLE, 20.tpx, colors.text3)
            BasicText(stringResource(R.string.settings_game_saved_note), style = TextStyle(color = colors.text3, fontFamily = Figtree, fontSize = 17.tpx))
        }
    }
}

/** The panel's source box (`.src`): a quiet frame, violet while the value is custom. */
@Composable
private fun SourceBox(custom: Boolean, icon: String, title: String, body: AnnotatedString) {
    val colors = LocalDelosColors.current
    val shape = RoundedCornerShape(20.px)
    val tint = colors.accent3
    Column(Modifier.fillMaxWidth().clip(shape).background(if (custom) tint.copy(alpha = .22f) else colors.text.copy(alpha = .06f))
        .border(1.px, if (custom) tint.copy(alpha = .5f) else colors.line, shape).padding(horizontal = 20.px, vertical = 16.px),
        verticalArrangement = Arrangement.spacedBy(8.px)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.px)) {
            Symbol(icon, 24.tpx, if (custom) tint else colors.text2, filled = true)
            BasicText(title, style = TextStyle(color = if (custom) tint else colors.text2, fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 20.tpx))
        }
        BasicText(body, style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 20.tpx, lineHeight = 1.3.em))
    }
}
