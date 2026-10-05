package com.huidu.farmersdelight.api.util;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.ApiStatus;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

@ApiStatus.NonExtendable
public final class CompatItemMeta {

    private static final MethodHandle SET_ITEM_MODEL = findItemModelMethod("setItemModel", void.class, NamespacedKey.class);
    private static final MethodHandle GET_ITEM_MODEL = findItemModelMethod("getItemModel", NamespacedKey.class);

    private CompatItemMeta() {
    }

    private static MethodHandle findItemModelMethod(String name, Class<?> result, Class<?>... parameters) {
        try {
            return MethodHandles.publicLookup().findVirtual(ItemMeta.class, name, MethodType.methodType(result, parameters));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    public static boolean isSupported() {
        return SET_ITEM_MODEL != null;
    }

    public static void setItemModel(ItemMeta meta, NamespacedKey key) {
        if (SET_ITEM_MODEL == null || meta == null) {
            return;
        }
        try {
            SET_ITEM_MODEL.invokeExact(meta, key);
        } catch (Throwable failure) {
            throw new IllegalStateException("Could not update the item model", failure);
        }
    }

    public static NamespacedKey getItemModel(ItemMeta meta) {
        if (GET_ITEM_MODEL == null || meta == null) return null;
        try {
            return (NamespacedKey) GET_ITEM_MODEL.invokeExact(meta);
        } catch (Throwable failure) {
            throw new IllegalStateException("Could not read the item model", failure);
        }
    }

    public static boolean hasItemModel(ItemMeta meta) {
        return getItemModel(meta) != null;
    }
}
