package com.limelight.shell

import android.content.Intent
import androidx.compose.runtime.Composable

/** Release builds have no per-game settings preview: the debug build's one is in `src/debug` (docs/GUI.md). */
internal object GameSettingsPreview {
    fun from(@Suppress("UNUSED_PARAMETER") intent: Intent): (@Composable () -> Unit)? = null
}
