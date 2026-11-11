package com.limelight.shell

import android.content.Intent
import androidx.compose.runtime.Composable

/** Release builds have no screen previews: the debug build's ones are in `src/debug` (docs/GUI.md). */
internal object ScreenPreviews {
    fun from(@Suppress("UNUSED_PARAMETER") intent: Intent, @Suppress("UNUSED_PARAMETER") shell: ShellState): (@Composable () -> Unit)? = null
}
