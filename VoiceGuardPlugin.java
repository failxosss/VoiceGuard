package cz.voiceguard;

import cz.voiceguard.audio.AudioBufferManager;
import cz.voiceguard.command.VoiceGuardCommand;
import cz.voiceguard.config.ConfigManager;
import cz.voiceguard.db.DatabaseManager;
import cz.voiceguard.filter.TextNormalizer;
import cz.voiceguard.filter.WordFilter;
import cz.voiceguard.log.ViolationLogger;
import cz.voiceguard.punish.MuteManager;
import cz.voiceguard.punish.PunishmentManager;
import cz.voiceguard.stt.VoskSpeechToTextService;
import cz.voiceguard.voicechat.VoiceChatHook;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;

public final class VoiceGuardPlugin extends JavaPlugin implements Listener {

    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private ViolationLogger violationLogger;
    private WordFilter wordFilter;
    private TextNormalizer textNormalizer;
    private VoskSpeechToTextService sttService;
    private AudioBufferManager bufferManager;
    private MuteManager muteManager;
    private PunishmentManager punishmentManager;
    private VoiceChatHook voiceChatHook;

    @Override
    public void onEnable() {
        configManager = new ConfigManager(this);
        configManager.load();

        violationLogger = new ViolationLogger(this);
        violationLogger.init();

        databaseManager = new DatabaseManager(this, configManager);

        wordFilter = new WordFilter(configManager);
        textNormalizer = new TextNormalizer(configManager);

        muteManager = new MuteManager(this, databaseManager);

        Path modelPath = getDataFolder().toPath().resolve(configManager.getSttModelPath());
        sttService = new VoskSpeechToTextService(this, modelPath, configManager.getSttSampleRate(),
                configManager.getSttLanguage(), configManager.getSttWorkerThreads());

        punishmentManager = new PunishmentManager(this, configManager, textNormalizer, wordFilter,
                muteManager, databaseManager, violationLogger);

        bufferManager = new AudioBufferManager(this, configManager, sttService,
                (playerId, text) -> punishmentManager.handleTranscription(playerId, text));
        bufferManager.start();

        voiceChatHook = new VoiceChatHook(this, muteManager, bufferManager);

        BukkitVoicechatService service = getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (service != null) {
            service.registerPlugin(voiceChatHook);
            getLogger().info("Registered VoiceGuard with Simple Voice Chat.");
        } else {
            getLogger().severe("Simple Voice Chat's BukkitVoicechatService is not available! "
                    + "Is Simple Voice Chat installed and enabled? VoiceGuard cannot function without it.");
        }

        Bukkit.getPluginManager().registerEvents(this, this);

        VoiceGuardCommand commandExecutor = new VoiceGuardCommand(this);
        getCommand("voiceguard").setExecutor(commandExecutor);

        databaseManager.init().thenRun(() -> Bukkit.getScheduler().runTask(this, muteManager::loadPersistedMutes));

        getLogger().info("VoiceGuard enabled. Loading Vosk model in the background...");
    }

    @Override
    public void onDisable() {
        if (bufferManager != null) {
            bufferManager.stop();
        }
        if (sttService != null) {
            sttService.shutdown();
        }
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
        if (violationLogger != null) {
            violationLogger.shutdown();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        muteManager.applyAttachmentIfMuted(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        bufferManager.onPlayerLeave(event.getPlayer().getUniqueId());
        muteManager.cleanupOnQuit(event.getPlayer().getUniqueId());
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public VoskSpeechToTextService getSttService() {
        return sttService;
    }

    public AudioBufferManager getBufferManager() {
        return bufferManager;
    }

    public MuteManager getMuteManager() {
        return muteManager;
    }

    public VoiceChatHook getVoiceChatHook() {
        return voiceChatHook;
    }
}
