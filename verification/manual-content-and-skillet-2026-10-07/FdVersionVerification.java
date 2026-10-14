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
}
