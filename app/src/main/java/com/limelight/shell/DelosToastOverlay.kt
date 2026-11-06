package com.limelight.shell

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.limelight.shell.data.ProfileStore
import com.limelight.shell.settings.AppearanceStore
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalReducedMotion
import com.limelight.shell.ui.Stage
import com.limelight.shell.ui.ToastHost
import com.limelight.shell.ui.delosColors
import com.limelight.shell.ui.systemAccentHue
import com.limelight.utils.DelosToast

/** The toast of the stream and control windows: the shell's own console card, never a focusable or touchable window. */
object DelosToastOverlay {
    /** Call immediately after super.onCreate, before initialization can report a toast. */
    @JvmStatic
    fun attach(activity: ComponentActivity) {
        val state = ShellState(Screen.Start)
        var root: FrameLayout? = null
        var compose: ComposeView? = null
        DelosToast.register(
            activity,
            show = { text, durationMs, kind -> state.showToast(text, durationMs = durationMs, kind = kind) },
            onStart = {
                // Wait for onStart: setContentView and the intended window geometry are now ready.
                val view = ComposeView(activity).apply {
                    isFocusable = false
                    isFocusableInTouchMode = false
                    isSoundEffectsEnabled = false
                    setViewTreeLifecycleOwner(activity)
                    setViewTreeViewModelStoreOwner(activity)
                    setViewTreeSavedStateRegistryOwner(activity)
                }
                val overlay = object : FrameLayout(activity) {
                    // Reject dispatch over the ENTIRE overlay, including the toast itself.
                    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false
                    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = false
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean = false
                }.apply {
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
                ViewCompat.setOnApplyWindowInsetsListener(overlay) { _, insets ->
                    val safe = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
                    overlay.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                    insets
                }
                val appearance = AppearanceStore(activity) { ProfileStore.currentSettings(activity) }.appearance.value
                val colors = delosColors(appearance, systemAccentHue(activity))
                view.setContent {
                    CompositionLocalProvider(LocalDelosColors provides colors, LocalReducedMotion provides appearance.reducedMotion) {
                        Stage(textScale = appearance.uiScale / 100f) {
                            ToastHost(state.toast, onHidden = state::hideToast, onAction = {})
                        }
                    }
                }
                // A decor sibling of the content stays above the loader/stream even when they add
                // children later. The stream menu is its own dialog above this window; it cannot
                // destroy or reset this host when runWhenStreamFocused closes the menu.
                (activity.window.decorView as ViewGroup).addView(
                    overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
                ViewCompat.requestApplyInsets(overlay)
                root = overlay
                compose = view
            },
            onStop = {
                (root?.parent as? ViewGroup)?.removeView(root)
                compose?.disposeComposition()
                root = null
                compose = null
            },
            onDestroy = {
                (root?.parent as? ViewGroup)?.removeView(root)
                compose?.disposeComposition()
                root = null
                compose = null
            },
        )
    }
}
