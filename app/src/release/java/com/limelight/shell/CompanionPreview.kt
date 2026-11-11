package com.limelight.shell

import android.content.Intent
import androidx.compose.runtime.Composable
import com.limelight.shell.companion.CompanionState

/** Release builds have no companion preview: the debug build's one is in `src/debug` (docs/DUAL_SCREEN.md). */
internal object CompanionPreview {
    fun from(@Suppress("UNUSED_PARAMETER") intent: Intent): (@Composable () -> Unit)? = null

    fun fakeState(@Suppress("UNUSED_PARAMETER") intent: Intent): CompanionState? = null
}
