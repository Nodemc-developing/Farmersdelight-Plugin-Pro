package com.huidu.farmersdelight.manager;
import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import java.util.function.Consumer;
public final class HandheldSkilletFlipListener implements Listener {
    private final Consumer<Player> onJump;
    public HandheldSkilletFlipListener(Consumer<Player> onJump) { this.onJump = onJump; }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJump(PlayerJumpEvent event) { onJump.accept(event.getPlayer()); }
}
