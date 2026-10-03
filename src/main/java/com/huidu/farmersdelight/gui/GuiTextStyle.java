package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Locale;

/** Presentation styles for menu copies; game items and editor drafts keep their own metadata. */
public final class GuiTextStyle {
    public enum Role {
        HEADER(NamedTextColor.GOLD), NAME(NamedTextColor.WHITE), VALUE(NamedTextColor.AQUA),
        DESCRIPTION(NamedTextColor.GRAY), ACTION(NamedTextColor.GREEN),
        WARNING(NamedTextColor.YELLOW), ERROR(NamedTextColor.RED);

        private final NamedTextColor color;
        Role(NamedTextColor color) { this.color = color; }
        public NamedTextColor color() { return color; }
    }

    private GuiTextStyle() { }

    /** Keeps colors, fonts, translations and glyphs, including those in translation arguments. */
    public static Component upright(Component source) {
        if (source == null) return Component.empty().decoration(TextDecoration.ITALIC, false);
        Component result = source.decoration(TextDecoration.ITALIC, false);
        if (!source.children().isEmpty()) result = result.children(source.children().stream().map(GuiTextStyle::upright).toList());
        if (result instanceof TranslatableComponent translated && !translated.arguments().isEmpty()) {
            result = translated.arguments(translated.arguments().stream().map(argument ->
                    argument.value() instanceof ComponentLike value
                            ? TranslationArgument.component(upright(value.asComponent())) : argument).toList());
        }
        return result;
    }

    /** Explicit content-pack colors take precedence over the menu's default hierarchy. */
    public static Component styled(Component source, Role role) {
        return upright(source).colorIfAbsent(role.color());
    }

    public static Component name(Component source) { return styled(source, Role.NAME); }
    public static Component name(String raw) { return name(Text.deserialize(raw)); }
    public static Component lore(Component source) { return styled(source, Role.DESCRIPTION); }
    public static Component lore(String raw) { return lore(Text.deserialize(raw)); }
    public static List<Component> loreLines(List<Component> lines) { return lines.stream().map(GuiTextStyle::lore).toList(); }

    public static Component title(Component source) {
        Component result = upright(source);
        // Resource-pack pictures and spacing glyphs must keep their existing tint and font.
        return containsGlyph(source) ? result : result.colorIfAbsent(Role.HEADER.color());
    }

    public static Component title(String raw) { return title(Text.title(raw)); }

    static boolean containsGlyph(Component component) {
        if (component == null) return false;
        if (component.font() != null) return true;
        if (component instanceof TextComponent text && text.content().codePoints().anyMatch(code -> Character.getType(code) == Character.PRIVATE_USE)) return true;
        if (component.children().stream().anyMatch(GuiTextStyle::containsGlyph)) return true;
        return component instanceof TranslatableComponent translated && translated.arguments().stream()
                .anyMatch(argument -> argument.value() instanceof ComponentLike value && containsGlyph(value.asComponent()));
    }

    public static Role role(String key) {
        if (key == null) return Role.NAME;
        String suffix = key.substring(key.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT).replace('-', '_');
        if (suffix.equals("title") || suffix.startsWith("title_") || suffix.equals("info")) return Role.HEADER;
        if (suffix.endsWith("_hint") || suffix.endsWith("_prompt") || suffix.endsWith("_line")) return Role.DESCRIPTION;
        return switch (suffix) {
            case "count", "page", "members", "result_count", "cook_time", "experience", "priority", "weight", "score" -> Role.VALUE;
            case "save", "edit", "new", "new_recipe", "new_group", "create", "fill", "confirm", "add", "search", "refresh", "next", "previous", "next_page", "prev_page", "rename", "choose" -> Role.ACTION;
            case "delete", "delete_confirm", "cancel", "close", "remove" -> Role.ERROR;
            case "empty", "unknown", "not_configured", "invalid_or_duplicate", "locked", "disabled" -> Role.WARNING;
            case "mode_exact", "mode_fuzzy", "addon_recipe" -> Role.DESCRIPTION;
            default -> Role.NAME;
        };
    }

    public static Component label(Component source, String key) { return styled(source, role(key)); }

    /** Only call for detached menu/display metadata, never a real storage item or a saved draft. */
    public static void normalizeDisplayMeta(ItemMeta meta) {
        if (meta.displayName() != null) meta.displayName(name(meta.displayName()));
        if (meta.hasItemName()) meta.itemName(name(meta.itemName()));
        if (meta.lore() != null) meta.lore(loreLines(meta.lore()));
    }

    public static ItemStack displayCopy(ItemStack source) {
        if (source == null) return null;
        ItemStack result = source.clone();
        ItemMeta meta = result.getItemMeta();
        if (meta != null) { normalizeDisplayMeta(meta); result.setItemMeta(meta); }
        return result;
    }
}
