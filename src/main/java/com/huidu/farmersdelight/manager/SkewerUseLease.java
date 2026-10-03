package com.huidu.farmersdelight.manager;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.FoodProperties;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Objects;
import java.util.UUID;

/** Owns only the temporary use components of one serving, including moved copies of that stack. */
@SuppressWarnings("UnstableApiUsage")
final class SkewerUseLease {
    private static final NamespacedKey MARKER = new NamespacedKey("farmersdelight", "skewer_use");
    private final String identity;
    private final ItemStack original;
    private final Consumable installedConsumable;
    private final FoodProperties installedFood;
    private final boolean changedFood;
    private final ItemStack prepared;
    private final int duration;

    private SkewerUseLease(String identity, ItemStack original, Consumable consumable,
                          FoodProperties food, boolean changedFood, ItemStack prepared, int duration) {
        this.identity = identity; this.original = original;
        installedConsumable = consumable; installedFood = food; this.changedFood = changedFood;
        this.prepared = prepared; this.duration = duration;
    }

    static SkewerUseLease prepare(Player player, ItemStack source, int cookTicks) {
        if (source.getPersistentDataContainer().has(MARKER))
            throw new IllegalArgumentException("Item already contains an unresolved temporary skewer use component");
        String identity = UUID.randomUUID().toString();
        ItemStack original = source.clone();
        ItemStack prepared = source.clone();
        int needed = durationTicks(cookTicks, source.getMaxItemUseDuration(player));
        Consumable previous = source.getData(DataComponentTypes.CONSUMABLE);
        Consumable.Builder builder = previous == null ? Consumable.consumable() : previous.toBuilder();
        // Round upwards so conversion back to native integer ticks cannot shorten the requested budget.
        Consumable installed = builder.consumeSeconds(Math.nextUp(needed / 20f)).build();
        prepared.setData(DataComponentTypes.CONSUMABLE, installed);
        FoodProperties food = source.getData(DataComponentTypes.FOOD);
        boolean changedFood = food != null && !food.canAlwaysEat();
        FoodProperties installedFood = changedFood ? food.toBuilder().canAlwaysEat(true).build() : food;
        if (changedFood) prepared.setData(DataComponentTypes.FOOD, installedFood);
        prepared.editPersistentDataContainer(pdc -> pdc.set(MARKER, PersistentDataType.STRING, identity));
        int duration = prepared.getMaxItemUseDuration(player);
        if (duration < needed) throw new IllegalStateException("Skewer source cannot provide its requested native use duration");
        return new SkewerUseLease(identity, original, installed, installedFood, changedFood, prepared, duration);
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
        if (Objects.equals(current.getData(DataComponentTypes.CONSUMABLE), installedConsumable))
            restoreComponent(restored, DataComponentTypes.CONSUMABLE, original);
        if (changedFood && Objects.equals(current.getData(DataComponentTypes.FOOD), installedFood))
            restoreComponent(restored, DataComponentTypes.FOOD, original);
        restored.editPersistentDataContainer(pdc -> pdc.remove(MARKER));
        return restored;
    }
    private static <T> void restoreComponent(ItemStack destination, DataComponentType.Valued<T> key, ItemStack original) {
        if (!original.isDataOverridden(key)) destination.resetData(key);
        else {
            T previous = original.getData(key);
            if (previous == null) destination.unsetData(key);
            else destination.setData(key, previous);
        }
    }
}
