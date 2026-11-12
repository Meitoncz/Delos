package com.limelight.binding.input;

import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.jni.MoonBridge;

import java.util.Locale;

/**
 * Builds the {@code ControllerSend} diagnostic lines (docs/CONTROLLER_STATE.md): one line for every controller state
 * packet that {@link ControllerHandler} hands to the connection, but only when it says something new.
 *
 * <ul>
 *   <li>A packet whose buttons or triggers differ from the previous one of its pad is always logged: a press or a
 *       release is the event the host must not miss.</li>
 *   <li>A packet that differs only in the sticks is logged at most every {@link #STICK_LINE_INTERVAL_MS}, with the
 *       number of stick-only packets left out since the last line, so a stick held in motion cannot flood the rolling
 *       log file.</li>
 *   <li>A packet identical to the previous one is counted, and every {@link #IDENTICAL_LINE_INTERVAL}th identical
 *       call is logged with that count.</li>
 * </ul>
 *
 * {@code #n} counts every call over all pads, logged or not, so a gap between two lines is the number of packets left
 * out. Pure Java, thread-safe (the packets come from the main thread, USB threads and the on-screen controller).
 */
final class ControllerSendLog {
    static final int IDENTICAL_LINE_INTERVAL = 50;
    static final long STICK_LINE_INTERVAL_MS = 250;

    private static final int PADS = 16;

    private static final class Pad {
        boolean sent;
        int buttons;
        byte leftTrigger, rightTrigger;
        short leftStickX, leftStickY, rightStickX, rightStickY;
        int identicalRun;
        int stickOnlyLeftOut;
        long lastLineMs;
    }

    private final Pad[] pads = new Pad[PADS];
    private long sequence;

    /**
     * Registers one packet and returns the line to log for it, or null when nothing new is to be said.
     *
     * @param nowMs a monotonic millisecond clock
     */
    synchronized String onSend(int pad, int buttons, byte leftTrigger, byte rightTrigger,
                               short leftStickX, short leftStickY, short rightStickX, short rightStickY, long nowMs) {
        long number = ++sequence;
        int index = pad & (PADS - 1);
        Pad state = pads[index];
        if (state == null) {
            state = pads[index] = new Pad();
        }

        String suffix = "";
        if (state.sent) {
            boolean buttonsOrTriggersChanged = state.buttons != buttons
                    || state.leftTrigger != leftTrigger || state.rightTrigger != rightTrigger;
            boolean sticksChanged = state.leftStickX != leftStickX || state.leftStickY != leftStickY
                    || state.rightStickX != rightStickX || state.rightStickY != rightStickY;
            if (!buttonsOrTriggersChanged && !sticksChanged) {
                state.identicalRun++;
                if (state.identicalRun % IDENTICAL_LINE_INTERVAL != 0) {
                    return null;
                }
                state.lastLineMs = nowMs;
                return format(number, index, buttons, leftTrigger, rightTrigger,
                        leftStickX, leftStickY, rightStickX, rightStickY, " identical=" + state.identicalRun);
            }
            state.identicalRun = 0;
            if (!buttonsOrTriggersChanged && nowMs - state.lastLineMs < STICK_LINE_INTERVAL_MS) {
                store(state, buttons, leftTrigger, rightTrigger, leftStickX, leftStickY, rightStickX, rightStickY);
                state.stickOnlyLeftOut++;
                return null;
            }
            if (state.stickOnlyLeftOut > 0) {
                suffix = " (+" + state.stickOnlyLeftOut + " stick-only packets not logged)";
            }
        }

        store(state, buttons, leftTrigger, rightTrigger, leftStickX, leftStickY, rightStickX, rightStickY);
        state.stickOnlyLeftOut = 0;
        state.lastLineMs = nowMs;
        return format(number, index, buttons, leftTrigger, rightTrigger,
                leftStickX, leftStickY, rightStickX, rightStickY, suffix);
    }

    private static void store(Pad pad, int buttons, byte leftTrigger, byte rightTrigger,
                              short leftStickX, short leftStickY, short rightStickX, short rightStickY) {
        pad.sent = true;
        pad.buttons = buttons;
        pad.leftTrigger = leftTrigger;
        pad.rightTrigger = rightTrigger;
        pad.leftStickX = leftStickX;
        pad.leftStickY = leftStickY;
        pad.rightStickX = rightStickX;
        pad.rightStickY = rightStickY;
    }

    private static String format(long number, int pad, int buttons, byte leftTrigger, byte rightTrigger,
                                 short leftStickX, short leftStickY, short rightStickX, short rightStickY, String suffix) {
        return String.format(Locale.ROOT, "ControllerSend #%d buttons=0x%04x lt=%d rt=%d lx=%d ly=%d rx=%d ry=%d pad %d%s",
                number, buttons, leftTrigger & 0xFF, rightTrigger & 0xFF,
                leftStickX, leftStickY, rightStickX, rightStickY, pad, suffix);
    }

    /** Readable names of the button flags set in [flags] ("RB+A"), or "none". */
    static String describeButtons(int flags) {
        if (flags == 0) {
            return "none";
        }
        StringBuilder out = new StringBuilder();
        append(out, flags, ControllerPacket.A_FLAG, "A");
        append(out, flags, ControllerPacket.B_FLAG, "B");
        append(out, flags, ControllerPacket.X_FLAG, "X");
        append(out, flags, ControllerPacket.Y_FLAG, "Y");
        append(out, flags, ControllerPacket.UP_FLAG, "UP");
        append(out, flags, ControllerPacket.DOWN_FLAG, "DOWN");
        append(out, flags, ControllerPacket.LEFT_FLAG, "LEFT");
        append(out, flags, ControllerPacket.RIGHT_FLAG, "RIGHT");
        append(out, flags, ControllerPacket.LB_FLAG, "LB");
        append(out, flags, ControllerPacket.RB_FLAG, "RB");
        append(out, flags, ControllerPacket.PLAY_FLAG, "START");
        append(out, flags, ControllerPacket.BACK_FLAG, "BACK");
        append(out, flags, ControllerPacket.LS_CLK_FLAG, "L3");
        append(out, flags, ControllerPacket.RS_CLK_FLAG, "R3");
        append(out, flags, ControllerPacket.SPECIAL_BUTTON_FLAG, "GUIDE");
        append(out, flags, ControllerPacket.PADDLE1_FLAG, "PADDLE1");
        append(out, flags, ControllerPacket.PADDLE2_FLAG, "PADDLE2");
        append(out, flags, ControllerPacket.PADDLE3_FLAG, "PADDLE3");
        append(out, flags, ControllerPacket.PADDLE4_FLAG, "PADDLE4");
        append(out, flags, ControllerPacket.TOUCHPAD_FLAG, "TOUCHPAD");
        append(out, flags, ControllerPacket.MISC_FLAG, "SHARE");
        return out.length() == 0 ? String.format(Locale.ROOT, "0x%04x", flags) : out.toString();
    }

    /** The name of a MoonBridge.LI_CTYPE_* controller type. */
    static String controllerType(byte type) {
        switch (type) {
            case MoonBridge.LI_CTYPE_UNKNOWN: return "UNKNOWN";
            case MoonBridge.LI_CTYPE_XBOX: return "XBOX";
            case MoonBridge.LI_CTYPE_PS: return "PS";
            case MoonBridge.LI_CTYPE_NINTENDO: return "NINTENDO";
            case MoonBridge.LI_CTYPE_STEAM: return "STEAM";
            default: return "type " + type;
        }
    }

    /** The names of the MoonBridge.LI_CCAP_* capabilities set in [capabilities] ("RUMBLE+GYRO"), or "none". */
    static String capabilities(short capabilities) {
        if (capabilities == 0) {
            return "none";
        }
        StringBuilder out = new StringBuilder();
        append(out, capabilities, MoonBridge.LI_CCAP_ANALOG_TRIGGERS, "ANALOG_TRIGGERS");
        append(out, capabilities, MoonBridge.LI_CCAP_RUMBLE, "RUMBLE");
        append(out, capabilities, MoonBridge.LI_CCAP_TRIGGER_RUMBLE, "TRIGGER_RUMBLE");
        append(out, capabilities, MoonBridge.LI_CCAP_TOUCHPAD, "TOUCHPAD");
        append(out, capabilities, MoonBridge.LI_CCAP_ACCEL, "ACCEL");
        append(out, capabilities, MoonBridge.LI_CCAP_GYRO, "GYRO");
        append(out, capabilities, MoonBridge.LI_CCAP_BATTERY_STATE, "BATTERY");
        append(out, capabilities, MoonBridge.LI_CCAP_RGB_LED, "RGB_LED");
        append(out, capabilities, MoonBridge.LI_CCAP_DUAL_TOUCHPAD, "DUAL_TOUCHPAD");
        return out.toString();
    }

    private static void append(StringBuilder out, int flags, int flag, String name) {
        if ((flags & flag) != 0) {
            if (out.length() > 0) {
                out.append('+');
            }
            out.append(name);
        }
    }
}
