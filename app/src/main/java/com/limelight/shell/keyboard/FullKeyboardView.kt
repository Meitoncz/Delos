@file:JvmName("FullKeyboardView")

package com.limelight.shell.keyboard

import android.content.Context
import android.view.ViewGroup
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import com.limelight.shell.data.ProfileStore
import com.limelight.shell.settings.AppearanceStore
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalReducedMotion
import com.limelight.shell.ui.delosColors
import com.limelight.shell.ui.systemAccentHue

/**
 * The view that draws the Full keyboard over the stream (`KeyBoardLayoutController` adds it to the stream's layout, or to the
 * external display's control screen): the theme and motion settings of the new UI, then [FullKeyboard]. It never takes the
 * focus, so the stream view keeps receiving the keys.
 */
fun createFullKeyboardView(context: Context, state: FullKeyboardState): ComposeView {
    val appearance = AppearanceStore(context) { ProfileStore.currentSettings(context) }.appearance.value
    val colors = delosColors(appearance, systemAccentHue(context))
    return ComposeView(context).apply {
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setContent {
            CompositionLocalProvider(LocalDelosColors provides colors, LocalReducedMotion provides appearance.reducedMotion) {
                FullKeyboard(state)
            }
        }
    }
}
