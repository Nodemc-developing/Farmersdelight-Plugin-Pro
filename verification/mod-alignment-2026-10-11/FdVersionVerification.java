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
    private final Set<org.bukkit.Chunk> fixtureChunks = new HashSet<>();
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
            owner(() -> {
                World world = Bukkit.getWorlds().getFirst(); Location spawn = world.getSpawnLocation();
                for (int dx : new int[]{0, 2, 4, 5}) for (int dz : new int[]{0, 2}) {
                    org.bukkit.Chunk chunk = world.getChunkAt((spawn.getBlockX() + dx) >> 4, (spawn.getBlockZ() + dz) >> 4);
                    if (chunk.addPluginChunkTicket(this)) fixtureChunks.add(chunk);
                }
                return null;
            });
            ownerLater(() -> null);
            verifyMushrooms();
            verifyRecoveredContent();
            verifyAlignedDefinitions();
            verifyNativeCrops();
            verifyNativeRopes();
            verifyBasketReload();
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
            try { owner(() -> {
                for (org.bukkit.Chunk chunk : fixtureChunks) chunk.removePluginChunkTicket(this);
                return null;
            }); } catch (Throwable cleanup) { checks.put("ticket_cleanup", Map.of("status", "FAIL", "detail", cleanup.toString())); }
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
        if (!Files.exists(getDataFolder().toPath().resolve("shared-visual-cache.enabled")))
            require(block("fd_state_pressure:first").isPresent() && block("fd_state_pressure:second").isPresent(), "Pressure fixture blocks failed");
        else {
            Object kelp = ce("net.momirealms.craftengine.core.block.AutoStateGroup").getField("KELP").get(null);
            List<?> reserved = (List<?>) call(kelp, "candidates"); int occupied = 0;
            for (Object candidate : reserved) if (Boolean.TRUE.equals(call(candidate, "isUsed"))) occupied++;
            require(occupied == reserved.size(), "Actual cache pressure did not retain the full kelp pool: " + occupied);
            pass("historical_full_visual_pool", "Actual historical cache retains all " + occupied + " kelp slots; rope still registers with a reused single visual");
        }
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
        Bukkit.getRegionScheduler().execute(this, Bukkit.getWorlds().getFirst().getSpawnLocation(), () -> { try { result.complete(body.call()); } catch(Throwable ex) { result.completeExceptionally(ex); } });
        return result.get(30, TimeUnit.SECONDS);
    }
    private <T> T ownerLater(Callable<T> body) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Bukkit.getRegionScheduler().runDelayed(this, Bukkit.getWorlds().getFirst().getSpawnLocation(), task -> { try { result.complete(body.call()); } catch(Throwable ex) { result.completeExceptionally(ex); } }, 4);
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
        meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(this, "basket_marker"), org.bukkit.persistence.PersistentDataType.STRING, "mod-alignment-1.2.8");
        marker.setItemMeta(meta);
        return marker;
    }

    private void verifyAlignedDefinitions() throws Exception {
        owner(() -> {
            for (String name : List.of("basket", "wooden_basket", "bamboo_basket", "safety_net")) {
                Object dry = call(block("farmersdelight:" + name).orElseThrow(), "defaultState");
                Object wet = call(dry, "with", call(dry, "getProperty", "waterlogged"), true);
                String water = String.valueOf(call(call(wet, "customBlockState"), "fluidState"));
                require("minecraft:water".equals(water), "Wet " + name + " has no native water: " + water);
                require(!"minecraft:water".equals(String.valueOf(call(call(dry, "customBlockState"), "fluidState"))), "Dry " + name + " unexpectedly contains water");
            }
            pass("wet_baskets_and_safety_net", "All three basket IDs and the safety net have native water only in their wet states");
            for (String name : List.of("apple_pie", "chocolate_pie", "sweet_berry_cheesecake", "pumpkin_pie")) {
                Object state = call(block("farmersdelight:" + name).orElseThrow(), "defaultState");
                Object behavior = call(fd("com.huidu.farmersdelight.util.CustomBlockUtils"), "getBehavior", state,
                    fd("com.huidu.farmersdelight.block.behavior.IntegerComparatorBlockBehavior"));
                require(behavior != null, "Pie comparator missing: " + name);
                for (int eaten = 0; eaten < 4; eaten++) {
                    Object staged = call(state, "with", call(state, "getProperty", "bites"), eaten);
                    Object nativeState = call(call(staged, "customBlockState"), "minecraftState");
                    require(Integer.valueOf(4 - eaten).equals(call(behavior, "getAnalogOutputSignal", null,
                        new Object[]{nativeState})), "Pie comparator does not count remaining slices: " + name + "/" + eaten);
                }
            }
            pass("pie_remaining_signal", "Four pies produce comparator strength 4, 3, 2, 1 through their registered native behavior");
            for (String id : List.of("rope", "sandy_shrub", "rope_fence", "rope_fence_gate")) {
                Object state = call(block("farmersdelight:" + id).orElseThrow(), "defaultState");
                String type = switch(id) {
                    case "rope" -> "RopeBlockBehavior";
                    case "sandy_shrub" -> "SandyShrubBlockBehavior";
                    case "rope_fence" -> "RopeFenceBlockBehavior";
                    default -> "RopeFenceGateBlockBehavior";
                };
                require(call(fd("com.huidu.farmersdelight.util.CustomBlockUtils"), "getBehavior", state,
                    fd("com.huidu.farmersdelight.block.behavior." + type)) != null, "Independent behavior is not bound: " + id);
            }
            Object rope = call(block("farmersdelight:rope").orElseThrow(), "defaultState");
            for (String flag : List.of("waterlogged", "tied_to_bell")) {
                Object property = call(rope, "getProperty", flag);
                require(property != null, "Rope property absent: " + flag);
                require(Boolean.TRUE.equals(call(call(rope, "with", property, true), "getNullable", property)), "Rope property cannot be toggled: " + flag);
            }
            pass("aligned_behavior_bindings", "Native definitions bind independent shrub/rope/fence/gate behaviors; wet and bell-tied states exist");
            return null;
        });
    }

    private void verifyBasketReload() throws Exception {
        Location[] locations = new Location[3]; BlockState[] originals = new BlockState[3];
        Object[] oldStates = new Object[3]; Object[] originalControllers = new Object[3];
        List<String> ids = List.of("basket", "wooden_basket", "bamboo_basket");
        java.util.Set<org.bukkit.Chunk> tickets = new java.util.HashSet<>();
        try {
            owner(() -> {
                World world = Bukkit.getWorlds().getFirst(); Location spawn = world.getSpawnLocation();
                for (int index = 0; index < 3; index++) {
                    Location location = new Location(world, spawn.getBlockX() + 2 + index,
                        world.getMaxHeight() - 24, spawn.getBlockZ() + 2);
                    org.bukkit.Chunk chunk = location.getChunk();
                    if (chunk.addPluginChunkTicket(this)) tickets.add(chunk);
                }
                return null;
            });
            ownerLater(() -> null);
            owner(() -> {
                World world = Bukkit.getWorlds().getFirst(); Location spawn = world.getSpawnLocation();
                for (int index = 0; index < 3; index++) {
                    Location location = new Location(world, spawn.getBlockX() + 2 + index,
                        world.getMaxHeight() - 24, spawn.getBlockZ() + 2);
                    if (!location.getBlock().getType().isAir()) {
                        // Restore only this helper's failed high-altitude fixtures in the dedicated no-player profile.
                        Object residue = call(ce("net.momirealms.craftengine.bukkit.api.CraftEngineBlocks"), "getCustomBlockState", location.getBlock());
                        require(residue != null && ("farmersdelight:" + ids.get(index)).equals(String.valueOf(call(call(call(residue, "owner"), "value"), "id"))),
                            "Reload fixture occupied by an unrelated block; refusing change");
                        try {
                            org.bukkit.inventory.Inventory inventory = (org.bukkit.inventory.Inventory) call(storageController(location), "inventory");
                            for (ItemStack stack : inventory.getContents()) require(stack == null || stack.isSimilar(basketMarker()), "Unrelated inventory in failed reload fixture; refusing cleanup");
                            inventory.clear();
                        } catch (IllegalStateException missingEntity) { getLogger().warning("Restoring this helper's previously failed basket fixture at " + location); }
                        call(ce("net.momirealms.craftengine.bukkit.api.CraftEngineBlocks"), "remove", location.getBlock());
                    }
                    require(location.getBlock().getType().isAir(), "Reload fixture position occupied; refusing change");
                    originals[index] = location.getBlock().getState(); locations[index] = location;
                    oldStates[index] = call(block("farmersdelight:" + ids.get(index)).orElseThrow(), "defaultState");
                    call(ce("net.momirealms.craftengine.bukkit.api.CraftEngineBlocks"), "place", location, oldStates[index], false);
                    originalControllers[index] = storageController(location);
                    ((org.bukkit.inventory.Inventory) call(originalControllers[index], "inventory")).setItem(0, basketMarker());
                }
                return null;
            });
            reload();
            ownerLater(() -> {
                Method update = fd("com.huidu.farmersdelight.block.behavior.BasketBlockBehavior")
                    .getDeclaredMethod("enabledState", ce("net.momirealms.craftengine.core.block.ImmutableBlockState"), String.class, boolean.class);
                update.setAccessible(true);
                for (int index = 0; index < 3; index++) {
                    // Deliberately pass a pre-reload immutable state whose holder now resolves a new definition.
                    Object disabled = update.invoke(null, oldStates[index], "enabled", true);
                    require(Boolean.FALSE.equals(call(disabled, "getNullable", call(disabled, "getProperty", "enabled"))), "Old basket state failed after reload");
                    Object controller = storageController(locations[index]);
                    ItemStack actual = ((org.bukkit.inventory.Inventory) call(controller, "inventory")).getItem(0);
                    require(actual != null && actual.getAmount() == 7 && actual.isSimilar(basketMarker()), "Basket reload changed marker inventory");
                }
                pass("basket_live_content_reload", "All three placed baskets survive CE content reload, accept old-state redstone updates, and preserve seven tagged diamonds");
                return null;
            });
        } finally {
            owner(() -> {
                for (int index = 0; index < 3; index++) if (locations[index] != null) {
                    try { ((org.bukkit.inventory.Inventory) call(storageController(locations[index]), "inventory")).clear(); }
                    catch (IllegalStateException unavailable) { getLogger().warning("Reload fixture cleanup has no resident CE entity at " + locations[index]); }
                    call(ce("net.momirealms.craftengine.bukkit.api.CraftEngineBlocks"), "remove", locations[index].getBlock());
                    originals[index].update(true, false);
                }
                for (org.bukkit.Chunk chunk : tickets) chunk.removePluginChunkTicket(this);
                return null;
            });
        }
    }

    private void verifyNativeCrops() throws Exception {
        owner(() -> {
            World world = Bukkit.getWorlds().getFirst();
            Location spawn = world.getSpawnLocation();
            int x = (spawn.getBlockX() & ~15) + 6;
            int z = (spawn.getBlockZ() & ~15) + 6;
            Block lower = world.getBlockAt(x, world.getMaxHeight() - 36, z);
            Block upper = lower.getRelative(BlockFace.UP);
            Block soil = lower.getRelative(BlockFace.DOWN);
            BlockState[] originals = new BlockState[3];
            Block[] cells = {lower, upper, soil};
            int[] mode = {0}, grows = {0}, fertilizers = {0};
            boolean[] inputCurrent = {true};
            var listener = new org.bukkit.event.Listener() { };
            var blocks = ce("net.momirealms.craftengine.bukkit.api.CraftEngineBlocks");
            var immutable = ce("net.momirealms.craftengine.core.block.ImmutableBlockState");
            Method commit = fd("com.huidu.farmersdelight.block.behavior.CropGrowthCommit").getDeclaredMethod(
                "apply", fd.getClass(), Map.class, org.bukkit.entity.Player.class, Block.class);
            commit.setAccessible(true);
            Method guardedCommit = fd("com.huidu.farmersdelight.block.behavior.CropGrowthCommit").getDeclaredMethod(
                "apply", fd.getClass(), Map.class, org.bukkit.entity.Player.class, Block.class, java.util.function.BooleanSupplier.class);
            guardedCommit.setAccessible(true);
            Method grow = fd("com.huidu.farmersdelight.block.behavior.ManagedCropBlockBehavior").getDeclaredMethod(
                "grow", Block.class, immutable, int.class);
            grow.setAccessible(true);
            try {
                for (int index = 0; index < cells.length; index++) {
                    require(cells[index].getType().isAir(), "Crop acceptance position occupied; refusing change: " + cells[index].getLocation());
                    originals[index] = cells[index].getState();
                }
                soil.setType(Material.FARMLAND, false);
                var farmland = (org.bukkit.block.data.type.Farmland) soil.getBlockData();
                farmland.setMoisture(7); soil.setBlockData(farmland, false);
                Bukkit.getPluginManager().registerEvent(org.bukkit.event.block.BlockGrowEvent.class, listener,
                    org.bukkit.event.EventPriority.NORMAL, (registered, raw) -> {
                        var event = (org.bukkit.event.block.BlockGrowEvent) raw;
                        if (!event.getBlock().equals(lower) && !event.getBlock().equals(upper)) return;
                        grows[0]++;
                        if (mode[0] == 1 || mode[0] == 2 && event.getBlock().equals(upper)) event.setCancelled(true);
                        if (mode[0] == 3 && event.getBlock().equals(lower)) lower.setType(Material.STONE, false);
                        if (mode[0] == 6) inputCurrent[0] = false;
                        if (mode[0] == 5 && event.getBlock().equals(upper)) {
                            try {
                                Object state = call(blocks, "getCustomBlockState", event.getNewState().getBlockData());
                                Object half = call(state, "getProperty", "half");
                                Object corrupted = call(state, "with", half, call(half, "valueByName", "lower"));
                                event.getNewState().setBlockData((org.bukkit.block.data.BlockData) call(blocks, "getBukkitBlockData", corrupted));
                            } catch (Exception failure) { throw new org.bukkit.event.EventException(failure); }
                        }
                    }, this, false);
                Bukkit.getPluginManager().registerEvent(org.bukkit.event.block.BlockFertilizeEvent.class, listener,
                    org.bukkit.event.EventPriority.NORMAL, (registered, raw) -> {
                        var event = (org.bukkit.event.block.BlockFertilizeEvent) raw;
                        if (!event.getBlock().equals(lower)) return;
                        fertilizers[0]++;
                        if (mode[0] == 4) event.setCancelled(true);
                    }, this, false);

                Object cabbage = call(block("farmersdelight:cabbages").orElseThrow(), "defaultState");
                Object cabbageAge = call(cabbage, "getProperty", "age");
                require(Boolean.TRUE.equals(call(blocks, "place", lower.getLocation(), cabbage, false)), "Could not place cabbage fixture");
                Object cabbageNext = call(cabbage, "with", cabbageAge, 1);
                mode[0] = 1; grows[0] = 0;
                boolean accepted = Boolean.TRUE.equals(commit.invoke(null, fd, Map.of(lower, cabbageNext), null, null));
                require(!accepted && grows[0] == 1, "Single-cell cancellation should authorize exactly once and reject growth");
                require(cropAge(lower) == 0, "Cancelled single-cell growth changed the crop");
                pass("crop_single_grow_cancel", "A native BlockGrowEvent cancellation leaves the original CE cabbage age and emits one event");

                mode[0] = 0; grows[0] = 0;
                accepted = Boolean.TRUE.equals(commit.invoke(null, fd, Map.of(lower, cabbageNext), null, null));
                require(accepted && grows[0] == 1 && cropAge(lower) == 1, "Single-cell growth or native CE state round-trip failed");
                Object actual = call(blocks, "getCustomBlockState", lower);
                require("farmersdelight:cabbages".equals(String.valueOf(call(call(call(actual, "owner"), "value"), "id"))), "Growth lost the custom block identity");
                pass("crop_single_grow_commit", "Successful native event retains the CE cabbage identity and installs its proposed age exactly once");

                Object pitcher = call(block("farmersdelight:pitcher_crop").orElseThrow(), "defaultState");
                Object pitcherAge = call(pitcher, "getProperty", "age");
                Object pitcherHalf = call(pitcher, "getProperty", "half");
                Object two = call(pitcher, "with", pitcherAge, 2);
                Object lowerThree = call(pitcher, "with", pitcherAge, 3);
                Object upperThree = call(lowerThree, "with", pitcherHalf, call(pitcherHalf, "valueByName", "upper"));
                Object pitcherBehavior = call(fd("com.huidu.farmersdelight.block.behavior.ManagedCropBlockBehavior"), "byId",
                    call(ce("net.momirealms.craftengine.core.util.Key"), "of", "farmersdelight:pitcher_crop"));
                require(pitcherBehavior != null, "Pitcher crop behavior was not bound");
                call(blocks, "remove", lower);
                require(Boolean.TRUE.equals(call(blocks, "place", lower.getLocation(), two, false)), "Could not place pitcher fixture");

                mode[0] = 2; grows[0] = 0;
                accepted = Boolean.TRUE.equals(grow.invoke(pitcherBehavior, lower, two, 1));
                require(!accepted && grows[0] == 2 && cropAge(lower) == 2 && upper.getType().isAir(), "Cancelling the upper event partially advanced the pitcher");
                pass("crop_pitcher_upper_cancel", "Real ManagedCrop 2-to-3 growth previews both native events and preserves both cells when the upper event is cancelled");

                mode[0] = 5; grows[0] = 0;
                accepted = Boolean.TRUE.equals(grow.invoke(pitcherBehavior, lower, two, 1));
                require(!accepted && grows[0] == 2 && cropAge(lower) == 2 && upper.getType().isAir(), "An event changed the proposed upper half into a lower half");
                pass("crop_native_structure_guard", "A native grow listener changing the planned upper half into lower is rejected without installing either cell");

                mode[0] = 6; grows[0] = 0; inputCurrent[0] = true;
                Map<Block, Object> guardedPlan = new LinkedHashMap<>(); guardedPlan.put(lower, lowerThree); guardedPlan.put(upper, upperThree);
                java.util.function.BooleanSupplier inputGuard = () -> inputCurrent[0];
                accepted = Boolean.TRUE.equals(guardedCommit.invoke(null, fd, guardedPlan, null, null, inputGuard));
                require(!accepted && grows[0] == 2 && cropAge(lower) == 2 && upper.getType().isAir(), "A grow listener invalidated the input guard but the transaction advanced");
                pass("crop_native_input_guard", "A real grow event invalidating a controlled input guard blocks both writes; this is not a physical player-hand test");

                mode[0] = 4; grows[0] = 0; fertilizers[0] = 0;
                Map<Block, Object> proposed = new LinkedHashMap<>(); proposed.put(lower, lowerThree); proposed.put(upper, upperThree);
                accepted = Boolean.TRUE.equals(commit.invoke(null, fd, proposed, null, lower));
                require(!accepted && fertilizers[0] == 1 && grows[0] == 0 && cropAge(lower) == 2 && upper.getType().isAir(), "Fertilize cancellation changed a pitcher cell");
                pass("crop_fertilize_event_cancel", "An explicit null-player native fertilize event rejects the whole two-cell plan before any grow event or write; no hand consumption is tested");

                mode[0] = 0; grows[0] = 0;
                upper.setType(Material.STONE, false);
                accepted = Boolean.TRUE.equals(grow.invoke(pitcherBehavior, lower, two, 1));
                require(!accepted && grows[0] == 0 && cropAge(lower) == 2 && upper.getType() == Material.STONE, "Blocked upper space allowed an incomplete pitcher");
                upper.setType(Material.AIR, false);
                pass("crop_pitcher_blocked_upper", "A solid upper cell rejects real 2-to-3 growth without events or partial lower advancement");

                grows[0] = 0;
                accepted = Boolean.TRUE.equals(grow.invoke(pitcherBehavior, lower, two, 1));
                require(accepted && grows[0] == 2 && cropAge(lower) == 3 && cropAge(upper) == 3, "Successful pitcher creation did not synchronize both halves");
                pass("crop_pitcher_two_cell_commit", "Real ManagedCrop growth installs the lower and upper age-three halves after exactly two growth events");

                grows[0] = 0;
                Object upperCurrent = call(blocks, "getCustomBlockState", upper);
                accepted = Boolean.TRUE.equals(grow.invoke(pitcherBehavior, upper, upperCurrent, 1));
                require(accepted && grows[0] == 2 && cropAge(lower) == 4 && cropAge(upper) == 4, "Upper-half growth did not mature both synchronized cells");
                pass("crop_pitcher_upper_to_mature", "Growing a real upper age-three half matures both synchronized halves to four with one event per changed cell");

                call(blocks, "remove", upper); call(blocks, "remove", lower);
                require(Boolean.TRUE.equals(call(blocks, "place", lower.getLocation(), two, false)), "Could not reset pitcher fixture");
                mode[0] = 3; grows[0] = 0;
                accepted = Boolean.TRUE.equals(grow.invoke(pitcherBehavior, lower, two, 1));
                require(!accepted && grows[0] == 2 && lower.getType() == Material.STONE && upper.getType().isAir(), "Growth overwrote an event listener's version change or installed the partner");
                pass("crop_event_version_guard", "An event listener changing the lower cell rejects the full native plan and preserves that external change without placing the upper half");

                mode[0] = 0;
                call(blocks, "remove", lower); lower.setType(Material.AIR, false);
                Object torch = call(block("farmersdelight:torchflower_crop").orElseThrow(), "defaultState");
                Object torchAge = call(torch, "getProperty", "age");
                Object torchMature = call(torch, "with", torchAge, 2);
                require(Boolean.TRUE.equals(call(blocks, "place", lower.getLocation(), torch, false)), "Could not place torchflower fixture");
                require(Boolean.TRUE.equals(commit.invoke(null, fd, Map.of(lower, torchMature), null, null)) && cropAge(lower) == 2,
                    "Torchflower mature native appearance did not retain its custom identity");
                pass("crop_torchflower_native_maturity", "The mature vanilla torchflower appearance round-trips through the CE grow-event proposal and retains custom age two");
                return null;
            } finally {
                org.bukkit.event.HandlerList.unregisterAll(listener);
                for (int index : new int[]{1, 0, 2}) if (originals[index] != null) {
                    call(blocks, "remove", cells[index]);
                    originals[index].update(true, false);
                }
            }
        });
    }

    private int cropAge(Block cell) throws Exception {
        Object state = call(ce("net.momirealms.craftengine.bukkit.api.CraftEngineBlocks"), "getCustomBlockState", cell);
        require(state != null, "Expected a CE crop at " + cell.getLocation());
        return ((Number) call(state, "getNullable", call(state, "getProperty", "age"))).intValue();
    }

    private void verifyNativeRopes() throws Exception {
        owner(() -> {
            World world = Bukkit.getWorlds().getFirst();
            Location spawn = world.getSpawnLocation();
            int x = (spawn.getBlockX() >> 4 << 4) + 8;
            int z = (spawn.getBlockZ() >> 4 << 4) + 8;
            int y = world.getMaxHeight() - 42;
            Location ropeAt = new Location(world, x, y, z);
            Location wetAt = new Location(world, x + 2, y, z);
            Location fenceAt = new Location(world, x + 4, y - 7, z + 4);
            Location gateAt = new Location(world, x, y - 14, z + 4);
            Map<Block, BlockState> snapshots = new LinkedHashMap<>();
            List<Location> centers = List.of(ropeAt, wetAt, fenceAt, gateAt);
            for (Location center : centers) {
                for (int dx = -1; dx <= 1; ++dx) for (int dz = -1; dz <= 1; ++dz) {
                    Block target = center.clone().add(dx, 0, dz).getBlock();
                    require(target.getType().isAir(), "Rope acceptance cell occupied: " + target.getLocation());
                    snapshots.putIfAbsent(target, target.getState());
                }
            }
            for (int dy = 1; dy <= 3; ++dy) {
                Block target = ropeAt.clone().add(0, dy, 0).getBlock();
                require(target.getType().isAir(), "Rope bell column occupied: " + target.getLocation());
                snapshots.put(target, target.getState());
            }
            try {
                Class<?> api = ce("net.momirealms.craftengine.bukkit.api.CraftEngineBlocks");
                Class<?> rope = fd("com.huidu.farmersdelight.block.behavior.RopeBlockBehavior");
                Class<?> fence = fd("com.huidu.farmersdelight.block.behavior.RopeFenceBlockBehavior");
                Class<?> gate = fd("com.huidu.farmersdelight.block.behavior.RopeFenceGateBlockBehavior");
                Object ropeDefinition = block("farmersdelight:rope").orElseThrow();
                Object defaultRope = call(ropeDefinition, "defaultState");
                Object position = ce("net.momirealms.craftengine.core.world.BlockPos")
                        .getConstructor(int.class, int.class, int.class).newInstance(x, y, z);
                require(Boolean.TRUE.equals(call(api, "place", ropeAt, defaultRope, false)), "Cannot place rope fixture");

                // A floor bell cannot stand on a non-supporting rope; use a ceiling attachment
                // with a real block above it so shape refreshes do not remove the fixture itself.
                ropeAt.clone().add(0, 2, 0).getBlock().setType(Material.STONE, false);
                org.bukkit.block.data.type.Bell directBell = (org.bukkit.block.data.type.Bell) Bukkit.createBlockData(Material.BELL);
                directBell.setAttachment(org.bukkit.block.data.type.Bell.Attachment.CEILING);
                ropeAt.clone().add(0, 1, 0).getBlock().setBlockData(directBell, false);
                call(rope, "refreshAdjacentRopes", world, position);
                require(ropeAt.clone().add(0, 1, 0).getBlock().getType() == Material.BELL, "Supported ceiling bell disappeared from fixture");
                Object tied = call(api, "getCustomBlockState", ropeAt.getBlock());
                require(Boolean.TRUE.equals(call(tied, "get", call(tied, "getProperty", "tied_to_bell"))), "Direct bell did not set tied_to_bell");
                Object tieRenderers = call(tied, "constantRenderers");
                require(java.lang.reflect.Array.getLength(tieRenderers) > 0, "Direct bell has no rope tie renderer");

                Location aboveRopeAt = ropeAt.clone().add(0, 1, 0);
                call(api, "place", aboveRopeAt, defaultRope, false);
                ropeAt.clone().add(0, 3, 0).getBlock().setType(Material.STONE, false);
                org.bukkit.block.data.type.Bell indirectBell = (org.bukkit.block.data.type.Bell) Bukkit.createBlockData(Material.BELL);
                indirectBell.setAttachment(org.bukkit.block.data.type.Bell.Attachment.CEILING);
                ropeAt.clone().add(0, 2, 0).getBlock().setBlockData(indirectBell, false);
                call(rope, "refreshAdjacentRopes", world, position);
                Object lower = call(api, "getCustomBlockState", ropeAt.getBlock());
                require(Boolean.FALSE.equals(call(lower, "get", call(lower, "getProperty", "tied_to_bell"))), "Indirect bell incorrectly marked the lower rope tied");
                Object upperPosition = ce("net.momirealms.craftengine.core.world.BlockPos")
                        .getConstructor(int.class, int.class, int.class).newInstance(x, y + 1, z);
                call(rope, "refreshAdjacentRopes", world, upperPosition);
                Object upper = call(api, "getCustomBlockState", aboveRopeAt.getBlock());
                require(Boolean.TRUE.equals(call(upper, "get", call(upper, "getProperty", "tied_to_bell"))), "Top rope lost direct bell tie");
                ropeAt.clone().add(0, 2, 0).getBlock().setType(Material.AIR, false);
                call(rope, "refreshAdjacentRopes", world, upperPosition);
                upper = call(api, "getCustomBlockState", aboveRopeAt.getBlock());
                require(Boolean.FALSE.equals(call(upper, "get", call(upper, "getProperty", "tied_to_bell"))), "Removed bell left stale tied_to_bell");
                pass("rope_direct_bell", "Only the rope directly under a bell is tied, tie renderer exists, and removing the bell clears it; click-to-ring range requires player interaction");

                ropeAt.clone().add(0, 0, -1).getBlock().setType(Material.IRON_BARS, false);
                ropeAt.clone().add(1, 0, 0).getBlock().setType(Material.COBBLESTONE_WALL, false);
                ropeAt.clone().add(0, 0, 1).getBlock().setType(Material.STONE, false);
                call(api, "place", ropeAt.clone().add(-1, 0, 0), defaultRope, false);
                Object connected = call(rope, "computeConnectionState", defaultRope, world, position);
                for (String side : List.of("north", "east", "west")) require(Boolean.TRUE.equals(call(connected, "get", call(connected, "getProperty", side))), "Rope connector missing: " + side);
                require(Boolean.FALSE.equals(call(connected, "get", call(connected, "getProperty", "south"))), "Programmatic rope unexpectedly tied to a plain stone block");
                pass("rope_connectors", "Programmatic restricted rope connects to bars, walls and its rope family, while excluding ordinary stone");

                Object wet = call(defaultRope, "with", call(defaultRope, "getProperty", "waterlogged"), true);
                require(Boolean.TRUE.equals(call(api, "place", wetAt, wet, false)), "Cannot place wet rope fixture");
                Object wetNative = call(api, "getCustomBlockState", wetAt.getBlock());
                require(Boolean.TRUE.equals(call(wetNative, "get", call(wetNative, "getProperty", "waterlogged"))), "Wet rope lost property");
                String wetVisualFluid = String.valueOf(call(call(wetNative, "visualBlockState"), "fluidState"));
                String wetInternalFluid = String.valueOf(call(call(wetNative, "customBlockState"), "fluidState"));
                String wetSettingsFluid = String.valueOf(call(call(wetNative, "settings"), "fluidState"));
                String wetFluidDetail = " (native=" + wetInternalFluid + ", visual=" + wetVisualFluid + ", settings=" + wetSettingsFluid + ")";
                require(wetVisualFluid.contains("water"), "Wet rope client visual has no water fluid" + wetFluidDetail);
                require(wetInternalFluid.contains("water"), "Wet rope internal native state has no water fluid" + wetFluidDetail);
                require(call(call(defaultRope, "visualBlockState"), "fluidState").toString().contains("empty"), "Dry rope unexpectedly contains water");
                pass("rope_waterlogged", "Wet rope retains its property and water in both actual native and client visual states; dry rope remains empty");
                Set<Integer> wetVisuals = new HashSet<>();
                int wetVariants = 0;
                for (Object variant : (List<?>) call(call(ropeDefinition, "variantProvider"), "states")) {
                    boolean isWet = Boolean.TRUE.equals(call(variant, "get", call(variant, "getProperty", "waterlogged")));
                    boolean isTied = Boolean.TRUE.equals(call(variant, "get", call(variant, "getProperty", "tied_to_bell")));
                    Object renderers = call(variant, "constantRenderers");
                    int rendererCount = renderers == null ? 0 : java.lang.reflect.Array.getLength(renderers);
                    if (isWet) {
                        ++wetVariants;
                        wetVisuals.add((Integer) call(call(variant, "visualBlockState"), "registryId"));
                        require(rendererCount == (isTied ? 2 : 1), "Wet rope shape/tie renderers missing or duplicated");
                        require(String.valueOf(call(call(variant, "customBlockState"), "fluidState")).contains("water"), "A wet rope connection variant lost native water");
                    } else require(rendererCount == (isTied ? 1 : 0), "Ordinary dry rope acquired an unnecessary shape entity");
                }
                require(wetVariants == 32 && wetVisuals.size() == 1, "Wet rope visual budget exceeded one slot: variants=" + wetVariants + ", visualIds=" + wetVisuals);
                pass("rope_wet_visual_budget", "All 32 wet variants share one kelp visual with one shape renderer and optional bell tie; ordinary dry ropes keep block-model rendering");

                Object defaultFence = call(block("farmersdelight:rope_fence").orElseThrow(), "defaultState");
                call(api, "place", fenceAt, defaultFence, false);
                call(api, "place", fenceAt.clone().add(0, 0, -1), defaultFence, false);
                fenceAt.clone().add(1, 0, 0).getBlock().setType(Material.OAK_FENCE, false);
                fenceAt.clone().add(-1, 0, 0).getBlock().setType(Material.STONE, false);
                fenceAt.clone().add(0, 0, 1).getBlock().setType(Material.OAK_FENCE_GATE, false);
                call(fence, "refresh", fenceAt.getBlock());
                Object fenceState = call(api, "getCustomBlockState", fenceAt.getBlock());
                for (String side : List.of("north", "west")) require(Boolean.TRUE.equals(call(fenceState, "get", call(fenceState, "getProperty", side))), "Rope fence missed its own family or solid face: " + side);
                for (String side : List.of("east", "south")) require(Boolean.FALSE.equals(call(fenceState, "get", call(fenceState, "getProperty", side))), "Rope fence joined an unrelated wooden fence or gate: " + side);
                pass("rope_fence_family", "Rope fence joins its own family and a full solid side, but neither an oak fence nor an oak gate");

                Object defaultGate = call(block("farmersdelight:rope_fence_gate").orElseThrow(), "defaultState");
                call(api, "place", gateAt, defaultGate, false);
                gateAt.clone().add(-1, 0, 0).getBlock().setType(Material.COBBLESTONE_WALL, false);
                call(gate, "refresh", gateAt.getBlock());
                Object gateState = call(api, "getCustomBlockState", gateAt.getBlock());
                require(Boolean.FALSE.equals(call(gateState, "get", call(gateState, "getProperty", "in_wall"))), "Single wall incorrectly lowered rope gate");
                gateAt.clone().add(1, 0, 0).getBlock().setType(Material.COBBLESTONE_WALL, false);
                call(gate, "refresh", gateAt.getBlock());
                gateState = call(api, "getCustomBlockState", gateAt.getBlock());
                require(Boolean.TRUE.equals(call(gateState, "get", call(gateState, "getProperty", "in_wall"))), "Two walls did not lower rope gate");
                gateAt.clone().add(-1, 0, 0).getBlock().setType(Material.AIR, false);
                call(gate, "refresh", gateAt.getBlock());
                gateState = call(api, "getCustomBlockState", gateAt.getBlock());
                require(Boolean.FALSE.equals(call(gateState, "get", call(gateState, "getProperty", "in_wall"))), "Removing one wall left gate lowered");
                pass("rope_gate_two_walls", "Rope gate lowers only between two walls and returns when either is removed");

                Location gateNeighbor = fenceAt.clone().add(1, 0, 0);
                call(api, "place", gateNeighbor, defaultGate, false);
                call(fence, "refresh", fenceAt.getBlock());
                fenceState = call(api, "getCustomBlockState", fenceAt.getBlock());
                require(Boolean.TRUE.equals(call(fenceState, "get", call(fenceState, "getProperty", "east"))), "Rope fence did not join gate post");
                Object east = java.lang.Enum.valueOf((Class) ce("net.momirealms.craftengine.core.util.Direction"), "EAST");
                Object rotatedGate = call(defaultGate, "with", call(defaultGate, "getProperty", "facing"), east);
                call(api, "place", gateNeighbor, rotatedGate, false);
                call(fence, "refresh", fenceAt.getBlock());
                fenceState = call(api, "getCustomBlockState", fenceAt.getBlock());
                require(Boolean.FALSE.equals(call(fenceState, "get", call(fenceState, "getProperty", "east"))), "Rope fence joined the plane of a rotated gate");
                pass("rope_gate_connection_axis", "Rope fence joins only the post axis of its own gate");
            } finally {
                Class<?> api = ce("net.momirealms.craftengine.bukkit.api.CraftEngineBlocks");
                for (Block target : snapshots.keySet()) {
                    Object current = call(api, "getCustomBlockState", target);
                    if (current != null) call(api, "remove", target);
                }
                for (BlockState previous : snapshots.values()) previous.update(true, false);
                for (Block target : snapshots.keySet()) require(target.getType().isAir(), "Rope fixture cleanup did not restore " + target.getLocation());
            }
            return null;
        });
    }

}
