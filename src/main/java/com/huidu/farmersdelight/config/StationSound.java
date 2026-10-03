package com.huidu.farmersdelight.config;

import com.huidu.farmersdelight.api.sound.ToolSoundTable;
import org.bukkit.configuration.ConfigurationSection;

/** Immutable, reload-built sound settings; absent keys retain the station's own sound. */
public final class StationSound {
    private StationSound() { }

    public static ToolSoundTable.Entry read(ConfigurationSection section, String fallback,
                                            float volume, float pitch) {
        if (section == null) return new ToolSoundTable.Entry(fallback, volume, pitch, pitch);
        float configuredPitch = finite(section.getDouble("pitch", pitch), pitch);
        float min = finite(ConfigLookup.doubleValue(section, configuredPitch, "pitch-min", "pitch_min"), configuredPitch);
        float max = finite(ConfigLookup.doubleValue(section, configuredPitch, "pitch-max", "pitch_max"), configuredPitch);
        return new ToolSoundTable.Entry(section.getString("sound", fallback),
                Math.max(0, finite(section.getDouble("volume", volume), volume)),
                Math.max(0, Math.min(min, max)), Math.max(0, Math.max(min, max)));
    }

    private static float finite(double value, float fallback) {
        return Double.isFinite(value) ? (float) Math.min(128, Math.max(-128, value)) : fallback;
    }
}
