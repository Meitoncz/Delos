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
            true, new RumbleTranslator.Profile(1f, .6f, 220, 50, .80f, .40f, 0f));

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
