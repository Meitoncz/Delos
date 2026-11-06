package com.limelight.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.lang.ref.WeakReference

/** Every user-facing toast uses the Delos host whenever one of its windows is visible. */
object DelosToast {
    const val SHORT = 0
    const val LONG = 1

    private val main = Handler(Looper.getMainLooper())
    private val routing = ToastRouting<Host>()

    @JvmStatic
    fun show(context: Context, text: CharSequence) = show(context, text, SHORT)

    @JvmStatic
    fun show(context: Context, @StringRes text: Int) = show(context, text, SHORT)

    @JvmStatic
    fun show(context: Context, @StringRes text: Int, duration: Int) = show(context, text, duration, ToastKind.INFO)

    @JvmStatic
    fun show(context: Context, text: CharSequence, duration: Int) = show(context, text, duration, ToastKind.INFO)

    /** [kind] picks the colour, word and icon of the toast (a toast says INFO unless its caller knows better). */
    @JvmStatic
    fun show(context: Context, @StringRes text: Int, duration: Int, kind: ToastKind) {
        dispatch(context, duration, kind) { it.getText(text).toString() }
    }

    @JvmStatic
    fun show(context: Context, text: CharSequence, duration: Int, kind: ToastKind) {
        val message = text.toString()
        dispatch(context, duration, kind) { message }
    }

    private fun dispatch(context: Context, duration: Int, kind: ToastKind, message: (Context) -> String) {
        // Queued work must not retain a finishing Activity. Resolve foreground UI only on main.
        val requested = WeakReference(context)
        val app = context.applicationContext
        val deliver = Runnable {
            val source = requested.get() ?: app
            val activity = source.activity()
            val host = routing.select { it.activity === activity }
            val text = message(source)
            if (host != null) host.show(text, toastDurationMs(duration), kind)
            else android.widget.Toast.makeText(app, text, duration).show()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) deliver.run() else main.post(deliver)
    }

    /** The Activity owns this observer; the global router holds only a weak reference to it. */
    internal fun register(
        activity: ComponentActivity,
        show: (String, Long, ToastKind) -> Unit,
        onStart: () -> Unit = {},
        onStop: () -> Unit = {},
        onDestroy: () -> Unit = {},
    ) {
        val host = Host(activity, show, onStart, onStop, onDestroy)
        routing.register(host)
        activity.lifecycle.addObserver(host)
    }

    private class Host(
        val activity: ComponentActivity,
        val show: (String, Long, ToastKind) -> Unit,
        val started: () -> Unit,
        val stopped: () -> Unit,
        val destroyed: () -> Unit,
    ) : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            routing.start(this)
            started()
        }

        override fun onResume(owner: LifecycleOwner) = routing.resume(this)
        override fun onPause(owner: LifecycleOwner) = routing.pause(this)

        override fun onStop(owner: LifecycleOwner) {
            routing.stop(this)
            stopped()
        }

        override fun onDestroy(owner: LifecycleOwner) {
            routing.remove(this)
            destroyed()
            owner.lifecycle.removeObserver(this)
        }
    }

    private fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> if (baseContext !== this) baseContext.activity() else null
        else -> null
    }
}
