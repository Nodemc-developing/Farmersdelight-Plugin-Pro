package com.huidu.farmersdelight.util.compat;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.regex.Pattern;

/** Checks the host's version and required loading APIs without reading its JAR. */
public final class CraftEngineBaseline {
    public static final String STABLE_VERSION = "26.9.2";
    public static final String SNAPSHOT_VERSION = "26.10";
    private static final Pattern SUPPORTED_VERSION = Pattern.compile("(26\\.9\\.2|26\\.10)(?:[-+][A-Za-z0-9][A-Za-z0-9._+-]*)?");
    private static final String CORE = "net.momirealms.craftengine.core.";

    private CraftEngineBaseline() { }

    public static void verify(JavaPlugin plugin) {
        verifyHost(plugin.getServer().getPluginManager().getPlugin("CraftEngine"));
    }

    public static String verifyHost(Plugin host) {
        if (host == null) throw new IllegalStateException("CraftEngine must load before this plugin.");
        String version = versionFamily(host.getPluginMeta().getVersion());
        verifyApi(host.getClass().getClassLoader());
        return version;
    }

    static String versionFamily(String version) {
        var match = SUPPORTED_VERSION.matcher(version == null ? "" : version.trim());
        if (!match.matches()) throw new IllegalStateException("Unsupported CraftEngine version: " + version
                + "; use 26.9.2 or 26.10 with the required APIs.");
        return match.group(1);
    }

    /** Only bootstrap checks use reflection; gameplay keeps the existing typed and cached bindings. */
    static void verifyApi(ClassLoader loader) {
        try {
            Class<?> engine = load(CORE + "plugin.CraftEngine", loader);
            Class<?> state = load(CORE + "block.ImmutableBlockState", loader);
            Class<?> behavior = load(CORE + "block.behavior.BlockBehavior", loader);
            Class<?> hand = load(CORE + "entity.player.InteractionHand", loader);
            Class<?> event = load("net.momirealms.craftengine.bukkit.api.event.CustomBlockAttemptPlaceEvent", loader);
            load(CORE + "pack.PackManager", loader);
            load(CORE + "pack.PackCacheData", loader).getDeclaredConstructor(engine);
            requireReturn(state, "behavior", behavior);
            requireReturn(event, "blockState", state);
            requireReturn(event, "hand", hand);
            requireReturn(behavior, "getFirst", Object.class, Class.class);
            load(CORE + "block.entity.tick.BlockEntityTicker", loader);
        } catch (ReflectiveOperationException | LinkageError missing) {
            throw new IllegalStateException("CraftEngine required loading API is unavailable: " + missing.getMessage(), missing);
        }
    }

    private static Class<?> load(String name, ClassLoader loader) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    private static void requireReturn(Class<?> owner, String name, Class<?> expected, Class<?>... parameters)
            throws NoSuchMethodException {
        var method = owner.getMethod(name, parameters);
        if (method.getReturnType() != expected) throw new NoSuchMethodException(owner.getName() + "." + name
                + " must return " + expected.getName());
    }
}
