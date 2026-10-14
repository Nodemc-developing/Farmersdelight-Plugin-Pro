import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.Lightable;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Temporary acceptance profile only; the runner restores its pack/config inputs. */
public final class FdVersionVerification extends JavaPlugin {
    private final Map<String, Map<String, Object>> checks = new LinkedHashMap<>();
    private JavaPlugin fd;
    private ClassLoader ceLoader, fdLoader;
    private Object engine, blockManager, tray, skilletManager;
    private Path packs;
    private Location skillet;
    private BlockState oldHeat, oldPan;
    public void onEnable() {
        fd = (JavaPlugin) Bukkit.getPluginManager().getPlugin("Farmersdelight-Plugin-Pro");
        fdLoader = fd.getClass().getClassLoader();
        ceLoader = Bukkit.getPluginManager().getPlugin("CraftEngine").getClass().getClassLoader();
        packs = getDataFolder().toPath().getParent().resolve("CraftEngine/resources");
        Bukkit.getAsyncScheduler().runDelayed(this, task -> run(), 5, TimeUnit.SECONDS);
    }
    private void run() {
        try {
            engine = call(ce("net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine"), "instance");
            blockManager = call(engine, "blockManager");
            tray = call(fd, "getTrayManager");
            skilletManager = call(fd, "getSkilletManager");
            absent("initial");
            reload(); absent("reload");
            // Simulates an operator extracting the separately distributed ZIP, never the FD JAR.
            try (ZipFile zip = new ZipFile(getDataFolder().toPath().resolve("manual-pack.zip").toFile())) {
                for (var entries = zip.entries(); entries.hasMoreElements();) {
                    var entry = entries.nextElement(); if (entry.isDirectory()) continue;
                    Path target = packs.resolve(entry.getName()).normalize();
                    require(target.startsWith(packs.normalize()), "Unsafe manual ZIP path");
                    Files.createDirectories(target.getParent());
                    try (var input = zip.getInputStream(entry)) { Files.copy(input, target); }
                }
            }
            reload();
            require(block("farmersdelight:skillet").isPresent(), "Skillet missing after manual install");
            pass("manual_install", "Standalone content ZIP loads through CE reload");
            verifyMushrooms();
            verifyRecoveredContent();
            Object packManager = call(engine, "packManager"); call(packManager, "initCachedAssets");
            Path layer = (Path) call(fd("com.huidu.farmersdelight.pack.compat.ExternalContentCoordinator"), "selectedResourceLayer");
            require(Files.isRegularFile(layer.resolve("assets/farmersdelight/textures/block/skillet_side.png")), "Manual texture missing");
            pass("manual_assets", "Installed skillet texture included");
            owner(() -> {
                World world = Bukkit.getWorlds().getFirst(); Location spawn = world.getSpawnLocation();
                skillet = new Location(world, spawn.getBlockX(), world.getMaxHeight() - 20, spawn.getBlockZ());
                Block pan = skillet.getBlock(), heat = skillet.clone().subtract(0, 1, 0).getBlock();
                require(pan.getType().isAir() && heat.getType().isAir(), "Acceptance position occupied; refusing changes");
                oldPan = pan.getState(); oldHeat = heat.getState();
                heat.setType(Material.CAMPFIRE, false);
                Lightable light = (Lightable) heat.getBlockData(); light.setLit(true); heat.setBlockData(light, false);
                Object state = call(block("farmersdelight:skillet").orElseThrow(), "defaultState");
                call(ce("net.momirealms.craftengine.bukkit.api.CraftEngineBlocks"), "place", skillet, state, false);
                call(skilletManager, "recordPlacedSkillet", skillet, new ItemStack(Material.IRON_INGOT));
                require(support(), "Default campfire should produce a skillet tray");
                Object heatConfig = call(fd, "getHeatSourceConfig");
                require(call(heatConfig, "trayRequirement", heat) == null, "Omitted tray is not automatic");
                pass("campfire_support", "Shipped config creates tray on a lit campfire with omitted tray flag");
                ItemStack remaining = (ItemStack) call(skilletManager, "insertHopperInput", skillet, new ItemStack(Material.BEEF, 3));
                require(remaining == null || remaining.getType().isAir() || remaining.getAmount() == 0, "Input rejected");
                light.setLit(false); heat.setBlockData(light, false);
                Bukkit.getPluginManager().callEvent(new BlockPhysicsEvent(heat, heat.getBlockData()));
                return null;
            });
            ownerLater(() -> {
                require(!support(), "Tray remained after extinguishing"); requireStored(3);
                pass("extinguish_preserves_input", "Physics update clears tray without losing the three ingredients");
                Block heat = skillet.clone().subtract(0,1,0).getBlock();
                Lightable light = (Lightable) heat.getBlockData(); light.setLit(true); heat.setBlockData(light, false);
                for (int i=0;i<20;i++) Bukkit.getPluginManager().callEvent(new BlockPhysicsEvent(heat, heat.getBlockData()));
                Field pending = tray.getClass().getDeclaredField("pendingUpdates"); pending.setAccessible(true);
                require(((Set<?>) pending.get(tray)).size() == 1, "Repeated notifications queued duplicate support updates");
                return null;
            });
            ownerLater(() -> {
                require(support(), "Tray did not return after reignition"); requireStored(3);
                pass("reignite_coalesced", "Twenty notifications coalesce; tray returns and items remain");
                Block heat = skillet.clone().subtract(0,1,0).getBlock(); heat.setType(Material.MAGMA_BLOCK, false);
                call(tray, "checkAndPlaceTray", skillet); require(!support(), "Full heat block should not have a tray");
                heat.setType(Material.AIR, false); call(tray, "checkAndPlaceTray", skillet); require(!support(), "Air should not have a tray");
                pass("shape_and_air", "Full magma block and air do not produce a tray");
                heat.setType(Material.CAMPFIRE, false);
                Object state = call(ce("net.momirealms.craftengine.bukkit.api.CraftEngineBlocks"), "getCustomBlockState", skillet.getBlock());
                Object behavior = call(fd("com.huidu.farmersdelight.util.CustomBlockUtils"), "getBehavior", state, fd("com.huidu.farmersdelight.block.behavior.SkilletBlockBehavior"));
                Object prop = call(behavior, "getSupportProperty");
                // Simulates loading a persisted obsolete support=false state.
                call(ce("net.momirealms.craftengine.bukkit.api.CraftEngineBlocks"), "place", skillet, call(state, "with", prop, false), false);
                Object listener = fd("com.huidu.farmersdelight.listener.ChunkLoadListener").getConstructor(fd.getClass()).newInstance(fd);
                call(listener, "onChunkLoad", new org.bukkit.event.world.ChunkLoadEvent(skillet.getChunk(), false));
                return null;
            });
            ownerLater(() -> {
                require(support(), "Chunk-load support refresh missing"); requireStored(3);
                pass("chunk_load_refresh", "FD chunk-load handler repairs stale support without losing ingredients (existing loaded CE chunk)");
                return null;
            });
            // One deleted file and one edited marker must survive both reload paths unchanged.
            Path recipe = packs.resolve("farmersdelight/configuration/recipes/cooking_pot_recipes.yml");
            Files.delete(recipe);
            Path texture = packs.resolve("farmersdelight/resourcepack/assets/farmersdelight/textures/block/skillet_side.png");
            Files.delete(texture);
            reload();
            CompletableFuture<?> fdReload = owner(() -> (CompletableFuture<?>) call(fd, "reloadEditedRecipeFilesAsync"));
            fdReload.get(60, TimeUnit.SECONDS);
            require(!Files.exists(recipe) && !Files.exists(texture), "Reload restored a deleted file");
            call(packManager, "initCachedAssets");
            layer = (Path) call(fd("com.huidu.farmersdelight.pack.compat.ExternalContentCoordinator"), "selectedResourceLayer");
            require(!Files.exists(layer.resolve("assets/farmersdelight/textures/block/skillet_side.png")), "JAR backfilled a deleted texture");
            pass("intentional_deletion", "CE and FD reload preserve deleted recipe/texture; JAR does not backfill assets");
        } catch (Throwable failure) {
            checks.put("failure", Map.of("status", "FAIL", "detail", failure.toString())); failure.printStackTrace();
        } finally {
            try {
                if (oldPan != null) owner(() -> {
                    call(skilletManager, "breakSkillet", skillet, skillet, false);
                    // Restore the two previously empty positions after removing the CE entity.
                    call(ce("net.momirealms.craftengine.bukkit.api.CraftEngineBlocks"), "remove", skillet.getBlock());
                    oldPan.update(true, false); oldHeat.update(true, false); return null;
                });
            } catch (Throwable failure) { checks.put("cleanup", Map.of("status","FAIL","detail",failure.toString())); failure.printStackTrace(); }
            try {
                Files.createDirectories(getDataFolder().toPath());
                Files.writeString(getDataFolder().toPath().resolve("report.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create()
                    .toJson(Map.of("overall", checks.values().stream().allMatch(v -> "PASS".equals(v.get("status"))) ? "PASS" : "FAIL", "checks", checks)));
            } catch (Exception failure) { failure.printStackTrace(); }
        }
    }
    private void verifyMushrooms() throws Exception {
        Object groups = ce("net.momirealms.craftengine.core.block.AutoStateGroup").getField("SUGAR_CANE").get(null);
        List<?> candidates = (List<?>) call(groups, "candidates");
        int used = 0;
        for (Object candidate : candidates) if (Boolean.TRUE.equals(call(candidate, "isUsed"))) used++;
        require(candidates.size() == 15, "Unexpected sugar-cane capacity " + candidates.size());
        require(used >= 14, "Pressure fixture did not exercise the nearly full pool: " + used);
        for (String color : List.of("brown", "red")) {
            String id = "farmersdelight:" + color + "_mushroom_colony";
            require(block(id).isPresent(), "Mushroom failed to register: " + id);
            Object key = call(ce("net.momirealms.craftengine.core.util.Key"), "of", "farmersdelight:wild_" + color + "_mushroom_colony");
            require(call(call(engine, "worldManager"), "placedFeatureHolderById", key) != null, "Mushroom worldgen failed: " + key);
        }
        require(block("fd_state_pressure:first").isPresent() && block("fd_state_pressure:second").isPresent(), "Pressure fixture blocks failed");
        Object allocator = call(blockManager, "visualBlockStateAllocator");
        Map<?,?> assignments = (Map<?,?>) call(allocator, "cachedBlockStates");
        int mushroomStates = 0;
        Object saplings = ce("net.momirealms.craftengine.core.block.AutoStateGroup").getField("SAPLING").get(null);
        for (var entry : assignments.entrySet()) {
            String id = String.valueOf(entry.getKey());
            if ((id.contains("farmersdelight:brown_mushroom_colony") || id.contains("farmersdelight:red_mushroom_colony")) && id.contains("age=3")) {
                require(Boolean.TRUE.equals(call(saplings, "test", entry.getValue())), "Mature colony still uses wrong visual group " + entry);
                mushroomStates++;
            }
        }
        require(mushroomStates == 2, "Expected two allocated mature colony appearances; got " + mushroomStates);
        pass("pressure_pool", "Sugar cane " + used + "/" + candidates.size() + "; both pressure blocks and mature sapling visuals registered");
        pass("mushroom_worldgen", "Both brown/red colonies and both placed features decode on the native server");
    }
    private void absent(String phase) throws Exception {
        require(!Files.exists(packs.resolve("farmersdelight")) && !Files.exists(packs.resolve("farmersdelight_fluids")), "Content released automatically");
        require(block("farmersdelight:skillet").isEmpty(), "JAR registered skillet without manual pack");
        call(call(engine, "packManager"), "initCachedAssets");
        Path layer = (Path) call(fd("com.huidu.farmersdelight.pack.compat.ExternalContentCoordinator"), "selectedResourceLayer");
        require(!Files.exists(layer.resolve("assets/farmersdelight/textures/block/skillet_side.png")), "JAR injected assets without manual pack");
        pass(phase + "_no_auto_pack", "No extracted pack, implicit skillet definition or JAR asset fallback");
    }
    private void reload() throws Exception {
        Executor async = work -> Bukkit.getAsyncScheduler().runNow(this, task -> work.run());
        Executor owner = work -> Bukkit.getGlobalRegionScheduler().execute(this, work);
        Object result = ((CompletableFuture<?>) call(engine, "reloadPlugin", async, owner, true, true)).get(90, TimeUnit.SECONDS);
        require(Boolean.TRUE.equals(call(result, "success")), "CE reload failed");
    }
    private boolean support() throws Exception {
        Object state = call(ce("net.momirealms.craftengine.bukkit.api.CraftEngineBlocks"), "getCustomBlockState", skillet.getBlock());
        Object behavior = call(fd("com.huidu.farmersdelight.util.CustomBlockUtils"), "getBehavior", state, fd("com.huidu.farmersdelight.block.behavior.SkilletBlockBehavior"));
        return Boolean.TRUE.equals(call(state, "getNullable", call(behavior, "getSupportProperty")));
    }
    private void requireStored(int count) throws Exception {
        ItemStack input = (ItemStack) call(skilletManager, "getStoredItemSnapshot", skillet);
        require(input != null && input.getType()==Material.BEEF && input.getAmount()==count, "Stored ingredients changed during support update");
    }
    private Optional<?> block(String id) throws Exception { return (Optional<?>) call(blockManager, "blockById", call(ce("net.momirealms.craftengine.core.util.Key"), "of", id)); }
    private Class<?> ce(String name) throws Exception { return Class.forName(name, true, ceLoader); }
    private Class<?> fd(String name) throws Exception { return Class.forName(name, true, fdLoader); }
    private <T> T owner(Callable<T> body) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(this, () -> { try { result.complete(body.call()); } catch(Throwable ex) { result.completeExceptionally(ex); } });
        return result.get(30, TimeUnit.SECONDS);
    }
    private <T> T ownerLater(Callable<T> body) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskLater(this, () -> { try { result.complete(body.call()); } catch(Throwable ex) { result.completeExceptionally(ex); } }, 4);
        return result.get(30, TimeUnit.SECONDS);
    }
    private Object call(Object target, String name, Object... args) throws Exception {
        Class<?> type = target instanceof Class<?> c ? c : target.getClass();
        outer: for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            for (int i=0;i<args.length;i++) {
                Class<?> p = method.getParameterTypes()[i];
                if (p.isPrimitive()) p = p == boolean.class ? Boolean.class : p == int.class ? Integer.class : p;
                if (args[i] != null && !p.isInstance(args[i])) continue outer;
            }
            method.setAccessible(true); return method.invoke(target instanceof Class<?> ? null : target,args);
        }
        throw new NoSuchMethodException(type.getName()+"."+name+Arrays.toString(args));
    }
    private void pass(String name, String detail) { checks.put(name, Map.of("status","PASS","detail",detail)); }
    private void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
    private void verifyRecoveredContent() throws Exception {
        List<String> ids = List.of("wooden_basket", "bamboo_basket", "egg_carton", "rope_fence", "rope_fence_gate",
            "meat_skewer", "cooked_meat_skewer", "vegetable_skewer", "cooked_vegetable_skewer",
            "wild_carrots", "wild_potatoes", "wild_beetroots", "sandy_shrub", "cocoa_beans_bag", "glow_berries_bag",
            "sweet_berries_bag", "apple_crate", "chorus_fruit_crate", "brown_mushroom_crate", "red_mushroom_crate",
            "golden_apple_crate", "golden_carrot_crate");
        owner(() -> {
            for (String name : ids) {
                ItemStack item = (ItemStack) call(fd("com.huidu.farmersdelight.util.ItemUtils"), "createItem", "farmersdelight:" + name);
                require(item != null && !item.getType().isAir(), "Recovered item missing from independent pack: " + name);
                require(("farmersdelight:" + name).equals(call(fd("com.huidu.farmersdelight.util.ItemUtils"), "getCustomItemId", item)), "Recovered ID did not build as a custom item: " + name);
            }
            return null;
        });
        pass("recovered_player_items", "22 unconditional recovered items build with their correct CE IDs without the previous external pack");
        boolean copperExpected = !Bukkit.getMinecraftVersion().startsWith("1.21") ||
            Integer.parseInt(Bukkit.getMinecraftVersion().substring("1.21".length()).replaceFirst("^\\.", "").isBlank() ? "0" : Bukkit.getMinecraftVersion().substring("1.21".length()).replaceFirst("^\\.", "")) >= 9;
        owner(() -> {
            ItemStack copper = (ItemStack) call(fd("com.huidu.farmersdelight.util.ItemUtils"), "createItem", "farmersdelight:copper_knife");
            require(copperExpected == (copper != null), "Copper knife version guard did not match " + Bukkit.getMinecraftVersion());
            return null;
        });
        pass("copper_version_gate", "Copper knife " + (copperExpected ? "available" : "excluded") + " on " + Bukkit.getMinecraftVersion());
        owner(() -> { for (String basket : List.of("wooden_basket", "bamboo_basket", "basket")) verifyBasketStateChanges(basket); return null; });
    }

    private void verifyBasketStateChanges(String basketId) throws Exception {
        World world = Bukkit.getWorlds().getFirst(); Location spawn = world.getSpawnLocation();
        Location location = new Location(world, spawn.getBlockX() + 5, world.getMaxHeight() - 20, spawn.getBlockZ());
        Block basket = location.getBlock(), signal = location.clone().add(1,0,0).getBlock();
        require(basket.getType().isAir() && signal.getType().isAir(), "Basket acceptance positions occupied; refusing changes");
        BlockState beforeBasket = basket.getState(), beforeSignal = signal.getState();
        try {
            Object state = call(block("farmersdelight:" + basketId).orElseThrow(), "defaultState");
            call(ce("net.momirealms.craftengine.bukkit.api.CraftEngineBlocks"), "place", location, state, false);
            Object controller = storageController(location);
            org.bukkit.inventory.Inventory inventory = (org.bukkit.inventory.Inventory) call(controller, "inventory");
            require(inventory.getSize() == 27, "Recovered basket is not a 27-slot inventory");
            ItemStack marker = basketMarker(); inventory.setItem(0, marker);
            Object wet = call(state, "getProperty", "waterlogged"), enabled = call(state, "getProperty", "enabled"), facing = call(state, "getProperty", "facing");
            require(wet != null && enabled != null && facing != null, "Basket direction/water/redstone properties missing");
            Class<?> direction = ce("net.momirealms.craftengine.core.util.Direction");
            for (Object value : direction.getEnumConstants()) {
                Object rotated = call(state, "with", facing, value);
                call(ce("net.momirealms.craftengine.bukkit.api.CraftEngineBlocks"), "place", location, rotated, false);
                require(storageController(location) == controller, "Direction update recreated basket storage");
                require(inventory.getItem(0) != null && inventory.getItem(0).getAmount() == 7 && inventory.getItem(0).isSimilar(basketMarker()), "Direction update lost items");
            }
            Object waterlogged = call(call(state, "with", wet, true), "with", enabled, false);
            call(ce("net.momirealms.craftengine.bukkit.api.CraftEngineBlocks"), "place", location, waterlogged, false);
            require(storageController(location) == controller && inventory.getItem(0).getAmount() == 7 && inventory.getItem(0).isSimilar(basketMarker()), "Water/redstone property update lost inventory");
            pass("basket_properties_preserve_storage_" + basketId, "All six orientations and waterlogged/enabled property transitions preserve the same 27-slot storage and seven diamonds");
            verifyBasketPower(location, basketId);
        } finally {
            if (block("farmersdelight:" + basketId).isPresent()) {
                try { ((org.bukkit.inventory.Inventory) call(storageController(location), "inventory")).clear(); }
                catch (IllegalStateException alreadyRemoved) { }
            }
            call(ce("net.momirealms.craftengine.bukkit.api.CraftEngineBlocks"), "remove", basket);
            beforeBasket.update(true, false); beforeSignal.update(true, false);
        }
    }

    private Object storageController(Location location) throws Exception {
        Object world = call(fd("com.huidu.farmersdelight.util.CustomBlockUtils"), "getCEWorld", location.getWorld());
        Object pos = ce("net.momirealms.craftengine.core.world.BlockPos").getConstructor(int.class,int.class,int.class)
            .newInstance(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        Object entity = call(world, "getBlockEntityAtIfLoaded", pos);
        require(entity != null, "Basket entity did not exist after placement");
        Object composite = entity.getClass().getField("controller").get(entity);
        Object[] captured = new Object[1];
        call(composite, "let", ce("net.momirealms.craftengine.bukkit.block.entity.SimpleStorageBlockEntityController"),
            (java.util.function.Consumer<Object>) c -> captured[0] = c);
        require(captured[0] != null, "Basket has no simple storage controller");
        return captured[0];
    }

    private void verifyBasketPower(Location location, String basketId) throws Exception {
        // The caller owns the main thread; deferred checks are scheduled instead of blocking a tick.
        Object state = call(block("farmersdelight:" + basketId).orElseThrow(), "defaultState");
        call(ce("net.momirealms.craftengine.bukkit.api.CraftEngineBlocks"), "place", location, state, false);
        Object controller = storageController(location);
        org.bukkit.inventory.Inventory inventory = (org.bukkit.inventory.Inventory) call(controller, "inventory");
        inventory.setItem(0, basketMarker());
        location.clone().add(1,0,0).getBlock().setType(Material.REDSTONE_BLOCK, true);
        Object poweredState = call(ce("net.momirealms.craftengine.bukkit.api.CraftEngineBlocks"), "getCustomBlockState", location.getBlock());
        Object enabled = call(poweredState, "getProperty", "enabled");
        require(Boolean.FALSE.equals(call(poweredState, "getNullable", enabled)), "Redstone did not disable basket during the native neighbour update");
        require(storageController(location) == controller && inventory.getItem(0).getAmount() == 7 && inventory.getItem(0).isSimilar(basketMarker()), "Redstone pause lost storage");
        location.clone().add(1,0,0).getBlock().setType(Material.AIR, true);
        Object unpoweredState = call(ce("net.momirealms.craftengine.bukkit.api.CraftEngineBlocks"), "getCustomBlockState", location.getBlock());
        require(Boolean.TRUE.equals(call(unpoweredState, "getNullable", enabled)), "Signal removal did not enable basket during the native neighbour update");
        require(storageController(location) == controller && inventory.getItem(0).getAmount() == 7 && inventory.getItem(0).isSimilar(basketMarker()), "Redstone resume lost storage");
        pass("basket_native_redstone_" + basketId, "A real redstone block immediately toggles enabled in both directions without recreating storage or losing its seven items");
    }

    private ItemStack basketMarker() {
        ItemStack marker = new ItemStack(Material.DIAMOND, 7);
        var meta = marker.getItemMeta();
        meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(this, "basket_marker"), org.bukkit.persistence.PersistentDataType.STRING, "content-recovery-1.2.7");
        marker.setItemMeta(meta);
        return marker;
    }

}
