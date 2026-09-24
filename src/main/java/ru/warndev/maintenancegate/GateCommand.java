package ru.warndev.maintenancegate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class GateCommand implements TabExecutor, Listener {
    private static final UUID CONSOLE = new UUID(0, 0);
    private final MaintenanceGatePlugin plugin;
    private final GateService service;
    private final Clock clock;
    private final Map<UUID, UUID> requests = new HashMap<>();
    private final Map<UUID, Confirmation> confirmations = new HashMap<>();
    private boolean stopped;

    public GateCommand(MaintenanceGatePlugin plugin, GateService service, Clock clock) {
        this.plugin = plugin;
        this.service = service;
        this.clock = clock;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (stopped || !sender.hasPermission("maintenancegate.use")) {
            say(sender, "Нет доступа", true);
            return true;
        }
        if (!(sender instanceof Player) && !(sender instanceof ConsoleCommandSender)) {
            say(sender, "Используйте консоль или игрового персонажа", true);
            return true;
        }
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        String permission = permission(action);
        if (permission != null && !sender.hasPermission(permission)) {
            say(sender, "Нет доступа: " + permission, true);
            return true;
        }
        try {
            switch (action) {
                case "help" -> help(sender);
                case "status" -> status(sender);
                case "on" -> close(sender, args);
                case "off" -> {
                    exact(args, 1, "/mgate off");
                    UUID actor = owner(sender);
                    Instant now = clock.instant();
                    mutate(sender, state -> state.changeWindow(null, actor, now, AuditEntry.Action.OPEN));
                }
                case "schedule" -> schedule(sender, args);
                case "allow" -> allow(sender, args);
                case "audit" -> audit(sender, args);
                case "reload" -> {
                    exact(args, 1, "/mgate reload");
                    plugin.reloadSettings();
                    say(sender, service.recoveryRequired() ? "Настройки прочитаны; для выхода из аварийного режима требуется перезапуск"
                            : "Настройки обновлены; окно обслуживания не изменено", false);
                }
                default -> say(sender, "Неизвестная команда. /mgate help", true);
            }
        } catch (IllegalArgumentException error) {
            say(sender, error.getMessage(), true);
        } catch (java.io.IOException error) {
            say(sender, "Неверный config.yml; прежние настройки сохранены", true);
        }
        return true;
    }

    private void close(CommandSender sender, String[] args) {
        if (args.length < 3) {
            throw new IllegalArgumentException("/mgate on <длительность|forever> <причина>");
        }
        Instant now = clock.instant();
        Instant end = args[1].equals("forever") ? null : now.plus(GateArguments.duration(args[1]));
        GateWindow window = new GateWindow(now, end, GateArguments.reason(args, 2));
        UUID actor = owner(sender);
        mutate(sender, state -> state.changeWindow(window, actor, now, AuditEntry.Action.CLOSE));
    }

    private void schedule(CommandSender sender, String[] args) {
        if (args.length < 4) {
            throw new IllegalArgumentException("/mgate schedule <начало UTC> <длительность> <причина>");
        }
        Instant now = clock.instant();
        Instant start = GateArguments.timestamp(args[1], now);
        GateWindow window = new GateWindow(start, start.plus(GateArguments.duration(args[2])), GateArguments.reason(args, 3));
        UUID actor = owner(sender);
        mutate(sender, state -> state.changeWindow(window, actor, now, AuditEntry.Action.SCHEDULE));
    }

    private void allow(CommandSender sender, String[] args) {
        if (args.length < 2) {
            throw new IllegalArgumentException("/mgate allow list|add|remove|clear");
        }
        UUID actor = owner(sender);
        Instant now = clock.instant();
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                if (args.length > 3) {
                    throw new IllegalArgumentException("/mgate allow list [страница]");
                }
                List<String> lines = service.snapshot().allowed().stream().map(UUID::toString).sorted().toList();
                page(sender, "Список допуска", lines, args.length == 3 ? args[2] : "1");
            }
            case "add", "remove" -> {
                exact(args, 3, "/mgate allow add|remove <UUID|игрок онлайн>");
                UUID target = resolve(args[2]);
                if (args[1].equalsIgnoreCase("add")) {
                    mutate(sender, state -> state.allow(target, actor, now));
                } else {
                    mutate(sender, state -> state.deny(target, actor, now));
                }
            }
            case "clear" -> clear(sender, args, actor, now);
            default -> throw new IllegalArgumentException("/mgate allow list|add|remove|clear");
        }
    }

    private void clear(CommandSender sender, String[] args, UUID actor, Instant now) {
        confirmations.values().removeIf(value -> !value.expires().isAfter(now));
        if (args.length == 2) {
            if (service.snapshot().allowed().isEmpty()) {
                throw new IllegalArgumentException("Список уже пуст");
            }
            if (confirmations.size() >= 32 && !confirmations.containsKey(actor)) {
                throw new IllegalArgumentException("Слишком много ожидающих подтверждений");
            }
            String token = UUID.randomUUID().toString().substring(0, 8);
            confirmations.put(actor, new Confirmation(token, service.snapshot().revision(), now.plusSeconds(30)));
            say(sender, "Очистка всех допусков: /mgate allow clear " + token + " в течение 30 секунд", true);
            return;
        }
        exact(args, 3, "/mgate allow clear [код подтверждения]");
        Confirmation confirmation = confirmations.remove(actor);
        if (confirmation == null || !confirmation.token().equals(args[2])
                || confirmation.revision() != service.snapshot().revision()) {
            throw new IllegalArgumentException("Подтверждение истекло или состояние изменилось; повторите allow clear");
        }
        mutate(sender, state -> state.clear(actor, now));
    }

    private UUID resolve(String argument) {
        Player online = plugin.getServer().getPlayerExact(argument);
        return online == null ? GateArguments.uuid(argument) : online.getUniqueId();
    }

    private void audit(CommandSender sender, String[] args) {
        if (args.length > 2) {
            throw new IllegalArgumentException("/mgate audit [страница]");
        }
        List<String> lines = new ArrayList<>();
        for (AuditEntry entry : service.snapshot().audit().reversed()) {
            lines.add("#" + entry.revision() + " " + entry.time() + " " + entry.action() + " actor="
                    + entry.actor() + (entry.subject() == null ? "" : " target=" + entry.subject()));
        }
        page(sender, "Последние изменения", lines, args.length == 2 ? args[1] : "1");
    }

    private void status(CommandSender sender) {
        GateState state = service.snapshot();
        Instant now = clock.instant();
        say(sender, "Состояние: " + state.phase(now) + "; ревизия: " + state.revision()
                + "; допущено UUID: " + state.allowed().size(), false);
        if (state.window() != null) {
            say(sender, "Начало: " + state.window().start() + "; окончание: "
                    + (state.window().end() == null ? "бессрочно" : state.window().end()), false);
            say(sender, "Причина: " + state.window().reason(), false);
        }
        say(sender, "Обход операторами: " + plugin.settings().operatorBypass(), false);
        if (service.recoveryRequired()) {
            say(sender, "RECOVERY REQUIRED: восстановите хранилище при остановленном сервере", true);
        }
    }

    private void mutate(CommandSender sender, UnaryOperator<GateState> operation) {
        UUID actor = owner(sender);
        if (requests.containsKey(actor)) {
            throw new IllegalArgumentException("Предыдущая операция ещё выполняется");
        }
        if (requests.size() >= 32) {
            throw new IllegalArgumentException("Слишком много запросов");
        }
        UUID request = UUID.randomUUID();
        requests.put(actor, request);
        long revision = service.snapshot().revision();
        say(sender, "Сохранение изменения…", false);
        service.change(revision, operation).whenComplete((state, error) -> {
            try {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (stopped || !request.equals(requests.get(actor))) {
                        return;
                    }
                    requests.remove(actor);
                    if (sender instanceof Player player && !player.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        say(sender, error.getMessage() == null ? "Операция не выполнена" : error.getMessage(), true);
                    } else {
                        say(sender, "Сохранено. Ревизия " + state.revision() + "; режим " + state.phase(clock.instant()), false);
                    }
                });
            } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
            }
        });
    }

    private void page(CommandSender sender, String title, List<String> lines, String requested) {
        int pages = Math.max(1, (lines.size() + 7) / 8);
        int page = GateArguments.page(requested, pages);
        say(sender, title + " " + page + "/" + pages + "; записей: " + lines.size(), false);
        int start = (page - 1) * 8;
        for (String line : lines.subList(start, Math.min(start + 8, lines.size()))) {
            say(sender, line, false);
        }
    }

    private void help(CommandSender sender) {
        say(sender, "/mgate status | on <10m|forever> <причина> | off", false);
        say(sender, "/mgate schedule <ISO-8601 UTC> <длительность> <причина>", false);
        say(sender, "/mgate allow add|remove <UUID|онлайн-ник> | allow list [страница] | allow clear", false);
        say(sender, "/mgate audit [страница] | reload", false);
    }

    private static void exact(String[] args, int count, String usage) {
        if (args.length != count) {
            throw new IllegalArgumentException(usage);
        }
    }

    private static String permission(String action) {
        return switch (action) {
            case "on", "off", "schedule" -> "maintenancegate.manage";
            case "allow" -> "maintenancegate.allowlist";
            case "audit" -> "maintenancegate.audit";
            case "reload" -> "maintenancegate.reload";
            default -> null;
        };
    }

    private static UUID owner(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : CONSOLE;
    }

    private static void say(CommandSender sender, String text, boolean error) {
        sender.sendMessage(Component.text("[MaintenanceGate] ", NamedTextColor.GOLD)
                .append(Component.text(text, error ? NamedTextColor.RED : NamedTextColor.GRAY)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("maintenancegate.use")) {
            return List.of();
        }
        if (args.length == 1) {
            return List.of("help", "status", "on", "off", "schedule", "allow", "audit", "reload").stream()
                    .filter(action -> permission(action) == null || sender.hasPermission(permission(action)))
                    .filter(action -> action.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("allow") && sender.hasPermission("maintenancegate.allowlist")) {
            return List.of("list", "add", "remove", "clear").stream()
                    .filter(action -> action.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("on") && sender.hasPermission("maintenancegate.manage")) {
            return List.of("10m", "1h", "forever").stream().filter(value -> value.startsWith(args[1])).toList();
        }
        return List.of();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID actor = event.getPlayer().getUniqueId();
        requests.remove(actor);
        confirmations.remove(actor);
    }

    public void stop() {
        stopped = true;
        requests.clear();
        confirmations.clear();
    }

    private record Confirmation(String token, long revision, Instant expires) {
    }
}
