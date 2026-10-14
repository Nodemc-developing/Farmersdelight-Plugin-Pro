package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.function.BooleanSupplier;

/** A synchronous fertilizer action keeps its actor and complete input snapshot until consumption. */
final class CropBoneMealUse implements BooleanSupplier {
    private final FarmersDelightPlugin plugin;
    private final UseOnContext context;
    private final Player player;
    private final World world;
    private final GameMode mode;
    private final ItemStack item;

    private CropBoneMealUse(FarmersDelightPlugin plugin, UseOnContext context, Player player, World world, ItemStack item) {
        this.plugin = plugin;
        this.context = context;
        this.player = player;
        this.world = world;
        this.mode = player.getGameMode();
        this.item = item.clone();
    }

    static CropBoneMealUse capture(FarmersDelightPlugin plugin, UseOnContext context) {
        if (context.getPlayer() == null || !(context.getPlayer().platformPlayer() instanceof Player player)
                || !(context.getLevel().platformWorld() instanceof World world)) return null;
        ItemStack item = ItemUtils.getItemInHand(player, context.getHand());
        if (item == null || item.getType() != Material.BONE_MEAL || item.getAmount() < 1) return null;
        CropBoneMealUse use = new CropBoneMealUse(plugin, context, player, world, item);
        return use.getAsBoolean() ? use : null;
    }

    Player player() { return player; }

    @Override public boolean getAsBoolean() {
        return (plugin != null && plugin.scheduler().isFolia() ? Bukkit.isOwnedByCurrentRegion(player) : Bukkit.isPrimaryThread())
                && mode != GameMode.ADVENTURE && mode != GameMode.SPECTATOR && player.getGameMode() == mode
                && player.isOnline() && player.isValid() && !player.isDead() && world.equals(player.getWorld())
                && item.equals(ItemUtils.getItemInHand(player, context.getHand()));
    }

    void consume() {
        if (!getAsBoolean()) throw new IllegalStateException("Fertilizer input changed after the growth transaction");
        if (mode != GameMode.CREATIVE) {
            ItemStack actual = ItemUtils.getItemInHand(player, context.getHand());
            actual.setAmount(actual.getAmount() - 1);
        }
    }
}
