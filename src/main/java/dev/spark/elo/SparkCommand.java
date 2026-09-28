package dev.spark.elo;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SparkCommand implements CommandExecutor, TabCompleter {

    private static final String USAGE = "§eUsage: §f/spark setelo <player> <amount>";

    private final SparkElo plugin;
    private final NeptuneHelper neptune;

    public SparkCommand(SparkElo plugin) {
        this.plugin = plugin;
        this.neptune = new NeptuneHelper(plugin.getLogger());
    }

    // -------------------------------------------------------------------------
    // Command dispatch
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (!sender.hasPermission("spark.admin")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        // /spark setelo <player> <amount>
        if (args.length >= 1 && args[0].equalsIgnoreCase("setelo")) {
            handleSetElo(sender, args);
            return true;
        }

        sender.sendMessage(USAGE);
        return true;
    }

    // -------------------------------------------------------------------------
    // /spark setelo <player> <amount>
    // -------------------------------------------------------------------------

    private void handleSetElo(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(USAGE);
            return;
        }

        String targetName = args[1];
        int amount;
        try {
            amount = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§c'" + args[2] + "' is not a valid number.");
            return;
        }
        if (amount < 0) {
            sender.sendMessage("§cELO amount cannot be negative.");
            return;
        }

        // Resolve the target — works for both online and offline players.
        // For online players, Neptune's getProfile(UUID) returns immediately from
        // its in-memory cache (no DB round-trip). For offline players it goes to
        // the database. Either way we use the same async path so we never touch
        // getCachedProfile(), which is absent from some Neptune builds.
        Player onlineTarget = Bukkit.getPlayer(targetName);

        @SuppressWarnings("deprecation")
        OfflinePlayer offlineTarget = onlineTarget != null
                ? onlineTarget
                : Bukkit.getOfflinePlayer(targetName);

        if (!offlineTarget.hasPlayedBefore() && onlineTarget == null) {
            sender.sendMessage("§cPlayer §f'" + targetName + "'§c has never joined this server.");
            return;
        }

        UUID uuid = offlineTarget.getUniqueId();
        String resolvedName = offlineTarget.getName() != null ? offlineTarget.getName() : targetName;
        boolean isOnline = onlineTarget != null;

        if (!isOnline) {
            sender.sendMessage("§eLoading offline profile for §f" + resolvedName + "§e, please wait…");
        }

        int finalAmount = amount;
        neptune.loadProfile(uuid).thenAcceptAsync(profile -> {
            if (profile == null) {
                scheduleSync(() -> sender.sendMessage(
                        "§cCould not load Neptune profile for §f" + resolvedName + "§c."));
                return;
            }

            String division = neptune.applyElo(profile, finalAmount);

            scheduleSync(() -> {
                sender.sendMessage("§aSet ELO to §f" + finalAmount
                        + " §afor §f" + resolvedName
                        + " §a→ division: §f" + division);

                // Notify the player in-game if they are online.
                if (isOnline && onlineTarget.isOnline()) {
                    onlineTarget.sendMessage("§aYour ELO has been set to §f" + finalAmount
                            + " §a(§f" + division + "§a) by an admin.");
                }
            });
        }).exceptionally(ex -> {
            scheduleSync(() -> sender.sendMessage(
                    "§cAn error occurred: " + ex.getMessage()));
            plugin.getLogger().severe("[SparkElo] Error loading profile for " + resolvedName + ": " + ex);
            return null;
        });
    }

    // -------------------------------------------------------------------------
    // Tab completion
    // -------------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (!sender.hasPermission("spark.admin")) return List.of();

        List<String> out = new ArrayList<>();

        if (args.length == 1) {
            if ("setelo".startsWith(args[0].toLowerCase())) out.add("setelo");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("setelo")) {
            String partial = args[1].toLowerCase();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(partial)) out.add(p.getName());
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("setelo")) {
            out.add("<amount>");
        }

        return out;
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private void scheduleSync(Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }
}
