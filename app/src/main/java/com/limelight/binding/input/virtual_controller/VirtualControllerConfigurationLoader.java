/**
 * Created by Karim Mreisi.
 */

package com.limelight.binding.input.virtual_controller;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.DisplayMetrics;

import com.limelight.R;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.preferences.PreferenceConfiguration;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;

public class VirtualControllerConfigurationLoader {
    public static final String OSC_PREFERENCE = "OSC";

    private static DigitalPad createDigitalPad(
            final VirtualController controller,
            final Context context) {

        DigitalPad digitalPad = new DigitalPad(controller, context);
        digitalPad.addDigitalPadListener(new DigitalPad.DigitalPadListener() {
            @Override
            public void onDirectionChange(int direction) {
                VirtualController.ControllerInputContext inputContext =
                        controller.getControllerInputContext();

                if ((direction & DigitalPad.DIGITAL_PAD_DIRECTION_LEFT) != 0) {
                    inputContext.inputMap |= ControllerPacket.LEFT_FLAG;
                }
                else {
                    inputContext.inputMap &= ~ControllerPacket.LEFT_FLAG;
                }
                if ((direction & DigitalPad.DIGITAL_PAD_DIRECTION_RIGHT) != 0) {
                    inputContext.inputMap |= ControllerPacket.RIGHT_FLAG;
                }
                else {
                    inputContext.inputMap &= ~ControllerPacket.RIGHT_FLAG;
                }
                if ((direction & DigitalPad.DIGITAL_PAD_DIRECTION_UP) != 0) {
                    inputContext.inputMap |= ControllerPacket.UP_FLAG;
                }
                else {
                    inputContext.inputMap &= ~ControllerPacket.UP_FLAG;
                }
                if ((direction & DigitalPad.DIGITAL_PAD_DIRECTION_DOWN) != 0) {
                    inputContext.inputMap |= ControllerPacket.DOWN_FLAG;
                }
                else {
                    inputContext.inputMap &= ~ControllerPacket.DOWN_FLAG;
                }

                controller.sendControllerInputContext(10, 0x22);
            }
        });

        return digitalPad;
    }

    private static DigitalButton createDigitalButton(
            final int elementId,
            final int keyShort,
            final int keyLong,
            final int layer,
            final String text,
            final VirtualController controller,
            final Context context) {
        DigitalButton button = new DigitalButton(controller, elementId, layer, context);
        button.setText(text);
        button.addDigitalButtonListener(new DigitalButton.DigitalButtonListener() {
            @Override
            public void onClick() {
                VirtualController.ControllerInputContext inputContext =
                        controller.getControllerInputContext();
                inputContext.inputMap |= keyShort;

                controller.sendControllerInputContext();
            }

            @Override
            public void onLongClick() {
                VirtualController.ControllerInputContext inputContext =
                        controller.getControllerInputContext();
                inputContext.inputMap |= keyLong;

                controller.sendControllerInputContext();
            }

            @Override
            public void onRelease() {
                VirtualController.ControllerInputContext inputContext =
                        controller.getControllerInputContext();
                inputContext.inputMap &= ~keyShort;
                inputContext.inputMap &= ~keyLong;

                controller.sendControllerInputContext();
            }
        });

        return button;
    }

    private static DigitalButton createLeftTrigger(
            final int layer,
            final String text,
            final VirtualController controller,
            final Context context) {
        LeftTrigger button = new LeftTrigger(controller, layer, context);
        button.setText(text);
        return button;
    }

    private static DigitalButton createRightTrigger(
            final int layer,
            final String text,
            final VirtualController controller,
            final Context context) {
        RightTrigger button = new RightTrigger(controller, layer, context);
        button.setText(text);
        return button;
    }

    private static AnalogStick createLeftStick(
            final VirtualController controller,
            final Context context) {
        return new LeftAnalogStick(controller, context);
    }

    private static AnalogStick createRightStick(
            final VirtualController controller,
            final Context context) {
        return new RightAnalogStick(controller, context);
    }

    private static AnalogStickFree createLeftStick2(
            final VirtualController controller,
            final Context context) {
        return new LeftAnalogStickFree(controller, context);
    }

    private static AnalogStickFree createRightStick2(
            final VirtualController controller,
            final Context context) {
        return new RightAnalogStickFree(controller, context);
    }


    /** The default layout on the screen the controls are drawn on (the window's size, from the resources). */
    public static void createDefaultLayout(final VirtualController controller, final Context context) {
        DisplayMetrics screen = context.getResources().getDisplayMetrics();
        createDefaultLayout(controller, context, screen.widthPixels, screen.heightPixels);
    }

    /**
     * The default layout on a screen of {@code width} x {@code height} pixels. The positions come from {@link OscDefaultLayout}
     * (docs/GUI.md, "Screen shapes"): the 16:9 layout of the prototype from 3:2 up, shrunk to fit on squarer screens.
     */
    public static void createDefaultLayout(final VirtualController controller, final Context context, int width, int height) {
        createDefaultLayout(controller, context, width, height, PreferenceConfiguration.readPreferences(context));
    }

    /** The same with the settings given (the debug previews cannot read them: that loads the native library, which the emulator cannot). */
    public static void createDefaultLayout(final VirtualController controller, final Context context, int width, int height,
                                           PreferenceConfiguration config) {
        OscGeometry.Panel panel = new OscGeometry.Panel(width, height);
        Map<Integer, OscDefaultLayout.Slot> slots = OscDefaultLayout.compute(panel, config.onlyL3R3, config.showGuideButton);

        if (!config.onlyL3R3)
        {
            add(controller, slots, VirtualControllerElement.EID_DPAD, createDigitalPad(controller, context));

            add(controller, slots, VirtualControllerElement.EID_A, createDigitalButton(
                    VirtualControllerElement.EID_A,
                    !config.flipFaceButtons ? ControllerPacket.A_FLAG : ControllerPacket.B_FLAG, 0, 1,
                    !config.flipFaceButtons ? "A" : "B",  controller, context));

            add(controller, slots, VirtualControllerElement.EID_B, createDigitalButton(
                    VirtualControllerElement.EID_B,
                    config.flipFaceButtons ? ControllerPacket.A_FLAG : ControllerPacket.B_FLAG, 0, 1,
                    config.flipFaceButtons ? "A" : "B",  controller, context));

            add(controller, slots, VirtualControllerElement.EID_X, createDigitalButton(
                    VirtualControllerElement.EID_X,
                    !config.flipFaceButtons ? ControllerPacket.X_FLAG : ControllerPacket.Y_FLAG, 0, 1,
                    !config.flipFaceButtons ? "X" : "Y",  controller, context));

            add(controller, slots, VirtualControllerElement.EID_Y, createDigitalButton(
                    VirtualControllerElement.EID_Y,
                    config.flipFaceButtons ? ControllerPacket.X_FLAG : ControllerPacket.Y_FLAG, 0, 1,
                    config.flipFaceButtons ? "X" : "Y",  controller, context));

            add(controller, slots, VirtualControllerElement.EID_LT, createLeftTrigger(1, "LT",  controller, context));
            add(controller, slots, VirtualControllerElement.EID_RT, createRightTrigger(1, "RT",  controller, context));

            add(controller, slots, VirtualControllerElement.EID_LB, createDigitalButton(
                    VirtualControllerElement.EID_LB,
                    ControllerPacket.LB_FLAG, 0, 1, "LB",  controller, context));

            add(controller, slots, VirtualControllerElement.EID_RB, createDigitalButton(
                    VirtualControllerElement.EID_RB,
                    ControllerPacket.RB_FLAG, 0, 1, "RB",  controller, context));

            if(config.enableNewAnalogStick){
                add(controller, slots, VirtualControllerElement.EID_LS, createLeftStick2(controller, context));
                add(controller, slots, VirtualControllerElement.EID_RS, createRightStick2(controller, context));
            }else{
                add(controller, slots, VirtualControllerElement.EID_LS, createLeftStick(controller, context));
                add(controller, slots, VirtualControllerElement.EID_RS, createRightStick(controller, context));
            }

            add(controller, slots, VirtualControllerElement.EID_BACK, createDigitalButton(
                    VirtualControllerElement.EID_BACK,
                    ControllerPacket.BACK_FLAG, 0, 2, "BACK",  controller, context));

            add(controller, slots, VirtualControllerElement.EID_START, createDigitalButton(
                    VirtualControllerElement.EID_START,
                    ControllerPacket.PLAY_FLAG, 0, 3, "START",  controller, context));

            add(controller, slots, VirtualControllerElement.EID_LSB, createDigitalButton(
                    VirtualControllerElement.EID_LSB,
                    ControllerPacket.LS_CLK_FLAG, 0, 1, "L3",  controller, context));

            add(controller, slots, VirtualControllerElement.EID_RSB, createDigitalButton(
                    VirtualControllerElement.EID_RSB,
                    ControllerPacket.RS_CLK_FLAG, 0, 1, "R3",  controller, context));

            add(controller, slots, VirtualControllerElement.EID_TOUCHPAD, createDigitalButton(
                    VirtualControllerElement.EID_TOUCHPAD,
                    ControllerPacket.TOUCHPAD_FLAG, 0, 1, "TRACKPAD",  controller, context));
        }
        else {
            add(controller, slots, VirtualControllerElement.EID_LSB, createDigitalButton(
                    VirtualControllerElement.EID_LSB,
                    ControllerPacket.LS_CLK_FLAG, 0, 1, "L3", controller, context));

            add(controller, slots, VirtualControllerElement.EID_RSB, createDigitalButton(
                    VirtualControllerElement.EID_RSB,
                    ControllerPacket.RS_CLK_FLAG, 0, 1, "R3",  controller, context));
        }

        if(config.showGuideButton){
            add(controller, slots, VirtualControllerElement.EID_GDB, createDigitalButton(VirtualControllerElement.EID_GDB,
                    ControllerPacket.SPECIAL_BUTTON_FLAG, 0, 1, "GUIDE",  controller, context));
        }

        controller.setOpacity(config.oscOpacity);
    }

    private static void add(VirtualController controller, Map<Integer, OscDefaultLayout.Slot> slots, int elementId,
                            VirtualControllerElement element) {
        OscDefaultLayout.Slot slot = slots.get(elementId);
        controller.addElement(element, slot.left, slot.top, slot.width, slot.height);
    }

    public static void saveProfile(final VirtualController controller,
                                   final Context context) {
        // Delos: editable layouts belong to the active user profile.
        SharedPreferences.Editor prefEditor = com.limelight.shell.settings.ProfileLayouts.preferences(context, OSC_PREFERENCE).edit();

        for (VirtualControllerElement element : controller.getElements()) {
            String prefKey = ""+element.elementId;
            try {
                prefEditor.putString(prefKey, element.getConfiguration().toString());
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        prefEditor.apply();
    }

    public static void loadFromPreferences(final VirtualController controller, final Context context) {
        // Delos: editable layouts belong to the active user profile.
        SharedPreferences pref = com.limelight.shell.settings.ProfileLayouts.preferences(context, OSC_PREFERENCE);

        for (VirtualControllerElement element : controller.getElements()) {
            String prefKey = ""+element.elementId;

            String jsonConfig = pref.getString(prefKey, null);
            if (jsonConfig != null) {
                try {
                    element.loadConfiguration(new JSONObject(jsonConfig));
                } catch (JSONException e) {
                    e.printStackTrace();

                    // Remove the corrupt element from the preferences
                    pref.edit().remove(prefKey).apply();
                }
            }
        }
    }
}
