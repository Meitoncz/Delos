package com.limelight.utils;

/** Named laboratory profiles for comparing host-rumble translation on real hardware. */
public enum RumblePresets {
    ORIGINAL("Original (off)", "Translator off; keep the original rumble response.",
            false, RumbleTranslator.ORIGINAL),
    STRONGER("Stronger", "Boost the overall rumble strength without changing its response curve.",
            true, new RumbleTranslator.Profile(1.5f, 1f, 0, 0, .80f, .33f, 0f)),
    ALWAYS_FELT("Always felt", "Lift weak requests to a higher minimum amplitude.",
            true, new RumbleTranslator.Profile(1f, 1f, 140, 0, .80f, .33f, 0f)),
    DEEP_RUMBLE("Deep rumble", "Emphasize the low host motor and reduce the high motor contribution.",
            true, new RumbleTranslator.Profile(1f, .8f, 170, 0, 1f, .15f, 0f)),
    SHARP_IMPACTS("Sharp impacts", "Add a short full-strength start kick to emphasize impacts.",
            true, new RumbleTranslator.Profile(1f, 1f, 150, 60, .60f, .50f, 0f)),
    SMOOTH_DEEP("Smooth deep", "Favor the low host motor with gentler changes in amplitude.",
            true, new RumbleTranslator.Profile(1f, .9f, 160, 0, 1f, .20f, .6f)),
    MAX_IMMERSION("Max immersion", "Combine stronger weak requests, a high floor and a start kick.",
            true, new RumbleTranslator.Profile(1.4f, .7f, 200, 80, 1f, .40f, .2f)),
    GENTLE("Gentle", "Reduce overall strength and soften weak requests.",
            true, new RumbleTranslator.Profile(.7f, 1.3f, 0, 0, .80f, .33f, 0f)),
    KICK_ONLY("Kick only", "Keep the original response after a full-strength start kick.",
            true, new RumbleTranslator.Profile(1f, 1f, 0, 100, .80f, .33f, 0f)),
    EVERYTHING_FELT("Everything felt", "Lift all nonzero requests to a high floor with a brief start kick.",
            true, new RumbleTranslator.Profile(1f, .6f, 220, 50, .80f, .40f, 0f)),
    // TEMPORARY (#66): experimental pulse presets 11-18. Unless stated otherwise they share one shaping
    // (gain 1.0, curve 0.8, floor 50) so only the pulse timing differs between them.
    PULSE_25_KICK("Pulse 25 + kick", "Square pulses, 20 ms on and 20 ms off, after a 100 ms full-strength start kick.",
            true, pulse(.9f, .8f, 50, 100, 20, 20)),
    PULSE_20("Pulse 20 Hz", "Square pulses, 25 ms on and 25 ms off: a slower, heavier rattle.",
            true, pulse(1f, .8f, 50, 0, 25, 25)),
    PULSE_15("Pulse 15 Hz", "Square pulses, 33 ms on and 33 ms off: the slowest, deepest-sounding rattle.",
            true, pulse(1f, .8f, 50, 0, 33, 33)),
    PULSE_25_GENTLE("Pulse 25 gentle", "Square pulses, 20 ms on and 20 ms off, with the Gentle strength and no floor.",
            true, pulse(.7f, 1.3f, 0, 0, 20, 20)),
    PULSE_25_HEAVY("Pulse 25 heavy", "Square pulses, 28 ms on and 12 ms off: long pushes with short gaps.",
            true, pulse(1f, .8f, 50, 0, 28, 12)),
    PULSE_25("Pulse 25 Hz", "Square pulses, 20 ms on and 20 ms off: a slow, coarse rattle.",
            true, pulse(1f, .8f, 50, 0, 20, 20)),
    PULSE_40("Pulse 40 Hz", "Square pulses, 13 ms on and 12 ms off: a medium rumble.",
            true, pulse(1f, .8f, 50, 0, 13, 12)),
    PULSE_60("Pulse 60 Hz", "Square pulses, 8 ms on and 8 ms off: a fast, tight rumble.",
            true, pulse(1f, .8f, 50, 0, 8, 8));

    private static RumbleTranslator.Profile pulse(float gain, float curve, int floor, int kickMs, int onMs, int offMs) {
        return new RumbleTranslator.Profile(gain, curve, floor, kickMs, 1f, .35f, 0f,
                RumbleTechnique.PULSE, onMs, offMs);
    }

    public final String label;
    public final String description;
    public final boolean enabled;
    public final RumbleTranslator.Profile profile;

    RumblePresets(String label, String description, boolean enabled, RumbleTranslator.Profile profile) {
        this.label = label;
        this.description = description;
        this.enabled = enabled;
        this.profile = profile;
    }
}
