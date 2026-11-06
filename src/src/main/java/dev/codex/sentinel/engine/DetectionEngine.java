package dev.codex.sentinel.engine;

import dev.codex.sentinel.SentinelPlugin;
import dev.codex.sentinel.check.CheckResult;
import dev.codex.sentinel.data.PlayerData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Central VL bookkeeping. Every check is always on and nobody is exempt; every flag is alerted to ops and logged. */
public final class DetectionEngine {
    private final SentinelPlugin plugin;
    private final ConcurrentHashMap<UUID, PlayerData> players = new ConcurrentHashMap<>();

    public DetectionEngine(SentinelPlugin plugin) { this.plugin = plugin; }

    public PlayerData data(Player player) { return players.computeIfAbsent(player.getUniqueId(), PlayerData::new); }
    public void remove(UUID id) { players.remove(id); }
    public void clear() { players.clear(); }

    private long confirmationWindowMillis() {
        return plugin.getConfig().getLong("violations.confirmation-window-seconds", 30) * 1000L;
    }

    public void flag(Player player, CheckResult raw) {
        FileConfiguration cfg = plugin.getConfig();
        String check = raw.check();
        String path = "checks." + check + ".";
        long now = PlayerData.nowMillis();
        PlayerData d = data(player);
        double weight = cfg.getDouble(path + "violations-per-flag", raw.weight());
        double before = d.vl(check);
        double after = d.add(check, weight);

        // "Confirmed" (needed for automatic kick/ban) means N flags of the same check inside a rolling window.
        int recent = d.recordFlag(check, raw.category(), now, confirmationWindowMillis());
        boolean confirmed = raw.confirmed() && recent >= cfg.getInt(path + "confirmed-after", 5);
        CheckResult stored = new CheckResult(check, raw.category(), weight, confirmed, raw.evidence());

        plugin.alert(player, stored, after);                                     // every flag, to every subscribed op
        String action = plugin.processAutomaticPunishment(player, stored, after);
        plugin.persist(player, stored, before, after, action);                   // every flag, to console and SQLite
    }

    /** Number of distinct detection categories (movement, combat, ...) that are currently confirmed. */
    public int independentConfirmedCategories(Player player) {
        return data(player).confirmedCategories(PlayerData.nowMillis(), confirmationWindowMillis(),
                check -> plugin.getConfig().getInt("checks." + check + ".confirmed-after", 5)).size();
    }

    public void decayAll() {
        double amount = plugin.getConfig().getDouble("violations.decay-per-minute", 1.0);
        long now = PlayerData.nowMillis();
        players.values().forEach(p -> p.decay(amount, now));
    }
}
