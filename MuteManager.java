package cz.voiceguard.punish;

import cz.voiceguard.db.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks who is currently voice-muted and for how long.
 * <p>
 * The mechanism that actually and reliably stops audio from reaching other
 * players is {@code MicrophonePacketEvent#cancel()} in {@code VoiceChatHook}
 * - that check reads {@link #isMuted(UUID)} directly and works regardless of
 * what permission plugin (if any) is installed.
 * <p>
 * On top of that, this class also revokes the {@code voicechat.speak}
 * permission via a plain Bukkit {@link PermissionAttachment}. This is a
 * best-effort addition for servers where Simple Voice Chat itself checks
 * that permission (via a Vault-compatible permissions plugin) to drive the
 * client-side "you are muted" microphone icon - it does not require
 * LuckPerms or any other permission plugin, since PermissionAttachment is
 * core Bukkit API. If nothing checks that permission, the packet-cancel
 * above still fully enforces the mute; only the client icon feedback would
 * be missing.
 */
public final class MuteManager {

    private static final String SPEAK_PERMISSION = "voicechat.speak";

    private final JavaPlugin plugin;
    private final DatabaseManager database;

    private final Map<UUID, Long> muteExpiry = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastKnownName = new ConcurrentHashMap<>();
    private final Map<UUID, PermissionAttachment> attachments = new ConcurrentHashMap<>();

    public MuteManager(JavaPlugin plugin, DatabaseManager database) {
        this.plugin = plugin;
        this.database = database;
    }

    /**
     * Restores mutes that were still active at server shutdown.
     */
    public void loadPersistedMutes() {
        database.getActiveMutes().thenAccept(records -> Bukkit.getScheduler().runTask(plugin, () -> {
            for (DatabaseManager.PlayerRecord record : records) {
                muteExpiry.put(record.uuid(), record.muteUntil());
                lastKnownName.put(record.uuid(), record.name());
            }
            plugin.getLogger().info("Restored " + records.size() + " active voice mute(s) from database.");
        }));
    }

    public boolean hasBypass(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return player != null && player.hasPermission("voiceguard.bypass");
    }

    public boolean isMuted(UUID uuid) {
        Long expiry = muteExpiry.get(uuid);
        if (expiry == null) {
            return false;
        }
        if (expiry == DurationParser.PERMANENT) {
            return true;
        }
        if (System.currentTimeMillis() >= expiry) {
            unmute(uuid);
            return false;
        }
        return true;
    }

    public long getRemainingMillis(UUID uuid) {
        Long expiry = muteExpiry.get(uuid);
        if (expiry == null) {
            return 0L;
        }
        if (expiry == DurationParser.PERMANENT) {
            return DurationParser.PERMANENT;
        }
        return Math.max(0L, expiry - System.currentTimeMillis());
    }

    /**
     * @param durationMillis milliseconds from now, or {@link DurationParser#PERMANENT}
     */
    public void mute(UUID uuid, String name, long durationMillis) {
        long expiry = durationMillis == DurationParser.PERMANENT
                ? DurationParser.PERMANENT
                : System.currentTimeMillis() + durationMillis;
        muteExpiry.put(uuid, expiry);
        lastKnownName.put(uuid, name);
        applyPermissionAttachment(uuid);

        if (durationMillis != DurationParser.PERMANENT) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (isMuted(uuid)) {
                    // isMuted() already auto-unmutes on expiry; this just
                    // guarantees the permission attachment / event fire even
                    // if isMuted() isn't polled for this player in the meantime.
                    unmute(uuid);
                }
            }, Math.max(1L, durationMillis / 50L));
        }
    }

    public void unmute(UUID uuid) {
        muteExpiry.remove(uuid);
        removePermissionAttachment(uuid);
    }

    public void applyAttachmentIfMuted(Player player) {
        if (isMuted(player.getUniqueId())) {
            applyPermissionAttachment(player.getUniqueId());
        }
    }

    public void cleanupOnQuit(UUID uuid) {
        removePermissionAttachment(uuid);
    }

    private void applyPermissionAttachment(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return;
        }
        removePermissionAttachment(uuid);
        PermissionAttachment attachment = player.addAttachment(plugin);
        attachment.setPermission(SPEAK_PERMISSION, false);
        attachments.put(uuid, attachment);
    }

    private void removePermissionAttachment(UUID uuid) {
        PermissionAttachment attachment = attachments.remove(uuid);
        if (attachment != null) {
            try {
                attachment.remove();
            } catch (IllegalArgumentException ignored) {
                // Already removed (e.g. player left) - safe to ignore.
            }
        }
    }
}
