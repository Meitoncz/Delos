package com.limelight.utils;

/**
 * The rumble feels offered in Settings ("Rumble feel"), the Debug laboratory and the stream menu.
 * The enum names are the identities saved in {@link RumblePreferences#PRESET}; never rename them.
 * Profiles saved under an identity that is no longer listed load as Custom.
 * The names and descriptions shown to the user are string resources (see {@code RumbleText.kt}), not part of the enum.
 */
public enum RumblePresets {
    ORIGINAL(false, RumbleTranslator.ORIGINAL),
    GENTLE(true, new RumbleTranslator.Profile(.7f, 1.3f, 0, 0, .80f, .33f, 0f)),
    KICK_ONLY(true, new RumbleTranslator.Profile(1f, 1f, 0, 100, .80f, .33f, 0f)),
    PULSE_25(true, pulse(1f, .8f, 50, 0, 20, 20));

    private static RumbleTranslator.Profile pulse(float gain, float curve, int floor, int kickMs, int onMs, int offMs) {
        return new RumbleTranslator.Profile(gain, curve, floor, kickMs, 1f, .35f, 0f,
                RumbleTechnique.PULSE, onMs, offMs);
    }

    public final boolean enabled;
    public final RumbleTranslator.Profile profile;

    RumblePresets(boolean enabled, RumbleTranslator.Profile profile) {
        this.enabled = enabled;
        this.profile = profile;
    }
}
