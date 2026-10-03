package com.huidu.farmersdelight.pack.compat;

import net.momirealms.craftengine.core.plugin.config.yaml.StringKeyConstructor;
import net.momirealms.craftengine.libraries.snakeyaml.engine.v2.api.ConstructNode;
import net.momirealms.craftengine.libraries.snakeyaml.engine.v2.api.LoadSettings;
import net.momirealms.craftengine.libraries.snakeyaml.engine.v2.common.FlowStyle;
import net.momirealms.craftengine.libraries.snakeyaml.engine.v2.common.ScalarStyle;
import net.momirealms.craftengine.libraries.snakeyaml.engine.v2.nodes.MappingNode;
import net.momirealms.craftengine.libraries.snakeyaml.engine.v2.nodes.Node;
import net.momirealms.craftengine.libraries.snakeyaml.engine.v2.nodes.NodeTuple;
import net.momirealms.craftengine.libraries.snakeyaml.engine.v2.nodes.ScalarNode;
import net.momirealms.craftengine.libraries.snakeyaml.engine.v2.nodes.SequenceNode;
import net.momirealms.craftengine.libraries.snakeyaml.engine.v2.nodes.Tag;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Resolves private loading inputs with the supported host's own version selector. */
final class HostVersionInputs {
    private static final Tag LOADED_VALUE = new Tag("tag:farmersdelight.dev,2026:loaded-value");

    static Map<String, Object> expand(Path path, Map<String, Object> input) {
        if (!hasConditions(input)) return ConfigPriorityFilter.copyMap(input);
        Map<Node, Object> scalars = new IdentityHashMap<>();
        Node root = node(input, scalars);
        ConstructNode preserveScalar = value -> {
            if (!scalars.containsKey(value)) throw new IllegalStateException("Unknown loaded scalar");
            return scalars.get(value);
        };
        var settings = LoadSettings.builder().setTagConstructors(Map.of(LOADED_VALUE, preserveScalar)).build();
        try {
            Object expanded = new StringKeyConstructor(settings, path).construct(root);
            if (!(expanded instanceof Map<?, ?> result))
                throw new IllegalArgumentException("A configuration root must resolve to a mapping");
            @SuppressWarnings("unchecked") Map<String, Object> typed = (Map<String, Object>) result;
            return typed;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Could not resolve CraftEngine version conditions in " + path
                    + ": " + failure.getMessage(), failure);
        }
    }

    private static boolean hasConditions(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet())
                if (String.valueOf(entry.getKey()).startsWith("$$") || hasConditions(entry.getValue())) return true;
        } else if (value instanceof List<?> list) {
            for (Object child : list) if (hasConditions(child)) return true;
        }
        return false;
    }

    private static Node node(Object value, Map<Node, Object> scalars) {
        if (value instanceof Map<?, ?> map) {
            List<NodeTuple> entries = new ArrayList<>(map.size());
            map.forEach((key, child) -> entries.add(new NodeTuple(
                    new ScalarNode(Tag.STR, String.valueOf(key), ScalarStyle.PLAIN), node(child, scalars))));
            return new MappingNode(Tag.MAP, entries, FlowStyle.BLOCK);
        }
        if (value instanceof List<?> list) {
            List<Node> entries = new ArrayList<>(list.size());
            list.forEach(child -> entries.add(node(child, scalars)));
            return new SequenceNode(Tag.SEQ, entries, FlowStyle.BLOCK);
        }
        // Already parsed scalars retain their Java types, precision and typed array values.
        Node scalar = new ScalarNode(LOADED_VALUE, "", ScalarStyle.PLAIN);
        scalars.put(scalar, value);
        return scalar;
    }
}
