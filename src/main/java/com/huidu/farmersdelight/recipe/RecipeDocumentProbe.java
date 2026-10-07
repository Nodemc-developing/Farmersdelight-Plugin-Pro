package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.config.PlainYamlDocuments;
import com.huidu.farmersdelight.pack.PackSection;
import net.momirealms.sparrow.yaml.SparrowYaml;
import net.momirealms.sparrow.yaml.libs.snakeyaml.engine.v2.api.LoadSettings;
import net.momirealms.sparrow.yaml.libs.snakeyaml.engine.v2.api.lowlevel.Compose;
import net.momirealms.sparrow.yaml.libs.snakeyaml.engine.v2.nodes.MappingNode;
import net.momirealms.sparrow.yaml.libs.snakeyaml.engine.v2.nodes.Node;
import net.momirealms.sparrow.yaml.libs.snakeyaml.engine.v2.nodes.ScalarNode;
import net.momirealms.sparrow.yaml.libs.snakeyaml.engine.v2.nodes.SequenceNode;
import net.momirealms.sparrow.yaml.libs.snakeyaml.engine.v2.nodes.Tag;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;

/** Identifies owned roots before constructing a strict recipe document. */
final class RecipeDocumentProbe {
    private static final LoadSettings SETTINGS = SparrowYaml.create().loadSettings();
    private static final Set<String> ROOTS;
    static {
        Set<String> roots = new HashSet<>();
        for (PackSection kind : PackSection.values()) {
            roots.add(kind.sectionId());
            roots.add(kind.rootKey());
        }
        roots.addAll(Set.of("config_factory", "config-factory", "config_factories", "config-factories"));
        ROOTS = Set.copyOf(roots);
    }

    private RecipeDocumentProbe() { }

    /** The caller supplies the same captured text for probing and strict construction. */
    static YamlConfiguration parse(Path source, String contents, boolean managed) throws InvalidConfigurationException {
        try {
            if (!managed && !ownsRecipeRoots(contents)) return null;
            return PlainYamlDocuments.parse(contents, true);
        } catch (InvalidConfigurationException | RuntimeException invalid) {
            throw new InvalidConfigurationException("Invalid recipe document: " + source, invalid);
        }
    }

    static boolean ownsRecipeRoots(String contents) {
        // Composition retains duplicate key nodes without changing the strict constructor's settings.
        for (Node document : new Compose(SETTINGS).composeAllFromString(contents)) {
            Set<Node> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            if (document instanceof MappingNode mapping && ownedMapping(mapping, visited)) return true;
        }
        return false;
    }

    private static boolean ownedMapping(MappingNode mapping, Set<Node> visited) {
        if (!visited.add(mapping)) return false;
        for (var entry : mapping.getValue()) {
            if (!(entry.getKeyNode() instanceof ScalarNode key)) continue;
            String name = key.getValue();
            if (ROOTS.contains(name.split("#", 2)[0])) return true;
            if ((Tag.MERGE.equals(key.getTag()) || key.isPlain() && name.equals("<<"))
                    && ownedMerge(entry.getValueNode(), visited)) return true;
        }
        return false;
    }

    private static boolean ownedMerge(Node value, Set<Node> visited) {
        if (value instanceof MappingNode mapping) return ownedMapping(mapping, visited);
        if (value instanceof SequenceNode sequence && visited.add(sequence)) {
            for (Node child : sequence.getValue()) if (ownedMerge(child, visited)) return true;
        }
        return false;
    }
}
