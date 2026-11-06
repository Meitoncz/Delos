package com.limelight.shell

import android.content.Intent
import androidx.compose.runtime.Composable

/** Release builds have no toast preview: the debug build's one is in `src/debug` (docs/GUI.md). */
internal object ToastPreview {
    fun from(@Suppress("UNUSED_PARAMETER") intent: Intent, @Suppress("UNUSED_PARAMETER") shell: ShellState): (@Composable () -> Unit)? = null
}
