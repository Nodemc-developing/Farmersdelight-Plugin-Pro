package com.huidu.farmersdelight.util;

import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Key;

import java.util.LinkedHashMap;
import java.util.Map;

/** A configured subset of a custom soil's properties, compiled once during loading. */
public record CustomSoilState(Key block, Map<String, String> properties) {
    public CustomSoilState {
        properties = Map.copyOf(properties);
    }

    public static CustomSoilState parse(String descriptor) {
        int start = descriptor.indexOf('[');
        if (start <= 0 || !descriptor.endsWith("]") || descriptor.indexOf('[', start + 1) >= 0
                || descriptor.indexOf(']') != descriptor.length() - 1)
            throw new IllegalArgumentException("Invalid soil block state: " + descriptor);
        Key id = Key.of(descriptor.substring(0, start).trim());
        Map<String, String> values = new LinkedHashMap<>();
        String body = descriptor.substring(start + 1, descriptor.length() - 1);
        if (body.isBlank()) throw new IllegalArgumentException("Empty soil block properties: " + descriptor);
        for (String entry : body.split(",", -1)) {
            int equals = entry.indexOf('=');
            if (equals <= 0 || equals != entry.lastIndexOf('='))
                throw new IllegalArgumentException("Invalid soil property: " + entry);
            String name = entry.substring(0, equals).trim(), value = entry.substring(equals + 1).trim();
            if (!name.matches("[a-z0-9_]+") || !value.matches("[a-z0-9_.-]+")
                    || values.putIfAbsent(name, value) != null)
                throw new IllegalArgumentException("Invalid or duplicate soil property: " + entry);
        }
        return new CustomSoilState(id, values);
    }

    public boolean matches(ImmutableBlockState state) {
        if (state == null || state.isEmpty() || !block.equals(state.owner().value().id())) return false;
        for (var entry : properties.entrySet()) {
            Property<?> property = state.getProperty(entry.getKey());
            if (property == null || !entry.getValue().equals(Property.formatValue(property, state.propertyEntries().get(property))))
                return false;
        }
        return true;
    }
}
