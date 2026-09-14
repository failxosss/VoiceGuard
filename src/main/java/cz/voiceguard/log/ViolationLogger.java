package cz.voiceguard.log;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Appends one line per violation to plugins/VoiceGuard/logs/violations-YYYY-MM-DD.log.
 * File I/O runs on its own single background thread so logging never blocks
 * the main thread or the STT workers.
 */
public final class ViolationLogger {

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter LINE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final JavaPlugin plugin;
    private final File logsDir;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VoiceGuard-Logger");
        t.setDaemon(true);
        return t;
    });

    public ViolationLogger(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logsDir = new File(plugin.getDataFolder(), "logs");
    }

    public void init() {
        if (!logsDir.exists()) {
            logsDir.mkdirs();
        }
    }

    public void log(String playerName, UUID uuid, String recognizedText, String detectedWord,
                     String punishmentDescription, String muteDuration) {
        executor.execute(() -> {
            Instant now = Instant.now();
            File file = new File(logsDir, "violations-" + FILE_DATE.format(now) + ".log");
            String line = String.format(
                    "[%s] player=%s uuid=%s detected_word=%s punishment=%s mute_duration=%s recognized_text=\"%s\"",
                    LINE_TIME.format(now), playerName, uuid, detectedWord, punishmentDescription, muteDuration,
                    recognizedText == null ? "" : recognizedText.replace("\"", "'")
            );
            try (PrintWriter writer = new PrintWriter(new FileWriter(file, true))) {
                writer.println(line);
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to write violation log: " + e.getMessage());
            }
        });
    }

    public void shutdown() {
        executor.shutdown();
    }
}
