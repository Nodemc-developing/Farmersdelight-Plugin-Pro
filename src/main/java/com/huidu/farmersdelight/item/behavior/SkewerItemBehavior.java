package com.huidu.farmersdelight.item.behavior;

import com.huidu.farmersdelight.manager.HandheldSkewerSpec;
import com.huidu.farmersdelight.manager.SkewerCookingService;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.item.behavior.ItemBehavior;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviorFactory;
import net.momirealms.craftengine.core.world.World;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.inventory.EquipmentSlot;

public final class SkewerItemBehavior extends ItemBehavior {
    public static final ItemBehaviorFactory<SkewerItemBehavior> FACTORY = factory(() -> null);
    public static ItemBehaviorFactory<SkewerItemBehavior> factory(java.util.function.Supplier<SkewerCookingService> service) {
        return (pack, path, id, config) -> new SkewerItemBehavior(HandheldSkewerSpec.parse(id, config.values()), service);
    }
    private final HandheldSkewerSpec spec;
    private final java.util.function.Supplier<SkewerCookingService> service;
    private SkewerItemBehavior(HandheldSkewerSpec spec, java.util.function.Supplier<SkewerCookingService> service) { this.spec = spec; this.service = service; }
    public HandheldSkewerSpec spec() { return spec; }
    @Override public InteractionResult use(World world, net.momirealms.craftengine.core.entity.player.Player player, InteractionHand hand) {
        return begin(player, hand);
    }
    @Override public InteractionResult useOnBlock(UseOnContext context) {
        return context.getPlayer() == null ? InteractionResult.PASS : begin(context.getPlayer(), context.getHand());
    }
    private InteractionResult begin(net.momirealms.craftengine.core.entity.player.Player contextPlayer, InteractionHand hand) {
        var player = ItemUtils.getBukkitPlayer(contextPlayer);
        var current = service.get();
        return player != null && current != null && current.use(player,
                hand == InteractionHand.OFF_HAND ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND, spec)
                ? InteractionResult.SUCCESS_AND_CANCEL : InteractionResult.PASS;
    }
}
