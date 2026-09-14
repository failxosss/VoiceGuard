package cz.voiceguard.stt;

import java.util.concurrent.CompletableFuture;

/**
 * Converts 16-bit mono PCM audio into recognized text. Implementations must
 * never block the Minecraft main thread - all work happens on internal
 * worker threads, and results are delivered via the returned future.
 */
public interface SpeechToTextService {

    /**
     * @param pcm16BitMono PCM audio already resampled to {@link #getSampleRate()}
     * @return a future completing with the recognized text (may be empty string
     * if nothing was recognized), or completing exceptionally on error.
     */
    CompletableFuture<String> transcribeAsync(short[] pcm16BitMono);

    /**
     * @return true once the model has finished loading and the service can accept work.
     */
    boolean isReady();

    int getSampleRate();

    String getLanguage();

    void shutdown();
}
