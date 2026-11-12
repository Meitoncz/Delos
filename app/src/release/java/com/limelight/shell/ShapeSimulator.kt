package com.limelight.shell

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import com.limelight.shell.settings.Appearance
import kotlinx.coroutines.flow.StateFlow

/** Release builds have no shape simulator: the debug build's one is in `src/debug` (docs/GUI.md, "Screen shapes"). */
internal class ShapeSimulator private constructor() {
    @Composable
    fun Host(@Suppress("UNUSED_PARAMETER") shell: ShellState, @Suppress("UNUSED_PARAMETER") appearance: StateFlow<Appearance>, content: @Composable () -> Unit) = content()

    companion object {
        fun from(@Suppress("UNUSED_PARAMETER") context: Context, @Suppress("UNUSED_PARAMETER") intent: Intent): ShapeSimulator? = null
    }
}
