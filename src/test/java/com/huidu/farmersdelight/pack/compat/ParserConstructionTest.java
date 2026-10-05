package com.huidu.farmersdelight.pack.compat;

import net.momirealms.craftengine.core.pack.Pack;
import net.momirealms.craftengine.core.plugin.config.ConfigParser;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.config.IdConfigParser;
import net.momirealms.craftengine.core.plugin.config.IdSectionConfigParser;
import net.momirealms.craftengine.core.plugin.config.lifecycle.LoadingStage;
import net.momirealms.craftengine.core.registry.ConstantBoundRegistry;
import net.momirealms.craftengine.core.registry.Registry;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.ResourceKey;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.*;

class ParserConstructionTest {
    private static final LoadingStage STAGE = new LoadingStage("local_test_items");
    private static final LoadingStage DEPENDENCY = new LoadingStage("local_test_dependency");

    @Test void selectionUsesEveryRegisteredParserBeyondThePackManagersInternalParsers() {
        ResourceKey<Registry<ConfigParser>> key = ResourceKey.create(Key.of("minecraft:root"), Key.of("local_test:parsers"));
        ConstantBoundRegistry<ConfigParser> registry = new ConstantBoundRegistry<>(key, 3);
        ConfigParser internal = new SectionParser("local_test:config_factory", "config_factory");
        ConfigParser items = new SynchronousItems();
        ConfigParser addon = new SectionParser("farmersdelight:pack_sections", "cooking_pot_recipes");
        registry.register(ResourceKey.create(key.location(), internal.type()), internal);
        registry.register(ResourceKey.create(key.location(), items.type()), items);
        registry.register(ResourceKey.create(key.location(), addon.type()), addon);
        var selected = ExternalContentCoordinator.parserSnapshot(registry);
        assertEquals(List.of(internal, items, addon), selected);
        assertSame(items, selected.get(1));
        assertSame(addon, selected.get(2));
        assertSame(STAGE, items.loadingStage());
        assertEquals(List.of(DEPENDENCY), addon.dependencies());
        assertEquals(0, registry.getId(internal));
        assertEquals(1, registry.getId(items));
        assertEquals(2, registry.getId(addon));
        assertSame(items, registry.getValue(1));
        assertThrows(UnsupportedOperationException.class, () -> selected.add(internal));
        registry.register(ResourceKey.create(key.location(), Key.of("local_test:later")), new SectionParser("local_test:later", "later"));
        assertEquals(3, selected.size());
        assertEquals(4, ExternalContentCoordinator.parserSnapshot(registry).size());
    }

    private static final class SectionParser extends IdSectionConfigParser {
        private final Key type;
        private final String section;
        private SectionParser(String type, String section) { this.type = Key.of(type); this.section = section; }
        @Override public Key type() { return type; }
        @Override public String[] sectionId() { return new String[]{section}; }
        @Override public LoadingStage loadingStage() { return STAGE; }
        @Override public List<LoadingStage> dependencies() { return List.of(DEPENDENCY); }
        @Override protected void parseSection(Pack pack, Path path, Key id, ConfigSection section) { }
    }

    @Test void actualHostSuperclassConstructorCannotObserveAnUninitializedDelegate() throws Exception {
        for (ConfigParser delegate : List.of(new SynchronousItems(), new AsynchronousItems())) {
            Class<?> type = Class.forName(ExternalContentCoordinator.class.getName() + "$PrioritizingParser");
            var constructor = type.getDeclaredConstructor(ExternalContentCoordinator.class, ConfigParser.class);
            constructor.setAccessible(true);
            // The constructor does not access coordinator state. Its real host superclass still runs.
            ConfigParser wrapper = (ConfigParser) constructor.newInstance(null, delegate);
            assertEquals(delegate.async(), wrapper.async());
            assertSame(STAGE, wrapper.loadingStage());
            assertEquals(List.of(DEPENDENCY), wrapper.dependencies());
            assertArrayEquals(new String[]{"items"}, wrapper.sectionId());
            assertTrue(ExternalContentCoordinator.field(IdConfigParser.class, "idToPath").get(wrapper) instanceof ConcurrentMap);
        }
    }

    @Test void loadingFieldsRejectChangedContainerTypesBeforeInstallingAParser() throws Exception {
        assertTrue(List.class.isAssignableFrom(ExternalContentCoordinator.typedField(
                SynchronousItems.class, "configStorage", List.class).getType()));
        assertTrue(List.class.isAssignableFrom(ExternalContentCoordinator.typedField(
                SynchronousItems.class, "pendingConfigSections", List.class).getType()));
        var failure = assertThrows(IllegalStateException.class, () -> ExternalContentCoordinator.typedField(
                SynchronousItems.class, "configStorage", java.util.Map.class));
        assertTrue(failure.getMessage().contains("configStorage"));
        assertTrue(failure.getMessage().contains("expected java.util.Map"));
    }

    private static final class SynchronousItems extends IdSectionConfigParser {
        @Override public Key type() { return Key.of("local_test:sync_items"); }
        @Override public String[] sectionId() { return new String[]{"items"}; }
        @Override public LoadingStage loadingStage() { return STAGE; }
        @Override public List<LoadingStage> dependencies() { return List.of(DEPENDENCY); }
        @Override public boolean async() { return false; }
        @Override protected void parseSection(Pack pack, Path path, Key id, ConfigSection section) { }
    }

    private static final class AsynchronousItems extends IdSectionConfigParser {
        @Override public Key type() { return Key.of("local_test:async_items"); }
        @Override public String[] sectionId() { return new String[]{"items"}; }
        @Override public LoadingStage loadingStage() { return STAGE; }
        @Override public List<LoadingStage> dependencies() { return List.of(DEPENDENCY); }
        @Override public boolean async() { return true; }
        @Override protected void parseSection(Pack pack, Path path, Key id, ConfigSection section) { }
    }
}
