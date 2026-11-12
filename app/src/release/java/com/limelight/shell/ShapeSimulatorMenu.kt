package com.limelight.shell

import android.content.Context
import com.limelight.shell.screens.DebugToolRow

/** Release builds have no shape simulator rows in Debug info: the debug build's are in `src/debug` (docs/GUI.md, "Screen shapes"). */
internal object ShapeSimulatorMenu {
    const val SECTION_TITLE = ""

    @Suppress("UNUSED_PARAMETER")
    fun rows(shell: ShellState, tracker: FocusTracker, context: Context): List<DebugToolRow> = emptyList()
}
