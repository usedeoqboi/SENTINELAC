package dev.codex.sentinel;

import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Reports and stores the brand Paper receives from each joining client. */
public final class ClientBrandReporter implements Listener, CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final File dataFile;
    private final YamlConfiguration saved;
    private final Set<UUID> reported = new HashSet<>();

    public ClientBrandReporter(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "client-brands.yml");
        this.saved = YamlConfiguration.loadConfiguration(dataFile);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (plugin.getCommand("brandcheck") != null) {
            plugin.getCommand("brandcheck").setExecutor(this);
            plugin.getCommand("brandcheck").setTabCompleter(this);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Paper receives the client brand during login/configuration; check after the join transition,
        // then retry once in case a proxy/client has not supplied it yet.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || reported.contains(player.getUniqueId())) return;
            String brand = clean(player.getClientBrandName());
            if (brand != null) {
                report(player, brand);
                return;
            }
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline() || reported.contains(player.getUniqueId())) return;
                report(player, clean(player.getClientBrandName()) == null
                        ? "unknown (client did not report a brand)"
                        : clean(player.getClientBrandName()));
            }, 20L);
        }, 1L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        reported.remove(event.getPlayer().getUniqueId());
    }

    private static String clean(String brand) {
        if (brand == null || brand.isBlank()) return null;
        String safe = brand.replaceAll("[\\p{Cntrl}]", "?").trim();
        return safe.isEmpty() ? null : safe.substring(0, Math.min(safe.length(), 80));
    }

    private void report(Player player, String brand) {
        UUID id = player.getUniqueId();
        if (!reported.add(id)) return;

        String path = "players." + id;
        saved.set(path + ".name", player.getName());
        saved.set(path + ".brand", brand);
        saved.set(path + ".last-seen", Instant.now().toString());
        try {
            saved.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save client brand data: " + e.getMessage());
        }

        Component notice = Component.text("[SentinelAC] " + player.getName() + " joined with " + brand);
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (viewer.isOp()) viewer.sendMessage(notice);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player) || !player.isOp()) {
            sender.sendMessage(Component.text("Only operators can use /brandcheck."));
            return true;
        }
        if (args.length != 1) {
            player.sendMessage(Component.text("Usage: /brandcheck <player>"));
            return true;
        }

        String query = args[0];
        if (saved.getConfigurationSection("players") != null) {
            for (String key : saved.getConfigurationSection("players").getKeys(false)) {
                String path = "players." + key;
                String name = saved.getString(path + ".name", "");
                if (name.equalsIgnoreCase(query)) {
                    player.sendMessage(Component.text("[SentinelAC] " + name + " last joined with "
                            + saved.getString(path + ".brand", "unknown") + " ("
                            + saved.getString(path + ".last-seen", "time unknown") + ")"));
                    return true;
                }
            }
        }
        player.sendMessage(Component.text("No saved client brand found for " + query + "."));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || !player.isOp() || args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(java.util.Locale.ROOT);
        List<String> names = new ArrayList<>();
        if (saved.getConfigurationSection("players") != null) {
            for (String key : saved.getConfigurationSection("players").getKeys(false)) {
                String name = saved.getString("players." + key + ".name", "");
                if (name.toLowerCase(java.util.Locale.ROOT).startsWith(prefix)) names.add(name);
            }
        }
        return names;
    }
}
