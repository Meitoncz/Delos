package com.limelight.binding.input;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Looper;
import android.util.SparseArray;
import android.view.KeyEvent;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.LimeLog;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.shadows.ShadowGameManager;
import com.limelight.shadows.ShadowMoonBridge;
import com.limelight.ui.GameGestures;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * What ControllerHandler hands to the connection for a button press and its release (docs/CONTROLLER_STATE.md), the
 * ControllerSend lines it writes for that, and the optional state refresh on the real handler.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = {ShadowMoonBridge.class, ShadowGameManager.class})
public class ControllerHandlerSendTest {
    private NvConnection conn;
    private PreferenceConfiguration prefs;
    private ControllerHandler handler;
    private final List<String> lines = new ArrayList<>();
    private final java.util.logging.Handler capture = new java.util.logging.Handler() {
        @Override public void publish(LogRecord record) {
            synchronized (lines) {
                lines.add(record.getMessage());
            }
        }

        @Override public void flush() { }

        @Override public void close() { }
    };

    @Before
    public void setUp() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        conn = mock(NvConnection.class);
        prefs = PreferenceConfiguration.readPreferences(ApplicationProvider.getApplicationContext());
        handler = new ControllerHandler(activity, conn, mock(GameGestures.class), prefs);
        // The Shield controller extensions bind a service that does not exist here; Robolectric answers the bind with a
        // null binder the first time the looper runs. Let that happen now, not in the middle of a test.
        try {
            shadowOf(Looper.getMainLooper()).idle();
        } catch (NullPointerException expectedNullBinder) {
            // nothing else is pending
        }
        Logger.getLogger(LimeLog.class.getName()).addHandler(capture);
    }

    @After
    public void tearDown() {
        Logger.getLogger(LimeLog.class.getName()).removeHandler(capture);
        handler.destroy();
    }

    // Device id 0 is the handler's default context: the one that needs no InputDevice
    private static KeyEvent key(int action, int keyCode, long downTime, long eventTime) {
        return new KeyEvent(downTime, eventTime, action, keyCode, 0, 0, 0, 0);
    }

    private List<String> controllerSendLines() {
        synchronized (lines) {
            List<String> out = new ArrayList<>();
            for (String line : lines) {
                if (line != null && line.startsWith("ControllerSend ")) {
                    out.add(line);
                }
            }
            return out;
        }
    }

    @Test
    public void aPressAndItsReleaseReachTheConnectionInOrder() {
        handler.handleButtonDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R1, 0, 0));
        handler.handleButtonUp(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_R1, 0, 80));

        InOrder order = inOrder(conn);
        order.verify(conn).sendControllerInput(eq((short) 0), anyShort(), eq(ControllerPacket.RB_FLAG),
                eq((byte) 0), eq((byte) 0), eq((short) 0), eq((short) 0), eq((short) 0), eq((short) 0));
        order.verify(conn).sendControllerInput(eq((short) 0), anyShort(), eq(0),
                eq((byte) 0), eq((byte) 0), eq((short) 0), eq((short) 0), eq((short) 0), eq((short) 0));
        order.verifyNoMoreInteractions();
    }

    @Test
    public void everyPacketAndItsReleaseIsWrittenAsAControllerSendLine() {
        handler.handleButtonDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R1, 0, 0));
        handler.handleButtonUp(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_R1, 0, 80));

        assertEquals(List.of(
                "ControllerSend #1 buttons=0x0200 lt=0 rt=0 lx=0 ly=0 rx=0 ry=0 pad 0",
                "ControllerSend #2 buttons=0x0000 lt=0 rt=0 lx=0 ly=0 rx=0 ry=0 pad 0"), controllerSendLines());
    }

    /** A controller context that holds [buttons] and has a sensor manager that offers a gyro, like a pad with sensors. */
    @SuppressWarnings("unchecked")
    private ControllerHandler.InputDeviceContext addMotionContext(int deviceId, int buttons) throws Exception {
        SensorManager sensors = mock(SensorManager.class);
        when(sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)).thenReturn(mock(Sensor.class));
        ControllerHandler.InputDeviceContext context = handler.new InputDeviceContext();
        context.assignedControllerNumber = true;
        context.controllerNumber = 0;
        context.inputMap = buttons;
        context.sensorManager = sensors;
        Field field = ControllerHandler.class.getDeclaredField("inputDeviceContexts");
        field.setAccessible(true);
        ((SparseArray<ControllerHandler.InputDeviceContext>) field.get(handler)).put(deviceId, context);
        return context;
    }

    private void hostSwitchesGyro(int reportRateHz) {
        handler.handleSetMotionEventState((short) 0, MoonBridge.LI_MOTION_TYPE_GYRO, (short) reportRateHz);
    }

    private void idle(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    @Test
    public void withTheSettingOffNoStateIsEverResentWhileMotionEventsRun() throws Exception {
        prefs.gyroStateRefresh = false;
        addMotionContext(7, ControllerPacket.RB_FLAG);
        hostSwitchesGyro(200);
        idle(2000);
        verify(conn, never()).sendControllerInput(anyShort(), anyShort(), anyInt(),
                anyByte(), anyByte(), anyShort(), anyShort(), anyShort(), anyShort());
    }

    @Test
    public void withTheSettingOnTheHeldStateIsResentUnchangedWhileMotionEventsRun() throws Exception {
        prefs.gyroStateRefresh = true;
        addMotionContext(7, ControllerPacket.RB_FLAG);
        hostSwitchesGyro(200);
        idle(10 * ControllerStateRefresh.INTERVAL_MS + 5);

        verify(conn, atLeast(9)).sendControllerInput(eq((short) 0), anyShort(), eq(ControllerPacket.RB_FLAG),
                eq((byte) 0), eq((byte) 0), eq((short) 0), eq((short) 0), eq((short) 0), eq((short) 0));
        // Never anything but the state the user holds: no other buttons, no stick or trigger values
        verify(conn, never()).sendControllerInput(anyShort(), anyShort(), intThat(b -> b != ControllerPacket.RB_FLAG),
                anyByte(), anyByte(), anyShort(), anyShort(), anyShort(), anyShort());
    }

    @Test
    public void aReleaseWhileTheRefreshRunsIsFollowedByTheReleasedStateNotTheOldOne() throws Exception {
        prefs.gyroStateRefresh = true;
        ControllerHandler.InputDeviceContext context = addMotionContext(7, ControllerPacket.RB_FLAG);
        hostSwitchesGyro(200);
        idle(3 * ControllerStateRefresh.INTERVAL_MS + 5);

        context.inputMap = 0;
        clearInvocations(conn);
        idle(3 * ControllerStateRefresh.INTERVAL_MS + 5);

        verify(conn, atLeast(2)).sendControllerInput(eq((short) 0), anyShort(), eq(0),
                eq((byte) 0), eq((byte) 0), eq((short) 0), eq((short) 0), eq((short) 0), eq((short) 0));
        verify(conn, never()).sendControllerInput(anyShort(), anyShort(), eq(ControllerPacket.RB_FLAG),
                anyByte(), anyByte(), anyShort(), anyShort(), anyShort(), anyShort());
    }

    @Test
    public void theRefreshEndsWhenTheHostSwitchesMotionEventsOffAndWhenTheHandlerStops() throws Exception {
        prefs.gyroStateRefresh = true;
        addMotionContext(7, ControllerPacket.A_FLAG);
        hostSwitchesGyro(200);
        idle(2 * ControllerStateRefresh.INTERVAL_MS + 5);
        verify(conn, atLeast(1)).sendControllerInput(anyShort(), anyShort(), anyInt(),
                anyByte(), anyByte(), anyShort(), anyShort(), anyShort(), anyShort());

        // The host asks for no more motion events
        hostSwitchesGyro(0);
        idle(ControllerStateRefresh.INTERVAL_MS + 5);
        clearInvocations(conn);
        idle(2000);
        verify(conn, never()).sendControllerInput(anyShort(), anyShort(), anyInt(),
                anyByte(), anyByte(), anyShort(), anyShort(), anyShort(), anyShort());

        // Back on, then the stream ends: stop() cancels it
        hostSwitchesGyro(200);
        idle(ControllerStateRefresh.INTERVAL_MS + 5);
        verify(conn, atLeast(1)).sendControllerInput(anyShort(), anyShort(), anyInt(),
                anyByte(), anyByte(), anyShort(), anyShort(), anyShort(), anyShort());
        handler.stop();
        clearInvocations(conn);
        idle(2000);
        verify(conn, never()).sendControllerInput(anyShort(), anyShort(), eq(ControllerPacket.A_FLAG),
                anyByte(), anyByte(), anyShort(), anyShort(), anyShort(), anyShort());
    }
}
