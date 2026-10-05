package com.huidu.farmersdelight.api.util;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CompatItemMetaTest {
    @Test
    void nullRestoresAnAbsentModelAndAnOlderApiLeavesMetadataUntouched() {
        AtomicReference<NamespacedKey> model = new AtomicReference<>();
        List<String> calls = new ArrayList<>();
        ItemMeta meta = (ItemMeta) Proxy.newProxyInstance(ItemMeta.class.getClassLoader(), new Class<?>[]{ItemMeta.class},
                (ignored, method, args) -> {
                    calls.add(method.getName());
                    if (method.getName().equals("setItemModel")) { model.set((NamespacedKey) args[0]); return null; }
                    if (method.getName().equals("getItemModel")) return model.get();
                    throw new AssertionError("Unrelated metadata access: " + method.getName());
                });

        CompatItemMeta.setItemModel(meta, NamespacedKey.minecraft("stone"));
        if (CompatItemMeta.isSupported()) {
            assertEquals(NamespacedKey.minecraft("stone"), CompatItemMeta.getItemModel(meta));
            assertTrue(CompatItemMeta.hasItemModel(meta));
            CompatItemMeta.setItemModel(meta, null);
            assertNull(model.get());
            assertFalse(CompatItemMeta.hasItemModel(meta));
        } else {
            assertNull(model.get());
            assertNull(CompatItemMeta.getItemModel(meta));
            assertFalse(CompatItemMeta.hasItemModel(meta));
            assertTrue(calls.isEmpty());
        }
        assertDoesNotThrow(() -> CompatItemMeta.setItemModel(null, null));
    }
}
