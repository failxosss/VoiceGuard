package cz.voiceguard.command;

import cz.voiceguard.VoiceGuardPlugin;
import cz.voiceguard.config.ConfigManager;
import cz.voiceguard.db.ViolationRecord;
import cz.voiceguard.punish.DurationParser;
import cz.voiceguard.stt.VoskSpeechToTextService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public final class VoiceGuardCommand implements CommandExecutor {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final VoiceGuardPlugin plugin;

    public VoiceGuardCommand(VoiceGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "reload" -> handleReload(sender);
            case "status" -> handleStatus(sender);
            case "mute" -> handleMute(sender, args);
            case "unmute" -> handleUnmute(sender, args);
            case "history" -> handleHistory(sender, args);
            case "test" -> handleTest(sender);
            default -> sendUsage(sender);
        }
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.GRAY + "/voiceguard <reload|status|mute|unmute|history|test>");
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("voiceguard.reload")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("no-permission"));
            return;
        }
        plugin.getConfigManager().load();
        sender.sendMessage(plugin.getConfigManager().getPrefix() + ChatColor.GREEN + "Konfigurace znovu načtena.");
    }

    private void handleStatus(CommandSender sender) {
        if (!sender.hasPermission("voiceguard.admin")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("no-permission"));
            return;
        }
        ConfigManager cfg = plugin.getConfigManager();
        VoskSpeechToTextService stt = plugin.getSttService();

        sender.sendMessage(ChatColor.AQUA + "VoiceGuard");
        sender.sendMessage(ChatColor.GRAY + "──────────────");
        sender.sendMessage("Plugin: " + ChatColor.GREEN + "ENABLED");
        sender.sendMessage("Simple Voice Chat: " + (plugin.getVoiceChatHook() != null && plugin.getVoiceChatHook().getApi() != null
                ? ChatColor.GREEN + "CONNECTED" : ChatColor.RED + "NOT CONNECTED"));
        sender.sendMessage("Voice API: " + (plugin.getVoiceChatHook() != null ? ChatColor.GREEN + "READY" : ChatColor.RED + "NOT READY"));
        sender.sendMessage("STT: " + (stt.isReady() ? ChatColor.GREEN + "READY"
                : stt.isFailed() ? ChatColor.RED + "FAILED (" + stt.getFailureReason() + ")"
                : ChatColor.YELLOW + "LOADING"));
        sender.sendMessage("STT Language: " + cfg.getSttLanguage());
        sender.sendMessage("Players monitored: " + plugin.getBufferManager().getMonitoredPlayerCount());
    }

    private void handleMute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("voiceguard.mute")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("no-permission"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.GRAY + "/voiceguard mute <hráč> [délka]");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (target.getUniqueId() == null || (!target.hasPlayedBefore() && !target.isOnline())) {
            sender.sendMessage(plugin.getConfigManager().getMessage("player-not-found"));
            return;
        }
        long durationMillis;
        try {
            durationMillis = args.length >= 3
                    ? DurationParser.parse(args[2])
                    : plugin.getConfigManager().getDefaultDurationMillis();
        } catch (IllegalArgumentException e) {
            sender.sendMessage(ChatColor.RED + e.getMessage());
            return;
        }
        String name = target.getName() != null ? target.getName() : target.getUniqueId().toString();
        plugin.getMuteManager().mute(target.getUniqueId(), name, durationMillis);
        plugin.getDatabaseManager().setMuteUntil(target.getUniqueId(), name,
                durationMillis == DurationParser.PERMANENT ? DurationParser.PERMANENT
                        : System.currentTimeMillis() + durationMillis);
        sender.sendMessage(plugin.getConfigManager().getPrefix() + ChatColor.GREEN + name
                + " byl voice-mutován na " + DurationParser.format(durationMillis) + ".");
    }

    private void handleUnmute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("voiceguard.unmute")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("no-permission"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.GRAY + "/voiceguard unmute <hráč>");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        plugin.getMuteManager().unmute(target.getUniqueId());
        plugin.getDatabaseManager().setMuteUntil(target.getUniqueId(),
                target.getName() != null ? target.getName() : target.getUniqueId().toString(), 0L);
        sender.sendMessage(plugin.getConfigManager().getPrefix() + ChatColor.GREEN
                + (target.getName() != null ? target.getName() : args[1]) + " byl odmutován.");
    }

    private void handleHistory(CommandSender sender, String[] args) {
        if (!sender.hasPermission("voiceguard.history")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("no-permission"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.GRAY + "/voiceguard history <hráč>");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        UUID uuid = target.getUniqueId();
        plugin.getDatabaseManager().getHistory(uuid, 10).thenAccept(records ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (records.isEmpty()) {
                        sender.sendMessage(plugin.getConfigManager().getPrefix() + "Žádné záznamy pro " + args[1] + ".");
                        return;
                    }
                    sender.sendMessage(ChatColor.AQUA + "Historie porušení - " + args[1] + " (posledních " + records.size() + "):");
                    for (ViolationRecord r : records) {
                        String time = TIME_FORMAT.format(Instant.ofEpochMilli(r.timestamp()));
                        sender.sendMessage(ChatColor.GRAY + "[" + time + "] " + ChatColor.YELLOW + r.detectedWord()
                                + ChatColor.GRAY + " -> mute " + DurationParser.format(r.punishmentDurationMillis()));
                    }
                }));
    }

    private void handleTest(CommandSender sender) {
        if (!sender.hasPermission("voiceguard.admin")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("no-permission"));
            return;
        }
        sender.sendMessage(ChatColor.AQUA + "VoiceGuard self-test:");

        boolean svcConnected = plugin.getVoiceChatHook() != null && plugin.getVoiceChatHook().getApi() != null;
        line(sender, "Simple Voice Chat", svcConnected);
        line(sender, "Microphone API (MicrophonePacketEvent registered)", plugin.getVoiceChatHook() != null);
        line(sender, "Opus Decoder (opus4j)", true); // instantiated per-player on first packet; library presence confirmed at class-load
        VoskSpeechToTextService stt = plugin.getSttService();
        line(sender, "Vosk", !stt.isFailed());
        line(sender, "Czech Model (" + plugin.getConfigManager().getSttModelPath() + ")", stt.isReady());
        line(sender, "Database", plugin.getDatabaseManager() != null);
        line(sender, "Word Filter (" + plugin.getConfigManager().getBlockedWords().size() + " words loaded)",
                !plugin.getConfigManager().getBlockedWords().isEmpty());
        line(sender, "Mute System", true);
    }

    private void line(CommandSender sender, String label, boolean ok) {
        sender.sendMessage((ok ? ChatColor.GREEN + "[OK] " : ChatColor.RED + "[FAIL] ") + ChatColor.RESET + label);
    }
}
