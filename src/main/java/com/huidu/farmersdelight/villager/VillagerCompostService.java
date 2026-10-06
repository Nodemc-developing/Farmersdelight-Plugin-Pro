package com.huidu.farmersdelight.villager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Villager;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** Custom identities are composted without changing Minecraft's global material table. */
final class VillagerCompostService {
    record Settings(Set<String> items, int maximum, int minimumKept, double chance, Map<String, Double> chances) {
        static Settings read(ConfigurationSection config) {
            Map<String, Double> chances = new LinkedHashMap<>();
            var values = config.getConfigurationSection("villager.compost.chances");
            if (values != null) for (var entry : values.getValues(false).entrySet()) {
                double chance = Double.parseDouble(String.valueOf(entry.getValue()));
                if (!Double.isFinite(chance) || chance < 0 || chance > 1)
                    throw new IllegalArgumentException("Villager compost chance must be 0..1 for " + entry.getKey());
                chances.put(entry.getKey(), chance);
            }
            double chance = config.getDouble("villager.compost.default-chance", .3);
            if (!Double.isFinite(chance) || chance < 0 || chance > 1)
                throw new IllegalArgumentException("villager.compost.default-chance must be 0..1");
            return new Settings(Set.copyOf(config.getStringList("villager.compost.items")),
                    Math.max(1, Math.min(64, config.getInt("villager.compost.max-items-per-work", 20))),
                    Math.max(0, Math.min(512, config.getInt("villager.compost.minimum-kept-per-item", 32))),
                    chance, Map.copyOf(chances));
        }
    }
    private final FarmersDelightPlugin plugin;
    private record Position(UUID world, int x, int y, int z) {
        static Position of(Block block) { return new Position(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()); }
    }
    private final Map<Position, Long> maturation = new ConcurrentHashMap<>();
    VillagerCompostService(FarmersDelightPlugin plugin) { this.plugin = plugin; }

    static int expendable(int held, int minimumKept, int seedReserve) {
        return Math.max(0, held - Math.max(minimumKept, seedReserve));
    }

    boolean work(Villager villager, Settings settings, VillagerFoodRules food) {
        if (villager.getProfession() != Villager.Profession.FARMER
                || !Boolean.TRUE.equals(villager.getWorld().getGameRuleValue(GameRule.MOB_GRIEFING))) return false;
        Block composter = nearby(villager);
        if (composter == null || !(composter.getBlockData() instanceof Levelled current)) return false;
        if (current.getLevel() >= current.getMaximumLevel()) return extract(villager, composter, current);
        if (current.getLevel() == 7) return mature(villager, composter, current);
        Inventory inventory = villager.getInventory();
        int consumed = 0;
        for (int slot = 0; slot < inventory.getSize() && consumed < settings.maximum(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty()) continue;
            String id = ItemUtils.resolveItemId(stack);
            if (!settings.items().contains(id) || id.startsWith("minecraft:")) continue;
            int held = count(inventory, id);
            int available = Math.min(stack.getAmount(), expendable(held, settings.minimumKept(), food.reserve(id, true)));
            for (int item = 0; item < available && consumed < settings.maximum(); item++) {
                ItemStack before = inventory.getItem(slot);
                if (before == null || !before.isSimilar(stack)) break;
                before = before.clone();
                if (!(composter.getBlockData() instanceof Levelled expected) || expected.getLevel() >= 7) return consumed > 0;
                Levelled next = (Levelled) expected.clone();
                if (expected.getLevel() == 0 || ThreadLocalRandom.current().nextDouble() < settings.chances().getOrDefault(id, settings.chance()))
                    next.setLevel(expected.getLevel() + 1);
                EntityChangeBlockEvent event = new EntityChangeBlockEvent(villager, composter, next);
                Bukkit.getPluginManager().callEvent(event);
                if (event.isCancelled() || !resident(composter) || !composter.getBlockData().equals(expected)
                        || !same(inventory.getItem(slot), before)) return consumed > 0;
                // No callback runs between these commits. A failed level update leaves inventory intact.
                if (next.getLevel() != expected.getLevel()) composter.setBlockData(next, true);
                if (!composter.getBlockData().equals(next)) return consumed > 0;
                if (next.getLevel() == 7) maturation.put(Position.of(composter), composter.getWorld().getFullTime() + 20);
                ItemStack remaining = before.clone(); remaining.setAmount(before.getAmount() - 1);
                inventory.setItem(slot, remaining.getAmount() == 0 ? null : remaining);
                consumed++;
            }
        }
        return consumed > 0;
    }

    private boolean mature(Villager villager, Block block, Levelled expected) {
        long now = block.getWorld().getFullTime();
        long ready = maturation.computeIfAbsent(Position.of(block), ignored -> now + 20);
        if (now < ready) return false;
        Levelled full = (Levelled) expected.clone(); full.setLevel(full.getMaximumLevel());
        EntityChangeBlockEvent event = new EntityChangeBlockEvent(villager, block, full);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || !resident(block) || !block.getBlockData().equals(expected)) return false;
        block.setBlockData(full, true);
        if (!block.getBlockData().equals(full)) return false;
        maturation.remove(Position.of(block));
        return true;
    }

    void clear() { maturation.clear(); }

    private boolean extract(Villager villager, Block block, Levelled expected) {
        Inventory inventory = villager.getInventory();
        ItemStack meal = new ItemStack(Material.BONE_MEAL);
        if (VillagerInventoryMath.capacity(inventory.getStorageContents(), meal) < 1) return false;
        ItemStack[] before = cloneContents(inventory);
        Levelled empty = (Levelled) expected.clone(); empty.setLevel(0);
        EntityChangeBlockEvent event = new EntityChangeBlockEvent(villager, block, empty);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || !resident(block) || !block.getBlockData().equals(expected)
                || !java.util.Arrays.equals(before, inventory.getStorageContents())) return false;
        block.setBlockData(empty, true);
        if (!block.getBlockData().equals(empty)) return false;
        if (!inventory.addItem(meal).isEmpty()) {
            // The destination was checked before the world commit; compensate a rejecting implementation.
            block.setBlockData(expected, true);
            inventory.setStorageContents(before);
            return false;
        }
        maturation.remove(Position.of(block));
        return true;
    }
    private Block nearby(Villager villager) {
        var at = villager.getLocation();
        for (int distance = 0; distance <= 2; distance++) {
            for (int x = -distance; x <= distance; x++) for (int z = -distance; z <= distance; z++) {
                if (Math.max(Math.abs(x), Math.abs(z)) != distance) continue;
                Block block = at.getWorld().getBlockAt(at.getBlockX() + x, at.getBlockY(), at.getBlockZ() + z);
                if (resident(block) && block.getType() == Material.COMPOSTER) return block;
            }
        }
        return null;
    }
    private boolean resident(Block block) {
        return block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                && plugin.scheduler().isOwnedByCurrentRegion(block.getLocation());
    }
    static int count(Inventory inventory, String id) {
        int total = 0;
        for (ItemStack stack : inventory.getStorageContents())
            if (stack != null && !stack.isEmpty() && id.equals(ItemUtils.resolveItemId(stack))) total += stack.getAmount();
        return total;
    }
    private static boolean same(ItemStack actual, ItemStack expected) {
        return actual != null && actual.getAmount() == expected.getAmount() && actual.isSimilar(expected);
    }
    private static ItemStack[] cloneContents(Inventory inventory) {
        ItemStack[] contents = inventory.getStorageContents();
        for (int index = 0; index < contents.length; index++) if (contents[index] != null) contents[index] = contents[index].clone();
        return contents;
    }
}
