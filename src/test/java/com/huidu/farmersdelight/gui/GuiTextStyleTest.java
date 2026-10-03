package com.huidu.farmersdelight.gui;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GuiTextStyleTest {
    @Test void nameHasReadableDefaultsWithoutInheritedItalic() {
        Component result = GuiTextStyle.name(Component.text("菜谱"));
        assertEquals(NamedTextColor.WHITE, result.color());
        assertEquals(TextDecoration.State.FALSE, result.decoration(TextDecoration.ITALIC));
    }

    @Test void loreUsesSecondaryTextColor() {
        assertEquals(NamedTextColor.GRAY, GuiTextStyle.lore(Component.text("查看材料")).color());
    }

    @Test void explicitItemColorsAndBoldSurvive() {
        Component original = Component.text("配方物品", NamedTextColor.LIGHT_PURPLE).decorate(TextDecoration.BOLD, TextDecoration.ITALIC);
        Component result = GuiTextStyle.name(original);
        assertEquals(NamedTextColor.LIGHT_PURPLE, result.color());
        assertEquals(TextDecoration.State.TRUE, result.decoration(TextDecoration.BOLD));
        assertEquals(TextDecoration.State.FALSE, result.decoration(TextDecoration.ITALIC));
        assertEquals(TextDecoration.State.TRUE, original.decoration(TextDecoration.ITALIC));
    }

    @Test void nestedExplicitItalicCannotOverrideUprightMenu() {
        Component original = Component.text("标签 ").append(Component.text("数值", NamedTextColor.AQUA).decorate(TextDecoration.ITALIC));
        Component result = GuiTextStyle.lore(original);
        assertEquals(TextDecoration.State.FALSE, result.children().getFirst().decoration(TextDecoration.ITALIC));
        assertEquals(NamedTextColor.AQUA, result.children().getFirst().color());
        assertEquals(TextDecoration.State.TRUE, original.children().getFirst().decoration(TextDecoration.ITALIC));
    }

    @Test void translatedComponentsKeepKeyFallbackAndTypedArguments() {
        TranslatableComponent original = Component.translatable("menu.capacity", TranslationArgument.numeric(1000),
                TranslationArgument.bool(true), Component.text("mB", NamedTextColor.AQUA).decorate(TextDecoration.ITALIC)).fallback("capacity");
        TranslatableComponent result = (TranslatableComponent) GuiTextStyle.upright(original);
        assertEquals(original.key(), result.key());
        assertEquals(original.fallback(), result.fallback());
        assertEquals(original.arguments().get(0).value(), result.arguments().get(0).value());
        assertEquals(original.arguments().get(1).value(), result.arguments().get(1).value());
        assertEquals(TextDecoration.State.FALSE, result.arguments().get(2).asComponent().decoration(TextDecoration.ITALIC));
        assertEquals(NamedTextColor.AQUA, result.arguments().get(2).asComponent().color());
    }

    @Test void ordinaryTitleUsesGold() {
        assertEquals(NamedTextColor.GOLD, GuiTextStyle.title(Component.text("烹饪配方")).color());
    }

    @Test void pictureGlyphIsNotTintedByDefaultTitleColor() {
        Component original = Component.text("\ue123\ue124");
        Component result = GuiTextStyle.title(original);
        assertNull(result.color());
        assertEquals(original.children(), result.children());
        assertEquals(((net.kyori.adventure.text.TextComponent) original).content(), ((net.kyori.adventure.text.TextComponent) result).content());
    }

    @Test void CustomFontAndOffsetsKeepFontAndExplicitTint() {
        Component glyph = Component.text("-8\ue123", NamedTextColor.WHITE).font(Key.key("farmersdelight", "gui"));
        Component result = GuiTextStyle.title(Component.empty().append(glyph));
        assertNull(result.color());
        assertEquals(glyph.font(), result.children().getFirst().font());
        assertEquals(glyph.color(), result.children().getFirst().color());
    }

    @Test void styleClassificationSeparatesValuesActionsAndWarnings() {
        assertEquals(GuiTextStyle.Role.HEADER, GuiTextStyle.role("title_pot_recipes"));
        assertEquals(GuiTextStyle.Role.VALUE, GuiTextStyle.role("gui.editor.result-count"));
        assertEquals(GuiTextStyle.Role.ACTION, GuiTextStyle.role("gui.editor.button.save"));
        assertEquals(GuiTextStyle.Role.DESCRIPTION, GuiTextStyle.role("search_hint"));
        assertEquals(GuiTextStyle.Role.WARNING, GuiTextStyle.role("empty"));
        assertEquals(GuiTextStyle.Role.ERROR, GuiTextStyle.role("delete"));
    }

    @Test void configuredColorsOverrideDefaultRole() {
        Component result = GuiTextStyle.styled(Component.text("保存", NamedTextColor.AQUA), GuiTextStyle.Role.ACTION);
        assertEquals(NamedTextColor.AQUA, result.color());
    }

    @Test void rawUiItalicIsDisabledWithoutChangingGlobalTextParser() {
        assertEquals(TextDecoration.State.FALSE, GuiTextStyle.name("<italic><green>菜单").decoration(TextDecoration.ITALIC));
        assertTrue(hasItalic(com.huidu.farmersdelight.util.Text.name("<italic>物品")));
    }

    private static boolean hasItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC) == TextDecoration.State.TRUE
                || component.children().stream().anyMatch(GuiTextStyleTest::hasItalic);
    }
}
