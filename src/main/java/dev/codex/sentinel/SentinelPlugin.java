package dev.codex.sentinel;

import dev.codex.sentinel.check.CheckResult;
import dev.codex.sentinel.check.CombatChecks;
import dev.codex.sentinel.check.MovementChecks;
import dev.codex.sentinel.check.WorldChecks;
import dev.codex.sentinel.engine.DetectionEngine;
import dev.codex.sentinel.punishment.Durations;
import dev.codex.sentinel.punishment.PunishmentPolicy;
import dev.codex.sentinel.storage.SqliteViolationStore;
import dev.codex.sentinel.storage.ViolationStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public final class SentinelPlugin extends JavaPlugin implements TabCompleter {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final long DEFAULT_BAN_MILLIS = 7L * 86_400_000L;
    private static final List<String> SUBCOMMANDS = List.of("status", "alerts", "inspect", "violations", "checks", "reset", "debug", "reload", "punish");
    private static final Set<String> PLAYER_SUBCOMMANDS = Set.of("inspect", "violations", "reset", "debug", "punish");

    private static final List<String> CHECKS = List.of(
            "speed - horizontal movement vs vanilla momentum/friction/acceleration bound",
            "fly - gravity/jump prediction, air-time, hover, terminal velocity",
            "nofall - client onGround flag with nothing solid below",
            "timer - movement packet rate over real time",
            "antiknockback - server velocity not reflected in movement",
            "killaura - aim ray, through-wall, multi-target, attacking while using an item",
            "aim-assist - repeated near-perfect PvP aim, operator alert only",
            "hitbox - repeated PvP attack rays outside the server-side target hitbox",
            "criticals - repeated oversized hits while server-side grounded",
            "reach - eye to hitbox distance vs interaction range",
            "scaffold - placement rate, reach, crosshair",
            "boatfly / boatspeed / vehiclespeed - vehicle lift and per-surface speed",
            "macekill - mace damage vs server-observed fall",
            "spearkill - spear charge damage vs server-observed speed",
            "invalid-action - illegal pitch, sprinting with low food or blindness");

    private DetectionEngine engine;
    private ViolationStore store;
    private final Set<UUID> alerted = new HashSet<>();
    private final Map<UUID, Long> debugUntil = new HashMap<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        validateConfig();
        openStore();
        engine = new DetectionEngine(this);
        new ClientBrandReporter(this);
        MovementChecks movement = new MovementChecks(this);
        Bukkit.getPluginManager().registerEvents(movement, this);
        Bukkit.getPluginManager().registerEvents(new CombatChecks(this), this);
        Bukkit.getPluginManager().registerEvents(new WorldChecks(this), this);
        Bukkit.getScheduler().runTaskTimer(this, movement::tick, 1L, 1L);
        PluginCommand cmd = Objects.requireNonNull(getCommand("anticheat"), "anticheat missing from plugin.yml");
        cmd.setExecutor(this::command);
        cmd.setTabCompleter(this);
        Bukkit.getScheduler().runTaskTimer(this, engine::decayAll, 1200L, 1200L);
        getLogger().info("SentinelAC enabled for Paper 26.2. Monitoring-only=" + getConfig().getBoolean("punishments.monitoring-only", true));
    }

    @Override public void onDisable() {
        if (store != null) store.close();
        if (engine != null) engine.clear();
    }

    private void openStore() {
        try {
            store = new SqliteViolationStore(
                    getDataFolder().toPath().resolve(getConfig().getString("database.file", "sentinel.db")),
                    getConfig().getInt("performance.storage-queue-capacity", 10000), getLogger(),
                    getConfig().getInt("logging.retention-days", 30));
        } catch (Exception e) {
            getLogger().severe("SQLite unavailable; persistent evidence disabled: " + e.getMessage());
        }
    }

    public DetectionEngine engine() { return engine; }

    /** Records a flag for a check. All checks are always on; there is no per-check switch and no bypass. */
    public void flag(Player player, String check, String category, String evidence) {
        engine.flag(player, new CheckResult(check, category, 1.0, true, evidence));
    }

    // ------------------------------------------------------------ alert subscriptions
    public void setAlerts(UUID id, boolean on) { if (on) alerted.add(id); else alerted.remove(id); }
    public void forget(UUID id) { alerted.remove(id); debugUntil.remove(id); }

    // ------------------------------------------------------------ config
    private void validateConfig() {
        clampNonNegative("violations.alert-threshold", 3);
        clampNonNegative("punishments.automatic-kick.minimum-violations", 20);
        clampNonNegative("punishments.automatic-ban.minimum-violations", 50);
        clampNonNegative("performance.storage-queue-capacity", 10000);
        clampNonNegative("violations.confirmation-window-seconds", 30);
        String duration = getConfig().getString("punishments.automatic-ban.duration", "7d");
        if (Durations.parseMillis(duration) <= 0)
            getLogger().warning("punishments.automatic-ban.duration \"" + duration + "\" is invalid (use e.g. 30m, 12h, 7d); 7d will be used");
    }

    private void clampNonNegative(String key, double fallback) {
        if (getConfig().getDouble(key, -1) < 0) {
            getLogger().warning("Invalid or negative configuration value " + key + "; using " + fallback);
            getConfig().set(key, fallback);
        }
    }

    // ------------------------------------------------------------ output
    public Component color(String s) { return LEGACY.deserialize(s); }
    private void send(CommandSender to, String s) { to.sendMessage(color(s)); }

    public void persist(Player player, CheckResult r, double before, double after, String action) {
        FileConfiguration c = getConfig();
        int ping = player.getPing();
        String world = player.getWorld().getName();
        if (c.getBoolean("logging.console", true))
            getLogger().info("[VIOLATION] player=" + player.getName() + " uuid=" + player.getUniqueId() + " check=" + r.check()
                    + " category=" + r.category() + " vl=" + before + "->" + after + " confirmed=" + r.confirmed()
                    + " evidence=\"" + r.evidence() + "\" ping=" + ping + " world=" + world + " action=" + action);
        if (store != null && c.getBoolean("logging.sqlite", true))
            store.record(player.getUniqueId(), player.getName(), r, before, after, ping, world, action);
        if (c.getBoolean("logging.debug", false) || debugUntil.getOrDefault(player.getUniqueId(), 0L) > System.currentTimeMillis())
            getLogger().info("[DEBUG] " + player.getName() + " " + r.check() + ": " + r.evidence());
    }

    public void alert(Player player, CheckResult r, double vl) {
        if (!getConfig().getBoolean("alerts.enabled", true)) return;
        String format = getConfig().getString("alerts.format", "&c[Sentinel] &f{player} &7{check} &cVL {vl} &8({evidence}; ping {ping}ms)");
        Component msg = color(format.replace("{player}", player.getName()).replace("{check}", r.check())
                .replace("{vl}", String.format(Locale.US, "%.1f", vl)).replace("{evidence}", r.evidence())
                .replace("{ping}", String.valueOf(player.getPing())));
        for (UUID id : alerted) {
            Player staff = Bukkit.getPlayer(id);
            if (staff != null && staff.isOp()) staff.sendMessage(msg);   // logs are visible to operators only
        }
    }

    public void audit(Player actor, Player target, String action, String detail) {
        if (store != null) store.audit(actor == null ? null : actor.getUniqueId(), target == null ? null : target.getUniqueId(), action, detail);
        getLogger().info("[AUDIT] actor=" + (actor == null ? "console" : actor.getName()) + " target=" + (target == null ? "-" : target.getName()) + " action=" + action + " detail=" + detail);
    }

    // ------------------------------------------------------------ punishment
    public String processAutomaticPunishment(Player player, CheckResult r, double vl) {
        FileConfiguration c = getConfig();
        if (c.getBoolean("punishments.monitoring-only", true)) return "monitoring-only";

        String per = "punishments.checks." + r.check() + ".";
        PunishmentPolicy policy = new PunishmentPolicy(false,
                c.getBoolean("punishments.automatic-kick.enabled", false),
                c.getDouble(per + "automatic-kick.minimum-violations", c.getDouble("punishments.automatic-kick.minimum-violations", 20)),
                c.getBoolean("punishments.automatic-kick.require-confirmed-detection", true),
                c.getBoolean("punishments.automatic-ban.enabled", false),
                c.getDouble(per + "automatic-ban.minimum-violations", c.getDouble("punishments.automatic-ban.minimum-violations", 50)),
                c.getBoolean("punishments.automatic-ban.require-confirmed-detection", true));

        switch (policy.decide(vl, r.confirmed())) {
            case BAN -> {
                boolean permanent = c.getBoolean("punishments.automatic-ban.permanent", false);
                int independent = engine.independentConfirmedCategories(player);
                if (permanent && !PunishmentPolicy.permitsPermanentBan(r.confirmed(), independent)) {
                    audit(null, player, "automatic-ban-cancelled", "Permanent ban requires two independent confirmed categories; current=" + independent);
                    return "ban-cancelled-insufficient-independent-evidence";
                }
                long millis = Durations.parseMillis(c.getString("punishments.automatic-ban.duration", "7d"));
                Date expiry = permanent ? null : new Date(System.currentTimeMillis() + (millis > 0 ? millis : DEFAULT_BAN_MILLIS));
                audit(null, player, "automatic-ban", r.check() + " VL=" + vl + " evidence=" + r.evidence() + " threshold=" + policy.banThreshold() + " independent-confirmed-categories=" + independent);
                // The player-facing reason is deliberately generic: naming the check or evidence tells cheat developers what tripped.
                player.ban(c.getString("punishments.automatic-ban-reason", "Unfair advantage detected by SentinelAC"), expiry, "SentinelAC", true);
                return "automatic-ban";
            }
            case KICK -> {
                audit(null, player, "automatic-kick", r.check() + " VL=" + vl + " evidence=" + r.evidence() + " threshold=" + policy.kickThreshold());
                player.kick(color(c.getString("punishments.automatic-kick-message", "&cYou were removed by SentinelAC.")));
                return "automatic-kick";
            }
            default -> { return "none"; }
        }
    }

    // ------------------------------------------------------------ commands
    /** Every command is operator-only (console counts as an operator); permission plugins cannot widen this. */
    private boolean denied(CommandSender s, String ignoredPermission) {
        if (s.isOp()) return false;
        send(s, "&cOperators only.");
        return true;
    }

    private Player onlineTarget(CommandSender s, String[] a, String usage) {
        if (a.length < 2) { send(s, "&cUsage: " + usage); return null; }
        Player p = Bukkit.getPlayerExact(a[1]);
        if (p == null) send(s, "&cPlayer must be online.");
        return p;
    }

    private static Player actor(CommandSender s) { return s instanceof Player p ? p : null; }

    private boolean command(CommandSender sender, Command cmd, String label, String[] a) {
        String sub = a.length == 0 ? "status" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> {
                if (denied(sender, "sentinel.inspect")) return true; // do not tell every player whether punishments are live
                FileConfiguration c = getConfig();
                send(sender, "&eSentinelAC &7| Paper 26.2 | kick-ban-monitoring-only=" + c.getBoolean("punishments.monitoring-only", true)
                        + " | auto-kick=" + c.getBoolean("punishments.automatic-kick.enabled", false)
                        + " | auto-ban=" + c.getBoolean("punishments.automatic-ban.enabled", false)
                        + " | storage=" + (store != null ? "sqlite" : "unavailable"));
            }
            case "alerts" -> {
                if (!(sender instanceof Player p) || !p.isOp()) { send(sender, "&cOperators only (player command)."); return true; }
                boolean on = alerted.add(p.getUniqueId());
                if (!on) alerted.remove(p.getUniqueId());
                send(sender, "&eSentinel alerts: " + (on ? "&aon" : "&coff"));
            }
            case "reload" -> {
                if (denied(sender, "sentinel.admin")) return true;
                reloadConfig(); validateConfig();
                send(sender, "&aSentinelAC configuration reloaded.");
            }
            case "checks" -> {
                if (denied(sender, "")) return true;
                send(sender, "&eAll checks are always on:");
                for (String c : CHECKS) send(sender, "&7- " + c);
            }
            case "inspect", "violations" -> {
                if (denied(sender, "sentinel.inspect")) return true;
                Player p = onlineTarget(sender, a, "/anticheat inspect <player>");
                if (p != null) send(sender, "&eViolations for " + p.getName() + ": &f" + engine.data(p).violations());
            }
            case "reset" -> {
                if (denied(sender, "sentinel.admin")) return true;
                Player p = onlineTarget(sender, a, "/anticheat reset <player>");
                if (p == null) return true;
                engine.data(p).reset();
                audit(actor(sender), p, "reset-vl", "Manual reset");
                send(sender, "&aViolation data reset.");
            }
            case "debug" -> {
                if (denied(sender, "sentinel.admin")) return true;
                Player p = onlineTarget(sender, a, "/anticheat debug <player>");
                if (p == null) return true;
                debugUntil.put(p.getUniqueId(), System.currentTimeMillis() + getConfig().getInt("performance.debug-duration-seconds", 60) * 1000L);
                send(sender, "&eDebug enabled temporarily for " + p.getName());
            }
            case "punish" -> {
                if (denied(sender, "sentinel.admin") || denied(sender, "sentinel.punish")) return true;
                if (a.length < 3) { send(sender, "&cUsage: /anticheat punish <player> <reason>"); return true; }
                Player p = onlineTarget(sender, a, "/anticheat punish <player> <reason>");
                if (p == null) return true;
                String reason = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
                audit(actor(sender), p, "manual-kick", reason);
                p.kick(color(getConfig().getString("punishments.manual-kick-message", "&cRemoved: {reason}").replace("{reason}", reason)));
                send(sender, "&aManual punishment recorded.");
            }
            default -> send(sender, "&eUsage: /anticheat <" + String.join("|", SUBCOMMANDS) + ">");
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] args) {
        if (!s.isOp()) return List.of();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 1) return SUBCOMMANDS.stream().filter(x -> x.startsWith(prefix)).toList();
        if (args.length == 2 && PLAYER_SUBCOMMANDS.contains(sub))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        return List.of();
    }
}
