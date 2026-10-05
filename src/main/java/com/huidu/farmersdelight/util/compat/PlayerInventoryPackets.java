package com.huidu.farmersdelight.util.compat;

import net.momirealms.craftengine.core.util.VersionHelper;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundContainerSetSlotPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacketProxy;

/** Creates a local inventory-slot update without changing the server's inventory. */
public final class PlayerInventoryPackets {
    private PlayerInventoryPackets() { }

    public static Object slot(int inventorySlot, Object nativeItem) {
        if (inventorySlot < 0 || inventorySlot > 40) throw new IllegalArgumentException("Invalid player inventory slot");
        if (VersionHelper.isOrAbove1_21_2) {
            return ClientboundSetPlayerInventoryPacketProxy.INSTANCE.newInstance(inventorySlot, nativeItem);
        }
        // The legacy -2 window addresses PlayerInventory directly, including offhand slot 40.
        return ClientboundContainerSetSlotPacketProxy.INSTANCE.newInstance(-2, 0, inventorySlot, nativeItem);
    }
}
