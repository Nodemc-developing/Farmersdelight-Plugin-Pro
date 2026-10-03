package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.compat.CraftEngineAdapter;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.util.EntityUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import org.bukkit.NamespacedKey;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;

public final class HeatContactBlockBehavior extends FarmersDelightBlockBehavior {
    public static final BlockBehaviorFactory<HeatContactBlockBehavior> FACTORY = HeatContactBlockBehavior::new;
    private final double damage;
    private final Property<Boolean> enabled;
    private final HeatContactVolume volume;
    private final NamespacedKey damageType, fallback;
    private HeatContactBlockBehavior(BlockDefinition block, ConfigSection config) {
        super(block);
        damage = config.getDouble("damage", 1);
        if (!Double.isFinite(damage) || damage < 0) throw new IllegalArgumentException("damage must be finite and nonnegative");
        String property = config.getString("property", "");
        enabled = property.isBlank() ? null : BlockBehaviorFactory.getProperty(config.path(), block, property, Boolean.class);
        volume = HeatContactVolume.parse(config.getList(new String[]{"burn_area", "burn-area"}));
        damageType = NamespacedKey.fromString(config.getString(new String[]{"damage_type", "damage-type"}, "minecraft:on_fire"));
        fallback = NamespacedKey.fromString(config.getString(new String[]{"fallback_type", "fallback-type"}, "minecraft:hot_floor"));
        if (damageType == null || fallback == null) throw new IllegalArgumentException("damage types must be valid namespaced keys");
    }
    @Override public boolean isPathFindable(Object nativeBlock, Object[] args) { return false; }
    @Override public void stepOn(Object nativeBlock, Object[] args) {
        if (damage <= 0 || args.length < 4) return;
        var state = BlockStateUtils.getOptionalCustomBlockState(args[2]).orElse(null);
        if (state == null || state.isEmpty() || enabled != null && !Boolean.TRUE.equals(state.get(enabled))) return;
        var pos = CraftEngineAdapter.toBlockPos(args[1]);
        if (pos == null) return;
        if (!(EntityUtils.adaptNMS(args[3]).platformEntity() instanceof LivingEntity entity) || entity.isDead()
                || entity instanceof Player player && player.isSneaking() || !volume.touches(entity.getBoundingBox(), pos.x(), pos.y(), pos.z())) return;
        var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.DAMAGE_TYPE);
        DamageType type = registry.get(damageType);
        if (type == null) type = registry.get(fallback);
        entity.damage(damage, DamageSource.builder(type == null ? DamageType.HOT_FLOOR : type).build());
    }
}
