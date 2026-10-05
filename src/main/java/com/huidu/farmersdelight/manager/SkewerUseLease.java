package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.util.compat.CraftEngineItemComponents;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Objects;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owns only the temporary use components of one serving, including moved copies of that stack. */
final class SkewerUseLease {
    private static final NamespacedKey MARKER = new NamespacedKey("farmersdelight", "skewer_use");
    private final String identity;
    private final ItemStack original;
    private final Object installedConsumable;
    private final Object installedFood;
    private final boolean changedFood;
    private final Map<String, Object> originalLegacyFood;
    private final Map<String, Object> installedLegacyFood;
    private final ItemStack prepared;
    private final int duration;

    private SkewerUseLease(String identity, ItemStack original, Object consumable,
                          Object food, boolean changedFood, Map<String, Object> originalLegacyFood,
                          Map<String, Object> installedLegacyFood, ItemStack prepared, int duration) {
        this.identity = identity; this.original = original;
        installedConsumable = consumable; installedFood = food; this.changedFood = changedFood;
        this.originalLegacyFood = originalLegacyFood; this.installedLegacyFood = installedLegacyFood;
        this.prepared = prepared; this.duration = duration;
    }

    static SkewerUseLease prepare(Player player, ItemStack source, int cookTicks) {
        if (source.getPersistentDataContainer().has(MARKER))
            throw new IllegalArgumentException("Item already contains an unresolved temporary skewer use component");
        String identity = UUID.randomUUID().toString();
        ItemStack original = source.clone();
        ItemStack prepared = source.clone();
        int needed = durationTicks(cookTicks, source.getMaxItemUseDuration(player));
        Object previousFood = BukkitAdaptor.adapt(original).getExactComponent(DataComponentKeys.FOOD);
        Map<String, Object> originalLegacyFood = CraftEngineItemComponents.separateConsumable()
                ? null : CraftEngineItemComponents.food(original);
        CraftEngineItemComponents.setAlwaysEat(prepared, true);
        // Round upwards so conversion back to native integer ticks cannot shorten the requested budget.
        CraftEngineItemComponents.setUseDuration(prepared, Math.nextUp(needed / 20f));
        Item wrapped = BukkitAdaptor.adapt(prepared);
        Object installed = CraftEngineItemComponents.separateConsumable()
                ? wrapped.getExactComponent(DataComponentKeys.CONSUMABLE) : null;
        Object installedFood = wrapped.getExactComponent(DataComponentKeys.FOOD);
        boolean changedFood = !Objects.equals(previousFood, installedFood);
        Map<String, Object> installedLegacyFood = CraftEngineItemComponents.separateConsumable()
                ? null : CraftEngineItemComponents.getMap(wrapped, DataComponentKeys.FOOD);
        prepared.editMeta(meta -> meta.getPersistentDataContainer().set(MARKER, PersistentDataType.STRING, identity));
        int duration = prepared.getMaxItemUseDuration(player);
        if (duration < needed) throw new IllegalStateException("Skewer source cannot provide its requested native use duration");
        return new SkewerUseLease(identity, original, installed, installedFood, changedFood,
                originalLegacyFood, installedLegacyFood, prepared, duration);
    }

    static int durationTicks(int cookTicks, int originalTicks) {
        if (cookTicks < 1 || cookTicks > 72_000 || originalTicks < 0) throw new IllegalArgumentException("Invalid item use duration");
        return Math.max(cookTicks + 21, originalTicks);
    }

    ItemStack prepared() { return prepared.clone(); }
    int duration() { return duration; }
    boolean matches(ItemStack current) {
        return current != null && current.getAmount() == prepared.getAmount() && current.isSimilar(prepared);
    }
    boolean owns(ItemStack current) {
        return current != null && identity.equals(current.getPersistentDataContainer().get(MARKER, PersistentDataType.STRING));
    }
    ItemStack restore(ItemStack current) {
        if (!owns(current)) return current;
        ItemStack restored = current.clone();
        // Metadata and components changed by another plugin survive; only our exact installed values retract.
        Item wrapped = BukkitAdaptor.adapt(current);
        if (CraftEngineItemComponents.separateConsumable()
                && Objects.equals(wrapped.getExactComponent(DataComponentKeys.CONSUMABLE), installedConsumable))
            restoreComponent(restored, DataComponentKeys.CONSUMABLE, original);
        if (changedFood) {
            if (Objects.equals(wrapped.getExactComponent(DataComponentKeys.FOOD), installedFood))
                restoreComponent(restored, DataComponentKeys.FOOD, original);
            else if (!CraftEngineItemComponents.separateConsumable()) restoreLegacyFood(restored);
        }
        restored.editMeta(meta -> meta.getPersistentDataContainer().remove(MARKER));
        return restored;
    }

    private void restoreLegacyFood(ItemStack destination) {
        Map<String, Object> current = CraftEngineItemComponents.food(destination);
        // A component removed by another owner stays removed.
        if (current == null || installedLegacyFood == null) return;
        Map<String, Object> restored = retractLegacyFood(current, installedLegacyFood, originalLegacyFood);
        if (sameLegacyFood(restored, originalLegacyFood))
            restoreComponent(destination, DataComponentKeys.FOOD, original);
        else if (!current.equals(restored))
            CraftEngineItemComponents.setMap(destination, DataComponentKeys.FOOD, restored);
    }

    static Map<String, Object> retractLegacyFood(Map<String, Object> current, Map<String, Object> installed,
                                                  Map<String, Object> original) {
        Map<String, Object> result = new LinkedHashMap<>(current);
        if (sameSeconds(current.getOrDefault("eat_seconds", 1.6F), installed.getOrDefault("eat_seconds", 1.6F))) {
            if (original != null && original.containsKey("eat_seconds")) result.put("eat_seconds", original.get("eat_seconds"));
            else result.remove("eat_seconds");
        }
        boolean currentAlways = Boolean.TRUE.equals(current.get("can_always_eat"));
        boolean installedAlways = Boolean.TRUE.equals(installed.get("can_always_eat"));
        boolean originalAlways = original != null && Boolean.TRUE.equals(original.get("can_always_eat"));
        if (currentAlways == installedAlways && installedAlways != originalAlways) {
            if (original != null && original.containsKey("can_always_eat")) result.put("can_always_eat", original.get("can_always_eat"));
            else result.remove("can_always_eat");
        }
        return result;
    }

    static boolean sameLegacyFood(Map<String, Object> first, Map<String, Object> original) {
        if (first == null) return original == null;
        Map<String, Object> before = original == null ? Map.of("nutrition", 0, "saturation", 0F) : original;
        return normalizedLegacyFood(first).equals(normalizedLegacyFood(before));
    }

    private static Map<String, Object> normalizedLegacyFood(Map<String, Object> food) {
        Map<String, Object> result = new LinkedHashMap<>(food);
        result.putIfAbsent("can_always_eat", false);
        result.putIfAbsent("eat_seconds", 1.6F);
        result.putIfAbsent("effects", List.of());
        for (String key : List.of("saturation", "eat_seconds"))
            if (result.get(key) instanceof Number value) result.put(key, value.floatValue());
        return result;
    }

    private static boolean sameSeconds(Object first, Object second) {
        return first instanceof Number a && second instanceof Number b
                && Float.floatToIntBits(a.floatValue()) == Float.floatToIntBits(b.floatValue());
    }

    private static void restoreComponent(ItemStack destination, Object key, ItemStack original) {
        Item before = BukkitAdaptor.adapt(original), after = BukkitAdaptor.adapt(destination);
        if (!before.hasNonDefaultComponent(key)) after.resetComponent(key);
        else {
            Object previous = before.getExactComponent(key);
            if (previous == null) after.removeComponent(key);
            else after.setExactComponent(key, previous);
        }
        if (!destination.setItemMeta(ItemStackUtils.getBukkitStack(after).getItemMeta()))
            throw new IllegalStateException("Cannot restore temporary skewer component");
    }
}
