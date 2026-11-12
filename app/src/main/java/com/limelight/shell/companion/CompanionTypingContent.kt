package com.limelight.shell.companion

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.limelight.R
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.px
import com.limelight.shell.ui.textResources
import com.limelight.shell.ui.tpx

/** What a key of the companion keyboard does. */
internal sealed interface Cell {
    val weight: Float

    /** A character key: [c] as typed on the letters page (Shift makes a letter capital); on the symbols page the character itself. */
    data class Char(val c: kotlin.Char, override val weight: Float = 1f) : Cell
    data class Action(val kind: Kind, override val weight: Float) : Cell
    data class Gap(override val weight: Float) : Cell
}

internal enum class Kind(val code: Int?) {
    BACKSPACE(KeyEvent.KEYCODE_DEL), ENTER(KeyEvent.KEYCODE_ENTER), SPACE(KeyEvent.KEYCODE_SPACE),
    LEFT(KeyEvent.KEYCODE_DPAD_LEFT), RIGHT(KeyEvent.KEYCODE_DPAD_RIGHT), SHIFT(null), SYMBOLS(null),
}

/**
 * The rows of the keyboard for a panel (docs/DUAL_SCREEN.md, "No scrolling, ever"): the five QWERTY rows of ten keys when the panel is wide enough for every key to
 * be at least [MIN_TARGET] wide, else rows of eight (characters in the order they are typed, then the action keys), so a key never shrinks below a thumb.
 */
internal object KeyRows {
    /** The gap between keys, in stage px. */
    const val GAP = 8f

    /** The widest QWERTY row in key units (ten keys and the wide Backspace) and its gaps. */
    private const val QWERTY_UNITS = 11.6f
    private const val QWERTY_GAPS = 10

    /** The columns a keyboard in [width] stage px has: 10 (the QWERTY rows) or 8. */
    fun columnsFor(width: Float): Int = if ((width - QWERTY_GAPS * GAP) / QWERTY_UNITS >= MIN_TARGET) 10 else 8

    fun rows(symbols: Boolean, columns: Int): List<List<Cell>> = when {
        columns >= 10 -> if (symbols) SYMBOL_ROWS else LETTER_ROWS
        else -> narrow(symbols)
    }

    private fun chars(text: String) = text.map { Cell.Char(it) }

    private val LETTER_ROWS = listOf(
        chars("1234567890") + Cell.Action(Kind.BACKSPACE, 1.6f),
        chars("qwertyuiop"),
        chars("asdfghjkl") + Cell.Action(Kind.ENTER, 1.6f),
        listOf<Cell>(Cell.Action(Kind.SHIFT, 1.5f)) + chars("zxcvbnm"),
        listOf(Cell.Action(Kind.SYMBOLS, 1.5f), Cell.Char(',', 1f), Cell.Action(Kind.SPACE, 5.5f), Cell.Char('.', 1f), Cell.Action(Kind.LEFT, 1f), Cell.Action(Kind.RIGHT, 1f)),
    )

    private val SYMBOL_ROWS = listOf(
        chars("!@#$%^&*()") + Cell.Action(Kind.BACKSPACE, 1.6f),
        chars("[]{}<>+=_~"),
        chars(";:'\"/\\|?-") + Cell.Action(Kind.ENTER, 1.6f),
        listOf<Cell>(Cell.Gap(1.5f)) + chars("`,.") + Cell.Gap(5.5f),
        LETTER_ROWS.last(),
    )

    private const val NARROW_COLUMNS = 8

    private fun narrow(symbols: Boolean): List<List<Cell>> =
        chars(if (symbols) "!@#$%^&*()[]{}<>+=_~;:'\"/\\|?-`,." else "1234567890qwertyuiopasdfghjklzxcvbnm,.").chunked(NARROW_COLUMNS) + listOf(
            listOf(Cell.Action(Kind.SHIFT, 1.5f), Cell.Action(Kind.SYMBOLS, 1.5f), Cell.Action(Kind.SPACE, 3f), Cell.Action(Kind.BACKSPACE, 2f)),
            listOf(Cell.Action(Kind.ENTER, 4f), Cell.Action(Kind.LEFT, 2f), Cell.Action(Kind.RIGHT, 2f)),
        )
}

/** What the echo box says: the [text] and whether it is the hint of an empty box (set in the quiet color). */
internal data class EchoView(val text: String, val placeholder: Boolean)

internal object EchoFit {
    /**
     * The echo box's text for a box of [width] fit px: the hint (or its short form, or nothing) while empty; what was typed when it fits one line; else
     * how many characters have been typed, which [count] words (the whole text is on the PC, the box is only a glance).
     */
    fun view(value: String, hint: String, hintShort: String, width: Float, metrics: TextMetrics, count: (Int) -> String): EchoView = when {
        value.isEmpty() -> EchoView(listOf(hint, hintShort).firstOrNull { metrics.lines(it, width) <= 1 }.orEmpty(), true)
        metrics.lines(value, width) <= 1 -> EchoView(value, false)
        else -> EchoView(count(value.length), false)
    }
}

/**
 * The keyboard of the stream's companion: the keys go into the running stream through the Full keyboard's own path
 * ([StreamCommands.sendKey]: down when the finger lands, up when it lifts, Shift held around a shifted character) and a short echo
 * of what was typed shows above them (there is no text field: the PC has the text). "Stream menu" goes back to the tiles.
 *
 * Nothing scrolls: the echo is one bounded line, and the rows of keys share the height under it ([KeyRows]: ten columns, or eight on a narrow panel).
 */
@Composable
internal fun TypingContent(state: CompanionState.Stream, onMenu: () -> Unit) {
    var echo by remember { mutableStateOf(TypedEcho()) }
    var shifted by remember { mutableStateOf(false) }
    var symbols by remember { mutableStateOf(false) }
    val send: (Int, Boolean) -> Unit = { code, down -> state.commands?.sendKey?.invoke(code, down) }
    val stage = LocalStage.current
    val rows = KeyRows.rows(symbols, KeyRows.columnsFor(stage.stageWidth - 2 * CompanionFit.MARGIN))
    Column(
        Modifier.fillMaxSize().padding(start = CompanionFit.MARGIN.px, end = CompanionFit.MARGIN.px, top = 20.px, bottom = (CompanionFit.MARGIN + CompanionFit.FOOTER).px),
        verticalArrangement = Arrangement.spacedBy(10.px),
    ) {
        Row(Modifier.fillMaxWidth().height(MIN_TARGET.px), horizontalArrangement = Arrangement.spacedBy(10.px)) {
            Echo(echo.value, Modifier.weight(1f).fillMaxHeight())
            CompanionButton(stringResource(R.string.companion_typing_clear), { echo = echo.cleared() }, Modifier.fillMaxHeight(), textSize = MIN_TEXT)
            CompanionButton(stringResource(R.string.companion_typing_menu), onMenu, Modifier.fillMaxHeight(), ButtonStyle.SECONDARY, icon = Symbols.MORE_HORIZ, textSize = MIN_TEXT)
        }
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(KeyRows.GAP.px)) {
            rows.forEach { row ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KeyRows.GAP.px)) {
                    row.forEach { cell ->
                        when (cell) {
                            is Cell.Gap -> Box(Modifier.weight(cell.weight))
                            is Cell.Char -> {
                                val shown = if (shifted && !symbols && cell.c.isLetter()) cell.c.uppercaseChar() else cell.c
                                KeyCap(Modifier.weight(cell.weight).fillMaxSize(), stroke = KeyMap.stroke(shown), send = send, onTyped = {
                                    echo = echo.typed(shown)
                                    shifted = false
                                }) { Label(shown.toString()) }
                            }
                            is Cell.Action -> ActionKey(cell, Modifier.weight(cell.weight).fillMaxSize(), send, shifted, symbols,
                                onEcho = { kind ->
                                    echo = when (kind) {
                                        Kind.BACKSPACE -> echo.backspace()
                                        Kind.ENTER -> echo.enter()
                                        Kind.SPACE -> echo.typed(' ')
                                        else -> echo
                                    }
                                },
                                onShift = { shifted = !shifted }, onSymbols = { symbols = !symbols })
                        }
                    }
                }
            }
        }
    }
}

/** The echo: what was typed in one line, the count when it does not fit, the hint while empty. */
@Composable
private fun Echo(value: String, modifier: Modifier) {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    val res = textResources()
    val fit = rememberFit()
    val hint = stringResource(R.string.companion_typing_hint)
    val hintShort = stringResource(R.string.companion_typing_hint_short)
    BoxWithConstraints(modifier.card().padding(horizontal = 20.px), contentAlignment = Alignment.CenterStart) {
        val width = maxWidth.value / stage.dpPerPx / stage.textScale
        val view = EchoFit.view(value, hint, hintShort, width, fit.metrics) { res.getQuantityString(R.plurals.companion_typing_count, it, it) }
        BasicText(
            view.text,
            style = TextStyle(color = if (view.placeholder) colors.text3 else colors.text, fontFamily = Figtree, fontSize = MIN_TEXT.tpx),
            maxLines = 1,
            overflow = TextOverflow.Clip,
            softWrap = false,
        )
    }
}

@Composable
private fun Label(text: String) {
    val colors = LocalDelosColors.current
    BasicText(text, style = TextStyle(color = colors.text, fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 36.tpx), maxLines = 1)
}

/** A key that sends [stroke] while the finger is on it. A character with no key on the US layout does nothing. */
@Composable
private fun KeyCap(modifier: Modifier, stroke: KeyStroke?, send: (Int, Boolean) -> Unit, onTyped: () -> Unit, selected: Boolean = false, content: @Composable () -> Unit) {
    var held by remember { mutableStateOf<KeyStroke?>(null) }
    PressKey(
        onDown = {
            if (stroke != null) {
                held = stroke
                KeyMap.down(stroke).forEach { (code, down) -> send(code, down) }
                onTyped()
            }
        },
        onUp = {
            held?.let { KeyMap.up(it).forEach { (code, down) -> send(code, down) } }
            held = null
        },
        modifier = modifier,
        selected = selected,
    ) { content() }
}

@Composable
private fun ActionKey(cell: Cell.Action, modifier: Modifier, send: (Int, Boolean) -> Unit, shifted: Boolean, symbols: Boolean, onEcho: (Kind) -> Unit, onShift: () -> Unit, onSymbols: () -> Unit) {
    val colors = LocalDelosColors.current
    val text = when (cell.kind) {
        Kind.SHIFT -> "Shift" // l10n-ignore
        Kind.SYMBOLS -> if (symbols) "ABC" else "#+=" // l10n-ignore
        Kind.ENTER -> "Enter" // l10n-ignore
        else -> null
    }
    val icon = when (cell.kind) {
        Kind.BACKSPACE -> Symbols.BACKSPACE
        Kind.LEFT -> Symbols.CHEVRON_LEFT
        Kind.RIGHT -> Symbols.CHEVRON_RIGHT
        else -> null
    }
    val code = cell.kind.code
    var held by remember { mutableStateOf(false) }
    PressKey(
        onDown = {
            when (cell.kind) {
                Kind.SHIFT -> onShift()
                Kind.SYMBOLS -> onSymbols()
                else -> if (code != null) {
                    held = true
                    send(code, true)
                    onEcho(cell.kind)
                }
            }
        },
        onUp = {
            if (held && code != null) send(code, false)
            held = false
        },
        modifier = modifier,
        selected = (cell.kind == Kind.SHIFT && shifted) || (cell.kind == Kind.SYMBOLS && symbols),
        accent = cell.kind == Kind.ENTER,
    ) {
        if (icon != null) Symbol(icon, 40.tpx, colors.text)
        else if (cell.kind == Kind.SPACE) Box(Modifier.fillMaxWidth(.5f).height(6.px).background(colors.text3, CircleShape))
        else BasicText(text.orEmpty(), style = TextStyle(color = colors.text2, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = MIN_TEXT.tpx), maxLines = 1)
    }
}
