package com.huidu.farmersdelight.fluid;

import com.huidu.farmersdelight.recipe.AdvancedRecipeTags;
import com.huidu.farmersdelight.recipe.RecipeIngredient;
import com.huidu.farmersdelight.recipe.RecipeItemCodec;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Optional FluidCore integration. Each operation resolves current storage and commits on its owner thread. */
public final class FluidCoreBridge {
    private static final org.bukkit.NamespacedKey PRESERVED_TANK_DATA =
            new org.bukkit.NamespacedKey("fluidcore", "tank_data");
    public enum Outcome {
        READY, SUCCESS, UNAVAILABLE, INVALID_THREAD, NO_MATCH, NO_STORAGE, NO_FLUID, NO_CAPACITY,
        NOT_A_CONTAINER, PROTECTED_DATA, NO_INVENTORY_SPACE, UNSUPPORTED_VARIANT, TRANSACTION_FAILED;

        public boolean ready() { return this == READY; }
        public boolean success() { return this == SUCCESS; }
    }

    private final JavaPlugin owner;
    private volatile Api api;
    private volatile Plugin failedBinding;
    private volatile String unavailableReason = "FluidCore is not enabled";

    public FluidCoreBridge(JavaPlugin owner) { this.owner = java.util.Objects.requireNonNull(owner); }
    public static FluidCoreBridge create(JavaPlugin plugin) { return new FluidCoreBridge(plugin); }

    public boolean available() { return bindings() != null; }
    public String unavailableReason() { return unavailableReason; }

    /** Explicit reload hook; failed API binding is otherwise cached per plugin instance. */
    public synchronized void resetBindings() { api = null; failedBinding = null; }

    /** Checks availability without retaining a storage handle beyond this call. */
    public boolean storageAt(Location location) {
        Api bound = bindings();
        if (bound == null || location == null || location.getWorld() == null
                || !Bukkit.isOwnedByCurrentRegion(location)) return false;
        try { return bound.storage(location).isPresent(); }
        catch (RuntimeException unavailable) { return false; }
    }

    public Outcome process(Player player, int slot, Location location, FluidRecipeSpec recipe) {
        return process(player, slot, location, recipe, false);
    }

    public static boolean itemMatches(FluidRecipeSpec recipe, ItemStack item) {
        return recipe != null && item != null && !item.getType().isAir() && matches(item, recipe.ingredient());
    }

    /** Simulation rolls native storage changes back and never writes a live inventory. */
    public Outcome process(Player player, int slot, Location location, FluidRecipeSpec recipe, boolean simulate) {
        Api bound = bindings();
        if (bound == null) return Outcome.UNAVAILABLE;
        if (player == null || location == null || location.getWorld() == null || recipe == null) return Outcome.NO_MATCH;
        if (!Bukkit.isOwnedByCurrentRegion(player) || !Bukkit.isOwnedByCurrentRegion(location)) return Outcome.INVALID_THREAD;
        if (!player.isOnline() || !player.isValid() || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return Outcome.NO_MATCH;
        PlayerInventory inventory = player.getInventory();
        if (slot < 0 || slot >= inventory.getSize()) return Outcome.NO_MATCH;
        ItemStack live = inventory.getItem(slot);
        if (live == null || live.getType().isAir() || !matches(live, recipe.ingredient())) return Outcome.NO_MATCH;
        ItemStack original = live.clone();
        try {
            if (hasForeignFluidData(original) || hasPreservedTankData(original)) return Outcome.PROTECTED_DATA;
            Object read = bound.call(bound.itemData, bound.itemDataService, "read", original);
            if ((boolean) bound.call(bound.readResult, read, "protectedData")) return Outcome.PROTECTED_DATA;
            Optional<?> found = bound.storage(location);
            if (found.isEmpty()) return Outcome.NO_STORAGE;
            Object storage = found.get();
            Object storageContext = bound.call(bound.storage, storage, "context");
            bound.call(bound.context, storageContext, "checkAccess");
            if (!(boolean) bound.call(bound.storage, storage, "supportsTransactions")) return Outcome.TRANSACTION_FAILED;

            Optional<?> handlerFound = (Optional<?>) bound.call(bound.containers, bound.containerService, "resolve", original);
            Object handler = handlerFound.orElse(null);
            if (handler != null && (boolean) bound.call(bound.readResult,
                    bound.call(bound.handler, handler, "readResult"), "protectedData")) return Outcome.PROTECTED_DATA;
            Object variant;
            boolean emptying = recipe.type().equals("fluid_emptying");
            if (emptying) {
                if (handler != null) {
                    Object current = bound.call(bound.handler, handler, "content");
                    if (bound.empty(current)) return Outcome.NO_FLUID;
                    variant = bound.call(bound.stack, current, "variant");
                    if (!bound.matches(variant, recipe)) return Outcome.NO_FLUID;
                    Object drained = bound.call(bound.handler, handler, "drain", variant, recipe.amount(), bound.simulate);
                    if (bound.amount(drained) != recipe.amount()) return Outcome.NO_FLUID;
                    if (!recipe.result().isEmpty() && bound.amount(current) != recipe.amount()) return Outcome.UNSUPPORTED_VARIANT;
                } else {
                    Object dataStack = bound.call(bound.readResult, read, "stack");
                    if (!bound.empty(dataStack)) return Outcome.PROTECTED_DATA;
                    if (recipe.result().isEmpty()) return Outcome.NOT_A_CONTAINER;
                    String identity = recipe.fluidId();
                    if (recipe.fluidTag()) {
                        Object tag = bound.effectiveTag(recipe.fluidId());
                        java.util.Set<?> members = (java.util.Set<?>) bound.call(bound.registry, bound.registryService, "tagMembers", tag);
                        var resolved = FluidTagResolution.uniqueMember(members.stream().map(Object::toString).toList(),
                                fluid -> (boolean) bound.call(bound.registry, bound.registryService, "contains", fluid));
                        if (resolved.failure() != null) return resolved.failure();
                        identity = resolved.fluidId();
                    } else if (!(boolean) bound.call(bound.registry, bound.registryService, "contains", identity)) {
                        return Outcome.NO_FLUID;
                    }
                    variant = bound.call(bound.variant, null, "of", identity);
                }
            } else {
                variant = bound.selectExtractable(storage, recipe);
                if (variant == null) return Outcome.NO_FLUID;
                Object carried = handler == null ? bound.call(bound.readResult, read, "stack")
                        : bound.call(bound.handler, handler, "content");
                // A fixed result cannot discard fluid that the input already carries.
                if (!recipe.result().isEmpty() && !bound.empty(carried)) return Outcome.PROTECTED_DATA;
            }

            ItemStack replacement;
            if (!recipe.result().isEmpty()) {
                if (!recipe.type().equals("soaking")
                        && !((Map<?, ?>) bound.call(bound.variant, variant, "components")).isEmpty()) {
                    return Outcome.UNSUPPORTED_VARIANT;
                }
                replacement = RecipeItemCodec.deserializeItem(recipe.result());
            } else {
                if (handler == null) return Outcome.NOT_A_CONTAINER;
                if (emptying) {
                    Object drained = bound.call(bound.handler, handler, "drain", variant, recipe.amount(), bound.execute);
                    if (bound.amount(drained) != recipe.amount()) return Outcome.NO_FLUID;
                } else {
                    Object offered = bound.call(bound.stack, null, "of", variant, recipe.amount());
                    if ((long) bound.call(bound.handler, handler, "fill", offered, bound.execute) != recipe.amount()) return Outcome.NO_CAPACITY;
                }
                replacement = (ItemStack) bound.call(bound.handler, handler, "item");
            }
            if (replacement == null || replacement.getType().isAir() || replacement.getAmount() <= 0) return Outcome.NO_MATCH;
            if (hasForeignFluidData(replacement) || hasPreservedTankData(replacement)) return Outcome.PROTECTED_DATA;
            Object outputRead = bound.call(bound.itemData, bound.itemDataService, "read", replacement);
            if ((boolean) bound.call(bound.readResult, outputRead, "protectedData")) return Outcome.PROTECTED_DATA;
            Object entityContext = bound.call(bound.bukkitContext, null, "entity", player);
            Object slotAccess = bound.constructSlot(inventory, slot, entityContext);
            bound.call(bound.context, entityContext, "checkSameContext", storageContext);
            ItemStack output = replacement.clone();
            Object chosen = variant;
            return FluidRecipeTransaction.apply(new FluidRecipeTransaction.Operation() {
                @Override public boolean inventoryFits() { return canReplace(inventory, slot, original, output); }
                @Override public FluidRecipeTransaction.Transaction open() { return bound.openTransaction(); }
                @Override public long transfer(FluidRecipeTransaction.Transaction transaction) {
                    NativeTransaction nativeTx = (NativeTransaction) transaction;
                    if (recipe.type().equals("soaking") && !recipe.consumeFluid()) {
                        // The extractability probe ran before opening this transaction. Recheck the
                        // current identity and amount without opening a second root transaction.
                        return bound.availableAmount(storage, chosen, recipe.amount());
                    }
                    return (long) bound.call(bound.storage, storage, emptying ? "insert" : "extract",
                            chosen, recipe.amount(), nativeTx.delegate);
                }
                @Override public boolean replace(FluidRecipeTransaction.Transaction transaction) {
                    ItemStack current = (ItemStack) bound.call(bound.slot, slotAccess, "item");
                    return original.equals(current) && (boolean) bound.call(bound.slot, slotAccess, "replaceOne",
                            output, ((NativeTransaction) transaction).delegate);
                }
            }, recipe.amount(), emptying, simulate, owner.getLogger()::warning);
        } catch (RuntimeException failure) {
            if (failure.getClass().getName().endsWith("StorageAccessException")) return Outcome.INVALID_THREAD;
            owner.getLogger().warning("Fluid recipe " + recipe.id() + " failed: " + failure.getMessage());
            return Outcome.TRANSACTION_FAILED;
        }
    }

    private Api bindings() {
        Plugin fluidCore = Bukkit.getPluginManager().getPlugin("FluidCore");
        if (fluidCore == null || !fluidCore.isEnabled()) { api = null; failedBinding = null; return null; }
        Api bound = api;
        if (bound != null && bound.plugin == fluidCore) return bound;
        if (failedBinding == fluidCore) return null;
        synchronized (this) {
            if (api != null && api.plugin == fluidCore) return api;
            if (failedBinding == fluidCore) return null;
            try {
                api = new Api(fluidCore);
                unavailableReason = "";
                failedBinding = null;
            } catch (ReflectiveOperationException | RuntimeException failure) {
                unavailableReason = "FluidCore API is unavailable: " + failure.getMessage();
                api = null;
                failedBinding = fluidCore;
            }
            return api;
        }
    }

    /** Recognizable foreign storage data is preserved; it is never treated as an empty container. */
    public static boolean hasForeignFluidData(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            for (org.bukkit.NamespacedKey key : meta.getPersistentDataContainer().getKeys()) {
                if (foreignFluidKey(key.toString())) return true;
            }
        }
        var tag = BukkitItemManager.instance().wrap(item).getSparrowTag(DataComponentKeys.CUSTOM_DATA);
        return tag instanceof CompoundTag compound && foreignData(compound, 0);
    }

    /** A saved block tank may be placed to restore it, but cannot be consumed or copied by a recipe. */
    public static boolean hasPreservedTankData(ItemStack item) {
        if (item == null) return false;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(PRESERVED_TANK_DATA);
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
                || normalized.equals("papersdelight") || normalized.startsWith("papersdelight:")
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

    private static final class NativeTransaction implements FluidRecipeTransaction.Transaction {
        private final Api api;
        private final Object delegate;
        private NativeTransaction(Api api, Object delegate) { this.api = api; this.delegate = delegate; }
        @Override public void commit() { api.call(api.transaction, delegate, "commit"); }
        @Override public boolean committed() { return (boolean) api.call(api.transaction, delegate, "isCommitted"); }
        @Override public void close() { api.call(api.transaction, delegate, "close"); }
    }

    private static final class Api {
        private static final String PREFIX = "com.ydxc20091.fluidcore.";
        private final Plugin plugin;
        private final Class<?> service, storage, context, variant, stack, transaction, key;
        private final Class<?> containers, itemData, handler, readResult, registry, registrySnapshot, bukkitContext, slot;
        private final Object serviceInstance, registryService, containerService, itemDataService, simulate, execute;
        private final Constructor<?> slotConstructor;
        private final Map<Class<?>, Map<String, List<Method>>> publicMethods = new HashMap<>();
        private final Map<CallSignature, Method> calls = new ConcurrentHashMap<>();

        @SuppressWarnings({"rawtypes", "unchecked"})
        private Api(Plugin plugin) throws ReflectiveOperationException {
            this.plugin = plugin;
            ClassLoader loader = plugin.getClass().getClassLoader();
            service = load(loader, "BukkitFluidCoreService");
            storage = load(loader, "api.FluidStorage");
            context = load(loader, "api.StorageContext");
            variant = load(loader, "api.FluidVariant");
            stack = load(loader, "api.FluidStack");
            transaction = load(loader, "api.FluidTransaction");
            key = load(loader, "api.FluidKey");
            containers = load(loader, "bukkit.ItemFluidContainerRegistry");
            itemData = load(loader, "bukkit.ItemFluidData");
            handler = load(loader, "bukkit.ItemFluidContainer");
            readResult = load(loader, "bukkit.ItemFluidReadResult");
            registry = load(loader, "core.FluidRegistry");
            registrySnapshot = load(loader, "core.FluidRegistry$Snapshot");
            bukkitContext = load(loader, "bukkit.BukkitStorageContext");
            slot = load(loader, "bukkit.ItemSlotAccess");
            Class<? extends Enum> action = (Class<? extends Enum>) load(loader, "api.FluidAction");
            simulate = Enum.valueOf(action, "SIMULATE");
            execute = Enum.valueOf(action, "EXECUTE");
            serviceInstance = Bukkit.getServicesManager().load((Class) service);
            if (serviceInstance == null) throw new IllegalStateException("BukkitFluidCoreService has not been registered");
            registryService = call(service, serviceInstance, "registry");
            containerService = call(service, serviceInstance, "containers");
            itemDataService = call(service, serviceInstance, "itemData");
            slotConstructor = slot.getConstructor(PlayerInventory.class, int.class, context);
        }

        private Class<?> load(ClassLoader loader, String suffix) throws ClassNotFoundException {
            Class<?> loaded = Class.forName(PREFIX + suffix, false, loader);
            Map<String, List<Method>> byName = new LinkedHashMap<>();
            for (Method method : loaded.getMethods()) {
                byName.computeIfAbsent(method.getName(), ignored -> new java.util.ArrayList<>()).add(method);
            }
            publicMethods.put(loaded, byName);
            return loaded;
        }

        private Optional<?> storage(Location location) { return (Optional<?>) call(service, serviceInstance, "storageAt", location); }
        private boolean empty(Object fluidStack) { return (boolean) call(stack, fluidStack, "isEmpty"); }
        private long amount(Object fluidStack) { return (long) call(stack, fluidStack, "amount"); }
        private NativeTransaction openTransaction() { return new NativeTransaction(this, call(transaction, null, "open")); }
        private Object constructSlot(PlayerInventory inventory, int index, Object entityContext) {
            try { return slotConstructor.newInstance(inventory, index, entityContext); }
            catch (InvocationTargetException failed) { throw failure(failed.getCause()); }
            catch (ReflectiveOperationException failed) { throw new IllegalStateException("FluidCore slot constructor failed", failed); }
        }

        private boolean matches(Object fluidVariant, FluidRecipeSpec recipe) {
            Object identity = call(variant, fluidVariant, "fluid");
            if (!recipe.fluidTag()) return recipe.fluidId().equals(identity.toString());
            return (boolean) call(registry, registryService, "hasTag", identity, effectiveTag(recipe.fluidId()));
        }

        private Object effectiveTag(String requested) {
            Object tag = call(key, null, "of", requested);
            java.util.Set<?> members = (java.util.Set<?>) call(registry, registryService, "tagMembers", tag);
            Object snapshot = call(registry, registryService, "snapshot");
            Map<?, ?> declared = (Map<?, ?>) call(registrySnapshot, snapshot, "tags");
            String effective = FluidTagResolution.effectiveTag(requested, declared.containsKey(tag), members);
            return effective.equals(requested) ? tag : call(key, null, "of", effective);
        }

        private long availableAmount(Object source, Object identity, long required) {
            call(context, call(storage, source, "context"), "checkAccess");
            long remaining = required;
            int count = (int) call(storage, source, "tanks");
            for (int tank = 0; tank < count; tank++) {
                Object content = call(storage, source, "content", tank);
                if (empty(content) || !identity.equals(call(stack, content, "variant"))) continue;
                remaining -= Math.min(remaining, amount(content));
                if (remaining == 0) return required;
            }
            return required - remaining;
        }

        private Object selectExtractable(Object source, FluidRecipeSpec recipe) {
            int count = (int) call(storage, source, "tanks");
            java.util.Set<Object> checked = new java.util.HashSet<>();
            for (int tank = 0; tank < count; tank++) {
                Object content = call(storage, source, "content", tank);
                if (empty(content)) continue;
                Object identity = call(stack, content, "variant");
                if (!checked.add(identity) || !matches(identity, recipe)) continue;
                Object probe = call(storage, source, "drain", identity, recipe.amount(), simulate);
                if (amount(probe) == recipe.amount()) return identity;
            }
            return null;
        }

        private Object call(Class<?> declaring, Object target, String name, Object... args) {
            CallSignature signature = new CallSignature(declaring, name,
                    Arrays.stream(args).map(value -> value == null ? Void.class : value.getClass()).toList());
            Method method = calls.computeIfAbsent(signature, ignored -> {
                for (Method candidate : publicMethods.get(declaring).getOrDefault(name, List.of())) {
                    Class<?>[] types = candidate.getParameterTypes();
                    if (types.length != args.length) continue;
                    boolean compatible = true;
                    for (int index = 0; index < types.length; index++) {
                        if (args[index] != null && !boxed(types[index]).isInstance(args[index])) { compatible = false; break; }
                    }
                    if (compatible) return candidate;
                }
                throw new IllegalStateException("Unsupported FluidCore API method: " + declaring.getSimpleName() + "." + name);
            });
            try { return method.invoke(target, args); }
            catch (InvocationTargetException failed) { throw failure(failed.getCause()); }
            catch (ReflectiveOperationException failed) { throw new IllegalStateException("FluidCore API invocation failed", failed); }
        }

        private static Class<?> boxed(Class<?> type) {
            if (type == int.class) return Integer.class;
            if (type == long.class) return Long.class;
            if (type == boolean.class) return Boolean.class;
            if (type == double.class) return Double.class;
            return type;
        }

        private static RuntimeException failure(Throwable cause) {
            return cause instanceof RuntimeException runtime ? runtime : new IllegalStateException("FluidCore API failed", cause);
        }
        private record CallSignature(Class<?> declaring, String name, List<Class<?>> arguments) { }
    }
}
