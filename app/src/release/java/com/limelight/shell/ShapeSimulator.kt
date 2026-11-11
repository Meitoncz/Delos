package com.limelight.shell

import android.content.Intent
import androidx.compose.runtime.Composable

/** Release builds have no shape simulator: the debug build's one is in `src/debug` (docs/GUI.md, "Screen shapes"). */
internal class ShapeSimulator private constructor() {
    @Composable
    fun Host(content: @Composable () -> Unit) = content()

    companion object {
        fun from(@Suppress("UNUSED_PARAMETER") intent: Intent): ShapeSimulator? = null
    }
}
