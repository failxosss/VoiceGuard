package cz.voiceguard.voicechat;

import cz.voiceguard.audio.AudioBufferManager;
import cz.voiceguard.punish.MuteManager;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

/**
 * The actual Simple Voice Chat plugin entry point. Registered with the
 * BukkitVoicechatService from {@code VoiceGuardPlugin#onEnable}.
 * <p>
 * Uses only real, documented Simple Voice Chat Plugin API surface:
 * {@link VoicechatPlugin}, {@link MicrophonePacketEvent},
 * {@link MicrophonePacketEvent#getPacket()}, and
 * {@code MicrophonePacket#getOpusEncodedData()}. No invented events or
 * methods.
 */
public final class VoiceChatHook implements VoicechatPlugin {

    public static final String PLUGIN_ID = "voiceguard";

    private final JavaPlugin plugin;
    private final MuteManager muteManager;
    private final AudioBufferManager bufferManager;

    private VoicechatApi api;

    public VoiceChatHook(JavaPlugin plugin, MuteManager muteManager, AudioBufferManager bufferManager) {
        this.plugin = plugin;
        this.muteManager = muteManager;
        this.bufferManager = bufferManager;
    }

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        this.api = api;
        plugin.getLogger().info("Connected to Simple Voice Chat API.");
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        // Priority 0 (default) is fine here: we only ever cancel (block) or
        // read data, we never need to run before/after other plugins that
        // might also hook this event.
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
    }

    private void onMicrophonePacket(MicrophonePacketEvent event) {
        VoicechatConnection connection = event.getSenderConnection();
        if (connection == null || connection.getPlayer() == null) {
            return;
        }
        UUID playerId = connection.getPlayer().getUuid();

        if (muteManager.hasBypass(playerId)) {
            return;
        }

        if (muteManager.isMuted(playerId)) {
            // This is the actual enforcement mechanism: block the packet at
            // the source so it is never transmitted to other players. This
            // does not touch and cannot touch Minecraft's text chat.
            if (event.isCancellable()) {
                event.cancel();
            }
            return;
        }

        byte[] opusData = event.getPacket().getOpusEncodedData();
        if (opusData == null || opusData.length == 0) {
            return;
        }
        bufferManager.onMicrophonePacket(playerId, opusData);
    }

    public VoicechatApi getApi() {
        return api;
    }
}
