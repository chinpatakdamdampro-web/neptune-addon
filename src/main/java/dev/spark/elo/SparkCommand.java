package dev.spark.elo;

import dev.lrxh.api.profile.IProfile;
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

        // --- Online path ---
        Player onlineTarget = Bukkit.getPlayer(targetName);
        if (onlineTarget != null) {
            IProfile profile = neptune.getCached(onlineTarget.getUniqueId());
            if (profile == null) {
                sender.sendMessage("§cNeptune has no profile loaded for §f" + onlineTarget.getName()
                        + "§c. Try again in a moment.");
                return;
            }
            String division = neptune.applyElo(profile, amount);
            sender.sendMessage("§aSet ELO to §f" + amount
                    + " §afor §f" + onlineTarget.getName()
                    + " §a→ division: §f" + division);
            onlineTarget.sendMessage("§aYour ELO has been set to §f" + amount
                    + " §a(§f" + division + "§a) by an admin.");
            return;
        }

        // --- Offline path ---
        @SuppressWarnings("deprecation")
        OfflinePlayer offlineTarget = Bukkit.getOfflinePlayer(targetName);

        // getOfflinePlayer() with a name that has never played returns a dummy with
        // hasPlayedBefore() == false.
        if (!offlineTarget.hasPlayedBefore()) {
            sender.sendMessage("§cPlayer §f'" + targetName + "'§c has never joined this server.");
            return;
        }

        UUID uuid = offlineTarget.getUniqueId();
        String resolvedName = offlineTarget.getName() != null ? offlineTarget.getName() : targetName;

        sender.sendMessage("§eLoading offline profile for §f" + resolvedName + "§e, please wait…");

        int finalAmount = amount;
        neptune.loadProfile(uuid).thenAcceptAsync(profile -> {
            if (profile == null) {
                // Schedule message back to the main thread
                scheduleSync(() -> sender.sendMessage(
                        "§cCould not load Neptune profile for §f" + resolvedName + "§c."));
                return;
            }
            String division = neptune.applyElo(profile, finalAmount);
            scheduleSync(() -> sender.sendMessage(
                    "§aSet ELO to §f" + finalAmount
                    + " §afor offline player §f" + resolvedName
                    + " §a→ division: §f" + division));
        }).exceptionally(ex -> {
            scheduleSync(() -> sender.sendMessage(
                    "§cAn error occurred loading the profile: " + ex.getMessage()));
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
