package cz.voiceguard.punish;

import cz.voiceguard.config.ConfigManager;
import cz.voiceguard.db.DatabaseManager;
import cz.voiceguard.filter.TextNormalizer;
import cz.voiceguard.filter.WordFilter;
import cz.voiceguard.log.ViolationLogger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;
import java.util.UUID;

/**
 * Handles a single piece of recognized speech: normalize -> filter -> (if a
 * blocked word was found) escalate + mute + persist + log + notify.
 * <p>
 * Must be called on the main thread (it touches Bukkit Player/permission
 * APIs) - {@link cz.voiceguard.audio.AudioBufferManager} already bounces the
 * STT callback back to the main thread before invoking this.
 */
public final class PunishmentManager {

    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final TextNormalizer normalizer;
    private final WordFilter wordFilter;
    private final MuteManager muteManager;
    private final DatabaseManager database;
    private final ViolationLogger logger;

    public PunishmentManager(JavaPlugin plugin, ConfigManager config, TextNormalizer normalizer, WordFilter wordFilter,
                              MuteManager muteManager, DatabaseManager database, ViolationLogger logger) {
        this.plugin = plugin;
        this.config = config;
        this.normalizer = normalizer;
        this.wordFilter = wordFilter;
        this.muteManager = muteManager;
        this.database = database;
        this.logger = logger;
    }

    public void handleTranscription(UUID playerId, String rawText) {
        String normalized = normalizer.normalize(rawText);
        Optional<String> blocked = wordFilter.findBlockedWord(normalized);
        if (blocked.isEmpty()) {
            return;
        }
        if (!config.isPunishmentEnabled()) {
            return;
        }

        Player player = Bukkit.getPlayer(playerId);
        String name = player != null ? player.getName() : playerId.toString();
        String detectedWord = blocked.get();

        database.getOrCreatePlayer(playerId, name).thenAccept(record -> {
            int newViolationCount = record.violationCount() + 1;
            long durationMillis = config.resolveDurationForViolationCount(newViolationCount);
            long muteUntil = durationMillis == DurationParser.PERMANENT
                    ? DurationParser.PERMANENT
                    : System.currentTimeMillis() + durationMillis;

            Bukkit.getScheduler().runTask(plugin, () -> {
                muteManager.mute(playerId, name, durationMillis);

                if (player != null && player.isOnline()) {
                    player.sendMessage(config.getMessage("word-detected"));
                    player.sendMessage(config.getMessage("mute-applied")
                            .replace("%duration%", DurationParser.format(durationMillis)));
                }

                logger.log(name, playerId, config.isStoreTranscriptions() ? rawText : null, detectedWord,
                        "voice-mute (violation #" + newViolationCount + ")", DurationParser.format(durationMillis));
            });

            database.recordViolationAndMute(playerId, name, rawText, detectedWord, muteUntil,
                    newViolationCount, durationMillis);
        }).exceptionally(ex -> {
            plugin.getLogger().warning("Failed to process violation for " + name + ": " + ex.getMessage());
            return null;
        });
    }
}
