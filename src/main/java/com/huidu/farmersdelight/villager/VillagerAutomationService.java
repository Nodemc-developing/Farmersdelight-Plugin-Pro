package com.huidu.farmersdelight.villager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
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
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.PluginDisableEvent;
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
    private final VillagerCompostService compost;
    private final ThreadLocal<EntityPickupItemEvent> manualPickup = new ThreadLocal<>();
    private final ThreadLocal<EntityChangeBlockEvent> manualBlockChange = new ThreadLocal<>();
    private volatile VillagerContentSnapshot snapshot;
    private volatile VillagerFoodAccess food;
    private volatile boolean running;
    private volatile boolean externalPaused;

    public VillagerAutomationService(FarmersDelightPlugin plugin) { this.plugin = plugin; compost = new VillagerCompostService(plugin); }

    public void wake(Villager villager) {
        if (!running || !plugin.isEnabled()) return;
        track(villager);
        Tracker tracker = villagers.get(villager.getUniqueId());
        if (tracker != null) tracker.wake();
    }

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
        updateExternalMode();
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
        updateExternalMode();
        if (!incoming.settings().anyWork() || externalPaused) { villagers.values().forEach(Tracker::stop); villagers.clear(); }
        else if (running) {
            if (previous == null || !previous.settings().anyWork()) discoverResidentVillagers();
            villagers.values().forEach(Tracker::wake);
        }
    }

    @EventHandler public void externalEnabled(PluginEnableEvent event) {
        if (event.getPlugin().getName().equals("VillagersDelight")) updateExternalMode();
    }
    @EventHandler public void externalDisabled(PluginDisableEvent event) {
        if (event.getPlugin().getName().equals("VillagersDelight")) {
            externalPaused = false;
            if (running) discoverResidentVillagers();
        }
    }
    private void updateExternalMode() {
        boolean next = snapshot != null && snapshot.settings().pauseWithExternal()
                && Bukkit.getPluginManager().isPluginEnabled("VillagersDelight");
        if (next && !externalPaused) plugin.getLogger().warning("VillagersDelight is also enabled; integrated villager automation is paused to avoid duplicate processing.");
        boolean previous = externalPaused;
        externalPaused = next;
        if (next) { villagers.values().forEach(Tracker::stop); villagers.clear(); }
        else if (previous && running) discoverResidentVillagers();
    }
    private boolean locked(Villager villager) {
        var backpacks = plugin.getVillagerBackpackService();
        return backpacks != null && backpacks.isLocked(villager.getUniqueId());
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
        if (!running || externalPaused || current == null || !current.settings().pickup()
                || !current.pickupItems().contains(ItemUtils.resolveItemId(event.getEntity().getItemStack()))) return;
        Location location = event.getLocation();
        for (Entity nearby : location.getWorld().getNearbyEntities(location, 4, 2, 4))
            if (nearby instanceof Villager villager && owned(villager)) {
                track(villager); Tracker tracker = villagers.get(villager.getUniqueId()); if (tracker != null) tracker.wake();
            }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void nativePickup(EntityPickupItemEvent event) {
        if (manualPickup.get() == event || !(event.getEntity() instanceof Villager villager) || !running || externalPaused) return;
        if (locked(villager)) { event.setCancelled(true); return; }
        VillagerContentSnapshot current = snapshot;
        if (!current.settings().pickup() || !current.pickupItems().contains(ItemUtils.resolveItemId(event.getItem().getItemStack()))) return;
        // Custom carrier items must not be planted/consumed by the vanilla material-only path.
        event.setCancelled(true);
        track(villager);
        Tracker tracker = villagers.get(villager.getUniqueId()); if (tracker != null) tracker.wake();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void nativeCropChange(EntityChangeBlockEvent event) {
        if (manualBlockChange.get() == event || !running || externalPaused || !(event.getEntity() instanceof Villager villager)) return;
        if (locked(villager)) { event.setCancelled(true); return; }
        if (!snapshot.settings().harvest()) return;
        Block block = event.getBlock();
        if (!resident(block)) return;
        VillagerCrop known = snapshot.crops().find(block);
        if (known != null && !known.isVanilla()) {
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
        if (!running || externalPaused || snapshot == null || !snapshot.settings().anyWork()) return;
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
        if (externalPaused || locked(villager) || !current.settings().anyWork() || !villager.hasAI()
                || villager.getPose() == Pose.SLEEPING || villager.getTrader() != null) return false;
        boolean worked = current.settings().breed() && feed(villager, current);
        if (Boolean.TRUE.equals(villager.getWorld().getGameRuleValue(GameRule.MOB_GRIEFING))) {
            if (current.settings().pickup()) worked |= pickup(tracker, current);
            boolean farmer = villager.getAge() >= 0 && villager.getProfession() == Villager.Profession.FARMER;
            boolean workTime = !current.settings().workHoursOnly() || villager.getWorld().getTime() % 24000 < 12000;
            if (farmer && workTime) {
                if (current.settings().harvest() || current.settings().bonemeal()) worked |= farm(tracker, current);
                if (current.settings().compost()) worked |= compost.work(villager, current.compost(), current.foodRules());
            }
            if (current.settings().share() && villager.getTicksLived() >= tracker.nextShare) {
                tracker.nextShare = (long) villager.getTicksLived() + current.settings().shareInterval();
                worked |= share(villager, current);
            }
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
            if (!owned(villager) || locked(villager)) return changed;
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
        if (villager.getAge() < 0) return false;
        VillagerFoodAccess access = food;
        if (access == null) return false;
        int points = access.get(villager);
        if (points >= VillagerFoodAccess.BREEDING_THRESHOLD) return false;
        Inventory inventory = villager.getInventory(); boolean changed = false;
        for (int slot = 0; slot < inventory.getSize() && points < VillagerFoodAccess.BREEDING_THRESHOLD; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty()) continue;
            String id = ItemUtils.resolveItemId(stack);
            Integer perItem = current.foodPoints().get(id);
            if (perItem == null || perItem <= 0) continue;
            int count = Math.min(stack.getAmount(), current.foodRules().consumable(id,
                    VillagerCompostService.count(inventory, id), VillagerFoodAccess.BREEDING_THRESHOLD - points,
                    villager.getProfession() == Villager.Profession.FARMER));
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
            var crop = current.crops().find(tracker.target);
            if (crop != null && current.settings().harvest() && crop.isMature(tracker.target) && tracker.targetTries++ < 10) {
                if (villager.getLocation().distanceSquared(tracker.target.getLocation().add(.5, .5, .5)) > 4) {
                    villager.getPathfinder().moveTo(tracker.target.getLocation().add(.5, 0, .5), .6); return true;
                }
                Block target = tracker.target; tracker.target = null;
                var plan = crop.prepareHarvest(target, villager);
                return plan != null && crop.harvest(plan, this::callChange);
            }
        }
        tracker.target = null; tracker.targetTries = 0;
        Location center = villager.getLocation();
        for (int n = 0; n < current.settings().blockBudget(); n++) {
            int position = Math.floorMod(tracker.scanCursor++, 147);
            int dx = position % 7 - 3, dz = position / 7 % 7 - 3, dy = position / 49 - 1;
            Block target = center.getWorld().getBlockAt(center.getBlockX() + dx, center.getBlockY() + dy, center.getBlockZ() + dz);
            if (!resident(target)) continue;
            var crop = current.crops().find(target);
            if (crop != null && current.settings().harvest() && crop.isMature(target)) { tracker.target = target; return true; }
            if (crop != null && current.settings().bonemeal() && villager.getTicksLived() >= tracker.nextBonemeal
                    && center.distanceSquared(target.getLocation().add(.5, .5, .5)) <= 4 && bonemeal(villager, target, crop)) {
                tracker.nextBonemeal = (long) villager.getTicksLived() + current.settings().bonemealInterval();
                return true;
            }
            if (current.settings().harvest() && current.settings().planting() && (target.getType().isAir() || target.getType() == Material.WATER)
                    && center.distanceSquared(target.getLocation().add(.5, .5, .5)) <= 4
                    && plant(villager, target, current)) return true;
        }
        return false;
    }

    private boolean plant(Villager villager, Block target, VillagerContentSnapshot current) {
        Inventory inventory = villager.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot); if (stack == null || stack.isEmpty()) continue;
            var candidates = current.plants().get(ItemUtils.resolveItemId(stack)); if (candidates == null) continue;
            for (var crop : candidates) {
                if (!crop.canPlantAt(target)) continue;
                ItemStack expected = stack.clone();
                var plan = crop.preparePlant(target, villager);
                if (plan == null) continue;
                int seedSlot = slot;
                if (!crop.plant(plan, event -> {
                    callChange(event);
                    if (!sameStack(inventory.getItem(seedSlot), expected) || locked(villager)) event.setCancelled(true);
                })) continue;
                if (!sameStack(inventory.getItem(slot), expected)) {
                    if (!crop.rollbackPlant(plan)) plugin.getLogger().severe("Villager planting rollback rejected an externally changed crop at " + target.getLocation());
                    return false;
                }
                ItemStack remainder = expected.clone(); remainder.setAmount(expected.getAmount() - 1);
                inventory.setItem(slot, remainder.getAmount() == 0 ? null : remainder); return true;
            }
        }
        return false;
    }

    private boolean bonemeal(Villager villager, Block target, VillagerCrop crop) {
        Inventory inventory = villager.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty() || stack.getType() != Material.BONE_MEAL
                    || ItemUtils.getCustomItemId(stack) != null) continue;
            ItemStack expected = stack.clone(); int mealSlot = slot;
            if (!crop.bonemeal(target, villager, event -> {
                callChange(event);
                if (!sameStack(inventory.getItem(mealSlot), expected) || locked(villager)) event.setCancelled(true);
            })) return false;
            if (!sameStack(inventory.getItem(slot), expected)) return false;
            ItemStack after = expected.clone(); after.setAmount(expected.getAmount() - 1);
            inventory.setItem(slot, after.getAmount() == 0 ? null : after);
            return true;
        }
        return false;
    }

    private void callChange(EntityChangeBlockEvent event) {
        EntityChangeBlockEvent previous = manualBlockChange.get(); manualBlockChange.set(event);
        try { Bukkit.getPluginManager().callEvent(event); }
        finally { if (previous == null) manualBlockChange.remove(); else manualBlockChange.set(previous); }
    }

    private boolean share(Villager donor, VillagerContentSnapshot current) {
        if (food == null || donor.getAge() < 0 || locked(donor)) return false;
        Inventory inventory = donor.getInventory(); int total = 0;
        for (ItemStack stack : inventory.getStorageContents()) if (stack != null && !stack.isEmpty())
            total += current.foodPoints().getOrDefault(ItemUtils.resolveItemId(stack), 0) * stack.getAmount();
        if (total < current.settings().minimumFoodToShare()) return false;
        Villager receiver = null;
        for (Entity nearby : donor.getNearbyEntities(3, 1.5, 3))
            if (nearby instanceof Villager other && owned(other) && !locked(other) && other.getAge() >= 0 && food.get(other) < 12) { receiver = other; break; }
        if (receiver == null) return false;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot); if (stack == null || stack.isEmpty()) continue;
            String id = ItemUtils.resolveItemId(stack); Integer points = current.foodPoints().get(id);
            if (points == null || points <= 0 || current.foodRules().shareable(id, VillagerCompostService.count(inventory, id),
                    donor.getProfession() == Villager.Profession.FARMER) == 0) continue;
            ItemStack before = stack.clone(); ItemStack remainder = stack.clone(); remainder.setAmount(stack.getAmount() - 1);
            ItemStack offered = before.clone(); offered.setAmount(1);
            Item dropped;
            try { dropped = donor.getWorld().dropItem(donor.getLocation().add(0, 1, 0), offered); }
            catch (RuntimeException failure) { throw failure; }
            if (!dropped.isValid()) return false;
            if (!sameStack(inventory.getItem(slot), before)) {
                plugin.scheduler().runForEntity(dropped, () -> {
                    if (dropped.isValid() && sameStack(dropped.getItemStack(), offered)) dropped.remove();
                });
                return false;
            }
            inventory.setItem(slot, remainder.getAmount() == 0 ? null : remainder);
            var vector = receiver.getLocation().toVector().subtract(donor.getLocation().toVector());
            if (vector.lengthSquared() > 0 && owned(dropped)) dropped.setVelocity(vector.normalize().multiply(.2).setY(.1));
            track(receiver); Tracker tracker = villagers.get(receiver.getUniqueId()); if (tracker != null) tracker.wake();
            return true;
        }
        return false;
    }

    private static boolean sameStack(ItemStack current, ItemStack expected) {
        return current != null && current.getAmount() == expected.getAmount() && current.isSimilar(expected);
    }

    @Override public synchronized void close() {
        running = false; HandlerList.unregisterAll(this); villagers.values().forEach(Tracker::stop); villagers.clear(); trades.close(); compost.clear();
    }

    private final class Tracker {
        private final Villager villager;
        private ScheduledTask task;
        private long ticket;
        private boolean processing, wakeQueued, wakeRequested, stopped;
        private int scanCursor, itemCursor, targetTries;
        private long nextBonemeal, nextShare;
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
