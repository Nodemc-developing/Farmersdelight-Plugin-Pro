package com.huidu.farmersdelight.fluid;

import io.papermc.paper.persistence.PersistentDataContainerView;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FluidItemProtectionTest {
    @Test void ordinaryItemsNeedNoDiagnosticCopyButEveryNativeRecordMarkerStillRequiresDecoding() {
        assertFalse(FluidCoreBridge.requiresNativeFluidRecordValidation(stack(Set.of())));
        for (String marker : java.util.List.of("container_data", "container_initialized", "tank_data")) {
            // The view reports presence regardless of type, just as for corrupt STRING/INT markers.
            assertTrue(FluidCoreBridge.requiresNativeFluidRecordValidation(stack(Set.of(new NamespacedKey("fluidcore", marker)))));
        }
    }

    @Test void combinedReadonlyGuardProtectsForeignAndSavedBlockTankDataWithoutRejectingNativeContainerRecords() {
        assertTrue(FluidCoreBridge.protectedRecipePdc(view(Set.of(new NamespacedKey("libuid", "stored_jug")))));
        assertTrue(FluidCoreBridge.protectedRecipePdc(view(Set.of(new NamespacedKey("jug_color", "data")))));
        assertTrue(FluidCoreBridge.protectedRecipePdc(view(Set.of(new NamespacedKey("fluidcore", "tank_data")))));
        assertFalse(FluidCoreBridge.protectedRecipePdc(view(Set.of(new NamespacedKey("fluidcore", "container_data")))));
        assertFalse(FluidCoreBridge.protectedRecipePdc(view(Set.of(new NamespacedKey("minecraft", "plain_note")))));
    }

    @Test void emptyPdcNeverMakesOpaqueNestedCustomDataSafe() {
        assertFalse(FluidCoreBridge.protectedRecipePdc(view(Set.of())));
        var old = new CompoundTag();old.putString("libuid:saved_jug", "opaque-original");
        var nested = new CompoundTag();nested.put("unrelated", old);
        assertTrue(FluidCoreBridge.foreignRecipeData(nested, 0));
        var unnamespaced = new CompoundTag();unnamespaced.putInt("jug_color", 7);
        var nativeNamedRoot = new CompoundTag();nativeNamedRoot.put("fluidcore", unnamespaced);
        assertTrue(FluidCoreBridge.foreignRecipeData(nativeNamedRoot, 0));
        var caseChangedNamespace = new CompoundTag();caseChangedNamespace.put("FluidCore:container", old);
        assertTrue(FluidCoreBridge.foreignRecipeData(caseChangedNamespace, 0));
    }

    @Test void nativeNamespacedDataGoesToRecordDecoderWhileExcessiveOpaqueNestingStaysProtected() {
        var nativeRecord = new CompoundTag();nativeRecord.putByteArray("fluidcore:container_data", new byte[]{1, 2});
        assertFalse(FluidCoreBridge.foreignRecipeData(nativeRecord, 0));
        var deeplyNested = new CompoundTag();var current = deeplyNested;
        for (int i = 0; i < 34; i++) { var child = new CompoundTag(); current.put("ordinary", child); current = child; }
        assertTrue(FluidCoreBridge.foreignRecipeData(deeplyNested, 0));
    }

    private static ItemStack stack(Set<NamespacedKey> keys) {
        return new ItemStack() {
            @Override public PersistentDataContainerView getPersistentDataContainer() { return view(keys); }
            @Override public org.bukkit.inventory.meta.ItemMeta getItemMeta() { throw new AssertionError("Read-only protection must not clone metadata"); }
        };
    }
    private static PersistentDataContainerView view(Set<NamespacedKey> keys) {
        return (PersistentDataContainerView) Proxy.newProxyInstance(FluidItemProtectionTest.class.getClassLoader(),
                new Class<?>[]{PersistentDataContainerView.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "has" -> { assertEquals(1, args.length, "Presence checks must not ignore wrong PDC types"); yield keys.contains(args[0]); }
                    case "getKeys" -> keys;
                    default -> throw new AssertionError("Unexpected PDC access " + method.getName());
                });
    }
}
