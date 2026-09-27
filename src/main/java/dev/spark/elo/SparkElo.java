package dev.spark.elo;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class SparkElo extends JavaPlugin {

    private static SparkElo instance;

    @Override
    public void onEnable() {
        instance = this;

        PluginCommand cmd = getCommand("spark");
        if (cmd != null) {
            SparkCommand executor = new SparkCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        } else {
            getLogger().severe("Could not register /spark command. Check plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getLogger().info("SparkElo enabled. /spark setelo is ready.");
    }

    @Override
    public void onDisable() {
        getLogger().info("SparkElo disabled.");
    }

    public static SparkElo get() {
        return instance;
    }
}
