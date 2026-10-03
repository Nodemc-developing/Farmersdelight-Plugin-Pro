package com.huidu.farmersdelight.statistics;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record StatisticKey(UUID player, String activity, String item) {
    public static final UUID SERVER = new UUID(0L, 0L);

    public StatisticKey {
        Objects.requireNonNull(player);
        activity = normalized(activity);
        item = normalized(item);
    }

    private static String normalized(String value) {
        Objects.requireNonNull(value);
        if (value.isBlank() || value.length() > 256) throw new IllegalArgumentException("Invalid statistic dimension");
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.length() > 256) throw new IllegalArgumentException("Invalid normalized statistic dimension");
        return normalized;
    }
}
