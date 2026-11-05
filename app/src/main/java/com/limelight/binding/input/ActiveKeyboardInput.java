package com.limelight.binding.input;

import android.view.View;

import java.lang.ref.WeakReference;

/** The current stream's keyboard capability; consumers never retain an Activity or session target. */
public final class ActiveKeyboardInput {
    public interface Target extends View.OnKeyListener {
        boolean isKeyboardInputConnected();
        void mouseButtonEvent(int buttonId, boolean down);
        void mouseMove(int deltaX, int deltaY);
    }

    private static WeakReference<Target> current = new WeakReference<>(null);

    private ActiveKeyboardInput() {}

    /** Registered at the stream Activity's existing onCreate ownership point. */
    public static synchronized void register(Target target) {
        current = new WeakReference<>(target);
    }

    /** A late old onDestroy must not clear the replacement stream's target. */
    public static synchronized void unregister(Target target) {
        if (current.get() == target) {
            current.clear();
        }
    }

    /** Resolve once per event; stopping a connection disables routing before Activity destruction. */
    public static synchronized Target connectedTarget() {
        Target target = current.get();
        return target != null && target.isKeyboardInputConnected() ? target : null;
    }
}
