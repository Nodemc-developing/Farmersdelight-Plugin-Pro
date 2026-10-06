package com.huidu.farmersdelight.command;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.villager.VillagerBackpackService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;

import java.util.List;

import static com.huidu.farmersdelight.command.CommandSupport.normalize;
import static com.huidu.farmersdelight.command.CommandSupport.prefixFilter;

final class VillagerSubCommand extends SubCommand {
    private final FarmersDelightPlugin plugin;

    VillagerSubCommand(FarmersDelightPlugin plugin) {
        super("villager", List.of(), VillagerBackpackService.VIEW_PERMISSION, "command.help_villager");
        this.plugin = plugin;
    }

    @Override void execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(I18n.getComponent("command.player_only")); return;
        }
        if (args.length != 2 || !normalize(args[1]).equals("inventory")) {
            player.sendMessage(I18n.getComponent("villager.backpack.usage", player)); return;
        }
        plugin.scheduler().runForEntity(player, () -> {
            VillagerBackpackService service = plugin.getVillagerBackpackService();
            if (service == null) {
                player.sendMessage(I18n.getComponent("villager.backpack.disabled", player)); return;
            }
            // Native aiming respects intervening blocks and only considers already resident entities.
            // No UUID lookup or full-world entity enumeration is needed for this command.
            org.bukkit.entity.Entity target;
            try { target = player.getTargetEntity((int) Math.ceil(service.maximumDistance())); }
            catch (IllegalStateException unavailableRegion) {
                player.sendMessage(I18n.getComponent("villager.backpack.target_required", player)); return;
            }
            if (!(target instanceof Villager villager)) {
                player.sendMessage(I18n.getComponent("villager.backpack.target_required", player)); return;
            }
            service.open(player, villager);
        }, () -> { });
    }

    @Override List<String> tabComplete(CommandSender sender, String[] args) {
        return args.length == 2 ? prefixFilter(normalize(args[1]), List.of("inventory")) : List.of();
    }
}
