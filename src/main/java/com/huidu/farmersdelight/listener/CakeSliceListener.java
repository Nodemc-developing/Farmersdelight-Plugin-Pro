package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Cake;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Cutting a native cake changes one serving and delivers its drops in the same owned region. */
public final class CakeSliceListener implements Listener {
    private final FarmersDelightPlugin plugin;
    private final Map<UUID, CakeSliceRules.Click> clicks = new ConcurrentHashMap<>();
    private final ThreadLocal<Set<UUID>> editing = ThreadLocal.withInitial(HashSet::new);

    public CakeSliceListener(FarmersDelightPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null
                || !CakeSliceRules.permitsInteraction(event.useInteractedBlock(), event.useItemInHand()) || event.getHand() == null) return;
        Player player = event.getPlayer();
        if (!owns(player)) return;
        EquipmentSlot hand = event.getHand();
        ItemStack tool = hand == EquipmentSlot.OFF_HAND ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
        if (!plugin.isKnife(tool)) return;
        Block block = event.getClickedBlock();
        if (!resident(block) || !CakeSliceRules.target(block.getType(), customCarrier(block))) return;
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        UUID playerId = player.getUniqueId();
        var click = new CakeSliceRules.Click(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ(), Bukkit.getCurrentTick(), hand);
        if (CakeSliceRules.duplicateHand(clicks.get(playerId), click)) return;
        Set<UUID> busy = editing.get();
        if (!busy.add(playerId)) return;
        try {
            if (player.getGameMode() == GameMode.ADVENTURE || player.getGameMode() == GameMode.SPECTATOR
                    || !ProtectionCompat.canBreak(player, block, (String) null)
                    || !ProtectionCompat.canPlace(player, block, null)) return;
            ItemStack expectedTool = tool.clone();
            GameMode mode = player.getGameMode();
            BlockData before = block.getBlockData().clone();
            Material candle = CakeSliceRules.candleDrop(before.getMaterial());
            int next = candle == null ? CakeSliceRules.nextBites(((Cake) before).getBites()) : 1;
            BlockData after;
            if (next < 0) after = Bukkit.createBlockData(Material.AIR);
            else {
                Cake cake = (Cake) Bukkit.createBlockData(Material.CAKE);
                cake.setBites(next);
                after = cake;
            }
            ItemStack slice = ItemUtils.createItem("farmersdelight:cake_slice");
            if (slice == null || slice.getType().isAir()) return;
            slice.setAmount(1);
            List<ItemStack> proposed = new ArrayList<>();
            proposed.add(slice);
            if (candle != null) proposed.add(new ItemStack(candle));
            PlayerHarvestBlockEvent harvest = new PlayerHarvestBlockEvent(player, block, hand, proposed);
            Bukkit.getPluginManager().callEvent(harvest);
            if (harvest.isCancelled()) return;
            if (next < 0) {
                BlockBreakEvent breaking = new BlockBreakEvent(block, player);
                breaking.setDropItems(false);
                Bukkit.getPluginManager().callEvent(breaking);
                if (breaking.isCancelled()) return;
            }
            List<ItemStack> drops = harvest.getItemsHarvested().stream()
                    .filter(item -> item != null && !item.getType().isAir() && item.getAmount() > 0)
                    .map(ItemStack::clone).toList();
            if (drops.isEmpty()) return;
            CakeSliceTransaction.Cell<BlockData> cell = new CakeSliceTransaction.Cell<>() {
                @Override public boolean resident() { return CakeSliceListener.this.resident(block) && !customCarrier(block); }
                @Override public BlockData read() { return block.getBlockData().clone(); }
                @Override public boolean write(BlockData value) { block.setBlockData(value, false); return read().equals(value); }
            };
            Location dropAt = block.getLocation().add(.5, .2, .5);
            var result = CakeSliceTransaction.commit(cell, before, after, () -> owns(player)
                    && player.isOnline() && player.isValid() && !player.isDead() && player.getGameMode() == mode
                    && player.getWorld().equals(block.getWorld()) && resident(block)
                    && expectedTool.equals(hand == EquipmentSlot.OFF_HAND ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand())
                    && ProtectionCompat.canBreak(player, block, (String) null)
                    && ProtectionCompat.canPlace(player, block, null), new WorldDrops(dropAt, drops));
            if (!result.committed()) {
                if (!result.rollbackComplete()) plugin.getLogger().warning("Cake slice rollback could not complete at " + block.getLocation());
                return;
            }
            clicks.put(playerId, click);
            player.playSound(block.getLocation(), "farmersdelight:block.food.slice", SoundCategory.PLAYERS, .8F, .8F);
            if (hand == EquipmentSlot.OFF_HAND) player.swingOffHand(); else player.swingMainHand();
        } finally {
            busy.remove(playerId);
            if (busy.isEmpty()) editing.remove();
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { clicks.remove(event.getPlayer().getUniqueId()); }
    private boolean owns(Player player) { return plugin.scheduler().isFolia() ? Bukkit.isOwnedByCurrentRegion(player) : Bukkit.isPrimaryThread(); }
    private boolean resident(Block block) {
        World world = block.getWorld();
        return world.isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                && (plugin.scheduler().isFolia() ? plugin.scheduler().isOwnedByCurrentRegion(block.getLocation()) : Bukkit.isPrimaryThread());
    }
    private static boolean customCarrier(Block block) {
        ImmutableBlockState custom = CraftEngineBlocks.getCustomBlockState(block);
        return custom != null && !custom.isEmpty();
    }

    private final class WorldDrops implements CakeSliceTransaction.Output {
        private final Location location;
        private final List<ItemStack> drops;
        private final List<Item> spawned = new ArrayList<>();
        private WorldDrops(Location location, List<ItemStack> drops) { this.location = location; this.drops = drops; }
        @Override public boolean deliver() {
            for (ItemStack drop : drops) {
                if (!resident(location.getBlock())) return false;
                Item entity = location.getWorld().dropItem(location, drop.clone(), item -> {
                    spawned.add(item);
                    item.setPickupDelay(10);
                    item.setVelocity(new Vector(-.05, 0, 0));
                });
                if (entity == null || !entity.isValid() || entity.isDead()) return false;
                if (!spawned.contains(entity)) spawned.add(entity);
            }
            return true;
        }
        @Override public boolean retract() {
            return CakeSliceTransaction.retractAll(spawned, entity -> {
                if (!entity.isValid() || entity.isDead()) return true;
                if (plugin.scheduler().isFolia() && !Bukkit.isOwnedByCurrentRegion(entity)) return false;
                entity.remove();
                return !entity.isValid() || entity.isDead();
            });
        }
    }
}
