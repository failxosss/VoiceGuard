package cz.voiceguard.audio;

import cz.voiceguard.config.ConfigManager;
import cz.voiceguard.stt.SpeechToTextService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Owns one {@link AudioBuffer} per currently-connected, currently-speaking
 * player, decides when a segment of speech is "done" (silence timeout or
 * max length reached), and hands finished segments off to the STT service.
 * <p>
 * All packet handling here is expected to be called from the Simple Voice
 * Chat networking thread (fast: decode + array copy only). The periodic
 * flush check and the STT call itself run off the main thread; only the
 * final callback (word filter + punishment) is bounced back to the main
 * thread, since punishments touch Bukkit permissions/scheduler APIs that are
 * not thread-safe.
 */
public final class AudioBufferManager {

    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final SpeechToTextService stt;
    private final BiConsumer<UUID, String> onTranscription;

    private final ConcurrentHashMap<UUID, AudioBuffer> buffers = new ConcurrentHashMap<>();
    private BukkitTask flushTask;

    public AudioBufferManager(JavaPlugin plugin, ConfigManager config, SpeechToTextService stt,
                               BiConsumer<UUID, String> onTranscription) {
        this.plugin = plugin;
        this.config = config;
        this.stt = stt;
        this.onTranscription = onTranscription;
    }

    public void start() {
        // Runs on the main thread every tick-ish (4 ticks = 200ms) purely to
        // decide "has this player gone quiet long enough to flush", then
        // hands the actual decode/STT work off async. Reading buffer
        // timestamps here is cheap and doesn't touch any Bukkit state.
        flushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::checkAllBuffers, 4L, 4L);
    }

    public void stop() {
        if (flushTask != null) {
            flushTask.cancel();
        }
        buffers.values().forEach(AudioBuffer::close);
        buffers.clear();
    }

    /**
     * Called from the Simple Voice Chat microphone packet handler.
     */
    public void onMicrophonePacket(UUID playerId, byte[] opusData) {
        AudioBuffer buffer = buffers.computeIfAbsent(playerId, AudioBuffer::new);
        buffer.appendPacket(opusData);
    }

    public void onPlayerLeave(UUID playerId) {
        AudioBuffer removed = buffers.remove(playerId);
        if (removed != null) {
            removed.close();
        }
    }

    public int getMonitoredPlayerCount() {
        return buffers.size();
    }

    private void checkAllBuffers() {
        long silenceTimeout = config.getSilenceTimeoutMs();
        long maxSegment = config.getMaxSegmentMs();
        long minSegment = config.getMinSegmentMs();

        for (AudioBuffer buffer : buffers.values()) {
            if (!buffer.hasAudio()) {
                continue;
            }
            boolean silenceReached = buffer.getMillisSinceLastPacket() >= silenceTimeout;
            boolean maxReached = buffer.getSegmentDurationMillis() >= maxSegment;

            if (silenceReached || maxReached) {
                flush(buffer, minSegment);
            }
        }
    }

    private void flush(AudioBuffer buffer, long minSegmentMs) {
        long durationMs = buffer.getSegmentDurationMillis();
        short[] pcm48k = buffer.drain();
        if (pcm48k == null) {
            return;
        }
        if (durationMs < minSegmentMs) {
            // Too short to be meaningful speech (cough, click, etc.) - discard.
            return;
        }
        if (!stt.isReady()) {
            return;
        }

        short[] pcmTarget = AudioResampler.resample(pcm48k, buffer.getSourceSampleRate(), stt.getSampleRate());

        stt.transcribeAsync(pcmTarget).thenAccept(text -> {
            if (text == null || text.isBlank()) {
                return;
            }
            // Bounce back to the main thread: everything downstream (word
            // filter result handling, punishment, permissions, messaging)
            // touches Bukkit API that must run on the main thread.
            Bukkit.getScheduler().runTask(plugin, () -> onTranscription.accept(buffer.getPlayerId(), text));
        }).exceptionally(ex -> {
            plugin.getLogger().warning("STT processing failed for " + buffer.getPlayerId() + ": " + ex.getMessage());
            return null;
        });
    }
}
