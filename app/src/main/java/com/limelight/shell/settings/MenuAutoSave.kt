package com.limelight.shell.settings

/** The stream menu's settings that are kept for the running game as the user sets them (their last value wins). */
enum class MenuSavedSetting { UPSCALER, GYRO_SMOOTHING }

/**
 * Keeps what the stream menu sets as the game's own per-game overrides: a change only marks the setting, and its
 * live value is written once, [delayMs] after the last change (a slider drag or a held D-pad writes once, when it
 * settles), or at once by [flush] (menu closed, stream ended). Nothing is written without a change, so merely opening
 * the menu writes nothing, and the profile is never touched: [write] receives keys of [GameSettingsStore.KEYS] only.
 * Time comes from [clock] so the logic is testable; the host calls [tick] when [nextDelay] has passed.
 */
class MenuAutoSave(
    private val delayMs: Long,
    private val clock: () -> Long,
    private val values: (MenuSavedSetting) -> Map<String, Any>,
    private val write: (Map<String, Any>) -> Unit,
) {
    private val changedAt = LinkedHashMap<MenuSavedSetting, Long>()

    fun changed(setting: MenuSavedSetting) { changedAt[setting] = clock() }

    /** Milliseconds until the first pending setting is due (0 when it already is), or null when nothing is pending. */
    fun nextDelay(): Long? = changedAt.values.minOrNull()?.let { (it + delayMs - clock()).coerceAtLeast(0) }

    /** Writes the settings whose last change is at least [delayMs] old, in one write. */
    fun tick() = writeOnly(changedAt.filterValues { clock() - it >= delayMs }.keys)

    /** Writes every pending setting now. */
    fun flush() = writeOnly(changedAt.keys.toList())

    private fun writeOnly(due: Collection<MenuSavedSetting>) {
        if (due.isEmpty()) return
        val merged = LinkedHashMap<String, Any>()
        due.forEach { merged += values(it) }
        due.forEach { changedAt.remove(it) }
        write(merged)
    }

    companion object { const val DELAY_MS = 500L }
}
