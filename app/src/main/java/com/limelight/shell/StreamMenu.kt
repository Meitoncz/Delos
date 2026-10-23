package com.limelight.shell

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import com.limelight.binding.input.KeyboardTranslator
import com.limelight.GameMenu
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.focusable
import androidx.compose.runtime.key
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.limelight.Game
import com.limelight.shell.data.ProfileStore
import com.limelight.shell.settings.AppearanceStore
import com.limelight.shell.settings.StreamMenuSettings
import com.limelight.shell.ui.Feedback
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.FocusGlow
import com.limelight.shell.ui.FocusRing
import com.limelight.shell.ui.Glyph
import com.limelight.shell.ui.GlyphButton
import com.limelight.shell.ui.InitialFocus
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalFeedback
import com.limelight.shell.ui.Stage
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.Unbounded
import com.limelight.shell.ui.delosColors
import com.limelight.shell.ui.focusEffects
import com.limelight.shell.ui.px
import com.limelight.shell.ui.systemAccentHue
import com.limelight.shell.ui.tpx

/**
 * The Delos stream menu (the prototype's `#quickmenu`): a panel at the bottom of the stream with the
 * game's name and Resume, Keyboard, Performance, More (the classic Artemis quick menu), Disconnect and
 * Quit game. It opens with the buttons set in Delos (L3 + R3 by default), a swipe up from the bottom
 * edge (unless turned off) and every way the classic menu opened. It is its own window, so the
 * controller does not reach the game while it is open. Created by Game (a small "Delos:" hook).
 */
class StreamMenu(private val game: Game) {
    private val buttons = StreamMenuSettings.buttons(game)
    private val swipe = StreamMenuSettings.swipe(game)
    private val held = HashSet<Int>()

    /** The shortcut is complete; the menu opens once its buttons are let go (the game sees them released). */
    private var pending = false
    private var edgeGesture = false
    private var edgeStartY = 0f
    private var dialog: ComponentDialog? = null

    val isOpen: Boolean get() = dialog?.isShowing == true

    /** Returns whether the stream must not see [event] (the shortcut's final button). */
    fun onKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code !in buttons) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) held += code
                if (held.containsAll(buttons)) {
                    if (!pending) held.retainAll(buttons)
                    pending = true
                    return true
                }
            }
            KeyEvent.ACTION_UP -> {
                held -= code
                if (pending && held.isEmpty()) {
                    pending = false
                    show()
                    return true
                }
            }
        }
        return false
    }

    /**
     * A swipe up from the bottom edge opens the menu. Touches that start at that edge are kept from the
     * game while it is decided.
     */
    fun onTouch(event: MotionEvent): Boolean {
        if (!swipe) return false
        val height = game.window.decorView.height.takeIf { it > 0 } ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edgeGesture = event.y >= height * (1f - EDGE)
                edgeStartY = event.y
                return edgeGesture
            }
            MotionEvent.ACTION_MOVE -> {
                if (!edgeGesture) return false
                if (edgeStartY - event.y >= height * DISTANCE && !isOpen) show()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = edgeGesture
                edgeGesture = false
                return was
            }
        }
        return edgeGesture
    }

    fun show() {
        if (isOpen || game.isFinishing) return
        val feedback = Feedback(game)
        val d = ComponentDialog(game, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_FULLSCREEN)
            attributes = attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
            WindowCompat.setDecorFitsSystemWindows(this, false)
        }
        val appearance = AppearanceStore(game) { ProfileStore.currentSettings(game) }.appearance.value
        val colors = delosColors(appearance, systemAccentHue(game))
        val title = game.intent.getStringExtra(Game.EXTRA_APP_NAME) ?: "Stream"
        d.setContentView(ComposeView(d.context).apply {
            setContent {
                CompositionLocalProvider(LocalDelosColors provides colors, LocalFeedback provides feedback, LocalFocusTracker provides remember { FocusTracker() }) {
                    Stage(textScale = appearance.uiScale / 100f) { MenuPanel(title, d) }
                }
            }
        })
        d.setOnDismissListener {
            feedback.release()
            dialog = null
        }
        dialog = d
        d.show()
        d.window?.let { w ->
            WindowCompat.getInsetsController(w, w.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        feedback.view = d.window?.decorView
        feedback.play(com.limelight.shell.ui.Cue.OPEN)
    }

    fun hide() {
        dialog?.dismiss()
    }

    private enum class Page { MAIN, MORE, KEYS, COMMANDS }

    /** Runs [action] once the stream window has the focus again (after the menu closed), like GameMenu. */
    private fun whenGameFocused(action: () -> Unit) {
        if (game.isFinishing) return
        if (!game.hasWindowFocus()) {
            game.window.decorView.postDelayed({ whenGameFocused(action) }, 50)
            return
        }
        action()
    }

    @Composable
    private fun MenuPanel(title: String, d: ComponentDialog) {
        var page by remember { mutableStateOf(Page.MAIN) }
        // B on a sub page goes back to the main page; on the main page it resumes (closes the menu)
        BackHandler(enabled = page != Page.MAIN) { page = Page.MAIN }
        fun close(then: () -> Unit = {}) {
            d.dismiss()
            whenGameFocused(then)
        }
        val classic = game.gameMenuCallbacks as? GameMenu
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            // linear-gradient(transparent, rgba(0, 0, 0, .75) 40%)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(0f to Color.Transparent, .4f to Color.Black.copy(alpha = .75f), 1f to Color.Black.copy(alpha = .75f)))
                    .padding(top = 90.px, bottom = 56.px),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(26.px),
            ) {
                val heading = when (page) {
                    Page.MAIN -> title
                    Page.MORE -> "More"
                    Page.KEYS -> "Send keys"
                    Page.COMMANDS -> "Server commands"
                }
                BasicText(heading, style = TextStyle(color = Color.White, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 30.tpx, textAlign = TextAlign.Center))
                key(page) {
                    val first = remember { FocusRequester() }
                    InitialFocus(first)
                    when (page) {
                        Page.MAIN -> Row(horizontalArrangement = Arrangement.spacedBy(34.px)) {
                            MenuCircle(Symbols.PLAY_ARROW, "Resume", Modifier.focusRequester(first)) { close() }
                            MenuCircle(Symbols.KEYBOARD, "Keyboard") { close { game.toggleKeyboard() } }
                            MenuCircle(Symbols.SPEED, "Performance") { close { game.toggleHUD() } }
                            MenuCircle(Symbols.MORE_HORIZ, "More") { page = Page.MORE }
                            MenuCircle(Symbols.LOGOUT, "Disconnect") { close { game.disconnect() } }
                            MenuCircle(Symbols.POWER_SETTINGS_NEW, "Quit game") { close { game.quitWithoutAsking() } }
                        }
                        // Everything the classic Artemis quick menu offered that the main page does not
                        Page.MORE -> Column(verticalArrangement = Arrangement.spacedBy(26.px), horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(horizontalArrangement = Arrangement.spacedBy(34.px)) {
                                MenuCircle(Symbols.KEYBOARD_COMMAND_KEY, "Send keys", Modifier.focusRequester(first)) { page = Page.KEYS }
                                MenuCircle(Symbols.UPLOAD, "Send clipboard") { close { game.sendClipboard(true) } }
                                MenuCircle(Symbols.CONTENT_PASTE, "Get clipboard") { close { game.getClipboard(0) } }
                                MenuCircle(Symbols.TERMINAL, "Commands") { page = Page.COMMANDS }
                                MenuCircle(Symbols.MONITORING, "Task manager") {
                                    close { game.sendKeys(shortArrayOf(KeyboardTranslator.VK_LCONTROL.toShort(), KeyboardTranslator.VK_LSHIFT.toShort(), KeyboardTranslator.VK_ESCAPE.toShort())) }
                                }
                                MenuCircle(Symbols.ZOOM_IN, if (game.isZoomModeEnabled) "Zoom off" else "Zoom") { close { game.toggleZoomMode() } }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(34.px)) {
                                if (game.allowChangeMouseMode) MenuCircle(Symbols.MOUSE, "Mouse mode") { close { game.selectMouseMode(game) } }
                                MenuCircle(Symbols.JOYSTICK, "On-screen controls") { close { game.toggleVirtualController() } }
                                MenuCircle(Symbols.KEYBOARD_ALT, "Full keyboard") { close { game.toggleFullKeyboard() } }
                                MenuCircle(Symbols.KEYBOARD, "Key layout") { close { game.toggleKeyboardController() } }
                                MenuCircle(Symbols.TOUCH_APP, "Touch sensitivity") { close { game.switchTouchSensitivity() } }
                                MenuCircle(Symbols.SCREEN_ROTATION, "Rotate") { close { game.rotateScreen() } }
                            }
                        }
                        Page.KEYS -> PillGrid(classic?.specialKeyOptions().orEmpty().map { it.label }, first) { i ->
                            val option = classic?.specialKeyOptions()?.getOrNull(i)
                            d.dismiss()
                            if (option != null) classic?.run(option)
                        }
                        Page.COMMANDS -> {
                            val commands = game.serverCmds.orEmpty()
                            if (commands.isEmpty()) {
                                BasicText(
                                    "No commands are set up on the PC. Vibepollo can add them in its web UI.",
                                    style = TextStyle(color = Color.White.copy(alpha = .8f), fontFamily = Figtree, fontSize = 24.tpx, textAlign = TextAlign.Center),
                                    modifier = Modifier.focusRequester(first).focusable(),
                                )
                            } else {
                                PillGrid(commands, first) { i -> close { game.sendExecServerCmd(i) } }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(28.px), verticalAlignment = Alignment.CenterVertically) {
                    MenuHint(Glyph.A, "Select")
                    MenuHint(Glyph.B, if (page == Page.MAIN) "Resume" else "Back")
                }
            }
        }
    }

    /** Text choices (key combinations, server commands) as pills that wrap to the next line. */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun PillGrid(labels: List<String>, first: FocusRequester, onSelect: (Int) -> Unit) {
        FlowRow(
            Modifier.widthIn(max = 1500.px),
            horizontalArrangement = Arrangement.spacedBy(16.px, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(16.px),
        ) {
            labels.forEachIndexed { i, label ->
                var focused by remember { mutableStateOf(false) }
                BasicText(
                    label,
                    style = TextStyle(color = Color.White, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 22.tpx),
                    modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier)
                        .focusEffects({ onSelect(i) }, corner = 999f, focusScale = 1.06f, ring = FocusRing(4f, 4f), glow = FocusGlow(14f, 40f, -12f), onFocusChange = { focused = it })
                        .clip(CircleShape)
                        .background(if (focused) Color(0xFF2E3A52) else Color(0xD91E283C))
                        .border(1.px, Color.White.copy(alpha = .12f), CircleShape)
                        .padding(horizontal = 28.px, vertical = 16.px),
                )
            }
        }
    }

    /** `#quickmenu .circle`: 112 px on a dark glass, white icon and label. */
    @Composable
    private fun MenuCircle(icon: String, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
        var focused by remember { mutableStateOf(false) }
        Column(Modifier.width(150.px), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.px)) {
            Box(
                modifier
                    .focusEffects(onClick, corner = 999f, focusScale = 1.16f, ring = FocusRing(5f, 5f), glow = FocusGlow(20f, 50f, -14f), onFocusChange = { focused = it })
                    .size(112.px)
                    .clip(CircleShape)
                    .background(Color(0xD91E283C))
                    .border(1.px, Color.White.copy(alpha = .12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(icon, 48.tpx, Color.White, filled = focused)
            }
            BasicText(label, style = TextStyle(color = Color.White, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 20.tpx, textAlign = TextAlign.Center))
        }
    }

    @Composable
    private fun MenuHint(glyph: Glyph, label: String) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.px)) {
            GlyphButton(glyph)
            BasicText(label, style = TextStyle(color = Color.White.copy(alpha = .8f), fontFamily = Figtree, fontSize = 21.tpx))
        }
    }

    private companion object {
        /** The bottom strip a swipe has to start in, and how far up it has to go (share of the height). */
        const val EDGE = .06f
        const val DISTANCE = .12f
    }
}
