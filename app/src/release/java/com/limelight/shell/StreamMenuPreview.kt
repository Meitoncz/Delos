package com.limelight.shell

import android.content.Intent
import androidx.compose.runtime.Composable

/** Release builds have no stream menu preview: the debug build's one is in `src/debug` (docs/GUI.md). */
internal object StreamMenuPreview {
    fun from(@Suppress("UNUSED_PARAMETER") intent: Intent): (@Composable () -> Unit)? = null
}
