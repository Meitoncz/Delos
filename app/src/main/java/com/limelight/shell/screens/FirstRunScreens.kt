package com.limelight.shell.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.res.stringResource
import com.limelight.R
import com.limelight.shell.LocalFocusTracker
import com.limelight.shell.LocalShell
import com.limelight.shell.data.AVATARS
import com.limelight.shell.data.Profile
import com.limelight.shell.ui.CenterColumn
import com.limelight.shell.ui.CircleItem
import androidx.compose.ui.focus.focusProperties
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.FocusGlow
import com.limelight.shell.ui.FocusRing
import com.limelight.shell.ui.Heading
import com.limelight.shell.ui.InitialFocus
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalFeedback
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.PillButton
import com.limelight.shell.ui.RowGlideHost
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.ValuePill
import com.limelight.shell.ui.Unbounded
import com.limelight.shell.ui.px
import com.limelight.shell.ui.tpx

/** First launch (`#scr-boot`): the logo, the wordmark and "Get started"; [onSkip] when PCs are paired already. */
@Composable
fun BootScreen(onBegin: () -> Unit, onSkip: (() -> Unit)?) {
    val colors = LocalDelosColors.current
    val begin = remember { FocusRequester() }
    InitialFocus(begin)
    CenterColumn(gap = 28f) {
        BootLogo(Modifier.padding(bottom = 8.px))
        // margin-left .24em balances the letter spacing after the last letter
        BasicText(
            "DELOS",
            style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 132.tpx, letterSpacing = .24.em),
            modifier = Modifier.padding(start = 30.px),
        )
        BasicText(stringResource(R.string.firstrun_boot_tagline), style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 28.tpx))
        Row(Modifier.padding(top = 26.px), horizontalArrangement = Arrangement.spacedBy(24.px)) {
            PillButton(stringResource(R.string.firstrun_boot_begin), Symbols.ARROW_FORWARD, onBegin, Modifier.focusRequester(begin), primary = true)
            if (onSkip != null) PillButton(stringResource(R.string.firstrun_boot_skip), Symbols.FAST_FORWARD, onSkip)
        }
    }
}

/** `.boot-logo`: the Delos ring with its glow, the island inside and the moon circling it (7 s a turn). */
@Composable
private fun BootLogo(modifier: Modifier = Modifier) {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    val turn by rememberInfiniteTransition(label = "orbit").animateFloat(0f, 360f, infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "orbitTurn")
    Canvas(modifier.size(210.px)) {
        val k = stage.dpPerPx * density
        val glow = 160f * k
        drawCircle(Brush.radialGradient(0f to colors.glow, .66f to colors.glow.copy(alpha = colors.glow.alpha * .4f), 1f to Color.Transparent, center = center, radius = glow), radius = glow)
        drawCircle(colors.accent, radius = 101f * k, style = Stroke(8f * k))
        val isle = 37f * k
        // radial-gradient(circle at 35% 30%, text, accent-3): the third color tints the island's shadow side
        val light = Offset(center.x - isle * .3f, center.y - isle * .4f)
        drawCircle(Brush.radialGradient(0f to colors.text, 1f to colors.accent3, center = light, radius = isle * 1.6f), radius = isle, alpha = .92f)
        // The moon sits at the orbit's top right (top 18, right 30 in a 262 px box)
        rotate(turn) {
            val moon = center + Offset(87f * k, -99f * k)
            drawCircle(Brush.radialGradient(0f to colors.accent2.copy(alpha = .6f), 1f to Color.Transparent, center = moon, radius = 44f * k), radius = 44f * k, center = moon)
            drawCircle(colors.accent2, radius = 14f * k, center = moon)
        }
    }
}

/**
 * `#scr-create`: an avatar in circles and a name. [editing] changes the current profile ([initial])
 * instead of creating one.
 */
@Composable
fun CreateProfileScreen(editing: Boolean, initial: Profile?, onDone: (name: String, avatar: Int) -> Unit) {
    val res = com.limelight.shell.ui.textResources()
    val shell = LocalShell.current
    val feedback = LocalFeedback.current
    val tracker = LocalFocusTracker.current
    var avatar by remember { mutableIntStateOf((initial?.avatar ?: 0).coerceIn(AVATARS.indices)) }
    val defaultName = stringResource(R.string.firstrun_create_default_name)
    val nameTitle = stringResource(R.string.firstrun_create_name_title)
    var name by remember { mutableStateOf(initial?.name ?: defaultName) }
    val avatarFocus = remember { List(AVATARS.size) { FocusRequester() } }
    val changeName = remember { FocusRequester() }
    val create = remember { FocusRequester() }
    InitialFocus(avatarFocus[avatar.coerceIn(AVATARS.indices)])
    // The name is typed in the same dialog as Rename profile; when it is accepted the page shows it and the focus goes on to Create / Save
    val askName = {
        openProfileNameDialog(res, shell, tracker, feedback, nameTitle, name) { entered ->
            name = entered
            shell.returnFocusTo(create)
        }
    }
    // Without a name there is nothing to create: the dialog asks for one
    val submit = { if (name.isBlank()) askName() else onDone(name.trim(), avatar) }
    CenterColumn(gap = 40f) {
        Heading(
            stringResource(if (editing) R.string.firstrun_create_edit_title else R.string.firstrun_create_new_title),
            stringResource(if (editing) R.string.firstrun_create_edit_note else R.string.firstrun_create_new_note),
        )
        // All avatars in rows of AVATAR_COLUMNS. Up and down move spatially; left at the start of a row and
        // right at its end continue in the previous and the next row (and wrap from the first to the last).
        RowGlideHost(
            ring = FocusRing(5f, 5f), corner = 999f, focusScale = 1.16f,
            fill = null, glow = FocusGlow(26f, 70f, -16f),
        ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(26.px)) {
            AVATARS.withIndex().chunked(AVATAR_COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(32.px), verticalAlignment = Alignment.Top) {
                    row.forEach { (i, a) ->
                        val wrap = Modifier.focusProperties {
                            if (i % AVATAR_COLUMNS == 0) left = avatarFocus[(i - 1 + AVATARS.size) % AVATARS.size]
                            if (i % AVATAR_COLUMNS == AVATAR_COLUMNS - 1 || i == AVATARS.lastIndex) right = avatarFocus[(i + 1) % AVATARS.size]
                        }
                        CircleItem(
                            a.icon, stringResource(a.label),
                            onClick = {
                                avatar = i
                                if (!editing) runCatching { changeName.requestFocus() }
                            },
                            modifier = wrap.focusRequester(avatarFocus[i]),
                            selected = i == avatar,
                            avatar = a,
                            diameter = AVATAR_DIAMETER,
                            width = AVATAR_CELL,
                        )
                    }
                }
            }
        }
        }
        // The name as it is (not an input) and the button that opens the dialog; D-pad: avatars, Change name, Create / Save
        Row(horizontalArrangement = Arrangement.spacedBy(24.px), verticalAlignment = Alignment.CenterVertically) {
            ValuePill(stringResource(R.string.firstrun_create_name_label), name, Symbols.BADGE, placeholder = stringResource(R.string.firstrun_create_name_placeholder))
            PillButton(stringResource(R.string.firstrun_create_change_name), Symbols.EDIT, askName, Modifier.focusRequester(changeName))
        }
        PillButton(stringResource(if (editing) R.string.firstrun_create_save else R.string.firstrun_create_create), Symbols.CHECK, submit, Modifier.focusRequester(create), primary = true)
    }
}

/** The avatar picker: 6 columns of 144 px circles in 184 px cells, 2 rows (six cells and five gaps are 1264 px of the 1920 px stage). */
private const val AVATAR_COLUMNS = 6
private const val AVATAR_DIAMETER = 144f
private const val AVATAR_CELL = 184f
