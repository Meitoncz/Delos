package com.limelight.shell

/** Lifecycle-only state: removing the stopped launch subtree cannot replay its exit on resume. */
internal data class LaunchVisibilityState(
    val returningFromStream: Boolean = false,
    val suppressLaunchOverlay: Boolean = false,
) {
    fun stopped(hasLaunch: Boolean, diving: Boolean): LaunchVisibilityState =
        if (hasLaunch && diving) copy(returningFromStream = true, suppressLaunchOverlay = true) else this

    fun needsResumeCleanup(hasLaunch: Boolean, diving: Boolean): Boolean =
        returningFromStream || (hasLaunch && diving)

    /** Keep suppression after consuming return work; only an explicitly new launch can show it again. */
    fun resumed(hasLaunch: Boolean, diving: Boolean): LaunchVisibilityState =
        if (needsResumeCleanup(hasLaunch, diving)) copy(returningFromStream = false, suppressLaunchOverlay = true) else this

    fun newLaunch(): LaunchVisibilityState = LaunchVisibilityState()
}
