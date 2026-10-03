package com.huidu.farmersdelight.villager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.ManagedCropBlockBehavior;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Event-discovered villagers work in bounded, independent entity tasks, including on Folia. */
public final class VillagerAutomationService implements Listener, AutoCloseable {
    private final FarmersDelightPlugin plugin;
    private final Map<UUID, Tracker> villagers = new ConcurrentHashMap<>();
    private final VillagerConfiguredTrades trades = new VillagerConfiguredTrades();
    private final ThreadLocal<EntityPickupItemEvent> manualPickup = new ThreadLocal<>();
    private final ThreadLocal<EntityChangeBlockEvent> manualBlockChange = new ThreadLocal<>();
    private volatile VillagerContentSnapshot snapshot;
    private volatile VillagerFoodAccess food;
    private volatile boolean running;

    public VillagerAutomationService(FarmersDelightPlugin plugin) { this.plugin = plugin; }

    /** Call onEnable after CraftEngine has loaded its definitions. */
    public synchronized void start() {
        if (running) return;
        reload();
        try { food = VillagerFoodAccess.link(); }
        catch (ReflectiveOperationException unavailable) {
            plugin.getLogger().severe("Custom villager feeding could not link the supported native food counter: "
                    + unavailable.getMessage() + "; feeding is disabled, harvesting, pickup and trades remain available.");
        }
        running = true;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // Only the initial resident chunks are enumerated. Routine work never scans all worlds/entities.
        discoverResidentVillagers();
    }

    private void discoverResidentVillagers() {
        for (var world : Bukkit.getWorlds()) for (var chunk : world.getLoadedChunks()) {
            plugin.scheduler().runAt(world, chunk.getX(), chunk.getZ(), () -> {
                if (!running || !world.isChunkLoaded(chunk.getX(), chunk.getZ())) return;
                for (Entity entity : chunk.getEntities()) if (entity instanceof Villager villager) track(villager);
            });
        }
    }

    /** Rebuilds registry indexes before publishing one immutable content snapshot. */
    public synchronized void reload() {
        VillagerContentSnapshot previous = snapshot;
        VillagerContentSnapshot incoming = VillagerContentSnapshot.read(plugin);
        trades.reload(plugin.getConfig());
        snapshot = incoming;
        if (!incoming.settings().anyWork()) { villagers.values().forEach(Tracker::stop); villagers.clear(); }
        else if (running) {
            if (previous == null || !previous.settings().anyWork()) discoverResidentVillagers();
            villagers.values().forEach(Tracker::wake);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void spawned(CreatureSpawnEvent event) { if (event.getEntity() instanceof Villager villager) track(villager); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void loaded(EntitiesLoadEvent event) { for (Entity entity : event.getEntities()) if (entity instanceof Villager villager) track(villager); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void unloaded(EntitiesUnloadEvent event) { for (Entity entity : event.getEntities()) remove(entity.getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void died(EntityDeathEvent event) { if (event.getEntity() instanceof Villager) remove(event.getEntity().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void itemSpawned(ItemSpawnEvent event) {
        VillagerContentSnapshot current = snapshot;
        if (!running || current == null || !current.settings().pickup()
                || !current.pickupItems().contains(ItemUtils.resolveItemId(event.getEntity().getItemStack()))) return;
        Location location = event.getLocation();
        for (Entity nearby : location.getWorld().getNearbyEntities(location, 4, 2, 4))
            if (nearby instanceof Villager villager && owned(villager)) {
                track(villager); Tracker tracker = villagers.get(villager.getUniqueId()); if (tracker != null) tracker.wake();
            }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void nativePickup(EntityPickupItemEvent event) {
        if (manualPickup.get() == event || !(event.getEntity() instanceof Villager villager) || !running) return;
        VillagerContentSnapshot current = snapshot;
        if (!current.settings().pickup() || !current.pickupItems().contains(ItemUtils.resolveItemId(event.getItem().getItemStack()))) return;
        // Custom carrier items must not be planted/consumed by the vanilla material-only path.
        event.setCancelled(true);
        track(villager);
        Tracker tracker = villagers.get(villager.getUniqueId()); if (tracker != null) tracker.wake();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void nativeCropChange(EntityChangeBlockEvent event) {
        if (manualBlockChange.get() == event || !running || !(event.getEntity() instanceof Villager villager)
                || !snapshot.settings().harvest()) return;
        Block block = event.getBlock();
        if (!resident(block)) return;
        ImmutableBlockState present = CustomBlockUtils.getStateIfResident(block);
        if (present != null && !present.isEmpty() && ManagedCropBlockBehavior.byId(present.owner().value().id()) != null) {
            event.setCancelled(true); track(villager);
            Tracker tracker = villagers.get(villager.getUniqueId()); if (tracker != null) { tracker.target = block; tracker.wake(); }
            return;
        }
        Material seed = switch (event.getTo()) {
            case WHEAT -> Material.WHEAT_SEEDS;
            case BEETROOTS -> Material.BEETROOT_SEEDS;
            case CARROTS -> Material.CARROT;
            case POTATOES -> Material.POTATO;
            default -> null;
        };
        if (seed == null) return;
        for (ItemStack stack : villager.getInventory().getStorageContents()) {
            if (stack == null || stack.getType() != seed) continue;
            if (ItemUtils.getCustomItemId(stack) != null) {
                event.setCancelled(true); track(villager);
                Tracker tracker = villagers.get(villager.getUniqueId()); if (tracker != null) tracker.wake();
            }
            break;
        }
    }

    private void track(Villager villager) {
        if (!running || snapshot == null || !snapshot.settings().anyWork()) return;
        Tracker tracker = villagers.computeIfAbsent(villager.getUniqueId(), ignored -> new Tracker(villager));
        tracker.begin();
    }
    private void remove(UUID id) { Tracker tracker = villagers.remove(id); if (tracker != null) tracker.stop(); }
    private boolean owned(Entity entity) { return !plugin.scheduler().isFolia() || Bukkit.isOwnedByCurrentRegion(entity); }
    private boolean resident(Block block) {
        return block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                && plugin.scheduler().isOwnedByCurrentRegion(block.getLocation());
    }

    private boolean work(Tracker tracker) {
        Villager villager = tracker.villager;
        if (!running || !villager.isValid() || villager.isDead()) { remove(villager.getUniqueId()); return false; }
        VillagerContentSnapshot current = snapshot;
        if (!current.settings().anyWork() || !villager.hasAI() || villager.getAge() < 0
                || villager.getPose() == Pose.SLEEPING || villager.getTrader() != null) return false;
        boolean worked = current.settings().breed() && feed(villager, current);
        if (Boolean.TRUE.equals(villager.getWorld().getGameRuleValue(GameRule.MOB_GRIEFING))) {
            if (current.settings().pickup()) worked |= pickup(tracker, current);
            if (current.settings().harvest() && villager.getProfession() == Villager.Profession.FARMER
                    && villager.getWorld().getTime() < 12000) worked |= farm(tracker, current);
            if (current.settings().share() && current.settings().breed()) worked |= share(villager, current);
        }
        return worked;
    }

    private boolean pickup(Tracker tracker, VillagerContentSnapshot current) {
        Villager villager = tracker.villager;
        double radius = current.settings().pickupRadius();
        var nearby = villager.getNearbyEntities(radius, 1.5, radius);
        if (nearby.isEmpty()) return false;
        int count = Math.min(16, nearby.size()); boolean changed = false, pending = nearby.size() > count;
        int offset = Math.floorMod(tracker.itemCursor, nearby.size()); tracker.itemCursor += count;
        for (int index = 0; index < count; index++) {
            Entity candidate = nearby.get((offset + index) % nearby.size());
            if (!(candidate instanceof Item item) || !owned(item)) continue;
            if (!item.isValid()) continue;
            ItemStack expected = item.getItemStack().clone();
            if (!current.pickupItems().contains(ItemUtils.resolveItemId(expected))) continue;
            if (item.getPickupDelay() > 0) { pending = true; continue; }
            Inventory inventory = villager.getInventory();
            int accepted = VillagerInventoryMath.capacity(inventory.getStorageContents(), expected);
            if (accepted <= 0) continue;
            EntityPickupItemEvent event = new EntityPickupItemEvent(villager, item, expected.getAmount() - accepted);
            EntityPickupItemEvent previous = manualPickup.get(); manualPickup.set(event);
            try { Bukkit.getPluginManager().callEvent(event); }
            finally { if (previous == null) manualPickup.remove(); else manualPickup.set(previous); }
            if (event.isCancelled() || !item.isValid() || !sameStack(item.getItemStack(), expected)) continue;
            accepted = VillagerInventoryMath.capacity(inventory.getStorageContents(), expected);
            if (accepted <= 0) continue;
            ItemStack offered = expected.clone(); offered.setAmount(accepted);
            int left = inventory.addItem(offered).values().stream().mapToInt(ItemStack::getAmount).sum();
            int taken = accepted - left;
            if (taken <= 0) continue;
            if (taken == expected.getAmount()) item.remove();
            else { ItemStack remainder = expected.clone(); remainder.setAmount(expected.getAmount() - taken); item.setItemStack(remainder); }
            changed = true;
            if (current.settings().breed()) feed(villager, current);
        }
        return changed || pending;
    }

    private boolean feed(Villager villager, VillagerContentSnapshot current) {
        VillagerFoodAccess access = food;
        if (access == null) return false;
        int points = access.get(villager);
        if (points >= VillagerFoodAccess.BREEDING_THRESHOLD) return false;
        Inventory inventory = villager.getInventory(); boolean changed = false;
        for (int slot = 0; slot < inventory.getSize() && points < VillagerFoodAccess.BREEDING_THRESHOLD; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty()) continue;
            Integer perItem = current.foodPoints().get(ItemUtils.resolveItemId(stack));
            if (perItem == null || perItem <= 0) continue;
            int count = VillagerInventoryMath.unitsToReach(points, perItem, stack.getAmount(), VillagerFoodAccess.BREEDING_THRESHOLD);
            if (count == 0) continue;
            ItemStack before = stack.clone(); ItemStack after = stack.clone(); after.setAmount(stack.getAmount() - count);
            inventory.setItem(slot, after.getAmount() == 0 ? null : after);
            try { access.set(villager, points + count * perItem); }
            catch (RuntimeException failed) { inventory.setItem(slot, before); throw failed; }
            points += count * perItem; changed = true;
        }
        return changed;
    }

    private boolean farm(Tracker tracker, VillagerContentSnapshot current) {
        Villager villager = tracker.villager;
        if (tracker.target != null && resident(tracker.target)) {
            ImmutableBlockState state = CustomBlockUtils.getStateIfResident(tracker.target);
            var crop = state == null || state.isEmpty() ? null : ManagedCropBlockBehavior.byId(state.owner().value().id());
            if (crop != null && crop.isMature(state) && tracker.targetTries++ < 10) {
                if (villager.getLocation().distanceSquared(tracker.target.getLocation().add(.5, .5, .5)) > 4) {
                    villager.getPathfinder().moveTo(tracker.target.getLocation().add(.5, 0, .5), .6); return true;
                }
                Block target = tracker.target; tracker.target = null; return harvest(villager, target, crop);
            }
        }
        tracker.target = null; tracker.targetTries = 0;
        Location center = villager.getLocation();
        for (int n = 0; n < current.settings().blockBudget(); n++) {
            int position = Math.floorMod(tracker.scanCursor++, 147);
            int dx = position % 7 - 3, dz = position / 7 % 7 - 3, dy = position / 49 - 1;
            Block target = center.getWorld().getBlockAt(center.getBlockX() + dx, center.getBlockY() + dy, center.getBlockZ() + dz);
            if (!resident(target)) continue;
            ImmutableBlockState state = CustomBlockUtils.getStateIfResident(target);
            var crop = state == null || state.isEmpty() ? null : ManagedCropBlockBehavior.byId(state.owner().value().id());
            if (crop != null && crop.isMature(state)) { tracker.target = target; return true; }
            if (target.getType().isAir() && center.distanceSquared(target.getLocation().add(.5, .5, .5)) <= 4
                    && plant(villager, target, current)) return true;
        }
        return false;
    }

    private boolean harvest(Villager villager, Block target, ManagedCropBlockBehavior crop) {
        if (!crop.options().villageHarvest()) return false;
        var prepared = crop.prepareHarvest(target, villager);
        if (prepared == null) return false;
        var reset = crop.resetState(prepared.expected());
        EntityChangeBlockEvent change = new EntityChangeBlockEvent(villager, target,
                BlockStateUtils.fromBlockData(reset.customBlockState().minecraftState()));
        callChange(change); if (change.isCancelled()) return false;
        if (prepared.expectedPartner() != null) {
            Block partner = target.getRelative(BlockFace.UP);
            if (!resident(partner)) return false;
            ImmutableBlockState other = CustomBlockUtils.getStateIfResident(partner);
            if (other == prepared.expectedPartner()) {
                EntityChangeBlockEvent remove = new EntityChangeBlockEvent(villager, partner, Bukkit.createBlockData(Material.AIR));
                callChange(remove); if (remove.isCancelled()) return false;
            }
        }
        if (!crop.commitHarvest(target, prepared)) return false;
        for (ItemStack output : prepared.drops()) if (!output.isEmpty()) target.getWorld().dropItemNaturally(target.getLocation().add(.5, .5, .5), output);
        return true;
    }

    private boolean plant(Villager villager, Block target, VillagerContentSnapshot current) {
        Inventory inventory = villager.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot); if (stack == null || stack.isEmpty()) continue;
            var candidates = current.plants().get(ItemUtils.resolveItemId(stack)); if (candidates == null) continue;
            for (var crop : candidates) {
                if (!crop.canPlantAt(target)) continue;
                ItemStack expected = stack.clone(); ImmutableBlockState state = crop.plantingState();
                EntityChangeBlockEvent change = new EntityChangeBlockEvent(villager, target,
                        BlockStateUtils.fromBlockData(state.customBlockState().minecraftState()));
                callChange(change);
                if (change.isCancelled() || !target.getType().isAir() || !sameStack(inventory.getItem(slot), expected)) continue;
                if (!CraftEngineBlocks.place(target.getLocation(), state, true)) continue;
                if (!sameStack(inventory.getItem(slot), expected)) {
                    if (CustomBlockUtils.getStateIfResident(target) == state) CraftEngineBlocks.remove(target);
                    return false;
                }
                ItemStack remainder = expected.clone(); remainder.setAmount(expected.getAmount() - 1);
                inventory.setItem(slot, remainder.getAmount() == 0 ? null : remainder); return true;
            }
        }
        return false;
    }

    private void callChange(EntityChangeBlockEvent event) {
        EntityChangeBlockEvent previous = manualBlockChange.get(); manualBlockChange.set(event);
        try { Bukkit.getPluginManager().callEvent(event); }
        finally { if (previous == null) manualBlockChange.remove(); else manualBlockChange.set(previous); }
    }

    private boolean share(Villager donor, VillagerContentSnapshot current) {
        if (food == null) return false;
        Inventory inventory = donor.getInventory(); int total = 0;
        for (ItemStack stack : inventory.getStorageContents()) if (stack != null && !stack.isEmpty())
            total += current.foodPoints().getOrDefault(ItemUtils.resolveItemId(stack), 0) * stack.getAmount();
        if (total < 24) return false;
        Villager receiver = null;
        for (Entity nearby : donor.getNearbyEntities(3, 1.5, 3))
            if (nearby instanceof Villager other && owned(other) && other.getAge() >= 0 && food.get(other) < 12) { receiver = other; break; }
        if (receiver == null) return false;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot); if (stack == null || stack.isEmpty()) continue;
            String id = ItemUtils.resolveItemId(stack); Integer points = current.foodPoints().get(id);
            if (points == null || points <= 0 || current.plants().containsKey(id) && stack.getAmount() <= 16) continue;
            ItemStack before = stack.clone(); ItemStack remainder = stack.clone(); remainder.setAmount(stack.getAmount() - 1);
            inventory.setItem(slot, remainder.getAmount() == 0 ? null : remainder);
            ItemStack offered = before.clone(); offered.setAmount(1);
            Item dropped;
            try { dropped = donor.getWorld().dropItem(donor.getLocation().add(0, 1, 0), offered); }
            catch (RuntimeException failure) { inventory.setItem(slot, before); throw failure; }
            if (!dropped.isValid()) { inventory.setItem(slot, before); return false; }
            var vector = receiver.getLocation().toVector().subtract(donor.getLocation().toVector());
            if (vector.lengthSquared() > 0) dropped.setVelocity(vector.normalize().multiply(.2).setY(.1));
            track(receiver); Tracker tracker = villagers.get(receiver.getUniqueId()); if (tracker != null) tracker.wake();
            return true;
        }
        return false;
    }

    private static boolean sameStack(ItemStack current, ItemStack expected) {
        return current != null && current.getAmount() == expected.getAmount() && current.isSimilar(expected);
    }

    @Override public synchronized void close() {
        running = false; HandlerList.unregisterAll(this); villagers.values().forEach(Tracker::stop); villagers.clear(); trades.close();
    }

    private final class Tracker {
        private final Villager villager;
        private ScheduledTask task;
        private long ticket;
        private boolean processing, wakeQueued, wakeRequested, stopped;
        private int scanCursor, itemCursor, targetTries;
        private Block target;
        Tracker(Villager villager) { this.villager = villager; scanCursor = Math.floorMod(villager.getUniqueId().hashCode(), 147); }
        synchronized void begin() { if (!stopped && task == null && !processing) schedule(1 + Math.floorMod(villager.getUniqueId().hashCode(), 20)); }
        synchronized void wake() {
            if (stopped || !running) return;
            if (processing) { wakeRequested = true; return; }
            if (wakeQueued) return;
            wakeQueued = true; schedule(1);
        }
        synchronized void schedule(long delay) {
            if (stopped || !running) return;
            long current = ++ticket;
            if (task != null) task.cancel();
            task = villager.getScheduler().runDelayed(plugin, ignored -> {
                synchronized (this) { if (current != ticket || stopped) return; task = null; processing = true; wakeQueued = false; }
                boolean changed = false;
                try { changed = work(this); }
                catch (RuntimeException failure) {
                    plugin.getLogger().warning("Villager work stopped for " + villager.getUniqueId() + ": " + failure.getMessage());
                    remove(villager.getUniqueId());
                } finally {
                    synchronized (this) {
                        processing = false;
                        long next = wakeRequested ? 1 : changed ? snapshot.settings().activeTicks() : snapshot.settings().idleTicks();
                        wakeRequested = false; schedule(next);
                    }
                }
            }, () -> remove(villager.getUniqueId()), Math.max(1, delay));
            if (task == null) { stopped = true; villagers.remove(villager.getUniqueId(), this); }
        }
        synchronized void stop() { stopped = true; ++ticket; if (task != null) task.cancel(); task = null; target = null; }
    }
}
