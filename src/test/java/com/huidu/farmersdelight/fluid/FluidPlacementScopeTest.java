package com.huidu.farmersdelight.fluid;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.ydxc20091.fluidcore.ce.FluidTankBehavior;
import io.papermc.paper.persistence.PersistentDataContainerView;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockAttemptPlaceEvent;
import net.momirealms.craftengine.bukkit.block.behavior.CompositeBlockBehavior;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.registry.Holder;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

import java.io.InputStreamReader;
import java.lang.ref.Reference;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Actual listener/event routing with owner data kept opaque; no running server or item mutation. */
@Isolated("Temporarily supplies bundled translations and the known non-air fixture material")
@Execution(ExecutionMode.SAME_THREAD)
class FluidPlacementScopeTest {
    private static Object previousLocaleState;
    private static Object previousPaperBlockType;
    private static boolean stateCaptured;

    @BeforeAll static void supplyBundledLanguageAndKnownNonAirMaterial() throws Exception {
        var localeState = field(I18n.class, "state");
        var blockType = field(Material.class, "blockType");
        previousLocaleState = localeState.get(null);
        previousPaperBlockType = blockType.get(Material.PAPER);
        stateCaptured = true;
        try {
            var language = new YamlConfiguration();
            try (var stream = I18n.class.getResourceAsStream("/lang/zh_cn.yml")) {
                assertNotNull(stream, "The actual bundled language must be available to the warning path");
                try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                    language.load(reader);
                }
            }
            assertNotNull(language.getString("fluid.outcome.protected_data"));
            var constructor = localeState.getType().getDeclaredConstructor(Map.class, YamlConfiguration.class, String.class);
            constructor.setAccessible(true);
            localeState.set(null, constructor.newInstance(Map.of("zh_cn", language), language, "zh_cn"));
            // The fixture is an ordinary PAPER item. Its non-air query should not bootstrap Paper's
            // server-only registry, while the real listener, behavior lookup and data guards still run.
            blockType.set(Material.PAPER, (Supplier<Object>) () -> null);
        } catch (Exception | Error failure) {
            try { restoreLanguageAndMaterial(); }
            catch (Exception | Error restoration) { if (restoration != failure) failure.addSuppressed(restoration); }
            throw failure;
        }
    }

    @AfterAll static void restoreLanguageAndMaterial() throws Exception {
        if (!stateCaptured) return;
        try {
            var blockType = field(Material.class, "blockType");
            blockType.set(Material.PAPER, previousPaperBlockType);
            assertSame(previousPaperBlockType, blockType.get(Material.PAPER));
        } finally {
            var localeState = field(I18n.class, "state");
            localeState.set(null, previousLocaleState);
            assertSame(previousLocaleState, localeState.get(null));
            previousLocaleState = null;
            previousPaperBlockType = null;
            stateCaptured = false;
        }
    }

    @Test void nativeOwnerPayloadPlacesFromEitherHandOnOrdinaryAndBothStoveStates() throws Exception {
        withServer(true, (world, enabled) -> {
            var listener = listener(enabled);
            var destination = state("kaleidoscopecookery:teapot", false, false);
            for (String clicked : List.of("minecraft:stone", "farmersdelight:stove_unlit", "farmersdelight:stove_lit")) {
                for (InteractionHand hand : InteractionHand.values()) {
                    var held = new Stack(Map.of(), teapot());
                    var other = new Stack(Map.of(new NamespacedKey("fluidcore", "container_data"), "wrong type"), teapot());
                    var fixture = player(hand == InteractionHand.MAIN_HAND ? held : other,
                            hand == InteractionHand.OFF_HAND ? held : other);
                    var event = event(fixture.player, world, destination, clicked, hand);
                    String before = held.payload.toString();
                    listener.onAttemptPlace(event);
                    assertFalse(event.isCancelled(), clicked + " / " + hand);
                    assertTrue(fixture.messages.isEmpty());
                    assertEquals(before, held.payload.toString());
                    assertEquals(0, other.pdcReads, "Only the actual event hand may be inspected");
                    assertTrue(FluidCoreBridge.foreignRecipeData(held.payload, 0),
                            "Allowing native placement must not allow opaque recipe conversion");
                }
            }
        });
    }

    @Test void genuineFluidCoreMarkersCannotBeDiscardedByNonNativePlacementEvenWithWrongPdcTypes() throws Exception {
        withServer(true, (world, enabled) -> {
            var listener = listener(enabled);
            var destination = state("kaleidoscopecookery:teapot", false, false);
            for (String marker : List.of("container_data", "container_initialized", "tank_data")) {
                for (InteractionHand hand : InteractionHand.values()) {
                    var held = new Stack(Map.of(new NamespacedKey("fluidcore", marker), "wrong type"), teapot());
                    var clean = new Stack(Map.of(), teapot());
                    var fixture = player(hand == InteractionHand.MAIN_HAND ? held : clean,
                            hand == InteractionHand.OFF_HAND ? held : clean);
                    var event = event(fixture.player, world, destination, "minecraft:stone", hand);
                    String before = held.payload.toString();
                    listener.onAttemptPlace(event);
                    assertTrue(event.isCancelled(), marker + " / " + hand);
                    assertEquals(List.of(I18n.getComponent("fluid.outcome.protected_data", fixture.player)), fixture.messages);
                    assertEquals(before, held.payload.toString());
                    assertEquals("wrong type", held.records.values().iterator().next());
                    assertEquals(0, clean.pdcReads);
                }
            }
        });
    }

    @Test void actualNativeTankBehaviorKeepsForeignDataProtectionAcrossNamespacesAndCompositeWrappers() throws Exception {
        withServer(true, (world, enabled) -> {
            var listener = listener(enabled);
            for (String id : List.of("farmersdelight:jug", "addon:custom_tank", "kaleidoscopecookery:teapot")) {
                for (boolean composite : List.of(false, true)) {
                    var destination = state(id, true, composite);
                    for (InteractionHand hand : InteractionHand.values()) {
                        var held = new Stack(Map.of(new NamespacedKey("libuid", "saved_jug"), "original contents"), teapot());
                        var clean = new Stack(Map.of(), new CompoundTag());
                        var fixture = player(hand == InteractionHand.MAIN_HAND ? held : clean,
                                hand == InteractionHand.OFF_HAND ? held : clean);
                        var event = event(fixture.player, world, destination, "minecraft:stone", hand);
                        assertEquals(id, event.customBlock().id().asString());
                        listener.onAttemptPlace(event);
                        assertTrue(event.isCancelled(), id + " / composite=" + composite + " / " + hand);
                        assertEquals(1, fixture.messages.size());
                        assertEquals("original contents", held.records.values().iterator().next());
                        assertEquals(0, clean.pdcReads);
                    }
                }
            }
        });
    }

    @Test void cancelledEventsAndUnavailableLibraryLeaveTheExistingPlacementDecisionAlone() throws Exception {
        var destination = state("kaleidoscopecookery:teapot", false, false);
        var unreadable = proxy(Player.class, (method, args) -> { throw new AssertionError("Cancelled/unavailable event inspected player: " + method); });
        var cancelled = event(unreadable, null, destination, "minecraft:stone", InteractionHand.MAIN_HAND);
        cancelled.setCancelled(true);
        new FluidRecipeListener(null, null).onAttemptPlace(cancelled);
        assertTrue(cancelled.isCancelled());
        withServer(false, (world, enabled) -> {
            var event = event(unreadable, world, destination, "minecraft:stone", InteractionHand.OFF_HAND);
            listener(enabled).onAttemptPlace(event);
            assertFalse(event.isCancelled());
        });
    }

    @Test void typedClassificationUsesTheDestinationBehaviorRatherThanItsIdOrWorldContents() throws Exception {
        var typed = allocate(TypedFluidCoreAccess.class);
        assertFalse(typed.isNativeTankState(null));
        assertFalse(typed.isNativeTankState(state("farmersdelight:jug", false, false)));
        assertFalse(typed.isNativeTankState(state("kaleidoscopecookery:teapot", false, true)));
        assertTrue(typed.isNativeTankState(state("addon:custom_tank", true, false)));
        assertTrue(typed.isNativeTankState(state("kaleidoscopecookery:teapot", true, true)));
    }

    private static FluidRecipeListener listener(Plugin enabled) throws Exception {
        var bridge = new FluidCoreBridge(allocate(FarmersDelightPlugin.class));
        field(FluidCoreBridge.class, "api").set(bridge, allocate(TypedFluidCoreAccess.class));
        field(FluidCoreBridge.class, "boundPlugin").set(bridge, enabled);
        var manager = allocate(FluidRecipeManager.class);
        field(FluidRecipeManager.class, "bridge").set(manager, bridge);
        return new FluidRecipeListener(null, manager);
    }

    private static ImmutableBlockState state(String id, boolean nativeTank, boolean composite) throws Exception {
        BlockDefinition definition = proxy(BlockDefinition.class, (method, args) -> {
            if (method.getName().equals("id")) return Key.of(id);
            throw new AssertionError("Placement classification queried definition: " + method);
        });
        var state = allocate(ImmutableBlockState.class);
        @SuppressWarnings("unchecked") Holder.Reference<BlockDefinition> holder = allocate(Holder.Reference.class);
        holder.bindValue(definition);
        field(ImmutableBlockState.class, "owner").set(state, holder);
        BlockBehavior behavior = nativeTank ? allocate(FluidTankBehavior.class) : null;
        if (composite) {
            var wrapper = allocate(CompositeBlockBehavior.class);
            field(CompositeBlockBehavior.class, "behaviors").set(wrapper,
                    behavior == null ? new BlockBehavior[0] : new BlockBehavior[]{behavior});
            behavior = wrapper;
        }
        state.setBehavior(behavior);
        return state;
    }

    private static CustomBlockAttemptPlaceEvent event(Player player, World world, ImmutableBlockState state,
                                                       String clickedId, InteractionHand hand) {
        Block clicked = proxy(Block.class, (method, args) -> { throw new AssertionError("Destination routing inspected clicked block " + clickedId + ": " + method); });
        return new CustomBlockAttemptPlaceEvent(player, new Location(world, 1, 65, 1), state, BlockFace.UP, clicked, hand);
    }

    private record PlayerFixture(Player player, List<Object> messages) {}
    private static PlayerFixture player(Stack main, Stack off) {
        List<Object> messages = new ArrayList<>();
        PlayerInventory inventory = proxy(PlayerInventory.class, (method, args) -> switch (method.getName()) {
            case "getItemInMainHand" -> main;
            case "getItemInOffHand" -> off;
            default -> throw new AssertionError("Placement modified/read other inventory state: " + method);
        });
        Player player = proxy(Player.class, (method, args) -> switch (method.getName()) {
            case "getInventory" -> inventory;
            case "locale" -> Locale.SIMPLIFIED_CHINESE;
            case "sendMessage" -> { messages.add(args[0]); yield null; }
            default -> throw new AssertionError("Unexpected placement player access: " + method);
        });
        return new PlayerFixture(player, messages);
    }

    private static final class Stack extends ItemStack {
        final Map<NamespacedKey, Object> records;
        final CompoundTag payload;
        int pdcReads;
        Stack(Map<NamespacedKey, Object> records, CompoundTag payload) { this.records = Map.copyOf(records); this.payload = payload; }
        @Override public Material getType() { return Material.PAPER; }
        @Override public PersistentDataContainerView getPersistentDataContainer() {
            pdcReads++;
            return proxy(PersistentDataContainerView.class, (method, args) -> switch (method.getName()) {
                case "has" -> { assertEquals(1, args.length, "Wrong types must remain detectable"); yield records.containsKey(args[0]); }
                case "getKeys" -> records.keySet();
                default -> throw new AssertionError("Guard should only inspect PDC presence/keys: " + method);
            });
        }
        @Override public org.bukkit.inventory.meta.ItemMeta getItemMeta() { throw new AssertionError("Placement must not copy or convert item metadata"); }
        @Override public ItemStack clone() { throw new AssertionError("Placement guard must not clone or convert the held item"); }
    }

    private static CompoundTag teapot() {
        var payload = new CompoundTag();
        var owner = new CompoundTag(); owner.putString("fluid", "minecraft:water"); owner.putInt("servings", 8);
        payload.put("kaleidoscopecookery:teapot_data", owner);
        return payload;
    }

    private static void withServer(boolean available, ServerTask task) throws Exception {
        var serverField = field(Bukkit.class, "server");
        Object previous = serverField.get(null);
        World world = proxy(World.class, (method, args) -> { throw new AssertionError("Placement guard queried world: " + method); });
        Plugin enabled = proxy(Plugin.class, (method, args) -> {
            if (method.getName().equals("isEnabled")) return available;
            throw new AssertionError("Unexpected plugin access: " + method);
        });
        PluginManager manager = proxy(PluginManager.class, (method, args) -> {
            if (method.getName().equals("getPlugin")) return available ? enabled : null;
            throw new AssertionError("Unexpected plugin manager access: " + method);
        });
        serverField.set(null, proxy(Server.class, (method, args) -> {
            if (method.getName().equals("getPluginManager")) return manager;
            throw new AssertionError("Placement guard queried server: " + method);
        }));
        try { task.run(world, enabled); }
        finally { Reference.reachabilityFence(world); serverField.set(null, previous); }
    }

    private static Field field(Class<?> type, String name) throws Exception { var field = type.getDeclaredField(name); field.setAccessible(true); return field; }
    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Object instance = field(unsafe, "theUnsafe").get(null);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(instance, type));
    }
    private static <T> T proxy(Class<T> type, Calls call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (instance, method, args) -> call.invoke(method, args)));
    }
    @FunctionalInterface private interface Calls { Object invoke(Method method, Object[] args) throws Throwable; }
    @FunctionalInterface private interface ServerTask { void run(World world, Plugin enabled) throws Exception; }
}
