package com.limelight.shell

import android.app.Activity
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.limelight.shell.data.ProfileStore
import com.limelight.shell.settings.Appearance
import com.limelight.shell.settings.AppearanceStore
import com.limelight.shell.ui.DelosColors
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalReducedMotion
import com.limelight.shell.ui.Stage
import com.limelight.shell.ui.ToastCovers
import com.limelight.shell.ui.ToastHost
import com.limelight.shell.ui.ToastStage
import com.limelight.shell.ui.delosColors
import com.limelight.shell.ui.systemAccentHue
import com.limelight.utils.DelosToast
import java.util.WeakHashMap

/** Everything the toast hosts of one Activity share: the state they show, the stage they draw and which window draws it. */
internal class ToastSession(val state: ShellState) {
    val stage = ToastStage()
    private val covers = ToastCovers()

    /** The window that covers the stream window and draws the toast instead (null: the stream window's own host). Read by the hosts. */
    var cover by mutableStateOf<Any?>(null)
        private set

    fun ready(window: Any) {
        covers.ready(window)
        cover = covers.top
    }

    fun gone(window: Any) {
        covers.gone(window)
        cover = covers.top
    }
}

/** A toast host inside a window that covers the stream window; [release] hands the drawing back (also done when the window goes). */
class ToastCover internal constructor(val view: View, val release: () -> Unit)

/**
 * The toast of the stream and control windows: the shell's own console card, never a focusable or touchable window. It lives in the
 * Activity's decor view. A window that blurs or covers the stream (the stream menu, the exit) would blur it too, so [coverView] puts a second host
 * into that window, above everything in it; the two share one stage, only one is active at a time, and the toast carries on where it was.
 */
object DelosToastOverlay {
    private val sessions = WeakHashMap<Activity, ToastSession>()

    /** Call immediately after super.onCreate, before initialization can report a toast. */
    @JvmStatic
    fun attach(activity: ComponentActivity) {
        val session = ToastSession(ShellState(Screen.Start))
        sessions[activity] = session
        var root: FrameLayout? = null
        var compose: ComposeView? = null
        DelosToast.register(
            activity,
            show = { text, durationMs, kind -> session.state.showToast(text, durationMs = durationMs, kind = kind) },
            onStart = {
                // Wait for onStart: setContentView and the intended window geometry are now ready.
                val appearance = AppearanceStore(activity) { ProfileStore.currentSettings(activity) }.appearance.value
                val colors = delosColors(appearance, systemAccentHue(activity))
                val (overlay, view) = overlayView(activity) {
                    HostContent(session, appearance, colors, window = null)
                }
                view.setViewTreeLifecycleOwner(activity)
                view.setViewTreeViewModelStoreOwner(activity)
                view.setViewTreeSavedStateRegistryOwner(activity)
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
                sessions.remove(activity)
            },
        )
    }

    /**
     * The toast host for a window of [activity] that blurs or covers the stream (the stream menu's dialog, the exit's): add [ToastCover.view] to
     * that window above everything else (it takes no touch, key or focus) and call [ToastCover.release] when the window is gone. Null when the
     * Activity has no toast host (then there is no toast to keep sharp).
     */
    fun coverView(activity: Activity, context: Context, appearance: Appearance, colors: DelosColors): ToastCover? {
        val session = sessions[activity] ?: return null
        val token = Any()
        val (overlay, _) = overlayView(context) {
            // The window shows the toast from its first drawn frame on, so the stream window's host can stop in the same frame
            LaunchedEffect(token) {
                withFrameNanos { }
                session.ready(token)
            }
            DisposableEffect(token) { onDispose { session.gone(token) } }
            HostContent(session, appearance, colors, window = token)
        }
        return ToastCover(overlay) { session.gone(token) }
    }

    @Composable
    private fun HostContent(session: ToastSession, appearance: Appearance, colors: DelosColors, window: Any?) {
        CompositionLocalProvider(LocalDelosColors provides colors, LocalReducedMotion provides appearance.reducedMotion) {
            Stage(textScale = appearance.uiScale / 100f) {
                ToastHost(
                    session.state.toast, onHidden = session.state::hideToast, onAction = {},
                    toastStage = session.stage, active = session.cover === window,
                )
            }
        }
    }

    private fun overlayView(context: Context, content: @Composable () -> Unit): Pair<FrameLayout, ComposeView> {
        val view = ComposeView(context).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            isSoundEffectsEnabled = false
            setContent(content)
        }
        val overlay = object : FrameLayout(context) {
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
        overlay.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = ViewCompat.requestApplyInsets(v)
            override fun onViewDetachedFromWindow(v: View) = Unit
        })
        return overlay to view
    }
}
