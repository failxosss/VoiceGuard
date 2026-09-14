package cz.voiceguard.config;

import cz.voiceguard.punish.DurationParser;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ConfigManager {

    private final JavaPlugin plugin;

    private String sttLanguage;
    private String sttModelPath;
    private int sttWorkerThreads;
    private int sttSampleRate;

    private long silenceTimeoutMs;
    private long maxSegmentMs;
    private long minSegmentMs;

    private Set<String> blockedWords = new LinkedHashSet<>();

    private boolean normalizationEnabled;
    private boolean lowercase;
    private boolean removePunctuation;
    private boolean normalizeSpaces;
    private boolean collapseLetterSpacing;

    private boolean punishmentEnabled;
    private long defaultDurationMillis;
    private boolean escalationEnabled;
    private final List<PunishmentLevel> escalationLevels = new ArrayList<>();

    private boolean storeAudio;
    private boolean storeTranscriptions;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();

        sttLanguage = cfg.getString("stt.language", "cs");
        sttModelPath = cfg.getString("stt.model-path", "models/vosk-model-small-cs-0.4");
        sttWorkerThreads = Math.max(1, cfg.getInt("stt.worker-threads", 2));
        sttSampleRate = cfg.getInt("stt.sample-rate", 16000);

        silenceTimeoutMs = cfg.getLong("audio.silence-timeout-ms", 500L);
        maxSegmentMs = cfg.getLong("audio.max-segment-ms", 8000L);
        minSegmentMs = cfg.getLong("audio.min-segment-ms", 250L);

        blockedWords = new LinkedHashSet<>();
        for (String word : cfg.getStringList("blocked-words")) {
            if (word != null && !word.isBlank()) {
                blockedWords.add(word.trim().toLowerCase());
            }
        }

        normalizationEnabled = cfg.getBoolean("text-normalization.enabled", true);
        lowercase = cfg.getBoolean("text-normalization.lowercase", true);
        removePunctuation = cfg.getBoolean("text-normalization.remove-punctuation", true);
        normalizeSpaces = cfg.getBoolean("text-normalization.normalize-spaces", true);
        collapseLetterSpacing = cfg.getBoolean("text-normalization.collapse-letter-spacing", true);

        punishmentEnabled = cfg.getBoolean("punishment.enabled", true);
        defaultDurationMillis = DurationParser.parse(cfg.getString("punishment.duration", "10m"));

        escalationEnabled = cfg.getBoolean("punishment.escalation.enabled", true);
        escalationLevels.clear();
        List<?> rawLevels = cfg.getList("punishment.escalation.levels");
        if (rawLevels != null) {
            for (Object o : rawLevels) {
                if (o instanceof ConfigurationSection section) {
                    int violations = section.getInt("violations");
                    long duration = DurationParser.parse(section.getString("duration"));
                    escalationLevels.add(new PunishmentLevel(violations, duration));
                } else if (o instanceof java.util.Map<?, ?> map) {
                    int violations = ((Number) map.get("violations")).intValue();
                    long duration = DurationParser.parse(String.valueOf(map.get("duration")));
                    escalationLevels.add(new PunishmentLevel(violations, duration));
                }
            }
        }
        escalationLevels.sort((a, b) -> Integer.compare(a.getViolations(), b.getViolations()));

        storeAudio = cfg.getBoolean("privacy.store-audio", false);
        storeTranscriptions = cfg.getBoolean("privacy.store-transcriptions", true);
    }

    public String getMessage(String key) {
        FileConfiguration cfg = plugin.getConfig();
        String prefix = cfg.getString("messages.prefix", "");
        String msg = cfg.getString("messages." + key, "");
        return ChatColor.translateAlternateColorCodes('&', prefix + msg);
    }

    public String getRawMessage(String key) {
        return plugin.getConfig().getString("messages." + key, "");
    }

    public String getPrefix() {
        return ChatColor.translateAlternateColorCodes('&', plugin.getConfig().getString("messages.prefix", ""));
    }

    // --- STT ---
    public String getSttLanguage() {
        return sttLanguage;
    }

    public String getSttModelPath() {
        return sttModelPath;
    }

    public int getSttWorkerThreads() {
        return sttWorkerThreads;
    }

    public int getSttSampleRate() {
        return sttSampleRate;
    }

    // --- Audio buffering ---
    public long getSilenceTimeoutMs() {
        return silenceTimeoutMs;
    }

    public long getMaxSegmentMs() {
        return maxSegmentMs;
    }

    public long getMinSegmentMs() {
        return minSegmentMs;
    }

    // --- Word filter ---
    public Set<String> getBlockedWords() {
        return blockedWords;
    }

    public boolean isNormalizationEnabled() {
        return normalizationEnabled;
    }

    public boolean isLowercase() {
        return lowercase;
    }

    public boolean isRemovePunctuation() {
        return removePunctuation;
    }

    public boolean isNormalizeSpaces() {
        return normalizeSpaces;
    }

    public boolean isCollapseLetterSpacing() {
        return collapseLetterSpacing;
    }

    // --- Punishment ---
    public boolean isPunishmentEnabled() {
        return punishmentEnabled;
    }

    public long getDefaultDurationMillis() {
        return defaultDurationMillis;
    }

    public boolean isEscalationEnabled() {
        return escalationEnabled;
    }

    public List<PunishmentLevel> getEscalationLevels() {
        return escalationLevels;
    }

    public long resolveDurationForViolationCount(int violationCount) {
        if (!escalationEnabled || escalationLevels.isEmpty()) {
            return defaultDurationMillis;
        }
        long duration = defaultDurationMillis;
        for (PunishmentLevel level : escalationLevels) {
            if (violationCount >= level.getViolations()) {
                duration = level.getDurationMillis();
            }
        }
        return duration;
    }

    // --- Privacy ---
    public boolean isStoreAudio() {
        return storeAudio;
    }

    public boolean isStoreTranscriptions() {
        return storeTranscriptions;
    }
}
