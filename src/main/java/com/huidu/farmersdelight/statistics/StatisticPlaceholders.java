package com.huidu.farmersdelight.statistics;

import java.util.UUID;

public final class StatisticPlaceholders {
    private StatisticPlaceholders() { }

    /** stats_<activity>[__item]; stats_server__<activity>[__item]; stats_player__<uuid/name>__<activity>[__item]. */
    public static String resolve(StatisticsService service, UUID requestingPlayer, String request) {
        if (!request.startsWith("stats_")) return null;
        if (service == null || !service.enabled()) return "-";
        String rest = request.substring(6);
        UUID player = requestingPlayer;
        if (rest.startsWith("server__")) {
            player = StatisticKey.SERVER;
            rest = rest.substring(8);
        } else if (rest.startsWith("player__")) {
            String[] fields = rest.substring(8).split("__", 2);
            if (fields.length != 2) return "-";
            player = service.cachedPlayer(fields[0]);
            rest = fields[1];
        }
        String[] dimension = rest.split("__", 2);
        if (dimension[0].isBlank()) return "-";
        String item = dimension.length == 2 ? dimension[1] : "*";
        try {
            var value = service.cached(player, dimension[0], item);
            return value.isPresent() ? Long.toString(value.getAsLong()) : "-";
        } catch (IllegalArgumentException invalid) { return "-"; }
    }
}
