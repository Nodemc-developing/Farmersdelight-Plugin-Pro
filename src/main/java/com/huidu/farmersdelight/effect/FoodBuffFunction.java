package com.huidu.farmersdelight.effect;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.effect.FarmersDelightFoodEffects;
import net.momirealms.craftengine.core.plugin.config.ConfigConstants;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.context.Condition;
import net.momirealms.craftengine.core.plugin.context.Context;
import net.momirealms.craftengine.core.plugin.context.function.AbstractConditionalFunction;
import net.momirealms.craftengine.core.plugin.context.function.FunctionFactory;
import net.momirealms.craftengine.core.plugin.context.number.NumberProvider;
import net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Function;

public final class FoodBuffFunction<CTX extends Context> extends AbstractConditionalFunction<CTX> {

    public enum Kind {COMFORT, NOURISHMENT}

    private final Kind kind;
    private final NumberProvider duration; // interpreted using the selected factory's unit
    private final NumberProvider level;    // 1-based
    private final boolean durationTicks;
    private final ContentFunctionOwner owner;

    private FoodBuffFunction(FarmersDelightPlugin plugin, List<Condition<CTX>> predicates, Kind kind, NumberProvider duration, NumberProvider level, boolean durationTicks) {
        super(predicates);
        this.kind = kind;
        this.duration = duration;
        this.level = level;
        this.durationTicks = durationTicks;
        this.owner = ContentFunctionOwner.forPlugin(plugin);
    }

    @Override
    protected void runInternal(CTX ctx) {
        ctx.getOptionalParameter(DirectContextParameters.PLAYER).ifPresent(cePlayer -> {
            if (!(cePlayer.platformPlayer() instanceof Player bukkitPlayer)) {
                return;
            }
            owner.run(bukkitPlayer, () -> {
                int configured = duration.getInt(ctx);
                int seconds = durationTicks ? ticksToSeconds(configured) : Math.max(1, configured);
                int lvl = Math.max(1, level.getInt(ctx));
                if (kind == Kind.COMFORT) FarmersDelightFoodEffects.applyComfort(bukkitPlayer, seconds, lvl);
                else FarmersDelightFoodEffects.applyNourishment(bukkitPlayer, seconds, lvl);
            });
        });
    }

    public static <CTX extends Context> FunctionFactory<CTX, FoodBuffFunction<CTX>> factory(
            Kind kind, Function<ConfigSection, Condition<CTX>> conditionFactory) {
        return factory(captureCompatibilityPlugin(), kind, conditionFactory);
    }

    public static <CTX extends Context> FunctionFactory<CTX, FoodBuffFunction<CTX>> factory(
            FarmersDelightPlugin plugin, Kind kind, Function<ConfigSection, Condition<CTX>> conditionFactory) {
        return new Factory<>(plugin, kind, conditionFactory, false);
    }

    public static <CTX extends Context> FunctionFactory<CTX, FoodBuffFunction<CTX>> ticksFactory(
            Kind kind, Function<ConfigSection, Condition<CTX>> conditionFactory) {
        return ticksFactory(captureCompatibilityPlugin(), kind, conditionFactory);
    }

    public static <CTX extends Context> FunctionFactory<CTX, FoodBuffFunction<CTX>> ticksFactory(
            FarmersDelightPlugin plugin, Kind kind, Function<ConfigSection, Condition<CTX>> conditionFactory) {
        return new Factory<>(plugin, kind, conditionFactory, true);
    }

    static int ticksToSeconds(int ticks) { return (int) Math.max(1L, ((long) ticks + 19L) / 20L); }

    /** Legacy factories have no plugin parameter; capture it once, never on an invocation hot path. */
    private static FarmersDelightPlugin captureCompatibilityPlugin() { return FarmersDelightPlugin.getInstance(); }

    private static final class Factory<CTX extends Context> extends AbstractFactory<CTX, FoodBuffFunction<CTX>> {
        private final Kind kind;
        private final boolean durationTicks;
        private final FarmersDelightPlugin plugin;

        Factory(FarmersDelightPlugin plugin, Kind kind, Function<ConfigSection, Condition<CTX>> conditionFactory, boolean durationTicks) {
            super(conditionFactory);
            this.plugin = plugin;
            this.kind = kind;
            this.durationTicks = durationTicks;
        }

        @Override
        public FoodBuffFunction<CTX> create(ConfigSection section) {
            return new FoodBuffFunction<>(
                    plugin,
                    getPredicates(section),
                    kind,
                    section.getNumber("duration", durationTicks ? ConfigConstants.CONSTANT_TWENTY : ConfigConstants.CONSTANT_NINETY),
                    section.getNumber("level", ConfigConstants.CONSTANT_ONE),
                    durationTicks
            );
        }
    }
}
