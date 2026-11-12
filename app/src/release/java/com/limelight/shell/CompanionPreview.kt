package com.limelight.shell

import android.content.Intent
import androidx.compose.runtime.Composable
import com.limelight.shell.companion.CompanionState
import com.limelight.shell.settings.Appearance
import kotlinx.coroutines.flow.StateFlow

/** Release builds have no companion preview: the debug build's one is in `src/debug` (docs/DUAL_SCREEN.md). */
internal object CompanionPreview {
    fun from(@Suppress("UNUSED_PARAMETER") intent: Intent): (@Composable () -> Unit)? = null

    fun fakeState(@Suppress("UNUSED_PARAMETER") intent: Intent): CompanionState? = null

    /** The shell of a screen preview (debug builds only), whose state the companion window should show instead of the activity's own. */
    val liveShell: ShellState? get() = null

    /** The appearance of that preview (debug builds only), which the companion window should follow instead of the activity's own. */
    val liveAppearance: StateFlow<Appearance>? get() = null
}
