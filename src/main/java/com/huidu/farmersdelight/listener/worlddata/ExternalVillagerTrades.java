package com.huidu.farmersdelight.listener.worlddata;

import com.huidu.farmersdelight.listener.worlddata.WorldDataConfig.TradeOffer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;

/**
 * Villager / wandering-trader offers registered at runtime by addons through the api
 * (FarmersDelightVillagerTrades), kept separate from the config-driven WorldDataConfig. WorldDataConfig is an
 * immutable snapshot rebuilt from defaults + config.yml on every reload, so pouring addon trades into it would
 * wipe them on the next /fd reload. This registry is a plain static store that survives reloads; VillagerTradeListener
 * unions its offers with the config candidates before its weighted substitution roll.
 *
 * A profession() of null marks a wandering-trader offer; a non-null profession marks a villager offer gated on
 * profession + level.
 */
public final class ExternalVillagerTrades {

    private static final Map<String, TradeOffer> BY_ID = new ConcurrentHashMap<>();
    private record Pool(String profession, int level) { }
    private static volatile Map<Pool, List<TradeOffer>> villagerPools = Map.of();
    private static volatile List<TradeOffer> wanderingPool = List.of();

    private ExternalVillagerTrades() {
    }

    public static synchronized boolean register(String id, TradeOffer offer) {
        if (id == null || id.isBlank() || offer == null) {
            return false;
        }
        BY_ID.put(id, offer);
        rebuild();
        return true;
    }

    public static synchronized boolean unregister(String id) {
        boolean removed = id != null && BY_ID.remove(id) != null;
        if (removed) rebuild();
        return removed;
    }

    public static boolean isRegistered(String id) {
        return id != null && BY_ID.containsKey(id);
    }

    public static Set<String> ids() {
        return Set.copyOf(BY_ID.keySet());
    }

    public static boolean isEmpty() {
        return BY_ID.isEmpty();
    }

    /** Registered villager offers for a profession + level (profession-gated entries only). */
    public static List<TradeOffer> villagerTradesFor(String professionPath, int level) {
        if (professionPath == null) {
            return List.of();
        }
        return villagerPools.getOrDefault(new Pool(professionPath, level), List.of());
    }

    /** Registered wandering-trader offers (entries with a null profession). */
    public static List<TradeOffer> wanderingTrades() {
        return wanderingPool;
    }

    private static void rebuild() {
        Map<Pool, List<TradeOffer>> mutable = new HashMap<>();
        List<TradeOffer> wandering = new ArrayList<>();
        BY_ID.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            TradeOffer offer = entry.getValue();
            if (offer.profession() == null) wandering.add(offer);
            else mutable.computeIfAbsent(new Pool(offer.profession(), offer.level()), ignored -> new ArrayList<>()).add(offer);
        });
        Map<Pool, List<TradeOffer>> frozen = new HashMap<>();
        mutable.forEach((pool, offers) -> frozen.put(pool, List.copyOf(offers)));
        villagerPools = Map.copyOf(frozen);
        wanderingPool = List.copyOf(wandering);
    }
}
