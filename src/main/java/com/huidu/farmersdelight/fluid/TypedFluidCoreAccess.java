package com.huidu.farmersdelight.fluid;

import com.ydxc20091.fluidcore.BukkitFluidCoreService;
import com.ydxc20091.fluidcore.api.*;
import com.ydxc20091.fluidcore.bukkit.*;
import com.ydxc20091.fluidcore.core.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Linked only after the optional plugin has registered its public service. */
final class TypedFluidCoreAccess implements FluidCoreAccess {
    private record Compiled(long registryGeneration, FluidIngredient predicate) { }
    private final JavaPlugin owner;
    private final BukkitFluidCoreService service;
    private final Map<FluidRecipeSpec, Compiled> predicates = new ConcurrentHashMap<>();
    private record ImplicitCarrier(String fluid, long amount) { }
    private volatile Map<FluidRecipeSpec, List<ImplicitCarrier>> implicitCarriers = Map.of();
    private TankRecipeProcessor processor;
    private com.ydxc20091.fluidcore.ce.TankRecipeNavigation.Registration navigation;
    private FluidRecipeBrowser browser;

    TypedFluidCoreAccess(JavaPlugin owner) {
        this.owner = owner;
        service = Bukkit.getServicesManager().load(BukkitFluidCoreService.class);
        if (service == null) throw new IllegalStateException("BukkitFluidCoreService is not registered");
    }

    @Override public boolean storageAt(Location location) {
        return location != null && location.getWorld() != null && Bukkit.isOwnedByCurrentRegion(location) && service.storageAt(location).isPresent();
    }
    @Override public boolean isNativeTank(Location location) {
        return location != null && location.getWorld() != null && Bukkit.isOwnedByCurrentRegion(location)
                && service.bridge().resolver().controller(location).isPresent();
    }
    boolean canUseHeld(Player player, Location location) {
        return player != null && location != null && location.getWorld() != null && owner.isEnabled()
                && Bukkit.isOwnedByCurrentRegion(player) && Bukkit.isOwnedByCurrentRegion(location)
                && player.isOnline() && player.isValid() && !player.isDead()
                && player.getGameMode() != org.bukkit.GameMode.SPECTATOR
                && player.hasPermission("farmersdelight.use.fluids")
                && player.getWorld().equals(location.getWorld())
                && player.getLocation().distanceSquared(location.clone().add(.5, .5, .5)) <= 25
                && com.huidu.farmersdelight.util.compat.ProtectionCompat.canUse(player, location, "farmersdelight-fluids")
                && net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine.instance().antiGriefProvider()
                .test(player, net.momirealms.craftengine.libraries.antigrieflib.Flag.OPEN_CONTAINER, location);
    }
    @Override public void validate(FluidRecipeSpec recipe) { predicate(recipe); }
    @Override public void attach(FluidRecipeManager recipes) {
        Map<FluidRecipeSpec, List<ImplicitCarrier>> carriers = new HashMap<>();
        for (FluidRecipeSpec filling : recipes.recipes()) {
            if (!filling.type().equals("fluid_filling") || filling.result().isEmpty()) continue;
            ItemStack output = FluidResultCodec.deserialize(filling.result());
            if (output == null || output.getAmount() != 1 || service.containers().resolve(output).isPresent()) continue;
            List<ImplicitCarrier> matching = new ArrayList<>();
            for (FluidRecipeSpec draining : recipes.candidates(output)) {
                if (!draining.type().equals("fluid_emptying") || draining.result().isEmpty()) continue;
                String fluid = FluidExpression.constructibleId(draining.fluidExpression());
                if (fluid != null) matching.add(new ImplicitCarrier(fluid, draining.amount()));
            }
            carriers.put(filling, List.copyOf(matching));
        }
        implicitCarriers = Map.copyOf(carriers);
        if (navigation != null) navigation.close();
        if (browser != null) browser.close();
        if (owner instanceof com.huidu.farmersdelight.FarmersDelightPlugin plugin) {
            browser = new FluidRecipeBrowser(plugin, recipes);
            navigation = service.bridge().registerRecipeNavigation(owner, browser::open);
        }
        if (processor == null) processor = new TankRecipeProcessor(recipes, this);
        service.bridge().processor(processor);
    }
    @Override public void close() {
        if (navigation != null) { navigation.close(); navigation = null; }
        if (browser != null) { browser.close(); browser = null; }
        if (service.bridge().processor() == processor) service.bridge().processor(null);
        if (processor != null) processor.clear(); predicates.clear(); implicitCarriers = Map.of();
    }

    FluidCoreBridge.Outcome processTank(com.ydxc20091.fluidcore.ce.FluidTankController controller, FluidRecipeSpec recipe, boolean simulate) {
        if (controller.hasProtectedData()) return FluidCoreBridge.Outcome.PROTECTED_DATA;
        var slots = controller.inventory(); ItemStack original = slots.input();
        if (!FluidCoreBridge.itemMatches(recipe, original)) return FluidCoreBridge.Outcome.NO_MATCH;
        try {
            if (FluidCoreBridge.hasProtectedRecipeData(original)) return FluidCoreBridge.Outcome.PROTECTED_DATA;
            FluidStorage storage = controller.storage(); FluidIngredient required = predicate(recipe);
            ItemFluidContainer handler = service.containers().resolve(original).orElse(null);
            ItemFluidReadResult read = readInput(original, handler);
            if (read != null && read.protectedData()) return FluidCoreBridge.Outcome.PROTECTED_DATA;
            boolean emptying = recipe.type().equals("fluid_emptying"); FluidVariant variant;
            if (emptying) {
                if (handler != null) {
                    FluidStack content = handler.content();
                    if (!required.matches(content) || handler.drain(content.variant(), recipe.amount(), FluidAction.SIMULATE).amount() != recipe.amount()) return FluidCoreBridge.Outcome.NO_FLUID;
                    if (!recipe.result().isEmpty() && content.amount() != recipe.amount()) return FluidCoreBridge.Outcome.UNSUPPORTED_VARIANT;
                    variant = content.variant();
                } else {
                    String id = FluidExpression.constructibleId(recipe.fluidExpression());
                    if (read != null && !read.stack().isEmpty()) return FluidCoreBridge.Outcome.PROTECTED_DATA;
                    if (recipe.result().isEmpty()) return FluidCoreBridge.Outcome.NOT_A_CONTAINER;
                    if (id == null || !service.registry().contains(id)) return FluidCoreBridge.Outcome.UNSUPPORTED_VARIANT;
                    variant = FluidVariant.of(id);
                }
            } else {
                variant = select(storage, required, recipe.amount()); if (variant == null) return FluidCoreBridge.Outcome.NO_FLUID;
                FluidStack carried = handler != null ? handler.content() : read == null ? FluidStack.EMPTY : read.stack();
                if (!recipe.result().isEmpty() && !carried.isEmpty()) return FluidCoreBridge.Outcome.PROTECTED_DATA;
            }
            ItemStack replacement;
            if (!recipe.result().isEmpty()) {
                replacement = FluidResultCodec.deserialize(recipe.result());
                if (!emptying && !recipe.type().equals("soaking")) {
                    replacement = filledReplacement(recipe, replacement, variant, recipe.amount());
                    if (replacement == null) return FluidCoreBridge.Outcome.UNSUPPORTED_VARIANT;
                }
            } else {
                if (handler == null) return FluidCoreBridge.Outcome.NOT_A_CONTAINER;
                if (emptying ? handler.drain(variant, recipe.amount(), FluidAction.EXECUTE).amount() != recipe.amount()
                        : handler.fill(FluidStack.of(variant, recipe.amount()), FluidAction.EXECUTE) != recipe.amount()) return FluidCoreBridge.Outcome.NO_FLUID;
                replacement = handler.item();
            }
            if (replacement == null || replacement.getType().isAir() || replacement.getAmount() < 1) return FluidCoreBridge.Outcome.NO_MATCH;
            if (protectedReplacement(replacement)) return FluidCoreBridge.Outcome.PROTECTED_DATA;
            ItemStack output = replacement;
            FluidCoreBridge.Outcome outcome = FluidRecipeTransaction.apply(new FluidRecipeTransaction.Operation() {
                public boolean inventoryFits() { return slots.canAcceptOutput(output); }
                public FluidRecipeTransaction.Transaction open() { return new Transaction(FluidTransaction.open()); }
                public long transfer(FluidRecipeTransaction.Transaction transaction) {
                    if (recipe.type().equals("soaking") && !recipe.consumeFluid()) return available(storage, variant, recipe.amount());
                    return emptying ? storage.insert(variant, recipe.amount(), ((Transaction) transaction).delegate)
                            : storage.extract(variant, recipe.amount(), ((Transaction) transaction).delegate);
                }
                public boolean replace(FluidRecipeTransaction.Transaction transaction) { return slots.completeOne(original, output, ((Transaction) transaction).delegate); }
            }, recipe.amount(), emptying, simulate, owner.getLogger()::warning);
            if (outcome.success()) recordStatistic(null, recipe, output);
            return outcome;
        } catch (StorageAccessException rejected) { return FluidCoreBridge.Outcome.INVALID_THREAD; }
        catch (RuntimeException failure) { owner.getLogger().warning("Tank recipe " + recipe.id() + " failed: " + failure.getMessage()); return FluidCoreBridge.Outcome.TRANSACTION_FAILED; }
    }

    FluidCoreBridge.Outcome processGenericTank(com.ydxc20091.fluidcore.ce.FluidTankController controller,
                                              boolean emptying, boolean simulate) {
        if (controller.hasProtectedData()) return FluidCoreBridge.Outcome.PROTECTED_DATA;
        ItemStack original = controller.inventory().input();
        if (original == null || original.getType().isAir()) return FluidCoreBridge.Outcome.NO_MATCH;
        boolean committed = false;
        try {
            if (FluidCoreBridge.hasProtectedRecipeData(original)) return FluidCoreBridge.Outcome.PROTECTED_DATA;
            FluidStorage storage = controller.storage();
            var transfers = service.containerTransfers();
            long maximum = emptying ? Math.max(0, storage.capacity(0) - storage.content(0).amount())
                    : storage.content(0).amount();
            var preview = emptying ? transfers.tryEmptyContainer(original, storage, maximum, FluidAction.SIMULATE)
                    : transfers.tryFillContainer(original, storage, maximum, FluidAction.SIMULATE);
            if (!preview.success()) return genericOutcome(preview.status());
            ItemStack output = preview.replacement();
            if (output == null || output.getAmount() != 1 || output.getType().isAir()) return FluidCoreBridge.Outcome.NO_MATCH;
            if (protectedReplacement(output)) return FluidCoreBridge.Outcome.PROTECTED_DATA;
            if (!controller.inventory().canAcceptOutput(output)) return FluidCoreBridge.Outcome.NO_INVENTORY_SPACE;
            if (simulate) return FluidCoreBridge.Outcome.READY;
            try (FluidTransaction transaction = FluidTransaction.open()) {
                var actual = emptying ? transfers.tryEmptyContainer(original, storage, maximum, FluidAction.EXECUTE, transaction)
                        : transfers.tryFillContainer(original, storage, maximum, FluidAction.EXECUTE, transaction);
                if (!actual.success()) return genericOutcome(actual.status());
                if (!actual.moved().equals(preview.moved()) || !actual.replacement().equals(output))
                    return FluidCoreBridge.Outcome.TRANSACTION_FAILED;
                if (!controller.inventory().completeOne(original, output, transaction)) return FluidCoreBridge.Outcome.NO_INVENTORY_SPACE;
                try { transaction.commit(); committed = true; }
                catch (FluidTransaction.CommitNotificationException committedNotification) {
                    committed = true;
                    owner.getLogger().log(java.util.logging.Level.FINE, "Native container conversion committed with a notification failure", committedNotification);
                }
            }
            recordStatistic(null, emptying ? "draining" : "filling", output);
            return FluidCoreBridge.Outcome.SUCCESS;
        } catch (StorageAccessException rejected) { return committed ? FluidCoreBridge.Outcome.SUCCESS : FluidCoreBridge.Outcome.INVALID_THREAD; }
        catch (RuntimeException failure) {
            if (committed) {
                try { owner.getLogger().log(java.util.logging.Level.FINE, "Cannot notify an already committed native container conversion", failure); }
                catch (RuntimeException ignored) { }
                return FluidCoreBridge.Outcome.SUCCESS;
            }
            owner.getLogger().warning("Native container conversion failed: " + failure.getMessage());
            return FluidCoreBridge.Outcome.TRANSACTION_FAILED;
        }
    }
    private static FluidCoreBridge.Outcome genericOutcome(ItemContainerTransferResult.Status status) {
        return switch (status) {
            case NOT_A_CONTAINER -> FluidCoreBridge.Outcome.NOT_A_CONTAINER;
            case PROTECTED_DATA -> FluidCoreBridge.Outcome.PROTECTED_DATA;
            case NO_TRANSFER -> FluidCoreBridge.Outcome.NO_FLUID;
            case ATOMIC_TRANSFER_UNSUPPORTED -> FluidCoreBridge.Outcome.TRANSACTION_FAILED;
            case SUCCESS -> FluidCoreBridge.Outcome.SUCCESS;
        };
    }

    private FluidIngredient predicate(FluidRecipeSpec recipe) {
        FluidRegistry registry = service.registry();
        long generation = registry.snapshot().generation();
        Compiled old = predicates.get(recipe);
        if (old != null && old.registryGeneration() == generation) return old.predicate();
        FluidIngredient predicate = FluidIngredient.parseConfiguration(aliases(recipe.fluidExpression(), registry), registry, recipe.amount());
        if (predicates.size() >= 16384) predicates.clear();
        predicates.put(recipe, new Compiled(generation, predicate));
        return predicate;
    }

    private static Object aliases(Object value, FluidRegistry registry) {
        if (value instanceof String text && text.startsWith("#")) return "#" + effectiveTag(text.substring(1), registry);
        if (value instanceof List<?> list) return list.stream().map(part -> aliases(part, registry)).toList();
        if (!(value instanceof Map<?, ?> raw)) return value;
        Map<String, Object> copy = new LinkedHashMap<>();
        raw.forEach((key, part) -> copy.put(String.valueOf(key), part));
        if (copy.get("tag") instanceof String tag) copy.put("tag", effectiveTag(tag.replaceFirst("^#", ""), registry));
        if (copy.containsKey("any-of")) copy.put("any-of", aliases(copy.get("any-of"), registry));
        return copy;
    }
    private static String effectiveTag(String requested, FluidRegistry registry) {
        FluidKey tag = FluidKey.of(requested);
        return FluidTagResolution.effectiveTag(requested, registry.snapshot().tags().containsKey(tag), registry.tagMembers(tag));
    }

    @Override public FluidCoreBridge.Outcome process(Player player, int slot, Location location, FluidRecipeSpec recipe, boolean simulate) {
        if (player == null || location == null || location.getWorld() == null || recipe == null) return FluidCoreBridge.Outcome.NO_MATCH;
        if (!Bukkit.isOwnedByCurrentRegion(player) || !Bukkit.isOwnedByCurrentRegion(location)) return FluidCoreBridge.Outcome.INVALID_THREAD;
        if (!player.isOnline() || !player.isValid() || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return FluidCoreBridge.Outcome.NO_MATCH;
        var inventory = player.getInventory();
        if (slot < 0 || slot >= inventory.getSize()) return FluidCoreBridge.Outcome.NO_MATCH;
        ItemStack live = inventory.getItem(slot);
        if (!FluidCoreBridge.itemMatches(recipe, live)) return FluidCoreBridge.Outcome.NO_MATCH;
        ItemStack original = live.clone();
        try {
            if (FluidCoreBridge.hasProtectedRecipeData(original)) return FluidCoreBridge.Outcome.PROTECTED_DATA;
            Optional<FluidStorage> resolved = service.storageAt(location);
            if (resolved.isEmpty()) return FluidCoreBridge.Outcome.NO_STORAGE;
            FluidStorage storage = resolved.get();
            storage.context().checkAccess();
            if (!storage.supportsTransactions()) return FluidCoreBridge.Outcome.TRANSACTION_FAILED;
            FluidIngredient required = predicate(recipe);
            ItemFluidContainer handler = service.containers().resolve(original).orElse(null);
            ItemFluidReadResult read = readInput(original, handler);
            if (read != null && read.protectedData()) return FluidCoreBridge.Outcome.PROTECTED_DATA;
            boolean emptying = recipe.type().equals("fluid_emptying");
            FluidVariant variant;
            if (emptying) {
                if (handler != null) {
                    FluidStack current = handler.content();
                    if (!required.matches(current)) return FluidCoreBridge.Outcome.NO_FLUID;
                    variant = current.variant();
                    if (handler.drain(variant, recipe.amount(), FluidAction.SIMULATE).amount() != recipe.amount()) return FluidCoreBridge.Outcome.NO_FLUID;
                    if (!recipe.result().isEmpty() && current.amount() != recipe.amount()) return FluidCoreBridge.Outcome.UNSUPPORTED_VARIANT;
                } else {
                    if (read != null && !read.stack().isEmpty()) return FluidCoreBridge.Outcome.PROTECTED_DATA;
                    if (recipe.result().isEmpty()) return FluidCoreBridge.Outcome.NOT_A_CONTAINER;
                    String id = FluidExpression.constructibleId(recipe.fluidExpression());
                    if (id == null) return FluidCoreBridge.Outcome.UNSUPPORTED_VARIANT;
                    if (!service.registry().contains(id)) return FluidCoreBridge.Outcome.NO_FLUID;
                    variant = FluidVariant.of(id);
                }
            } else {
                variant = select(storage, required, recipe.amount());
                if (variant == null) return FluidCoreBridge.Outcome.NO_FLUID;
                FluidStack carried = handler != null ? handler.content() : read == null ? FluidStack.EMPTY : read.stack();
                if (!recipe.result().isEmpty() && !carried.isEmpty()) return FluidCoreBridge.Outcome.PROTECTED_DATA;
            }

            ItemStack replacement;
            if (!recipe.result().isEmpty()) {
                replacement = FluidResultCodec.deserialize(recipe.result());
                if (!emptying && !recipe.type().equals("soaking")) {
                    replacement = filledReplacement(recipe, replacement, variant, recipe.amount());
                    if (replacement == null) return FluidCoreBridge.Outcome.UNSUPPORTED_VARIANT;
                }
            } else {
                if (handler == null) return FluidCoreBridge.Outcome.NOT_A_CONTAINER;
                if (emptying) {
                    if (handler.drain(variant, recipe.amount(), FluidAction.EXECUTE).amount() != recipe.amount()) return FluidCoreBridge.Outcome.NO_FLUID;
                } else if (handler.fill(FluidStack.of(variant, recipe.amount()), FluidAction.EXECUTE) != recipe.amount()) return FluidCoreBridge.Outcome.NO_CAPACITY;
                replacement = handler.item();
            }
            if (replacement == null || replacement.getType().isAir() || replacement.getAmount() <= 0) return FluidCoreBridge.Outcome.NO_MATCH;
            if (protectedReplacement(replacement)) return FluidCoreBridge.Outcome.PROTECTED_DATA;
            BukkitStorageContext context = BukkitStorageContext.entity(player);
            context.checkSameContext(storage.context());
            ItemSlotAccess access = new ItemSlotAccess(inventory, slot, context);
            ItemStack output = replacement;
            FluidVariant chosen = variant;
            boolean retain = emptying && player.getGameMode() == org.bukkit.GameMode.CREATIVE;
            FluidCoreBridge.Outcome outcome = FluidRecipeTransaction.apply(new FluidRecipeTransaction.Operation() {
                @Override public boolean inventoryFits() { return retain ? original.equals(inventory.getItem(slot)) : FluidCoreBridge.canReplace(inventory, slot, original, output); }
                @Override public FluidRecipeTransaction.Transaction open() { return new Transaction(FluidTransaction.open()); }
                @Override public long transfer(FluidRecipeTransaction.Transaction transaction) {
                    if (recipe.type().equals("soaking") && !recipe.consumeFluid()) return available(storage, chosen, recipe.amount());
                    return emptying ? storage.insert(chosen, recipe.amount(), ((Transaction) transaction).delegate)
                            : storage.extract(chosen, recipe.amount(), ((Transaction) transaction).delegate);
                }
                @Override public boolean replace(FluidRecipeTransaction.Transaction transaction) {
                    context.checkAccess();
                    return original.equals(inventory.getItem(slot)) && (retain || access.replaceOne(output, ((Transaction) transaction).delegate));
                }
            }, recipe.amount(), emptying, simulate, owner.getLogger()::warning);
            if (outcome.success()) recordStatistic(player.getUniqueId(), recipe, output);
            return outcome;
        } catch (StorageAccessException rejected) { return FluidCoreBridge.Outcome.INVALID_THREAD; }
        catch (RuntimeException failed) { owner.getLogger().warning("Fluid recipe " + recipe.id() + " failed: " + failed.getMessage()); return FluidCoreBridge.Outcome.TRANSACTION_FAILED; }
    }
    private ItemFluidReadResult readInput(ItemStack input, ItemFluidContainer handler) {
        // Resolved providers already validate the original native record; reuse their detached result.
        if (handler != null) return handler.readResult();
        return FluidCoreBridge.requiresNativeFluidRecordValidation(input) ? service.itemData().read(input) : null;
    }
    private boolean protectedReplacement(ItemStack item) {
        return FluidCoreBridge.hasProtectedRecipeData(item)
                || FluidCoreBridge.requiresNativeFluidRecordValidation(item) && service.itemData().read(item).protectedData();
    }
    private void recordStatistic(java.util.UUID player, FluidRecipeSpec recipe, ItemStack output) {
        String activity = switch (recipe.type()) { case "fluid_filling" -> "filling"; case "fluid_emptying" -> "draining"; default -> "soaking"; };
        recordStatistic(player, activity, output);
    }
    private void recordStatistic(java.util.UUID player, String activity, ItemStack output) {
        try { if (owner instanceof com.huidu.farmersdelight.FarmersDelightPlugin plugin && plugin.statistics() != null) {
            String item = net.momirealms.craftengine.bukkit.item.BukkitItemManager.instance().wrap(output).id().toString();
            plugin.statistics().committed(player, activity, item, output.getAmount());
        } } catch (RuntimeException notification) {
            owner.getLogger().log(java.util.logging.Level.FINE, "Cannot record an already committed fluid recipe", notification);
        }
    }
    private ItemStack filledReplacement(FluidRecipeSpec recipe, ItemStack result, FluidVariant actual, long totalAmount) {
        if (result == null || result.getAmount() < 1) return null;
        int count = result.getAmount();
        ItemFluidContainer handler = service.containers().resolve(result).orElse(null);
        // A fixed item without a container codec must have a matching inverse definition.
        // Otherwise its configured item could encode more fluid than the tank actually supplied.
        if (handler == null) {
            if (!actual.components().isEmpty() || count != 1) return null;
            boolean inverse = implicitCarriers.getOrDefault(recipe, List.of()).stream()
                    .anyMatch(carrier -> carrier.amount() == totalAmount && carrier.fluid().equals(actual.fluid().toString()));
            return inverse ? result : null;
        }
        if (handler.readResult().protectedData() || totalAmount % count != 0) return null;
        long unit = totalAmount / count;
        FluidStack configured = handler.content();
        if (!configured.isEmpty()) {
            if (configured.amount() != unit || !configured.variant().fluid().equals(actual.fluid())) return null;
            if (!configured.variant().equals(actual)) {
                if (handler.drain(configured.variant(), unit, FluidAction.EXECUTE).amount() != unit
                        || handler.fill(FluidStack.of(actual, unit), FluidAction.EXECUTE) != unit) return null;
            }
        } else if (handler.fill(FluidStack.of(actual, unit), FluidAction.EXECUTE) != unit) return null;
        ItemStack output = handler.item(); output.setAmount(count); return output;
    }

    private static FluidVariant select(FluidStorage storage, FluidIngredient predicate, long amount) {
        int count = storage.tanks();
        if (count == 1) {
            FluidStack content = storage.content(0);
            return !content.isEmpty() && predicate.matchesIdentity(content)
                    && storage.drain(content.variant(), amount, FluidAction.SIMULATE).amount() == amount ? content.variant() : null;
        }
        Set<FluidVariant> checked = new HashSet<>();
        for (int index = 0; index < count; index++) {
            FluidStack content = storage.content(index);
            if (content.isEmpty() || !checked.add(content.variant()) || !predicate.matchesIdentity(content)) continue;
            if (storage.drain(content.variant(), amount, FluidAction.SIMULATE).amount() == amount) return content.variant();
        }
        return null;
    }
    private static long available(FluidStorage storage, FluidVariant variant, long amount) {
        long remaining = amount;
        for (int index = 0, count = storage.tanks(); index < count; index++) {
            FluidStack content = storage.content(index);
            if (!content.isEmpty() && variant.equals(content.variant())) remaining -= Math.min(remaining, content.amount());
            if (remaining == 0) break;
        }
        return amount - remaining;
    }
    private static final class Transaction implements FluidRecipeTransaction.Transaction {
        final FluidTransaction delegate;
        Transaction(FluidTransaction delegate) { this.delegate = delegate; }
        public void commit() { delegate.commit(); }
        public boolean committed() { return delegate.isCommitted(); }
        public void close() { delegate.close(); }
    }
}
