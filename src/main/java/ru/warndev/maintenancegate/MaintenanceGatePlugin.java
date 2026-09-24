package ru.warndev.maintenancegate;

import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class MaintenanceGatePlugin extends JavaPlugin {
    private volatile GateSettings settings;
    private GateService service;
    private GateCommand commands;
    private BukkitTask transitionTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        boolean configFailed = false;
        try {
            reloadSettings();
        } catch (IOException error) {
            settings = GateSettings.emergency();
            configFailed = true;
            getLogger().severe("Invalid config.yml; emergency admission settings enabled");
        }
        service = new GateService(new GateStore(getDataFolder().toPath()), configFailed);
        Clock clock = Clock.systemUTC();
        commands = new GateCommand(this, service, clock);
        var command = Objects.requireNonNull(getCommand("maintenance"));
        command.setExecutor(commands);
        command.setTabCompleter(commands);
        getServer().getPluginManager().registerEvents(commands, this);
        getServer().getPluginManager().registerEvents(new GateListener(service, this::settings, clock), this);
        GateTransitions transitions = new GateTransitions(service.snapshot(), clock.instant());
        transitionTask = getServer().getScheduler().runTaskTimer(this, () -> {
            GateState state = service.snapshot();
            GateTransitions.Change change = transitions.observe(state, clock.instant());
            GateSettings current = settings;
            if (!current.broadcastTransitions() || change == GateTransitions.Change.NONE) {
                return;
            }
            String message = change == GateTransitions.Change.CLOSED ? current.closing() : current.opening();
            getServer().broadcast(Component.text(current.render(message, state.window())));
        }, 20, 20);
        if (service.recoveryRequired()) {
            getLogger().severe("State recovery required; non-bypass admission is closed");
        }
    }

    public GateSettings settings() {
        return settings;
    }

    public void reloadSettings() throws IOException {
        GateSettings replacement = GateSettings.load(getDataFolder().toPath().resolve("config.yml"));
        settings = replacement;
    }

    @Override
    public void onDisable() {
        if (transitionTask != null) {
            transitionTask.cancel();
        }
        if (commands != null) {
            commands.stop();
        }
        if (service != null) {
            service.close();
        }
    }
}
