package com.huidu.farmersdelight.util.compat;

import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import net.momirealms.craftengine.core.util.VersionHelper;
import net.momirealms.craftengine.proxy.minecraft.world.item.ItemStackTemplateProxy;
import net.momirealms.craftengine.proxy.minecraft.world.item.component.UseRemainderProxy;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.components.FoodComponent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.LinkedHashMap;
import java.util.Map;

/** Uses CraftEngine's native component codecs without linking to newer Paper component classes. */
public final class CraftEngineItemComponents {
    private static final MethodHandle LEGACY_FOOD_REMAINDER = linkLegacyFoodRemainder();

    private CraftEngineItemComponents() {}

    public static boolean separateConsumable() {
        return VersionHelper.isOrAbove1_21_2;
    }

    public static Map<String, Object> getMap(ItemStack stack, Object key) {
        return stack == null || stack.getType().isAir() ? null : getMap(BukkitAdaptor.adapt(stack), key);
    }

    public static Map<String, Object> getMap(Item item, Object key) {
        Object value = item.getComponentAsJava(key);
        if (!(value instanceof Map<?, ?> raw)) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((name, data) -> { if (name instanceof String string) result.put(string, data); });
        return result;
    }

    public static void setMap(ItemStack stack, Object key, Map<String, Object> data) {
        Item wrapped = BukkitAdaptor.adapt(stack);
        wrapped.setJavaComponent(key, data);
        // Adapting a plain Bukkit stack makes a native copy. Commit its metadata in both cases.
        stack.setItemMeta(ItemStackUtils.getBukkitStack(wrapped).getItemMeta());
    }

    public static Map<String, Object> food(ItemStack stack) {
        return getMap(stack, DataComponentKeys.FOOD);
    }

    public static void setAlwaysEat(ItemStack stack, boolean alwaysEat) {
        Map<String, Object> food = food(stack);
        if (food != null && !Boolean.valueOf(alwaysEat).equals(food.get("can_always_eat"))) {
            food.put("can_always_eat", alwaysEat);
            setMap(stack, DataComponentKeys.FOOD, food);
        }
    }

    public static void setUseDuration(ItemStack stack, float seconds) {
        if (!Float.isFinite(seconds) || seconds <= 0) throw new IllegalArgumentException("Invalid use duration");
        Object key = separateConsumable() ? DataComponentKeys.CONSUMABLE : DataComponentKeys.FOOD;
        Map<String, Object> data = withUseDuration(getMap(stack, key), seconds, separateConsumable());
        setMap(stack, key, data);
    }

    static Map<String, Object> withUseDuration(Map<String, Object> source, float seconds, boolean separate) {
        Map<String, Object> result = source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
        if (separate) result.put("consume_seconds", seconds);
        else {
            result.putIfAbsent("nutrition", 0);
            result.putIfAbsent("saturation", 0.0F);
            // In these versions FOOD supplies both nutrition and the native use action.
            result.put("can_always_eat", true);
            result.put("eat_seconds", seconds);
        }
        return result;
    }

    public static ItemStack useRemainder(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return null;
        if (separateConsumable()) {
            Object remainder = BukkitAdaptor.adapt(stack).getExactComponent(DataComponentKeys.USE_REMAINDER);
            if (remainder == null) return null;
            Object converted = UseRemainderProxy.INSTANCE.getConvertInto(remainder);
            if (VersionHelper.isOrAbove26_1) converted = ItemStackTemplateProxy.INSTANCE.create(converted);
            return nonEmpty(ItemStackUtils.getBukkitStack(converted));
        }
        if (LEGACY_FOOD_REMAINDER == null) return null;
        var meta = stack.getItemMeta();
        if (meta == null || !meta.hasFood()) return null;
        try {
            return nonEmpty((ItemStack) LEGACY_FOOD_REMAINDER.invokeExact(meta.getFood()));
        } catch (Throwable failure) {
            throw new IllegalStateException("Could not read the legacy food remainder", failure);
        }
    }

    private static ItemStack nonEmpty(ItemStack stack) {
        return stack == null || stack.getType().isAir() || stack.getAmount() < 1 ? null : stack.clone();
    }

    private static MethodHandle linkLegacyFoodRemainder() {
        try {
            return MethodHandles.publicLookup().findVirtual(FoodComponent.class, "getUsingConvertsTo",
                    MethodType.methodType(ItemStack.class));
        } catch (ReflectiveOperationException | LinkageError missing) {
            return null;
        }
    }

    public static boolean hasEquippable(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return false;
        if (separateConsumable()) return BukkitAdaptor.adapt(stack).hasComponent(DataComponentKeys.EQUIPPABLE);
        return legacyEquippable(stack.getType());
    }

    static boolean legacyEquippable(Material material) {
        EquipmentSlot slot = material.getEquipmentSlot();
        return slot != EquipmentSlot.HAND && slot != EquipmentSlot.OFF_HAND;
    }
}
