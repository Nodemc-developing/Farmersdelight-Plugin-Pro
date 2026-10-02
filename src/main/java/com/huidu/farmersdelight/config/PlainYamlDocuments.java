package com.huidu.farmersdelight.config;

import net.momirealms.sparrow.yaml.SparrowYaml;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Sparrow parsing for plain internal documents; public Bukkit item serialization remains unchanged. */
public final class PlainYamlDocuments {
    private PlainYamlDocuments() {
    }

    public static YamlConfiguration read(Path path) throws IOException, InvalidConfigurationException {
        return parse(Files.readString(path));
    }

    /** Content-pack keys are literal identifiers, including dots in item and recipe names. */
    public static YamlConfiguration readLiteral(Path path) throws IOException, InvalidConfigurationException {
        return parse(Files.readString(path), true);
    }

    public static YamlConfiguration parse(String contents) throws InvalidConfigurationException {
        return parse(contents, false);
    }

    public static YamlConfiguration parse(String contents, boolean literalKeys) throws InvalidConfigurationException {
        try {
            var document = SparrowYaml.create().load(contents);
            var configuration = new YamlConfiguration();
            if (literalKeys) configuration.options().pathSeparator('\u0001');
            copy(document.getValues(), configuration);
            return configuration;
        } catch (IOException | RuntimeException invalidYaml) {
            throw new InvalidConfigurationException("Invalid plain YAML document", invalidYaml);
        }
    }

    private static void copy(Map<?, ?> values, ConfigurationSection section) {
        values.forEach((key, value) -> {
            String name = String.valueOf(key);
            setValue(section, name, value);
        });
    }

    /** Bukkit set(path, map) keeps a raw map; recipe readers require real child sections. */
    public static void setValue(ConfigurationSection section, String key, Object value) {
        if (value instanceof ConfigurationSection child) value = child.getValues(false);
        if (value instanceof Map<?, ?> children) {
            section.set(key, null);
            copy(children, section.createSection(key));
        } else section.set(key, value);
    }
}
