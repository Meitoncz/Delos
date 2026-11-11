package com.limelight.shell.companion

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.limelight.R
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.Unbounded
import com.limelight.shell.ui.px
import com.limelight.shell.ui.tpx

/** What a key of the companion keyboard does. */
private sealed interface Cell {
    val weight: Float

    /** A character key: [c] as typed on the letters page (Shift makes a letter capital); on the symbols page the character itself. */
    data class Char(val c: kotlin.Char, override val weight: Float = 1f) : Cell
    data class Action(val kind: Kind, override val weight: Float) : Cell
    data class Gap(override val weight: Float) : Cell
}

private enum class Kind(val code: Int?) {
    BACKSPACE(KeyEvent.KEYCODE_DEL), ENTER(KeyEvent.KEYCODE_ENTER), SPACE(KeyEvent.KEYCODE_SPACE),
    LEFT(KeyEvent.KEYCODE_DPAD_LEFT), RIGHT(KeyEvent.KEYCODE_DPAD_RIGHT), SHIFT(null), SYMBOLS(null),
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

/**
 * The keyboard of the stream's companion: the keys go into the running stream through the Full keyboard's own path
 * ([StreamCommands.sendKey]: down when the finger lands, up when it lifts, Shift held around a shifted character) and a short echo
 * of what was typed shows above them (there is no text field: the PC has the text). "Stream menu" goes back to the tiles.
 */
@Composable
internal fun TypingContent(state: CompanionState.Stream, onMenu: () -> Unit) {
    val colors = LocalDelosColors.current
    var echo by remember { mutableStateOf(TypedEcho()) }
    var shifted by remember { mutableStateOf(false) }
    var symbols by remember { mutableStateOf(false) }
    val send: (Int, Boolean) -> Unit = { code, down -> state.commands?.sendKey?.invoke(code, down) }
    Column(Modifier.fillMaxSize().padding(start = 40.px, end = 40.px, top = 20.px, bottom = 26.px), verticalArrangement = Arrangement.spacedBy(14.px)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.px)) {
                Eyebrow(state.gameName ?: stringResource(R.string.companion_streaming))
                BasicText(
                    stringResource(R.string.stream_menu_tile_keyboard),
                    style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 34.tpx),
                    maxLines = 1,
                )
            }
            CompanionButton(stringResource(R.string.companion_typing_menu), onMenu, Modifier.height(80.px), ButtonStyle.SECONDARY, icon = Symbols.MORE_HORIZ)
        }
        Row(Modifier.fillMaxWidth().card().padding(horizontal = 26.px, vertical = 14.px), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.px)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.px)) {
                Eyebrow(stringResource(R.string.companion_typing_sent))
                BasicText(
                    echo.value.ifEmpty { stringResource(R.string.companion_typing_hint) },
                    style = TextStyle(
                        color = if (echo.value.isEmpty()) colors.text3 else colors.text, fontFamily = Figtree, fontSize = 36.tpx, textAlign = TextAlign.Start,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    softWrap = false,
                )
            }
            CompanionButton(stringResource(R.string.companion_typing_clear), { echo = echo.cleared() }, Modifier.height(MIN_TARGET.px + 8.px), textSize = MIN_TEXT)
        }
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.px)) {
            (if (symbols) SYMBOL_ROWS else LETTER_ROWS).forEach { row ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.px)) {
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
