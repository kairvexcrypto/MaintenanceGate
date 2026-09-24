package ru.warndev.maintenancegate;

import java.time.Clock;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.server.ServerListPingEvent;

public final class GateListener implements Listener {
    private final GateService service;
    private final Supplier<GateSettings> settings;
    private final Clock clock;

    public GateListener(GateService service, Supplier<GateSettings> settings, Clock clock) {
        this.service = service;
        this.settings = settings;
        this.clock = clock;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) {
            return;
        }
        GateState state = service.snapshot();
        GateSettings config = settings.get();
        Player player = event.getPlayer();
        if (!state.admits(player.getUniqueId(), player.hasPermission("maintenancegate.bypass"),
                player.isOp(), config.operatorBypass(), clock.instant())) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                    Component.text(config.render(config.rejection(), state.window())));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPing(ServerListPingEvent event) {
        GateState state = service.snapshot();
        GateSettings config = settings.get();
        if (config.showMotd() && state.phase(clock.instant()) == GateWindow.Phase.ACTIVE) {
            event.motd(Component.text(config.render(config.motd(), state.window())));
        }
    }
}
