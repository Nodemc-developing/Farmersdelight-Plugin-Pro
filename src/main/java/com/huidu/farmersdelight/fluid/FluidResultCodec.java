package com.huidu.farmersdelight.fluid;

import com.huidu.farmersdelight.recipe.RecipeItemCodec;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Map;

/** Explicit result components; unhandled semantic fields cannot silently disappear. */
final class FluidResultCodec {
    private FluidResultCodec() { }
    static ItemStack deserialize(Map<String, Object> result) {
        ItemStack item = RecipeItemCodec.deserializeItem(result);
        Object raw = result.get("components");
        if (raw == null || item == null) return item;
        if (raw instanceof ConfigurationSection section) raw = section.getValues(false);
        if (!(raw instanceof Map<?, ?> components)) throw new IllegalArgumentException("result.components must be a map");
        var meta = item.getItemMeta();
        if (meta == null) throw new IllegalArgumentException("Result item has no component metadata");
        for (var entry : components.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (key.startsWith("x-") || key.equals("extensions")) continue;
            switch (key) {
                case "minecraft:custom_name" -> meta.displayName(MiniMessage.miniMessage().deserialize(text(value, key)));
                case "minecraft:item_name" -> meta.itemName(MiniMessage.miniMessage().deserialize(text(value, key)));
                case "minecraft:lore" -> {
                    if (!(value instanceof java.util.List<?> lines)) throw new IllegalArgumentException("result.components.minecraft:lore must be a list");
                    meta.lore(lines.stream().map(line -> MiniMessage.miniMessage().deserialize(text(line, key))).toList());
                }
                case "minecraft:max_stack_size" -> meta.setMaxStackSize(integer(value, 1, 99, key));
                case "minecraft:custom_model_data" -> meta.setCustomModelData(integer(value, Integer.MIN_VALUE, Integer.MAX_VALUE, key));
                case "minecraft:damage" -> {
                    if (!(meta instanceof Damageable damage)) throw new IllegalArgumentException("Result does not support damage");
                    damage.setDamage(integer(value, 0, Integer.MAX_VALUE, key));
                }
                case "minecraft:max_damage" -> {
                    if (!(meta instanceof Damageable damage)) throw new IllegalArgumentException("Result does not support max_damage");
                    damage.setMaxDamage(integer(value, 1, Integer.MAX_VALUE, key));
                }
                case "minecraft:unbreakable" -> {
                    if (!(value instanceof Boolean flag)) throw new IllegalArgumentException("result.components.minecraft:unbreakable must be boolean");
                    meta.setUnbreakable(flag);
                }
                default -> throw new IllegalArgumentException("Unsupported result component: " + key + "; recipe disabled");
            }
        }
        if (!item.setItemMeta(meta)) throw new IllegalArgumentException("Result item rejected components");
        return item;
    }
    private static String text(Object value, String key) {
        if (!(value instanceof String text)) throw new IllegalArgumentException(key + " must be a MiniMessage string");
        return text;
    }
    private static int integer(Object value, int minimum, int maximum, String key) {
        try {
            int integer = new java.math.BigDecimal(String.valueOf(value)).intValueExact();
            if (integer < minimum || integer > maximum) throw new IllegalArgumentException(key + " is out of range");
            return integer;
        } catch (ArithmeticException | NumberFormatException invalid) { throw new IllegalArgumentException(key + " must be an integer", invalid); }
    }
}
