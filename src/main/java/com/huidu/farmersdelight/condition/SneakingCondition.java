package com.huidu.farmersdelight.condition;

import net.momirealms.craftengine.core.plugin.context.Condition;
import net.momirealms.craftengine.core.plugin.context.Context;
import net.momirealms.craftengine.core.plugin.context.condition.ConditionFactory;
import net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters;

public final class SneakingCondition implements Condition<Context> {
    public static final ConditionFactory<Context, SneakingCondition> FACTORY = ignored -> new SneakingCondition();
    @Override public boolean test(Context context) {
        return context.getOptionalParameter(DirectContextParameters.PLAYER).map(player -> player.isSecondaryUseActive()).orElse(false);
    }
}
