package com.limelight.shell

import com.limelight.shell.companion.CompanionState

/** Debug builds only: made-up companion states of one area, by the name of `preview_state` / `companion_fake` (see CompanionPreview). */
internal object CompanionFakesPc {
    fun state(name: String): CompanionState? = null
}
