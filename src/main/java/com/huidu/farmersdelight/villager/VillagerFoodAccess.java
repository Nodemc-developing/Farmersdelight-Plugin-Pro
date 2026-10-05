package com.huidu.farmersdelight.villager;

import org.bukkit.entity.Villager;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;

/** Native access is linked once. Calls stay on the villager's owning thread. */
final class VillagerFoodAccess {
    static final int BREEDING_THRESHOLD = 12;
    private final MethodHandle handle;
    private final VarHandle food;
    private VillagerFoodAccess(MethodHandle handle, VarHandle food) { this.handle = handle; this.food = food; }

    static VillagerFoodAccess link() throws ReflectiveOperationException {
        Class<?> nativeType;
        try { nativeType = Class.forName("net.minecraft.world.entity.npc.villager.Villager"); }
        catch (ClassNotFoundException olderPackage) { nativeType = Class.forName("net.minecraft.world.entity.npc.Villager"); }
        Class<?> craftType = Class.forName("org.bukkit.craftbukkit.entity.CraftVillager");
        MethodHandle handle = MethodHandles.publicLookup().findVirtual(craftType, "getHandle", MethodType.methodType(nativeType))
                .asType(MethodType.methodType(Object.class, Villager.class));
        VarHandle food = MethodHandles.privateLookupIn(nativeType, MethodHandles.lookup())
                .findVarHandle(nativeType, "foodLevel", int.class);
        return new VillagerFoodAccess(handle, food);
    }

    int get(Villager villager) {
        try { Object nativeVillager = (Object) handle.invokeExact(villager); return (int) food.get(nativeVillager); }
        catch (Throwable failure) { throw new IllegalStateException("Native villager food access failed", failure); }
    }
    void set(Villager villager, int value) {
        try { Object nativeVillager = (Object) handle.invokeExact(villager); food.set(nativeVillager, Math.max(0, value)); }
        catch (Throwable failure) { throw new IllegalStateException("Native villager food access failed", failure); }
    }
}
