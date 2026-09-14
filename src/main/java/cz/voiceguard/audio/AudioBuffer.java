package cz.voiceguard.audio;

import de.maxhenkel.opus4j.OpusDecoder;
import de.maxhenkel.opus4j.UnknownPlatformException;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Accumulates decoded PCM audio for a single player across multiple
 * MicrophonePacketEvents until a speech segment boundary is detected
 * (silence timeout or max length reached).
 *
 * Owns its own OpusDecoder instance because Opus decoding is stateful.
 */
public final class AudioBuffer {

    private static final int SOURCE_SAMPLE_RATE = 48000;
    private static final int CHANNELS = 1;
    private static final int FRAME_SIZE = 960; // 20ms @ 48kHz

    private final UUID playerId;
    private final OpusDecoder decoder;
    private final ReentrantLock lock = new ReentrantLock();

    private short[] pcm = new short[0];
    private long segmentStartMillis = -1L;
    private long lastPacketMillis = -1L;
    private volatile boolean closed = false;

    public AudioBuffer(UUID playerId) {
        this.playerId = playerId;

        try {
            this.decoder = new OpusDecoder(SOURCE_SAMPLE_RATE, CHANNELS);
            this.decoder.setFrameSize(FRAME_SIZE);
        } catch (IOException | UnknownPlatformException e) {
            throw new IllegalStateException(
                    "Failed to initialize Opus decoder for player " + playerId,
                    e
            );
        }
    }

    public UUID getPlayerId() {
        return playerId;
    }

    /**
     * Decodes and appends one microphone packet.
     * Thread-safe.
     */
    public void appendPacket(byte[] opusData) {
        lock.lock();
        try {
            if (closed) {
                return;
            }

            short[] decoded = decoder.decode(opusData);

            long now = System.currentTimeMillis();

            if (segmentStartMillis < 0) {
                segmentStartMillis = now;
            }

            lastPacketMillis = now;

            short[] combined = new short[pcm.length + decoded.length];

            System.arraycopy(
                    pcm,
                    0,
                    combined,
                    0,
                    pcm.length
            );

            System.arraycopy(
                    decoded,
                    0,
                    combined,
                    pcm.length,
                    decoded.length
            );

            pcm = combined;

        } finally {
            lock.unlock();
        }
    }

    public boolean hasAudio() {
        lock.lock();
        try {
            return pcm.length > 0;
        } finally {
            lock.unlock();
        }
    }

    public long getSegmentDurationMillis() {
        lock.lock();
        try {
            return (long) ((pcm.length / (double) SOURCE_SAMPLE_RATE) * 1000.0);
        } finally {
            lock.unlock();
        }
    }

    public long getMillisSinceLastPacket() {
        if (lastPacketMillis < 0) {
            return 0L;
        }

        return System.currentTimeMillis() - lastPacketMillis;
    }

    /**
     * Atomically takes ownership of the currently buffered PCM audio
     * and clears the buffer for the next segment.
     *
     * Returns null if empty.
     */
    public short[] drain() {
        lock.lock();
        try {
            if (pcm.length == 0) {
                return null;
            }

            short[] result = pcm;

            pcm = new short[0];
            segmentStartMillis = -1L;

            return result;

        } finally {
            lock.unlock();
        }
    }

    public int getSourceSampleRate() {
        return SOURCE_SAMPLE_RATE;
    }

    /**
     * Releases native decoder resources.
     */
    public void close() {
        lock.lock();
        try {
            if (!closed) {
                decoder.close();
                closed = true;
            }
        } finally {
            lock.unlock();
        }
    }
}
