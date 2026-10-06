package com.huidu.farmersdelight.fluid;

import com.huidu.farmersdelight.recipe.AdvancedRecipeTags;
import com.huidu.farmersdelight.recipe.RecipeIngredient;
import com.huidu.farmersdelight.recipe.RecipeItemCodec;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Optional FluidCore service; the typed implementation is linked only when its plugin is available. */
public final class FluidCoreBridge {
    private static final org.bukkit.NamespacedKey PRESERVED_TANK_DATA = new org.bukkit.NamespacedKey("fluidcore", "tank_data");
    private static final org.bukkit.NamespacedKey CONTAINER_DATA = new org.bukkit.NamespacedKey("fluidcore", "container_data");
    private static final org.bukkit.NamespacedKey CONTAINER_INITIALIZED = new org.bukkit.NamespacedKey("fluidcore", "container_initialized");
    public enum Outcome {
        READY, SUCCESS, UNAVAILABLE, INVALID_THREAD, NO_MATCH, NO_STORAGE, NO_FLUID, NO_CAPACITY,
        NOT_A_CONTAINER, PROTECTED_DATA, NO_INVENTORY_SPACE, UNSUPPORTED_VARIANT, TRANSACTION_FAILED;
        public boolean ready() { return this == READY; }
        public boolean success() { return this == SUCCESS; }
    }
    private final JavaPlugin owner;
    private volatile FluidCoreAccess api;
    private volatile Plugin boundPlugin;
    private volatile Plugin failedBinding;
    private volatile String unavailableReason = "FluidCore is not enabled";
    public FluidCoreBridge(JavaPlugin owner) { this.owner = java.util.Objects.requireNonNull(owner); }
    public static FluidCoreBridge create(JavaPlugin plugin) { return new FluidCoreBridge(plugin); }
    public boolean available() { return bindings() != null; }
    public String unavailableReason() { return unavailableReason; }
    public synchronized void resetBindings() { if (api != null) api.close(); api = null; boundPlugin = null; failedBinding = null; }
    public void attach(FluidRecipeManager manager) { FluidCoreAccess bound = bindings(); if (bound != null) bound.attach(manager); }
    public void close() { resetBindings(); }
    public void validate(FluidRecipeSpec recipe) {
        FluidCoreAccess bound = bindings();
        if (bound != null) bound.validate(recipe);
    }
    public boolean storageAt(Location location) {
        FluidCoreAccess bound = bindings();
        if (bound == null || location == null || location.getWorld() == null || !Bukkit.isOwnedByCurrentRegion(location)) return false;
        try { return bound.storageAt(location); } catch (RuntimeException unavailable) { return false; }
    }
    public boolean nativeTankAt(Location location) {
        FluidCoreAccess bound = bindings();
        if (bound == null || location == null || location.getWorld() == null || !Bukkit.isOwnedByCurrentRegion(location)) return false;
        try { return bound.isNativeTank(location); } catch (RuntimeException unavailable) { return false; }
    }

    /** Destination behavior is available before placement; no world write or namespace guess is needed. */
    public boolean isNativeTankState(ImmutableBlockState state) {
        FluidCoreAccess bound = bindings();
        return bound != null && state != null && bound.isNativeTankState(state);
    }

    /** Only native record carriers pay for the decoder's lossless diagnostic snapshot. */
    public boolean hasProtectedNativeFluidRecord(ItemStack item) {
        if (item == null || !requiresNativeFluidRecordValidation(item)) return false;
        FluidCoreAccess bound = bindings();
        if (bound == null) return true;
        try { return bound.protectedNativeRecord(item); }
        catch (RuntimeException | LinkageError unavailable) { return true; }
    }
    public Outcome process(Player player, int slot, Location location, FluidRecipeSpec recipe) { return process(player, slot, location, recipe, false); }
    public Outcome process(Player player, int slot, Location location, FluidRecipeSpec recipe, boolean simulate) {
        FluidCoreAccess bound = bindings();
        return bound == null ? Outcome.UNAVAILABLE : bound.process(player, slot, location, recipe, simulate);
    }
    public static boolean itemMatches(FluidRecipeSpec recipe, ItemStack item) {
        return recipe != null && item != null && !item.getType().isAir() && matches(item, recipe.ingredient());
    }
    private FluidCoreAccess bindings() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("FluidCore");
        if (plugin == null || !plugin.isEnabled()) { api = null; boundPlugin = null; failedBinding = null; return null; }
        if (api != null && boundPlugin == plugin) return api;
        if (failedBinding == plugin) return null;
        synchronized (this) {
            if (api != null && boundPlugin == plugin) return api;
            try {
                FluidCoreAccess linked = new TypedFluidCoreAccess(owner);
                boundPlugin = plugin;
                api = linked;
                failedBinding = null;
                unavailableReason = "";
            } catch (RuntimeException | LinkageError incompatible) {
                unavailableReason = "FluidCore public API is unavailable: " + incompatible.getMessage();
                failedBinding = plugin;
                api = null;
            }
            return api;
        }
    }

    /** Recognizable foreign storage data is preserved; it is never treated as an empty container. */
    public static boolean hasForeignFluidData(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        for (org.bukkit.NamespacedKey key : item.getPersistentDataContainer().getKeys())
            if (foreignFluidKey(key.toString())) return true;
        var tag = BukkitItemManager.instance().wrap(item).getComponentAsSparrowTag(DataComponentKeys.CUSTOM_DATA);
        return tag instanceof CompoundTag compound && foreignData(compound, 0);
    }

    /** A saved block tank may be placed to restore it, but cannot be consumed or copied by a recipe. */
    public static boolean hasPreservedTankData(ItemStack item) {
        return item != null && item.getPersistentDataContainer().has(PRESERVED_TANK_DATA);
    }

    /** One read-only PDC view plus the complete opaque custom-data guard; no ItemMeta snapshot is made. */
    static boolean hasProtectedRecipeData(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        if (protectedRecipePdc(item.getPersistentDataContainer())) return true;
        var tag = BukkitItemManager.instance().wrap(item).getComponentAsSparrowTag(DataComponentKeys.CUSTOM_DATA);
        return tag instanceof CompoundTag compound && foreignRecipeData(compound, 0);
    }

    static boolean protectedRecipePdc(io.papermc.paper.persistence.PersistentDataContainerView data) {
        if (data.has(PRESERVED_TANK_DATA)) return true;
        for (var key : data.getKeys()) if (foreignFluidKey(key.toString())) return true;
        return false;
    }

    /** Wrong types and invalid markers still enter the FluidCore decoder rather than this empty fast path. */
    static boolean requiresNativeFluidRecordValidation(ItemStack item) {
        var data = item.getPersistentDataContainer();
        return data.has(CONTAINER_DATA) || data.has(CONTAINER_INITIALIZED) || data.has(PRESERVED_TANK_DATA);
    }

    static boolean foreignRecipeData(CompoundTag compound, int depth) {
        if (depth > 32) return true;
        for (var entry : compound.entrySet()) {
            String key = entry.getKey().toLowerCase(java.util.Locale.ROOT);
            if (entry.getKey().startsWith("fluidcore:")) continue;
            // The record decoder also protects old unnamespaced jug payloads and nested foreign data.
            if (foreignFluidKey(key) || key.startsWith("jug_")) return true;
            if (entry.getValue() instanceof CompoundTag nested && foreignRecipeData(nested, depth + 1)) return true;
        }
        return false;
    }

    private static boolean foreignData(CompoundTag compound, int depth) {
        if (depth > 32) return true;
        for (var entry : compound.entrySet()) {
            String key = entry.getKey().toLowerCase(java.util.Locale.ROOT);
            if (key.equals("fluidcore") || key.startsWith("fluidcore:")) continue;
            if (foreignFluidKey(key)) return true;
            if (entry.getValue() instanceof CompoundTag nested && foreignData(nested, depth + 1)) return true;
        }
        return false;
    }

    static boolean foreignFluidKey(String key) {
        String normalized = key.toLowerCase(java.util.Locale.ROOT);
        if (normalized.equals("fluidcore") || normalized.startsWith("fluidcore:")) return false;
        return normalized.equals("libuid") || normalized.startsWith("libuid:")
                || normalized.startsWith("jug_")
                || normalized.contains("fluid") || normalized.contains("liquid")
                || normalized.contains("jug_data") || normalized.contains("tank_data");
    }

    private static boolean matches(ItemStack item, RecipeIngredient ingredient) {
        return switch (ingredient) {
            case RecipeIngredient.Item exact -> ItemUtils.matchesItemId(item, exact.key())
                    && (exact.nbt() == null || similarSnapshot(item, exact.nbt()));
            case RecipeIngredient.AdvancedTag tag -> AdvancedRecipeTags.matches(item, tag.key());
            case RecipeIngredient.Choice choice -> choice.options().stream().anyMatch(option -> matches(item, option));
            case RecipeIngredient.Tag tag -> ItemUtils.matchesCustomOrVanillaTag(item, tag.key().toString())
                    && tag.excludedItems().stream().noneMatch(id -> ItemUtils.matchesItemId(item, id))
                    && tag.excludedTags().stream().noneMatch(id -> ItemUtils.matchesCustomOrVanillaTag(item, id.toString()));
        };
    }

    private static boolean similarSnapshot(ItemStack item, String nbt) {
        ItemStack expected = RecipeItemCodec.itemFromBase64(nbt);
        return expected != null && expected.isSimilar(item);
    }

    static boolean canReplace(PlayerInventory inventory, int slot, ItemStack input, ItemStack output) {
        if (!input.equals(inventory.getItem(slot))) return false;
        if (input.getAmount() == 1) {
            return output.getAmount() <= Math.min(inventory.getMaxStackSize(), output.getMaxStackSize());
        }
        long space = 0;
        ItemStack[] contents = inventory.getStorageContents();
        for (int index = 0; index < contents.length; index++) {
            ItemStack current = contents[index];
            if (current == null || current.getType().isAir()) {
                space += Math.min(inventory.getMaxStackSize(), output.getMaxStackSize());
            } else if (current.isSimilar(output)) {
                int size = current.getAmount() - (index == slot ? 1 : 0);
                space += Math.max(0, Math.min(inventory.getMaxStackSize(), current.getMaxStackSize()) - size);
            }
            if (space >= output.getAmount()) return true;
        }
        return false;
    }

}
