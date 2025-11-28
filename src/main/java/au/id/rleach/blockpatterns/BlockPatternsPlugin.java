package au.id.rleach.blockpatterns;

import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.dvs.versioning.BasicVersioning;
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings;
import dev.dejvokep.boostedyaml.settings.updater.UpdaterSettings;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;

public final class BlockPatternsPlugin extends JavaPlugin {

    private BukkitAudiences adventure;
    private MiniMessage mini;
    private YamlDocument config;

    @Override
    public void onEnable() {
        this.adventure = BukkitAudiences.create(this);
        this.mini = MiniMessage.miniMessage();

        // Ensure data folder exists
        if (!getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            getDataFolder().mkdirs();
        }

        // Load config using BoostedYAML (preserve comments, update with defaults)
        try {
            File configFile = new File(getDataFolder(), "config.yml");
            if (!configFile.exists()) {
                try (InputStream in = getResource("config.yml")) {
                    if (in != null) {
                        Files.copy(in, configFile.toPath());
                    } else {
                        // fallback: create empty file if resource missing
                        //noinspection ResultOfMethodCallIgnored
                        configFile.createNewFile();
                    }
                }
            }
            this.config = YamlDocument.create(
                    configFile,
                    getResource("defaultConfig.yml"),
                    GeneralSettings.builder().setUseDefaults(true).build(),
                    UpdaterSettings.builder().setVersioning(new BasicVersioning("version")).setAutoSave(true).build()
            );
        } catch (Exception ex) {
            getSLF4JLogger().error("Failed to load defaultConfig.yml", ex);
        }

        getSLF4JLogger().info("BlockPatterns enabled");
    }

    @Override
    public void onDisable() {
        if (this.adventure != null) this.adventure.close();
        getSLF4JLogger().info("BlockPatterns disabled");
    }

    // Simple command handler as a starting point.
    // You can migrate to cloud-commandframework later for richer commands.
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("blockpatterns")) {
            return false;
        }
        if (!sender.hasPermission("blockpatterns.use")) {
            adventure.sender(sender).sendMessage(mini.deserialize("<red>You lack permission: <gray>blockpatterns.use"));
            return true;
        }

        if (args.length == 0) {
            adventure.sender(sender).sendMessage(mini.deserialize("<green>BlockPatterns <gray>- ready. Try </gray>/" + label + " ping<gray>."));
            return true;
        }

        if (args[0].equalsIgnoreCase("ping")) {
            adventure.sender(sender).sendMessage(mini.deserialize("<yellow>Pong!<gray> (hot-swap supported for method bodies)"));
            return true;
        }

        adventure.sender(sender).sendMessage(mini.deserialize("<red>Unknown subcommand. <gray>Try: </gray>ping"));
        return true;
    }
}
