package com.limelight.shell.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.limelight.R
import com.limelight.shell.settings.ClockFormat
import com.limelight.shell.system.rememberBatteryStatus
import com.limelight.shell.system.rememberClock
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.Symbols
import kotlin.math.roundToInt

/**
 * The status card of every screen that has no content of its own, new routes included (docs/DUAL_SCREEN.md, "The fallback"): the screen's title and its
 * one-line purpose, the PC the shell is connected to with its status and the running game, the clock and the battery, and what the controller does here
 * (the entries of the main hint bar, the same list). Never the empty "choose a game" card.
 */
internal data class FallbackPage(val context: ShellContext) : CompanionPage {
    override val key: String get() = "fallback/${context.title}"

    @Composable
    override fun Content() {
        val colors = LocalDelosColors.current
        val battery by rememberBatteryStatus()
        val clock by rememberClock(ClockFormat.SYSTEM)
        PageCard { fit ->
            val b = fit.budget
            val width = b.innerWidth
            PageHeading(Symbols.INFO, context.title.ifBlank { stringResource(R.string.companion_screen_home) }, stringResource(R.string.companion_context_eyebrow))
            val purpose = CompanionFit.summary(context.purpose, context.purpose, if (b.roomy) 3 else 1, width, fit.metrics, stringResource(R.string.companion_see_main))
            if (purpose.isNotBlank()) BodyText(purpose, Modifier.optional(60), color = colors.text2)
            context.host?.let { host ->
                val full = if (b.roomy || host.runningGame == null) "${host.name} \u00b7 ${host.status}" else "${host.name} \u00b7 ${host.status} \u00b7 ${host.runningGame}"
                BodyText(if (fit.metrics.lines(full, width) <= 1) full else host.status, Modifier.optional(80), color = colors.accent2, maxLines = 1)
                if (b.roomy && host.runningGame != null) BodyText(stringResource(R.string.companion_host_running, host.runningGame), Modifier.optional(30), color = colors.text, maxLines = 1)
            }
            Row(Modifier.fillMaxWidth().optional(40), horizontalArrangement = Arrangement.SpaceBetween) {
                BodyText(clock, color = colors.text, maxLines = 1)
                battery?.let { BodyText(stringResource(R.string.companion_battery, (it.level * 100).roundToInt()), color = colors.text, maxLines = 1) }
            }
            Filler()
            val (shown, rest) = CompanionFit.top(context.hints, b.hints)
            if (shown.isEmpty()) BodyText(stringResource(R.string.companion_no_hints), color = colors.text2, maxLines = 2)
            else HintList(shown, rest, Modifier.optional(70))
        }
    }
}

/** No PC is paired yet: a short welcome and the one thing to do (docs/DUAL_SCREEN.md). */
internal data class WelcomePage(val context: ShellContext) : CompanionPage {
    override val key: String get() = "welcome"

    @Composable
    override fun Content() {
        val colors = LocalDelosColors.current
        PageCard { fit ->
            val b = fit.budget
            PageHeading(Symbols.ADD_LINK, stringResource(R.string.companion_screen_boot), stringResource(R.string.companion_welcome_sub))
            val text = CompanionFit.summary(
                stringResource(R.string.companion_welcome_text), stringResource(R.string.companion_welcome_short), b.bodyLines, b.innerWidth, fit.metrics, stringResource(R.string.companion_see_main),
            )
            BodyText(text, Modifier.optional(60), color = colors.text2)
            Filler()
            if (context.hints.isNotEmpty()) HintList(context.hints.take(1), 0, Modifier.optional(70))
        }
    }
}
