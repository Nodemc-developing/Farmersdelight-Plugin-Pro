package com.huidu.farmersdelight.loot;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.advancement.FarmersDelightAdvancements;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.loot.LootContext;
import net.momirealms.craftengine.core.loot.function.LootFunction;
import net.momirealms.craftengine.core.loot.function.LootFunctionFactory;

public final class GrantAdvancementFunction implements LootFunction {
    public static final LootFunctionFactory<GrantAdvancementFunction> FACTORY = factory(null);
    public static LootFunctionFactory<GrantAdvancementFunction> factory(FarmersDelightPlugin plugin) {
        return config -> new GrantAdvancementFunction(plugin, config.getNonEmptyString("advancement"), config.getString("criterion", ""));
    }
    private final String id, criterion;
    private final FarmersDelightPlugin plugin;
    private GrantAdvancementFunction(FarmersDelightPlugin plugin, String id, String criterion) {
        this.plugin = plugin;
        this.id = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id; this.criterion = criterion;
    }
    @Override public Item apply(Item item, LootContext context) {
        if (!context.isPlayerPresent() || !(context.player().platformPlayer() instanceof org.bukkit.entity.Player player)) return item;
        if (plugin == null || !plugin.isEnabled()) return item;
        plugin.scheduler().runForEntity(player, () -> {
            if (!player.isOnline()) return;
            if (criterion.isBlank()) FarmersDelightAdvancements.award(player, id);
            else FarmersDelightAdvancements.awardCriteria(player, id, criterion);
        });
        return item;
    }
}
