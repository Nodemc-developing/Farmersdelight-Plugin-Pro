package com.huidu.farmersdelight.fluid;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.recipe.RecipeSerializer;
import com.ydxc20091.fluidcore.ce.TankRecipeNavigation;
import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.item.StaticItem;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.window.Window;
import net.momirealms.sparrow.ui.window.WindowCloseReason;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Public read-only recipe windows. No editor permission, inventory link or storage mutation is involved. */
final class FluidRecipeBrowser implements AutoCloseable {
    private static final List<String> TYPES = List.of("fluid_filling", "fluid_emptying", "soaking");
    private static final int[] CONTENT = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
    private final FarmersDelightPlugin plugin;
    private final FluidRecipeManager manager;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    FluidRecipeBrowser(FarmersDelightPlugin plugin, FluidRecipeManager manager) { this.plugin = plugin; this.manager = manager; }

    void open(Player player, Location position, TankRecipeNavigation.ReturnHandle back) {
        if (!Bukkit.isOwnedByCurrentRegion(player) || !plugin.isEnabled() || !plugin.recipeWindows().initialize()) { back.cancel(); return; }
        Session session = new Session(player, back, manager.generation(), manager.recipes());
        Session old = sessions.put(player.getUniqueId(), session); if (old != null) retire(old);
        request(session, () -> transition(session));
    }
    private void onPlayer(Session session, Runnable action) {
        if (Bukkit.isOwnedByCurrentRegion(session.player)) action.run();
        else if (plugin.isEnabled()) plugin.scheduler().runForEntity(session.player, action, () -> retire(session));
        else retire(session);
    }
    private boolean current(Session session) {
        return plugin.isEnabled() && sessions.get(session.player.getUniqueId()) == session && session.generation == manager.generation()
                && Bukkit.isOwnedByCurrentRegion(session.player) && session.player.isOnline() && session.player.isValid() && !session.player.isDead();
    }
    private void request(Session session, Runnable operation) {
        if (!current(session)) { retire(session); return; }
        if (!session.busy.compareAndSet(false, true)) return;
        var expected = session.player.getOpenInventory().getTopInventory();
        session.back.valid().whenComplete((valid, failure) -> onPlayer(session, () -> {
            if (failure != null || !Boolean.TRUE.equals(valid) || !current(session) || session.player.getOpenInventory().getTopInventory() != expected) { retire(session); return; }
            try { operation.run(); } catch (RuntimeException rejected) { retire(session); plugin.getLogger().log(java.util.logging.Level.WARNING, "Cannot show fluid recipe", rejected); }
        }));
    }
    private void transition(Session session) {
        session.programmatic = true;
        var closing = session.window == null ? java.util.concurrent.CompletableFuture.completedFuture(null) : session.window.close();
        closing.whenComplete((ignored, failure) -> onPlayer(session, () -> {
            if (failure != null || !current(session) || session.player.getOpenInventory().getTopInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING) { retire(session); return; }
            show(session);
        }));
    }
    private void show(Session session) {
        Player player = session.player; var state = session.state.current();
        Pane pane = Pane.empty(9, 6), lower = Pane.empty(9, 4);
        session.upper = pane;
        Component title = text(player, "title");
        if (state.type() == null) {
            for (int index = 0; index < TYPES.size(); index++) {
                String type = TYPES.get(index); long count = session.recipes.stream().filter(recipe -> recipe.type().equals(type)).count();
                pane.setItem(20 + index * 2, button(typeIcon(type), I18n.getComponent("fluid.type." + type, player), List.of(text(player, "count", "count", count)),
                        () -> request(session, () -> { session.state.type(type); transition(session); })));
            }
        } else if (state.recipe() == null) {
            var recipes = session.recipes.stream().filter(recipe -> recipe.type().equals(state.type())).toList();
            int start = state.page() * CONTENT.length;
            for (int index = 0; index < CONTENT.length && start + index < recipes.size(); index++) {
                FluidRecipeSpec recipe = recipes.get(start + index);
                pane.setItem(CONTENT[index], button(resultIcon(recipe), Component.text(recipe.id()), lines(recipe, player),
                        () -> request(session, () -> { session.state.recipe(recipe.id()); transition(session); })));
            }
            if (state.page() > 0) pane.setItem(46, button(Material.ARROW, text(player, "previous"), List.of(), () -> request(session, () -> { session.state.page(state.page() - 1, recipes.size(), CONTENT.length); transition(session); })));
            if (start + CONTENT.length < recipes.size()) pane.setItem(53, button(Material.ARROW, text(player, "next"), List.of(), () -> request(session, () -> { session.state.page(state.page() + 1, recipes.size(), CONTENT.length); transition(session); })));
            pane.setItem(49, button(Material.PAPER, text(player, "page", "page", state.page() + 1, "pages", Math.max(1, (recipes.size() + CONTENT.length - 1) / CONTENT.length)), List.of(), () -> {}));
            title = I18n.getComponent("fluid.type." + state.type(), player);
        } else {
            FluidRecipeSpec recipe = session.recipes.stream().filter(value -> value.id().equals(state.recipe())).findFirst().orElse(null);
            if (recipe == null) { retire(session); return; }
            pane.setItem(19, button(FluidRecipeEditor.ingredientIcon(recipe.ingredient()), I18n.getComponent("fluid.editor.ingredient", player), List.of(Component.text(RecipeSerializer.serializeIngredient(recipe.ingredient()))), () -> {}));
            pane.setItem(22, button(Material.WATER_BUCKET, I18n.getComponent("fluid.editor.fluid", player), lines(recipe, player), () -> {}));
            pane.setItem(25, button(resultIcon(recipe), text(player, "result"), List.of(Component.text(recipe.id())), () -> {}));
        }
        pane.setItem(4, button(Material.KNOWLEDGE_BOOK, title, List.of(text(player, "read_only")), () -> {}));
        pane.setItem(45, button(Material.BARRIER, text(player, "back"), List.of(), () -> request(session, () -> goBack(session))));
        for (int index = 0; index < 36; index++) {
            ItemStack item = player.getInventory().getItem((index + 9) % 36);
            if (item != null && !item.getType().isAir()) lower.setItem(index, new StaticItem(item));
        }
        Window window = Window.builder(pane).setLowerPane(lower).setTitle(title).addOutsideClickHandler(event -> event.setCancelled(true))
                .addCloseHandler((closed, reason) -> onPlayer(session, () -> {
                    if (session.window != closed || session.programmatic) return;
                    if (reason == WindowCloseReason.PLAYER && current(session)) request(session, () -> goBack(session));
                    else retire(session);
                })).build(player);
        session.window = window; session.programmatic = false;
        window.open().whenComplete((result, failure) -> onPlayer(session, () -> {
            session.busy.set(false);
            if (failure != null || result == Window.OpenResult.VIEWER_UNAVAILABLE) retire(session);
        }));
    }
    private void goBack(Session session) {
        if (session.state.back()) { transition(session); return; }
        sessions.remove(session.player.getUniqueId(), session); session.programmatic = true;
        session.window.close().whenComplete((ignored, failure) -> onPlayer(session, () -> {
            if (failure != null) { session.back.cancel(); return; }
            session.back.reopen().whenComplete((opened, rejected) -> { if (rejected != null || !Boolean.TRUE.equals(opened)) session.back.cancel(); });
        }));
    }
    private void retire(Session session) {
        sessions.remove(session.player.getUniqueId(), session); session.programmatic = true; session.back.cancel();
        if (session.window != null) session.window.close();
    }
    private static Material typeIcon(String type) { return type.equals("fluid_filling") ? Material.WATER_BUCKET : type.equals("fluid_emptying") ? Material.BUCKET : Material.CAULDRON; }
    private static ItemStack resultIcon(FluidRecipeSpec recipe) {
        ItemStack result = recipe.result().isEmpty() ? null : FluidResultCodec.deserialize(recipe.result());
        return result == null ? new ItemStack(typeIcon(recipe.type())) : result;
    }
    private static List<Component> lines(FluidRecipeSpec recipe, Player player) {
        List<Component> lines = new ArrayList<>();
        lines.add(I18n.getComponent("fluid.editor.fluid", player).append(Component.text(": " + FluidExpression.display(recipe.fluidExpression()))));
        lines.add(I18n.getComponent("fluid.editor.amount", player).append(Component.text(": " + recipe.amount() + " mB")));
        if (recipe.type().equals("soaking")) {
            lines.add(I18n.getComponent("fluid.editor.time", player).append(Component.text(": " + recipe.timeTicks() + " ")).append(I18n.getComponent("fluid.editor.tick_unit", player)));
            lines.add(I18n.getComponent("fluid.editor.consume_fluid", player).append(Component.text(": ")).append(I18n.getComponent(recipe.consumeFluid() ? "fluid.editor.enabled" : "fluid.editor.disabled", player)));
        }
        if (recipe.result().isEmpty()) lines.add(I18n.getComponent("fluid.editor.native_container", player));
        return List.copyOf(lines);
    }
    private static Component text(Player player, String key, Object... replacements) {
        if (replacements.length % 2 != 0) throw new IllegalArgumentException("Message placeholders require pairs");
        Map<String, String> values = new java.util.LinkedHashMap<>();
        for (int i = 0; i < replacements.length; i += 2) values.put(String.valueOf(replacements[i]), String.valueOf(replacements[i + 1]));
        return I18n.getComponent("fluid.browser." + key, player, values);
    }
    private static StaticItem button(Material material, Component title, List<Component> lore, Runnable action) { return button(new ItemStack(material), title, lore, action); }
    private static StaticItem button(ItemStack source, Component title, List<Component> lore, Runnable action) {
        ItemStack icon = source.clone(); icon.editMeta(meta -> { meta.displayName(title); meta.lore(lore); });
        return new StaticItem(ItemProvider.constant(icon), (item, click) -> { if (click.window().isOpen()) action.run(); });
    }
    @Override public void close() { sessions.values().forEach(this::retire); sessions.clear(); }
    private static final class Session {
        final Player player; final TankRecipeNavigation.ReturnHandle back; final long generation; final List<FluidRecipeSpec> recipes;
        final FluidRecipeBrowserState state = new FluidRecipeBrowserState(); final AtomicBoolean busy = new AtomicBoolean();
        volatile boolean programmatic; volatile Window window; Pane upper;
        Session(Player player, TankRecipeNavigation.ReturnHandle back, long generation, List<FluidRecipeSpec> recipes) { this.player = player; this.back = back; this.generation = generation; this.recipes = recipes; }
    }
}
