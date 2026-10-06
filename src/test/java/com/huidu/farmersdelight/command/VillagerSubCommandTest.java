package com.huidu.farmersdelight.command;

import com.huidu.farmersdelight.villager.VillagerBackpackService;
import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VillagerSubCommandTest {
    private Field localeField;
    private Object originalLocale;

    @BeforeEach void localeFixture() throws Exception {
        localeField = I18n.class.getDeclaredField("state"); localeField.setAccessible(true);
        originalLocale = localeField.get(null);
        var locale = new YamlConfiguration(); locale.set("command.player_only", "Players only");
        var constructor = originalLocale.getClass().getDeclaredConstructor(Map.class, YamlConfiguration.class, String.class);
        constructor.setAccessible(true);
        localeField.set(null, constructor.newInstance(Map.of("zh_cn", locale), locale, "zh_cn"));
    }

    @AfterEach void restoreLocale() throws Exception { localeField.set(null, originalLocale); }

    @Test void completesOnlyInventoryAndUsesBackpackViewPermission() {
        var command = new VillagerSubCommand(null);
        assertEquals(List.of("inventory"), command.tabComplete(null, new String[]{"villager", "INV"}));
        assertEquals(List.of(), command.tabComplete(null, new String[]{"villager", "inventory", ""}));
        assertEquals(List.of(), command.tabComplete(null, new String[]{"villager", "status"}));
        assertTrue(command.canUse(sender(true, new ArrayList<>())));
        assertFalse(command.canUse(sender(false, new ArrayList<>())));
        assertEquals("command.help_villager", command.helpKey());
    }

    @Test void consoleFailureDoesNotResolveEntitiesOrScheduleTasks() {
        var messages = new ArrayList<Object>();
        new VillagerSubCommand(null).execute(sender(true, messages), "fd", new String[]{"villager", "inventory"});
        assertEquals(1, messages.size());
    }

    private static CommandSender sender(boolean permitted, List<Object> messages) {
        return (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(),
                new Class<?>[]{CommandSender.class}, (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission")) {
                        assertEquals(VillagerBackpackService.VIEW_PERMISSION, args[0]);
                        return permitted;
                    }
                    if (method.getName().equals("sendMessage")) { messages.add(args[0]); return null; }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
